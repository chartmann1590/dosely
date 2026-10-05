package com.dosely.app.billing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dosely.app.ui.components.SectionCard

@Composable
fun SubscriptionCard() {
    val context = LocalContext.current
    val manager = remember { SubscriptionManager.get(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val isAdFree = state.adFree == true
    val displayPrice = state.price ?: "$0.99"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAdFree) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isAdFree) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    color = if (isAdFree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        if (isAdFree) "DOSELY+ MEMBER" else "DOSELY+ AD-FREE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isAdFree) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                Text(
                    "$displayPrice / mo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                if (isAdFree) "You're enjoying an ad-free journey" else "Focus on your wellness, ad-free",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isAdFree) "Your monthly subscription is active. All banner advertisements and promotional cards are disabled."
                else "Upgrade to Dosely+ for just 99¢ a month. Remove all ads while supporting private, on-device GLP-1 companion development.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FeatureRow("🚫 100% Ad-Free Experience (No banners or sponsor rows)")
                FeatureRow("📈 Estimated Medication Level pharmacokinetic trends")
                FeatureRow("⌚ Android Wear OS wrist logging & instant sync")
                FeatureRow("💚 Complete privacy: on-device data, cancel anytime in Play")
            }

            Spacer(Modifier.height(16.dp))
            if (!isAdFree) {
                Button(
                    onClick = { (context as? Activity)?.let(manager::purchase) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                ) {
                    Text("Subscribe for $displayPrice / month")
                }
            } else {
                FilledTonalButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://play.google.com/store/account/subscriptions?sku=${SubscriptionManager.PRODUCT_ID}&package=${context.packageName}"),
                            ))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                ) {
                    Text("✨ Active Subscription · Manage in Play")
                }
            }

            if (com.dosely.app.BuildConfig.DEBUG) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = manager::toggleDebugAdFree,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isAdFree) "Disable Ad-Free (Debug Test)" else "Enable Ad-Free (Debug Test)")
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = manager::refresh) { Text("Restore purchases") }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/account/subscriptions?sku=${SubscriptionManager.PRODUCT_ID}&package=${context.packageName}"),
                        ))
                    }
                }) { Text("Manage in Play") }
            }
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeatureRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
