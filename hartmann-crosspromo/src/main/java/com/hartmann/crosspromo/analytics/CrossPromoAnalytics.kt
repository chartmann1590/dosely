package com.hartmann.crosspromo.analytics

/**
 * Analytics abstraction. Host apps choose the sink:
 *  - [NoOpAnalytics] (default)
 *  - [FirebaseAnalyticsAdapter] if the host already uses Firebase
 *  - [BackendAnalyticsAdapter] to mirror events to /api/v1/events
 * Any combination can be installed via HartmannCrossPromo.initialize.
 */
interface CrossPromoAnalytics {
    fun impression(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    )

    fun click(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    )
}

/** Does nothing. Default. */
object NoOpAnalytics : CrossPromoAnalytics {
    override fun impression(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    ) = Unit

    override fun click(
        sourcePackage: String,
        targetPackage: String,
        placement: String,
        rankPosition: Int,
        selectionType: String?,
        sessionId: String?,
        recommendationRequestId: String?,
        sdkVersion: String,
    ) = Unit
}

/**
 * Fans out to multiple sinks (e.g. Firebase + backend) while never throwing —
 * analytics failures must never crash the host app.
 */
class CompositeAnalytics(private val sinks: List<CrossPromoAnalytics>) : CrossPromoAnalytics {
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
        for (s in sinks) {
            try {
                s.impression(sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, recommendationRequestId, sdkVersion)
            } catch (_: Exception) {
            }
        }
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
        for (s in sinks) {
            try {
                s.click(sourcePackage, targetPackage, placement, rankPosition, selectionType, sessionId, recommendationRequestId, sdkVersion)
            } catch (_: Exception) {
            }
        }
    }
}
