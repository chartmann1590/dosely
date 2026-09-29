package com.hartmann.crosspromo.attribution

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import com.hartmann.crosspromo.HartmannCrossPromo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the Google Play Install Referrer for THIS app and extracts
 * cross-promotion attribution when present.
 *
 * Uses ONLY Google's supported Play Install Referrer API. The Play Referrer
 * carries the `referrer` query parameter that the Play Store appends to
 * market:// links opened from our promo cards:
 *
 *   market://details?id=<target>&referrer=utm_source%3D<sourcePkg>%26utm_medium%3Dcrosspromo%26utm_campaign%3Dhartmann_crosspromo%26utm_content%3D<targetPkg>
 *
 * The API dependency is OPTIONAL: resolved via reflection so host apps are
 * never forced to add it. If it is absent or Play is unavailable, this class
 * simply reports "no attribution" — the app continues normally.
 */
object HartmannInstallAttribution {

    data class Attribution(
        val sourcePackage: String?,
        val targetPackage: String?,
        val campaign: String?,
        val medium: String?,
    ) {
        val isCrosspromo: Boolean get() = sourcePackage != null && medium == "crosspromo"
    }

    private const val UTM_SOURCE = "utm_source"
    private const val UTM_MEDIUM = "utm_medium"
    private const val UTM_CAMPAIGN = "utm_campaign"
    private const val UTM_CONTENT = "utm_content"

    /** Builds the referrer value embedded into promo deep links. */
    fun buildReferrerValue(sourcePackage: String, targetPackage: String): String =
        "$UTM_SOURCE%3D$sourcePackage%26$UTM_MEDIUM%3Dcrosspromo%26$UTM_CAMPAIGN%3Dhartmann_crosspromo%26$UTM_CONTENT%3D$targetPackage"

    /** Parses a raw referrer string into attribution data. */
    fun parseReferrer(raw: String?): Attribution {
        if (raw.isNullOrBlank()) return Attribution(null, null, null, null)
        // Play may deliver the referrer fully encoded (no literal '&' yet).
        // Normalize with bounded decoding until it is key=value&key=value form.
        var s: String = raw
        var rounds = 0
        while (!s.contains('&') && s.contains('%') && rounds < 3) {
            s = decodeOnce(s)
            rounds++
        }
        val params = s.split('&')
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null
                else {
                    val k = it.substring(0, idx)
                    val v = decodeOnce(it.substring(idx + 1))
                    k to v
                }
            }
            .toMap()
        return Attribution(
            sourcePackage = params[UTM_SOURCE],
            targetPackage = params[UTM_CONTENT],
            campaign = params[UTM_CAMPAIGN],
            medium = params[UTM_MEDIUM],
        )
    }

    private fun decodeOnce(v: String): String =
        runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)

    /**
     * Reads the install referrer via Google's supported API (reflection).
     * Returns null when the API/Play is unavailable or an error occurs.
     */
    suspend fun readInstallReferrer(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val installerClientClass = Class.forName("com.android.installreferrer.api.InstallReferrerClient")
            val newBuilder = Class.forName("com.android.installreferrer.api.InstallReferrerClient\$Builder")
            val buildMethod = newBuilder.getDeclaredMethod("build")
            val setContext = newBuilder.getDeclaredMethod("setContext", Context::class.java)

            val builder = newBuilder.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            setContext.invoke(builder, context.applicationContext)
            val client = buildMethod.invoke(builder)

            val startConnection = client.javaClass.getMethod(
                "startConnection",
                Class.forName("com.android.installreferrer.api.InstallReferrerStateListener"),
            )
            @Suppress("UNCHECKED_CAST")
            val responseClass = Class.forName("com.android.installreferrer.api.ReferrerDetails")
            val installReferrerGetter = responseClass.getMethod("getInstallReferrer")

            var result: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(Class.forName("com.android.installreferrer.api.InstallReferrerStateListener")),
            ) { _, method, args ->
                when (method.name) {
                    "onInstallReferrerSetupFinished" -> {
                        val responseCode = args?.get(0) as? Int ?: -1
                        if (responseCode == 0) { // OK
                            try {
                                val details = client.javaClass.getMethod("installReferrer").invoke(client)
                                result = installReferrerGetter.invoke(details) as? String
                            } catch (_: Exception) {
                            }
                        }
                        latch.countDown()
                        Unit
                    }
                    "onInstallReferrerServiceDisconnected" -> {
                        latch.countDown()
                        Unit
                    }
                    else -> null
                }
            }
            startConnection.invoke(client, listener)
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            try {
                client.javaClass.getMethod("endConnection").invoke(client)
            } catch (_: Exception) {
            }
            result
        } catch (_: ClassNotFoundException) {
            null // installreferrer dependency not present in this host app
        } catch (_: RemoteException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Convenience: reads the referrer and reports a crosspromo install event
     * (via the configured analytics sinks) when it carries cross-promo data.
     * Call once from the TARGET app's first launch.
     */
    suspend fun reportCrosspromoInstallIfAttributed(context: Context) {
        val raw = readInstallReferrer(context) ?: return
        val attribution = parseReferrer(raw)
        if (!attribution.isCrosspromo) return
        HartmannCrossPromo.analyticsSink().click(
            sourcePackage = attribution.sourcePackage ?: return,
            targetPackage = attribution.targetPackage ?: context.packageName,
            placement = "install_referrer",
            rankPosition = 1,
            selectionType = null,
            sessionId = HartmannCrossPromo.sessionId(),
            recommendationRequestId = null,
            sdkVersion = HartmannCrossPromo.SDK_VERSION,
        )
    }
}
