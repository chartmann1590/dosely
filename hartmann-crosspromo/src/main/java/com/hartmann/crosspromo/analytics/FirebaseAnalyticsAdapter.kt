package com.hartmann.crosspromo.analytics

/**
 * Logs `crosspromo_impression` / `crosspromo_click` to Firebase Analytics when
 * the host app already includes it. Firebase is resolved via reflection, so
 * host apps without Firebase pay nothing — no dependency is forced.
 *
 * Events are dispatched on Firebase's own executor via its API.
 */
class FirebaseAnalyticsAdapter private constructor(
    private val firebaseInstance: Any,
    private val logMethod: java.lang.reflect.Method,
) : CrossPromoAnalytics {

    private fun log(name: String, params: android.os.Bundle) {
        try {
            logMethod.invoke(firebaseInstance, name, params)
        } catch (_: Exception) {
            // Never crash the host for analytics.
        }
    }

    private fun bundle(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        sdkVersion: String,
    ): android.os.Bundle = android.os.Bundle().apply {
        putString("source_package", sourcePackage)
        putString("target_package", targetPackage)
        putString("placement", placement)
        putInt("rank_position", rankPosition)
        selectionType?.let { putString("selection_type", it) }
        sessionId?.let { putString("session_id", it) }
        putString("sdk_version", sdkVersion)
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
    ) {
        log(
            "crosspromo_impression",
            bundle(sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, sdkVersion),
        )
    }

    override fun click(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    ) {
        log(
            "crosspromo_click",
            bundle(sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, sdkVersion),
        )
    }

    companion object {
        /** Returns an adapter if Firebase Analytics is on the classpath, else null. */
        @JvmStatic
        fun createIfAvailable(): FirebaseAnalyticsAdapter? = try {
            val cls = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            val getInstance = cls.getMethod("getInstance", android.content.Context::class.java)
            val instance = getInstance.invoke(null, com.hartmann.crosspromo.HartmannCrossPromo.appContext())
                ?: return null
            val log = cls.getMethod("logEvent", String::class.java, android.os.Bundle::class.java)
            FirebaseAnalyticsAdapter(instance, log)
        } catch (_: Throwable) {
            null
        }
    }
}
