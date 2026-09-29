package com.hartmann.crosspromo.analytics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.hartmann.crosspromo.api.CrossPromoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Mirrors promo events to the cross-promotion backend (POST /api/v1/events).
 *
 * Design constraints honored here:
 *  - events are batched (max 20 per request) and flushed by threshold,
 *    on a periodic timer, and whenever the host app moves to the background
 *    (so short sessions still reach the server before process death)
 *  - if the queue overflows, OLDEST events are dropped (recent behavior wins)
 *  - all network work happens on a background dispatcher
 *  - failures are silent: analytics is never mission critical
 *  - only aggregate-safe fields are sent (no device identifiers)
 */
class BackendAnalyticsAdapter private constructor(
    private val baseUrl: String,
    private val appContext: Context,
    maxSdkVersion: String,
) : CrossPromoAnalytics {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sdkVersion: String = maxSdkVersion
    private var queuedSinceFlush = 0

    // Periodic safety-net flush: guarantees queued events reach the server
    // even when a session never accumulates FLUSH_THRESHOLD events.
    private val timerJob = scope.launch {
        while (true) {
            delay(FLUSH_INTERVAL_MS)
            runCatching { flush() }
        }
    }

    // App-background flush: the last chance before the process may be killed.
    private var startedActivities = 0
    private val lifecycleCallbacks: Application.ActivityLifecycleCallbacks? =
        (appContext as? Application)?.let { app ->
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivities++
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivities = (startedActivities - 1).coerceAtLeast(0)
                    if (startedActivities == 0) flush()
                }

                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
            }.also { app.registerActivityLifecycleCallbacks(it) }
        }

    private fun enqueue(event: kotlinx.serialization.json.JsonObject) {
        val current = prefs.getString(KEY_QUEUE, null) ?: "[]"
        val array = runCatching { Json.parseToJsonElement(current).let { it as JsonArray } }
            .getOrDefault(JsonArray(emptyList()))
        val next = (array + event).takeLast(MAX_QUEUE)
        prefs.edit().putString(KEY_QUEUE, next.toString()).apply()
        queuedSinceFlush++
        if (queuedSinceFlush >= FLUSH_THRESHOLD) flush()
    }

    /**
     * Flushes pending events, cancels the timer and unregisters lifecycle
     * hooks. Optional: lets host apps/tests stop the adapter cleanly.
     */
    fun shutdown() {
        runCatching { flush() }
        timerJob.cancel()
        (appContext as? Application)?.let { app ->
            lifecycleCallbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
        }
    }

    /** Sends queued events; safe to call from anywhere. */
    fun flush() {
        queuedSinceFlush = 0
        val current = prefs.getString(KEY_QUEUE, null) ?: "[]"
        val array = runCatching { Json.parseToJsonElement(current).let { it as JsonArray } }
            .getOrDefault(JsonArray(emptyList()))
        if (array.isEmpty()) return
        prefs.edit().putString(KEY_QUEUE, "[]").apply()
        scope.launch {
            try {
                val conn = URL(baseUrl.trimEnd('/') + "/api/v1/events").openConnection()
                    as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 5_000
                    conn.readTimeout = 8_000
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-SDK-Version", sdkVersion)
                    conn.outputStream.use { it.write(array.toString().toByteArray()) }
                    // 2xx → events stay dropped. Anything else → best effort requeue.
                    if (conn.responseCode !in 200..299) requeue(array)
                } finally {
                    conn.disconnect()
                }
            } catch (_: IOException) {
                requeue(array)
            }
        }
    }

    private fun requeue(array: JsonArray) {
        val current = prefs.getString(KEY_QUEUE, null) ?: "[]"
        val existing = runCatching { Json.parseToJsonElement(current).let { it as JsonArray } }
            .getOrDefault(JsonArray(emptyList()))
        prefs.edit().putString(KEY_QUEUE, (array + existing).takeLast(MAX_QUEUE).toString()).apply()
    }

    private fun eventBody(
        name: String,
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
    ) = buildJsonObject {
        put("event", name)
        put("sourcePackage", sourcePackage)
        put("targetPackage", targetPackage)
        put("placement", placement)
        put("rankPosition", rankPosition)
        selectionType?.let { put("selectionType", it) }
        sessionId?.let { put("sessionId", it) }
        recommendationRequestId?.let { put("recommendationRequestId", it) }
        put("sdkVersion", sdkVersion)
        put("ts", System.currentTimeMillis())
    }

    override fun impression(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    ) = enqueue(
        eventBody("promo_impression", sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, recommendationRequestId)
    )

    override fun click(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    ) = enqueue(
        eventBody("promo_click", sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, recommendationRequestId)
    )

    companion object {
        private const val PREFS = "hartmann_crosspromo_events"
        private const val KEY_QUEUE = "queue"
        private const val MAX_QUEUE = 200
        private const val FLUSH_THRESHOLD = 5
        private const val FLUSH_INTERVAL_MS = 60_000L

        @JvmStatic
        fun create(baseUrl: String, context: Context, sdkVersion: String): BackendAnalyticsAdapter =
            BackendAnalyticsAdapter(baseUrl, context.applicationContext, sdkVersion)
    }
}

/** Reference to CrossPromoApi kept for future direct-use integrations. */
@Suppress("unused")
private val apiRef: Class<CrossPromoApi>? = CrossPromoApi::class.java

/** URL-encode helper reused by attribution components. */
internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
