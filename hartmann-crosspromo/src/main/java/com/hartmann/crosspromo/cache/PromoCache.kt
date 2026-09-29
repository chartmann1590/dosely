package com.hartmann.crosspromo.cache

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val STORE_NAME = "hartmann_crosspromo_cache"

private val Context.promoDataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

private val keyFor = { sourcePackage: String, placement: String ->
    stringPreferencesKey("rec|$sourcePackage|$placement")
}

/** A cached recommendation with freshness metadata. */
data class CachedPromo(
    val response: PromoResponse,
    val cachedAt: Long,
    val expiresAt: Long,
) {
    val isFresh: Boolean get() = System.currentTimeMillis() < expiresAt
}

/**
 * Persistent recommendation cache with stale-while-revalidate semantics:
 * UI shows cached content instantly, refresh happens in the background.
 */
class PromoCache(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val ttlMs: Long = DEFAULT_TTL_MS

    suspend fun get(sourcePackage: String, placement: String): CachedPromo? {
        val key = keyFor(sourcePackage, placement)
        val raw = context.promoDataStore.data.first()[key] ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun observe(sourcePackage: String, placement: String): Flow<CachedPromo?> {
        val key = keyFor(sourcePackage, placement)
        return context.promoDataStore.data.map { prefs ->
            prefs[key]?.let { raw -> runCatching { decode(raw) }.getOrNull() }
        }
    }

    suspend fun put(sourcePackage: String, placement: String, response: PromoResponse) {
        val key = keyFor(sourcePackage, placement)
        if (response.apps.isEmpty()) {
            // An empty response is AUTHORITATIVE when it comes from the server
            // (kill switch active, placement disabled, no eligible targets).
            // Replace any previous entry so cached promos can't outlive the
            // remote switches. Network failures never reach put() — the
            // repository catches them and keeps the existing cache.
            context.promoDataStore.edit { prefs -> prefs.remove(key) }
            return
        }
        val now = System.currentTimeMillis()
        val entry = CachedPromo(response, now, now + response.expiresInMs(ttlMs))
        context.promoDataStore.edit { prefs ->
            prefs[key] = encode(entry)
        }
    }

    suspend fun clear() {
        context.promoDataStore.edit { it.clear() }
    }

    private fun encode(entry: CachedPromo): String =
        json.encodeToString(
            CachedEntry(
                response = entry.response,
                cachedAt = entry.cachedAt,
                expiresAt = entry.expiresAt,
            )
        )

    private fun decode(raw: String): CachedPromo {
        val entry = json.decodeFromString(CachedEntry.serializer(), raw)
        return CachedPromo(entry.response, entry.cachedAt, entry.expiresAt)
    }

    @kotlinx.serialization.Serializable
    private data class CachedEntry(
        val response: PromoResponse,
        val cachedAt: Long,
        val expiresAt: Long,
    )

    companion object {
        /** Default 6h TTL; the server's expiresAt can shorten/extend this. */
        const val DEFAULT_TTL_MS: Long = 6 * 60 * 60 * 1000
    }
}

/** Server-driven expiry with a local fallback TTL (API-21-safe ISO-8601 parsing). */
private fun PromoResponse.expiresInMs(fallback: Long): Long {
    val iso = expiresAt ?: return fallback
    val epochMs = parseIsoToEpochMs(iso) ?: return fallback
    val delta = epochMs - System.currentTimeMillis()
    return if (delta > 0) delta.coerceAtMost(fallback) else fallback
}

/** Parses ISO-8601 timestamps without java.time (API < 26 compatible). */
internal fun parseIsoToEpochMs(iso: String): Long? = runCatching {
    // Format: 2026-09-29T13:00:00Z (optionally with .mmm fraction).
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})?$").find(iso)
        ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
    }
    var ms = cal.timeInMillis
    m.groups[7]?.value?.let { frac -> ms += frac.padEnd(3, '0').take(3).toInt() }
    val zone = m.groups[8]?.value
    if (zone != null && zone != "Z") {
        val sign = if (zone.startsWith('-')) -1 else 1
        val digits = zone.filter { it.isDigit() }
        val offsetMinutes = if (digits.length >= 4) {
            digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
        } else digits.toInt() * 60
        ms -= sign * offsetMinutes * 60_000
    }
    ms
}.getOrNull()
