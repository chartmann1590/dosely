package com.dosely.app.data.feedback

import android.content.Context
import android.os.Build
import android.os.StatFs
import java.util.Locale
import java.util.TimeZone

/**
 * Collects generic, non-sensitive device/app diagnostics attached to feedback
 * reports. Never collects contacts, location, credentials, files, or tokens.
 */
object DiagnosticsHelper {

    /** Markdown diagnostics block matching the worker/app issue format. */
    fun collect(context: Context): String {
        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = pkgInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toString()
            else @Suppress("DEPRECATION") it.versionCode.toString()
        } ?: "unknown"

        val memInfo = android.app.ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
                ?.getMemoryInfo(it)
        }
        val storage = runCatching { StatFs(context.filesDir.path) }.getOrNull()

        return buildString {
            appendLine("## Diagnostics")
            appendLine()
            appendLine("- App: ${pm.getApplicationLabel(pm.getApplicationInfo(context.packageName, 0))}")
            appendLine("- Package: ${context.packageName}")
            appendLine("- Version: $versionName ($versionCode)")
            appendLine("- Device: ${Build.BRAND} ${Build.MODEL}")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("- Locale: ${Locale.getDefault()}")
            appendLine("- Time Zone: ${TimeZone.getDefault().id}")
            if (storage != null) {
                appendLine(
                    "- Storage Free/Total: ${gigabytes(storage.availableBytes)} / ${gigabytes(storage.totalBytes)}",
                )
            }
            appendLine(
                "- Memory Free/Total: ${gigabytes(memInfo.availMem)} / ${gigabytes(memInfo.totalMem)}",
            )
            appendLine("- Timestamp: ${java.time.OffsetDateTime.now()}")
        }
    }

    private fun gigabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
}
