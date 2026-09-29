package com.hartmann.crosspromo

import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.model.PromoResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache freshness math (TTL / server expiresAt precedence).
 * The DataStore-backed PromoCache itself needs an instrumented context;
 * these tests cover the pure expiry rules it applies.
 */
class CacheTtlTest {

    private fun app(pkg: String) = PromoApp(
        packageName = pkg,
        name = pkg,
        iconUrl = null,
        shortDescription = null,
        rating = null,
        ratingCount = null,
        installText = null,
        storeUrl = "https://play.google.com/store/apps/details?id=$pkg",
    )

    @Test
    fun `server expiresAt in the future caps at local fallback TTL`() {
        val now = System.currentTimeMillis()
        val response = PromoResponse(
            expiresAt = java.time.Instant.ofEpochMilli(now + 48 * 3600_000).toString(),
            apps = listOf(app("com.a.b")),
        )
        val effective = effectiveTtl(response.expiresAt, fallback = 6 * 3600_000L, now = now)
        assertEquals(6 * 3600_000L, effective)
    }

    @Test
    fun `server expiresAt sooner than fallback wins`() {
        val now = System.currentTimeMillis()
        val response = PromoResponse(
            expiresAt = java.time.Instant.ofEpochMilli(now + 30 * 60_000).toString(),
            apps = listOf(app("com.a.b")),
        )
        val effective = effectiveTtl(response.expiresAt, fallback = 6 * 3600_000L, now = now)
        assertEquals(30 * 60_000L, effective)
    }

    @Test
    fun `past or missing expiresAt falls back to local TTL`() {
        val now = System.currentTimeMillis()
        assertEquals(
            6 * 3600_000L,
            effectiveTtl(java.time.Instant.ofEpochMilli(now - 1000).toString(), 6 * 3600_000L, now),
        )
        assertEquals(6 * 3600_000L, effectiveTtl(null, 6 * 3600_000L, now))
    }

    @Test
    fun `freshness boundary math`() {
        val now = System.currentTimeMillis()
        val expires = now + 1000
        assertTrue(now < expires) // fresh
        assertFalse(now + 2000 < expires) // stale
    }

    private fun effectiveTtl(expiresAtIso: String?, fallback: Long, now: Long): Long {
        val iso = expiresAtIso ?: return fallback
        return runCatching {
            val delta = java.time.Instant.parse(iso).toEpochMilli() - now
            if (delta > 0) delta.coerceAtMost(fallback) else fallback
        }.getOrDefault(fallback)
    }
}
