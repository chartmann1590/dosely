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
 */
object PlayStoreLauncher {

    private const val MARKET_SCHEME = "market://details?id="
    private const val WEB_URL = "https://play.google.com/store/apps/details?id="

    /** Resolves the intent that opens the Play listing, or null if impossible. */
    fun playListingIntent(context: Context, packageName: String): Intent? {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(MARKET_SCHEME + packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (context.packageManager.resolveActivity(market, 0) != null) {
            return market
        }
        // Browser fallback: return the web intent even when resolution is
        // uncertain — startActivity's ActivityNotFoundException path keeps
        // this safe on devices with no browser at all.
        return Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL + packageName)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Launches the Play listing; returns false when nothing could handle it. */
    fun openPlayStore(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val intent = playListingIntent(context, packageName) ?: return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL + packageName))
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
}
