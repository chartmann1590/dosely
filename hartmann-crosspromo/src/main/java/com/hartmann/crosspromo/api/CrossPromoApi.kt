package com.hartmann.crosspromo.api

import com.hartmann.crosspromo.BuildConfig
import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Thrown when the backend cannot be reached or responds non-2xx. */
class CrossPromoException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Minimal HTTP client for the cross-promotion API.
 *
 * Deliberately dependency-free (HttpURLConnection) so the SDK drops into any
 * host app regardless of its networking stack. All calls are synchronous —
 * callers must invoke from a background dispatcher (the repository does).
 */
class CrossPromoApi(
    private val baseUrl: String,
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 8_000,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun recommendations(
        sourcePackage: String,
        placement: String,
        limit: Int,
        sessionId: String?,
        exclude: List<String> = emptyList(),
        locale: String? = null,
    ): PromoResponse {
        val sb = StringBuilder(baseUrl.trimEnd('/'))
            .append("/api/v1/recommendations?sourcePackage=")
            .append(URLEncoder.encode(sourcePackage, "UTF-8"))
            .append("&placement=").append(URLEncoder.encode(placement, "UTF-8"))
            .append("&limit=").append(limit.coerceIn(1, 10))
        sessionId?.let { sb.append("&sessionId=").append(URLEncoder.encode(it, "UTF-8")) }
        if (exclude.isNotEmpty()) {
            sb.append("&exclude=").append(URLEncoder.encode(exclude.joinToString(","), "UTF-8"))
        }
        locale?.let { sb.append("&locale=").append(URLEncoder.encode(it, "UTF-8")) }
        sb.append("&sdkVersion=").append(URLEncoder.encode(sdkVersion, "UTF-8"))
        return get(sb.toString())
    }

    fun catalog(): PromoResponse = get(baseUrl.trimEnd('/') + "/api/v1/catalog")

    fun health(): Boolean {
        val conn = open(baseUrl.trimEnd('/') + "/api/v1/health")
        return try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): PromoResponse {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw CrossPromoException("HTTP $code from $url")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(PromoResponse.serializer(), body)
        } catch (e: CrossPromoException) {
            throw e
        } catch (e: Exception) {
            throw CrossPromoException("request failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("X-SDK-Version", sdkVersion)
        conn.instanceFollowRedirects = true
        return conn
    }
}
