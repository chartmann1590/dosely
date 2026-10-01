package com.dosely.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dosely.app.ads.AdsManager
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

/**
 * Anchored adaptive banner. Must only be composed when AdsManager.canRequestAds
 * is true (consent gathered per UMP).
 */
@Composable
fun AdBanner(
    modifier: Modifier = Modifier,
    adUnitId: String = AdsManager.BANNER_AD_UNIT,
) {
    val context = LocalContext.current
    val adView = remember {
        val displayMetrics = context.resources.displayMetrics
        val widthDp = (displayMetrics.widthPixels / displayMetrics.density).toInt().coerceAtLeast(320)
        AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
            setAdUnitId(adUnitId)
            adListener = object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    // No fill / network issue: banner simply stays empty.
                }
            }
        }
    }

    DisposableEffect(adView) {
        adView.loadAd(AdRequest.Builder().build())
        onDispose {
            adView.destroy()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { adView },
    )
}
