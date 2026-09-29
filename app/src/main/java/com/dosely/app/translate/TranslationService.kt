package com.dosely.app.translate

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Manages ML Kit on-device translation packs and performs the app-wide
 * string translation with an in-memory cache.
 */
class TranslationService(
    private val appContext: Context,
) {
    data class State(
        /** ML Kit language code currently active, or null when translating is off. */
        val targetMlkit: String? = null,
        /** True between a language selection and the pack being ready. */
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, String>()
    private var translator: com.google.mlkit.nl.translate.Translator? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Mirrors the selected tag so Settings can show what is active. */
    private val _selectedTag = MutableStateFlow("")
    val selectedTag: StateFlow<String> = _selectedTag

    private var lastRequested: String? = null

    private val activateMutex = Mutex()

    fun start(initialTag: String) {
        if (lastRequested == initialTag && _state.value.error == null && !_state.value.busy) return
        lastRequested = initialTag
        if (initialTag.isNotEmpty()) {
            scope.launch { activate(initialTag) }
        }
    }

    /**
     * Activates translation for [tag] (BCP-47). English or blank disables translation
     * (the app is natively English). Serialized so concurrent callers (startup restore
     * and user selection) cannot interleave and clobber each other's state.
     */
    suspend fun activate(tag: String) = withContext(Dispatchers.IO) {
        activateMutex.withLock {
            val lang = AppLanguages.byTag(tag)
            val isEnglish = tag.isEmpty() || lang == null || lang.mlkitCode == TranslateLanguage.ENGLISH
            // Fast path: requested language already active and healthy.
            if (!_state.value.busy && _state.value.error == null) {
                if (isEnglish && _state.value.targetMlkit == null) return@withLock
                if (!isEnglish && _state.value.targetMlkit == lang.mlkitCode) return@withLock
            }
            _selectedTag.value = tag
            if (isEnglish) {
                closeTranslator()
                cache.clear()
                _state.value = State(targetMlkit = null, busy = false, error = null)
                LocalizerHolder.bump()
                return@withLock
            }
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(lang.mlkitCode)
                    .build()
                closeTranslator()
                val newTranslator = Translation.getClient(options)
                translator = newTranslator
                newTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                cache.clear()
                _state.value = State(targetMlkit = lang.mlkitCode, busy = false, error = null)
                LocalizerHolder.bump()
            } catch (t: Throwable) {
                _state.value = State(
                    targetMlkit = null,
                    busy = false,
                    error = t.message ?: t.javaClass.simpleName,
                )
                LocalizerHolder.bump()
            }
        }
    }

    suspend fun translate(text: String): String {
        val target = _state.value.targetMlkit ?: return text
        if (text.isBlank()) return text
        cache[text]?.let { return it }
        val t = translator ?: return text
        return try {
            val result = suspendCancellableCoroutine { cont ->
                t.translate(text)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(text) }
            }
            cache[text] = result
            result
        } catch (t2: Throwable) {
            text
        }
    }

    suspend fun downloadedPackTags(): List<String> = withContext(Dispatchers.IO) {
        try {
            val models = RemoteModelManager.getInstance()
                .getDownloadedModels(TranslateRemoteModel::class.java).await()
            models.mapNotNull { AppLanguages.byMlkitCode(it.language)?.tag }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    suspend fun deletePack(tag: String) = withContext(Dispatchers.IO) {
        val lang = AppLanguages.byTag(tag) ?: return@withContext
        try {
            val model = TranslateRemoteModel.Builder(lang.mlkitCode).build()
            RemoteModelManager.getInstance().deleteDownloadedModel(model).await()
        } catch (_: Throwable) {
            // Pack may not be downloaded; nothing to do.
        }
    }

    private fun closeTranslator() {
        translator?.close()
        translator = null
    }
}
