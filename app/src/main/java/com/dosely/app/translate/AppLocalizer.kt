package com.dosely.app.translate

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.dosely.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * On-screen translation layer. All UI text goes through [S]/[L] instead of
 * stringResource(), so a single service swap switches the entire app at runtime.
 */
object L10n {
    private val ids: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        val cl = R.string::class.java
        cl.declaredFields.forEach { f ->
            if (f.type == Int::class.javaPrimitiveType) {
                f.isAccessible = true
                map[f.name] = f.getInt(null)
            }
        }
        map
    }

    fun idOf(key: String): Int = ids[key] ?: 0
}

private fun rawString(context: Context, key: String, args: Array<out Any?>): String {
    val resId = L10n.idOf(key)
    return if (resId != 0) context.getString(resId, *args) else key
}

/**
 * Blocking translation, safe ONLY from worker threads (Task listeners need the main
 * thread alive). Used by workers and notification builders.
 */
fun L(context: Context, key: String, vararg args: Any?): String {
    val raw = rawString(context, key, args)
    val service = LocalizerHolder.getOrNull() ?: return raw
    return runBlocking { service.translate(raw) }
}

/**
 * Composition-friendly access. Never blocks: returns the cached translation when
 * available (initially the English source), schedules an async translation
 * otherwise, and bumps [LocalizerHolder.version] when it arrives so the text
 * swaps in on the next recomposition.
 */
@Composable
fun S(key: String, vararg args: Any?): String {
    val context = LocalContext.current
    val version by LocalizerHolder.version
    val argsHash = args.contentHashCode()
    return remember(key, argsHash, version) {
        LocalizerHolder.string(context, key, args)
    }
}

/** Holds the active TranslationService plus the translation cache for composables. */
object LocalizerHolder {
    @Volatile private var service: TranslationService? = null

    /** Bumped whenever translations arrive so all S() remember blocks invalidate. */
    val version = mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** target tag -> (raw english -> translated) */
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    fun init(service: TranslationService) {
        LocalizerHolder.service = service
    }

    fun getOrNull(): TranslationService? = service

    fun get(context: Context): TranslationService =
        service ?: TranslationService(context.applicationContext).also { service = it }

    fun string(context: Context, key: String, args: Array<out Any?>): String {
        val raw = rawString(context, key, args)
        val svc = service ?: return raw
        val target = svc.state.value.targetMlkit ?: return raw
        val langCache = cache.getOrPut(target) { ConcurrentHashMap() }
        langCache[raw]?.let { return it }
        if (pending.add(raw)) {
            scope.launch {
                try {
                    val translated = svc.translate(raw)
                    if (!translated.isNullOrBlank() && translated != raw) {
                        langCache[raw] = translated
                        bump()
                    }
                } finally {
                    pending.remove(raw)
                }
            }
        }
        return raw
    }

    fun bump() {
        version.value = version.value + 1
    }
}
