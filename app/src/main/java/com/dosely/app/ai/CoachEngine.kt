package com.dosely.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device Gemma chat via the LiteRT-LM Kotlin API. The engine is created lazily
 * on first use and kept alive for the app session; tokens stream out as a Flow of
 * accumulated response text.
 */
class CoachEngine(private val context: Context) {

    sealed interface EngineState {
        data object NoModel : EngineState
        data object Idle : EngineState
        data class Loading(val step: String) : EngineState
        data object Ready : EngineState
        data class Error(val message: String) : EngineState
    }

    private val _state = MutableStateFlow<EngineState>(EngineState.NoModel)
    val state: StateFlow<EngineState> = _state

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val mutex = Mutex()

    private fun downloadManager() = ModelDownloadManager(context)

    fun hasModel(option: ModelOption = GemmaModelCatalog.default): Boolean =
        downloadManager().isDownloaded(option)

    /** Loads the model if needed. Safe to call repeatedly. */
    suspend fun ensureLoaded(
        option: ModelOption = GemmaModelCatalog.default,
        systemInstruction: String,
    ) = mutex.withLock {
        if (_state.value == EngineState.Ready) {
            android.util.Log.d("CoachEngine", "ensureLoaded: already ready")
            return@withLock
        }
        val downloaded = hasModel(option)
        val fileBytes = downloadManager().modelFile(option).let { "${it.absolutePath}=${it.length()}" }
        android.util.Log.d("CoachEngine", "ensureLoaded: state=$_state.value hasModel=$downloaded file=$fileBytes")
        if (!downloaded) {
            _state.value = EngineState.NoModel
            return@withLock
        }
        try {
            _state.value = EngineState.Loading("initializing")
            android.util.Log.d("CoachEngine", "ensureLoaded: initializing CPU engine…")
            withContext(Dispatchers.IO) {
                engine?.close()
                // CPU (XNNPACK) backend: dependable on-device decode for E2B.
                // GPU can stall on first-run shader compilation on some devices.
                engine = Engine(
                    EngineConfig(
                        modelPath = downloadManager().modelFile(option).absolutePath,
                        backend = Backend.CPU(),
                    ),
                ).also { it.initialize() }
            }
            conversation?.close()
            conversation = engine?.createConversation(
                ConversationConfig(systemInstruction = Contents.of(systemInstruction)),
            )
            _state.value = EngineState.Ready
            android.util.Log.d("CoachEngine", "ensureLoaded: READY")
        } catch (t: Throwable) {
            android.util.Log.e("CoachEngine", "ensureLoaded failed", t)
            _state.value = EngineState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Streams the model response for [userText] as accumulated text fragments,
     * using the callback-based API (most reliable across LiteRT-LM builds).
     */
    fun respond(userText: String): Flow<String> = kotlinx.coroutines.flow.callbackFlow {
        val conv = conversation ?: run {
            trySend("")
            close(IllegalStateException("Engine not initialized"))
            return@callbackFlow
        }
        android.util.Log.d("CoachEngine", "respond: sending \"$userText\"")
        val accumulated = StringBuilder()
        val callback = object : com.google.ai.edge.litertlm.MessageCallback {
            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                val text = message.contents.contents
                    .filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    accumulated.append(text)
                    trySend(accumulated.toString())
                }
            }

            override fun onDone() {
                android.util.Log.d("CoachEngine", "respond: onDone")
                close()
            }

            override fun onError(throwable: Throwable) {
                android.util.Log.e("CoachEngine", "respond: onError", throwable)
                close(throwable)
            }
        }
        try {
            conv.sendMessageAsync(userText, callback)
        } catch (t: Throwable) {
            android.util.Log.e("CoachEngine", "respond: send failed", t)
            close(t)
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    /** Rebuilds the conversation with a fresh system instruction (e.g. new stats). */
    suspend fun reset(systemInstruction: String) = mutex.withLock {
        if (_state.value != EngineState.Ready) return@withLock
        withContext(Dispatchers.IO) {
            conversation?.close()
            conversation = engine?.createConversation(
                ConversationConfig(systemInstruction = Contents.of(systemInstruction)),
            )
        }
    }

    fun release() {
        try {
            conversation?.close()
        } catch (_: Throwable) {
        }
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        conversation = null
        engine = null
        _state.value = if (hasModel()) EngineState.Idle else EngineState.NoModel
    }
}
