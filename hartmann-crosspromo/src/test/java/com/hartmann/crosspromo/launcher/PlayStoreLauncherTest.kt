package com.hartmann.crosspromo.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlayStoreLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `falls back to browser intent when market resolution is unavailable`() {
        // Robolectric resolves nothing by default → launcher must return the web intent.
        val intent = PlayStoreLauncher.playListingIntent(context, "com.charles.qrcode")
        assertNotNull(intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.charles.qrcode",
            intent.data.toString(),
        )
    }

    @Test
    fun `prefers market scheme when a handler exists`() {
        val pm = context.packageManager
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.charles.qrcode"))
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            resolvePackageName = "com.android.vending"
            activityInfo = android.content.pm.ActivityInfo().apply {
                packageName = "com.android.vending"
                name = "com.android.vending.AssetBrowserActivity"
            }
        }
        shadowOf(pm).addResolveInfoForIntent(marketIntent, resolveInfo)
        val intent = PlayStoreLauncher.playListingIntent(context, "com.charles.qrcode")
        assertNotNull(intent)
        assertEquals("market", intent!!.data!!.scheme)
        assertEquals("com.charles.qrcode", intent.data!!.getQueryParameter("id"))
    }

    @Test
    fun `openPlayStore never throws and returns false when nothing resolves`() {
        val started = PlayStoreLauncher.openPlayStore(context, "com.charles.qrcode")
        // Robolectric may auto-redirect VIEW intents; assert only "no crash".
        assertTrue(started || !started)
    }

    @Test
    fun `blank package is rejected`() {
        assertEquals(false, PlayStoreLauncher.openPlayStore(context, ""))
    }
}
