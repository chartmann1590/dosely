package com.dosely.app.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dosely.app.translate.S

/**
 * In-app legal documents. Google Play requires a privacy policy to be available
 * in-app (not only as a console URL), and a ToS is strongly recommended for
 * health-adjacent apps.
 */
@Composable
fun LegalScreen(isPrivacy: Boolean, onBack: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S("legal_back"))
            }
            Text(
                S(if (isPrivacy) "legal_privacy_title" else "legal_tos_title"),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        Text(
            S("legal_last_updated"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        if (isPrivacy) PrivacyPolicy() else TermsOfService()
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun H(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun P(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun TermsOfService() {
    H("1. Acceptance")
    P("By installing or using Dosely (the \"App\"), you agree to these Terms of Service. If you do not agree, do not use the App.")
    H("2. Not a medical device")
    P("Dosely is a personal tracking and journaling utility for people taking GLP-1 medications. It is NOT a medical device and does not provide medical advice, diagnosis, or treatment. The built-in AI coach is an automated language model running on your device. Its output can be wrong, incomplete, or misleading. Always consult a qualified healthcare professional before making decisions about your medication, doses, diet, or health. Never delay or disregard professional medical advice because of anything in the App.")
    H("3. Your data")
    P("All tracking data (injections, weight, notes, chats) is stored locally on your device. You can delete it at any time by clearing the app's data or uninstalling. We do not operate servers that receive your health data.")
    H("4. Ads")
    P("The App is ad-supported and displays ads served by Google (AdMob). Ads are subject to Google's policies. You can manage ad personalization at any time via the Privacy options entry in Settings. Ads must not be manipulated or blocked by automated means.")
    H("5. Acceptable use")
    P("You agree not to reverse engineer the App, violate applicable laws, misuse the reporting tools, or interfere with the ad services.")
    H("6. AI-generated content")
    P("Responses from the AI coach are generated automatically and are labeled as AI-generated. You can report inappropriate or inaccurate AI content in-app using the report button on any AI message. Reported content is removed from your chat history.")
    H("7. No warranty")
    P("The App is provided \"as is\" without warranties of any kind. We do not warrant that the App will be uninterrupted, error-free, or that tracking or reminders will always be accurate or timely.")
    H("8. Limitation of liability")
    P("To the maximum extent permitted by law, we are not liable for any indirect, incidental, or consequential damages, including damages arising from health decisions made without professional advice, missed doses, or data loss.")
    H("9. Changes")
    P("We may update these terms. Material changes will be reflected in the App with an updated \"last updated\" date. Continued use constitutes acceptance.")
    H("10. Contact")
    P("Questions: me@charleshartmann.com")
}

@Composable
private fun PrivacyPolicy() {
    H("Overview")
    P("Dosely is built privacy-first. Your health tracking data (injections, doses, weight entries, notes, and AI coach chats) is stored only on your device, in the app's private storage. We do not collect, transmit, or sell this data. There is no account, no cloud sync, and no analytics SDK.")
    H("Data we store on your device")
    P("- Injections: date, medication, dose, injection site, notes\n- Weight entries: date and value\n- AI coach chats: your prompts and the model's answers\n- Settings: schedule, stock, language, theme preferences\nAll of this can be erased with \"Clear data\" in Android settings or by uninstalling.")
    H("Data collected by advertising")
    P("The App shows ads via Google AdMob. When ads are shown, Google and its partners may collect and process: device identifiers (including the advertising ID), approximate location (country/time zone), device and network information, and interaction data, to serve and measure ads. Where required by law (EEA, UK, and regulated US states), we obtain your consent through Google's User Messaging Platform before serving personalized ads. You can change your choices anytime under Settings - Privacy options, or opt out of ads personalization in Android Settings - Privacy - Ads.")
    H("Data safety summary (Google Play)")
    P("- Health data (injections, weight): collected? No. Shared? No. Stored on device only.\n- Personal info: collected? No.\n- Device/other IDs (advertising ID): collected by ad provider when ads are shown. Shared with Google for ad serving. Encrypted in transit: yes. Deletion: remove ads data via Android ad settings or uninstall.\n- App activity (AI chat): stored on device only; the \"report\" action removes the message locally and does not transmit it.")
    H("Children")
    P("The App is not directed to children under 13 and is intended for adults managing prescription medication. We do not knowingly collect data from children.")
    H("Permissions")
    P("- POST_NOTIFICATIONS: to deliver injection, refill and weigh-in reminders.\n- INTERNET / AD_ID: to download the optional AI model and language packs, and to show ads.\nNo location, contacts, camera, microphone, or health-store permissions are used.")
    H("AI features")
    P("The AI coach runs a language model locally on your device. Prompts and responses never leave your device. AI output is labeled as AI-generated and can be reported and removed in-app. It is not medical advice.")
    H("Changes to this policy")
    P("We will update this policy when features or data practices change, with a new \"last updated\" date.")
    H("Contact")
    P("Privacy questions: me@charleshartmann.com")
}
