package com.hartmann.crosspromo.repository

import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Orchestrates cache-first loading with stale-while-revalidate:
 *
 *   UI opens → observe cached recommendations (instant)
 *            → background refresh (if stale/empty) → update cache → UI updates
 *
 * Refreshes are deduplicated per (source, placement): only one in-flight
 * request per key at a time, and a fresh cache is not re-fetched.
 */
class CrossPromoRepository(
    private val api: CrossPromoApi = CrossPromoApi(HartmannCrossPromo.requireBaseUrl()),
    private val cache: PromoCache = HartmannCrossPromo.cache(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    // API-21-safe concurrent set (ConcurrentHashMap.newKeySet needs API 24).
    private val refreshing: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Cached recommendations as a cold Flow (emits null when nothing cached). */
    fun observe(sourcePackage: String, placement: String): Flow<PromoResponse?> =
        cache.observe(sourcePackage, placement).map { cached -> cached?.response }

    suspend fun getCached(sourcePackage: String, placement: String): PromoResponse? =
        cache.get(sourcePackage, placement)?.response

    /**
     * Starts a background refresh when the cache is stale or empty.
     * Returns true if a refresh was actually started.
     */
    fun refreshIfNeeded(
        sourcePackage: String,
        placement: String,
        limit: Int,
        sessionId: String?,
        force: Boolean = false,
    ): Boolean {
        val key = "$sourcePackage|$placement"
        synchronized(this) {
            if (!force) {
                if (key in refreshing) return false
                // Freshness is checked inside the coroutine (suspend cache read).
            }
            refreshing.add(key)
        }
        scope.launch {
            try {
                if (!force) {
                    val cached = cache.get(sourcePackage, placement)
                    if (cached != null && cached.isFresh) return@launch
                }
                val response = api.recommendations(
                    sourcePackage = sourcePackage,
                    placement = placement,
                    limit = limit,
                    sessionId = sessionId,
                )
                cache.put(sourcePackage, placement, response)
            } catch (_: Exception) {
                // Offline / backend down → keep cached content, stay silent.
            } finally {
                refreshing.remove(key)
            }
        }
        return true
    }

    /** Force a network refresh now (suspends until done). */
    suspend fun refreshNow(
        sourcePackage: String,
        placement: String,
        limit: Int,
        sessionId: String?,
    ): PromoResponse? = try {
        api.recommendations(sourcePackage, placement, limit, sessionId).also {
            cache.put(sourcePackage, placement, it)
        }
    } catch (_: Exception) {
        null
    }
}
