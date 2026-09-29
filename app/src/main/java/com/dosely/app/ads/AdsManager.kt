package com.dosely.app.ads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Central ad plumbing: UMP consent gathering (GDPR/EEA + regulated US states),
 * Mobile Ads initialization, and the privacy-options entry point state required
 * by Google Play's ads policy.
 */
class AdsManager constructor(context: Context) {

    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(context)

    /** True once the user has consented (or consent is not required) and ads may load. */
    @Volatile
    var canRequestAds: Boolean = false
        private set

    /** Whether the app must show a "Privacy options" entry point (Settings). */
    val isPrivacyOptionsRequired: Boolean
        get() = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    private var initDone = false

    /**
     * Call on every app launch. Gathers/refreshes consent, shows the UMP form when
     * required, initializes Mobile Ads as soon as allowed, and reports completion
     * (or consent error) via [onDone]. Idempotent within a session.
     */
    fun gatherConsent(activity: Activity, onDone: (error: String?) -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    postConsent(activity)
                    onDone(formError?.toString())
                }
            },
            { requestError ->
                // Consent failed: fall back to previous session's status if any.
                postConsent(activity)
                onDone(requestError.message)
            },
        )
    }

    /** Presents the privacy options form from the Settings entry point. */
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: (String?) -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            onDismissed(formError?.toString())
        }
    }

    private fun postConsent(activity: Activity) {
        canRequestAds = consentInformation.canRequestAds()
        if (canRequestAds && !initDone) {
            initDone = true
            MobileAds.initialize(activity) {}
        }
    }

    companion object {
        @Volatile private var instance: AdsManager? = null

        fun get(context: Context): AdsManager =
            instance ?: synchronized(this) {
                instance ?: AdsManager(context.applicationContext).also { instance = it }
            }

        /** Google's always-on test banner unit. Replace with the production unit before release. */
        const val BANNER_AD_UNIT =
            "ca-app-pub-3940256099942544/9214589741"
    }
}
