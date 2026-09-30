package com.hartmann.crosspromo.ui

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
                if (app.rating != null || !app.installText.isNullOrBlank()) {
                    Text(
                        text = buildString {
                            app.rating?.let { r ->
                                append("★ ").append(formatRating(r))
                                app.ratingCount?.let { c ->
                                    if (c > 0) append(" (").append(compactCount(c)).append(")")
                                }
                            }
                            if (!app.installText.isNullOrBlank()) {
                                if (isNotEmpty()) append("  ·  ")
                                append(compactInstalls(app.installText))
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * Icon rendering: real app icon when possible, letter avatar otherwise.
 * Priority: cached bitmap → Coil pipeline (host provides Coil) → built-in
 * loader (no host dependencies) → letter avatar.
 */
@Composable
fun PromoIcon(app: PromoApp, size: Dp) {
    val fallbackText = (app.name ?: app.packageName).take(1).uppercase()
    var bitmap by remember(app.iconUrl) {
        mutableStateOf(app.iconUrl?.let { PromoIconLoader.cached(it) })
    }

    LaunchedEffect(app.iconUrl) {
        val url = app.iconUrl ?: return@LaunchedEffect
        if (bitmap == null && !coilAvailable()) {
            // Dependency-free path: fetch + decode off the main thread; the
            // cache callback recomposes this composable when done.
            PromoIconLoader.load(url) { bitmap = PromoIconLoader.cached(url) }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        when {
            loaded != null -> {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = null, // decorative; the card carries its own description
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size),
                )
            }
            app.iconUrl != null && coilAvailable() -> {
                // Host provides Coil: use its pipeline (memory/disk caching).
                CoilIcon(url = app.iconUrl, modifier = Modifier.size(size))
            }
            else -> Surface(
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

/** "4.5" — trims trailing zeros of the Play rating. */
internal fun formatRating(r: Double): String {
    val rounded = (Math.round(r * 10) / 10.0)
    return if (rounded == Math.floor(rounded)) rounded.toInt().toString() else rounded.toString()
}

/** 1234 → "1.2k", 5_600_000 → "5.6M" — for rating counts. */
internal fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> trimOne((n / 100_000).toDouble() / 10) + "M"
    n >= 1_000 -> trimOne((n / 100).toDouble() / 10) + "K"
    else -> n.toString()
}

/** Play's "100+" / "10,000+" → "100+" / "10K+" — keeps the + suffix. */
internal fun compactInstalls(text: String): String {
    val digits = text.takeWhile { it.isDigit() }.replace(",", "")
    if (digits.isEmpty()) return text
    val n = digits.toLong()
    val compact = when {
        n >= 1_000_000 -> trimOne((n / 100_000).toDouble() / 10) + "M"
        n >= 10_000 -> trimOne((n / 1_000).toDouble()) + "K"
        else -> return text // Play-style "100+", "10+", "5" — already short
    }
    val suffix = text.dropWhile { it.isDigit() || it == ',' }
    return compact + suffix
}

private fun trimOne(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else v.toString()
