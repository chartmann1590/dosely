package com.hartmann.crosspromo.model

import kotlinx.serialization.Serializable

/**
 * A single promoted app from the Hartmann cross-promotion backend.
 * Unknown JSON fields are ignored for forward compatibility
 * (`ignoreUnknownKeys = true` on the client Json instance).
 */
@Serializable
data class PromoApp(
    val packageName: String,
    val name: String? = null,
    val iconUrl: String? = null,
    val shortDescription: String? = null,
    val rating: Double? = null,
    val ratingCount: Long? = null,
    val installText: String? = null,
    val storeUrl: String,
    val selectionType: String? = null,
)

/** Normalized recommendations response. */
@Serializable
data class PromoResponse(
    val version: Int = 1,
    val requestId: String? = null,
    val generatedAt: String? = null,
    val expiresAt: String? = null,
    val apps: List<PromoApp> = emptyList(),
)

/** Empty catalog placeholder (also used as the offline/no-data response). */
internal fun emptyResponse(): PromoResponse = PromoResponse(apps = emptyList())
