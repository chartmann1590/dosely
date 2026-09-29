package com.hartmann.crosspromo.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hartmann.crosspromo.model.PromoApp

/**
 * Horizontal carousel for placements like the bottom of a home screen.
 * Cards keep a comfortable fixed width across phones/tablets and both
 * orientations; the row scrolls horizontally and is hidden when empty.
 */
@Composable
fun HartmannPromoCarousel(
    placement: String,
    modifier: Modifier = Modifier,
    title: String = "More from Hartmann Studios",
    limit: Int = 3,
    controller: PromoUiController = rememberPromoController(placement, limit),
) {
    LaunchedEffect(placement) { controller.load() }

    when (val s = controller.state) {
        is PromoUiState.Ready -> {
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val cardWidth = if (screenWidth > 600.dp) 340.dp else screenWidth - 64.dp
            androidx.compose.foundation.layout.Column(modifier = modifier) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(s.apps, key = { _, app -> app.packageName }) { index, app ->
                        HartmannPromoCard(
                            app = app,
                            placement = placement,
                            rankPosition = index + 1,
                            width = cardWidth,
                        )
                    }
                }
            }
        }
        PromoUiState.Hidden -> Unit
    }
}

/** Re-export for XML-based hosts (see HartmannPromoCarousel docs). */
typealias CarouselApp = PromoApp
