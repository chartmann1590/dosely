package com.hartmann.crosspromo.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

/**
 * Coil-backed icon rendering. ISOLATED in this file so host apps that do not
 * include Coil never load these classes (the caller checks class availability
 * first — ART resolves method bodies lazily, so this is crash-safe).
 *
 * Host apps that want real icons add:
 *     implementation("io.coil-kt:coil-compose:2.6.0")
 * Icons are cached by Coil's memory/disk layers; loading never blocks the
 * main thread and a failure simply leaves the neutral fallback in place.
 */
@Composable
fun CoilIcon(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null, // decorative; the card carries its own description
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
