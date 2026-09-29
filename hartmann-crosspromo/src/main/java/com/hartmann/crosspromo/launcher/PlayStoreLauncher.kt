package com.hartmann.crosspromo.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens an app's official Google Play listing.
 *
 * Order: market:// intent (Play Store app) → HTTPS fallback (browser).
 * Never crashes when Play is missing (e.g. de-Googled devices).
 * This NEVER downloads APKs — it only deep-links to the official store page.
 *
 * When [referrer] is supplied (see
 * [com.hartmann.crosspromo.attribution.HartmannInstallAttribution.buildReferrerValue]),
 * it is appended to both the market:// and web URLs so the Play Store can
 * deliver install-referrer attribution to the promoted (target) app.
 */
object PlayStoreLauncher {

    private const val MARKET_SCHEME = "market://details?id="
    private const val WEB_URL = "https://play.google.com/store/apps/details?id="

    /** Resolves the intent that opens the Play listing, or null if impossible. */
    fun playListingIntent(context: Context, packageName: String, referrer: String? = null): Intent? {
        val market = Intent(Intent.ACTION_VIEW, listingUri(MARKET_SCHEME, packageName, referrer)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(market, 0) != null) {
            return market
        }
        // Browser fallback: return the web intent even when resolution is
        // uncertain — startActivity's ActivityNotFoundException path keeps
        // this safe on devices with no browser at all.
        return Intent(Intent.ACTION_VIEW, listingUri(WEB_URL, packageName, referrer)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Launches the Play listing; returns false when nothing could handle it. */
    fun openPlayStore(context: Context, packageName: String, referrer: String? = null): Boolean {
        if (packageName.isBlank()) return false
        val intent = playListingIntent(context, packageName, referrer) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, listingUri(WEB_URL, packageName, referrer))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds "scheme<pkg>[&referrer=<value>]". The referrer value must already
     * be URL-encoded (buildReferrerValue ships pre-encoded); blank/null values
     * are omitted.
     */
    private fun listingUri(prefix: String, packageName: String, referrer: String?): Uri {
        val sb = StringBuilder(prefix).append(packageName)
        if (!referrer.isNullOrBlank()) {
            sb.append("&referrer=").append(referrer)
        }
        return Uri.parse(sb.toString())
    }
}
