package com.hartmann.crosspromo.ui

import com.hartmann.crosspromo.HartmannCrossPromo
import java.util.concurrent.ConcurrentHashMap

/**
 * Impression deduplication: one impression per (placement, targetPackage)
 * per process lifetime, matching "one displayed recommendation per session
 * card should generally generate one impression". Recomposition storms and
 * carousel recycling never double-count.
 */
object ImpressionGuard {
    // API-21-safe concurrent set (ConcurrentHashMap.newKeySet needs API 24).
    private val seen: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun shouldCount(placement: String, targetPackage: String): Boolean =
        seen.add("$placement|$targetPackage")
}

/** Package-private helper for UI code. */
internal fun countImpressionOnce(placement: String, targetPackage: String): Boolean =
    ImpressionGuard.shouldCount(placement, targetPackage)
