package com.hartmann.crosspromo.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Dependency-free icon loader for hosts that do not include Coil.
 *
 * - Network + decode happen on IO; the callback runs on Main.
 * - A small LRU bitmap cache (≈64 entries of 48dp icons) keeps recompositions
 *   cheap: [cached] gives composition a synchronous, flicker-free hit.
 * - All failures are silent — the letter-avatar fallback stays visible.
 *
 * Host apps that include Coil get Coil's pipeline instead (see CoilIconLoader.kt);
 * this loader is only invoked when Coil is absent from the classpath.
 */
internal object PromoIconLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val cache = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
                size > 64
        }
    )

    /** Synchronous memory-cache hit; null when the icon is not (yet) loaded. */
    fun cached(url: String): Bitmap? = synchronized(cache) { cache[url] }

    /** True when a download for this url is currently in flight. */
    private val inFlight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Loads [url] if not cached and invokes [onLoaded] on the main thread
     * when done (whether or not the decode succeeded).
     */
    fun load(url: String, onLoaded: () -> Unit) {
        if (cached(url) != null) {
            onLoaded()
            return
        }
        if (!inFlight.add(url)) return // already loading; caller observes cache
        scope.launch {
            val bitmap = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 8_000
                conn.instanceFollowRedirects = true
                try {
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (bitmap != null) {
                synchronized(cache) { cache[url] = bitmap }
            }
            inFlight.remove(url)
            withContext(Dispatchers.Main) { onLoaded() }
        }
    }
}
