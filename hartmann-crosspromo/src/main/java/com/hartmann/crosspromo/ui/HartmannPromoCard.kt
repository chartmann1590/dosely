package com.hartmann.crosspromo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.launcher.PlayStoreLauncher
import com.hartmann.crosspromo.model.PromoApp

/**
 * One promoted app card ("More from Hartmann Studios" section).
 *
 * Accessibility: the whole card is one merged semantics node announcing
 * "App name — description — rated X stars — View App".
 * Impression: counted once per (placement, package) per process — never per
 * recomposition.
 * Icons: rendered with Coil when the host provides it; a neutral letter
 * avatar otherwise. Loading never blocks the main thread.
 */
@Composable
fun HartmannPromoCard(
    app: PromoApp,
    placement: String,
    rankPosition: Int,
    modifier: Modifier = Modifier,
    width: Dp? = null,
) {
    val context = LocalContext.current
    val source = HartmannCrossPromo.sourcePackage()
    val session = HartmannCrossPromo.sessionId()
    val analytics = HartmannCrossPromo.analyticsSink()
    val sdkVersion = HartmannCrossPromo.SDK_VERSION

    LaunchedEffect(app.packageName) {
        if (countImpressionOnce(placement, app.packageName)) {
            analytics.impression(
                sourcePackage = source,
                targetPackage = app.packageName,
                placement = placement,
                rankPosition = rankPosition,
                selectionType = app.selectionType,
                sessionId = session,
                recommendationRequestId = null,
                sdkVersion = sdkVersion,
            )
        }
    }

    val cardModifier = if (width != null) modifier.width(width) else modifier

    Card(
        modifier = cardModifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(app.name ?: app.packageName)
                    app.shortDescription?.let { append(". ").append(it) }
                    if (app.rating != null) append(". Rated ").append(app.rating).append(" stars")
                    append(". View App")
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PromoIcon(app = app, size = 48.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name ?: app.packageName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (!app.shortDescription.isNullOrBlank()) {
                    Text(
                        text = app.shortDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (app.rating != null) {
                    Text(
                        text = "★ " + app.rating.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            TextButton(onClick = {
                analytics.click(
                    sourcePackage = source,
                    targetPackage = app.packageName,
                    placement = placement,
                    rankPosition = rankPosition,
                    selectionType = app.selectionType,
                    sessionId = session,
                    recommendationRequestId = null,
                    sdkVersion = sdkVersion,
                )
                PlayStoreLauncher.openPlayStore(
                    context,
                    app.packageName,
                    com.hartmann.crosspromo.attribution.HartmannInstallAttribution
                        .buildReferrerValue(source, app.packageName)
                )
            }) {
                Text("View App")
            }
        }
    }
}

/** Icon with neutral fallback; uses Coil only when the host app provides it. */
@Composable
fun PromoIcon(app: PromoApp, size: Dp) {
    val fallbackText = (app.name ?: app.packageName).take(1).uppercase()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (app.iconUrl != null && coilAvailable()) {
            // Isolated in CoilIconLoader.kt so hosts without Coil never load it.
            CoilIcon(url = app.iconUrl, modifier = Modifier.size(size))
        } else {
            Surface(
                modifier = Modifier.size(size),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = fallbackText,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** Cheap, cached classpath probe — safe to call during composition. */
private val coilPresent: Boolean by lazy {
    runCatching { Class.forName("coil.compose.AsyncImage") }.isSuccess
}

private fun coilAvailable(): Boolean = coilPresent
