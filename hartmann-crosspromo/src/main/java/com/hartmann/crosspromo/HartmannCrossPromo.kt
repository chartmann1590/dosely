package com.hartmann.crosspromo

import android.content.Context
import com.hartmann.crosspromo.analytics.BackendAnalyticsAdapter
import com.hartmann.crosspromo.analytics.CompositeAnalytics
import com.hartmann.crosspromo.analytics.CrossPromoAnalytics
import com.hartmann.crosspromo.analytics.FirebaseAnalyticsAdapter
import com.hartmann.crosspromo.analytics.NoOpAnalytics
import com.hartmann.crosspromo.cache.PromoCache
import java.util.UUID

/**
 * HartmannCrossPromo — entry point of the reusable cross-promotion SDK.
 *
 * Initialize once from your Application:
 *
 * ```
 * HartmannCrossPromo.initialize(
 *     application = this,
 *     apiBaseUrl = "https://crosspromo.charleshartmann.com",
 *     enableFirebaseAnalytics = true, // only if the app already uses Firebase
 *     enableBackendAnalytics = true,
 * )
 * ```
 *
 * The source package is ALWAYS derived from the host app automatically
 * (context.packageName) — never hardcode it.
 */
object HartmannCrossPromo {

    const val SDK_VERSION: String = BuildConfig.SDK_VERSION

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var baseUrl: String? = null

    @Volatile
    private var analytics: CrossPromoAnalytics = NoOpAnalytics

    @Volatile
    private var cacheInstance: PromoCache? = null

    /** Random per-install session id, rotated at most every SESSION_TTL_MS. */
    private var sessionId: String = newSessionId()
    private var sessionIssuedAt: Long = System.currentTimeMillis()

    fun initialize(
        application: Context,
        apiBaseUrl: String,
        analyticsSink: CrossPromoAnalytics? = null,
        enableFirebaseAnalytics: Boolean = false,
        enableBackendAnalytics: Boolean = false,
    ) {
        check(apiBaseUrl.startsWith("https://") || apiBaseUrl.startsWith("http://localhost")) {
            "apiBaseUrl must be https"
        }
        synchronized(this) {
            if (initialized) return
            appContext = application.applicationContext
            baseUrl = apiBaseUrl.trimEnd('/')
            cacheInstance = PromoCache(appContext!!)

            val sinks = mutableListOf<CrossPromoAnalytics>()
            if (enableFirebaseAnalytics) {
                FirebaseAnalyticsAdapter.createIfAvailable()?.let { sinks.add(it) }
            }
            if (enableBackendAnalytics) {
                sinks.add(
                    BackendAnalyticsAdapter.create(
                        baseUrl = baseUrl!!,
                        context = appContext!!,
                        sdkVersion = SDK_VERSION,
                    )
                )
            }
            analyticsSink?.let { sinks.add(it) }
            analytics = if (sinks.isEmpty()) NoOpAnalytics else CompositeAnalytics(sinks)
            initialized = true
        }
    }

    val isInitialized: Boolean get() = initialized

    fun appContext(): Context =
        appContext ?: throw IllegalStateException("HartmannCrossPromo.initialize not called")

    fun requireBaseUrl(): String =
        baseUrl ?: throw IllegalStateException("HartmannCrossPromo.initialize not called")

    fun analyticsSink(): CrossPromoAnalytics = analytics

    fun cache(): PromoCache = cacheInstance ?: throw IllegalStateException("HartmannCrossPromo.initialize not called")

    /** The host app's own package name — never configured manually. */
    fun sourcePackage(): String = appContext().packageName

    /**
     * Short-lived random session identifier. Rotates after SESSION_TTL_MS or
     * when the process is recreated. Contains no user or device data.
     */
    @Synchronized
    fun sessionId(): String {
        val now = System.currentTimeMillis()
        if (now - sessionIssuedAt > SESSION_TTL_MS) {
            sessionId = newSessionId()
            sessionIssuedAt = now
        }
        return sessionId
    }

    /** Force rotation (used by tests). */
    @Synchronized
    fun rotateSession() {
        sessionId = newSessionId()
        sessionIssuedAt = System.currentTimeMillis()
    }

    private fun newSessionId(): String = UUID.randomUUID().toString().replace("-", "").take(24)

    const val SESSION_TTL_MS: Long = 12 * 60 * 60 * 1000
}
