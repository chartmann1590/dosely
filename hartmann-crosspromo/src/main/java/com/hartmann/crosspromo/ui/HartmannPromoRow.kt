package com.hartmann.crosspromo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hartmann.crosspromo.model.PromoApp

/**
 * Vertical promo section for placements like Settings/About screens.
 *
 * Failure policy: renders NOTHING when there is no data — no loading
 * spinners, no error text. The host layout is never disrupted.
 */
@Composable
fun HartmannPromoRow(
    placement: String,
    modifier: Modifier = Modifier,
    title: String = "More from Hartmann Studios",
    limit: Int = 3,
    controller: PromoUiController = rememberPromoController(placement, limit),
) {
    LaunchedEffect(placement) { controller.load() }

    when (val s = controller.state) {
        is PromoUiState.Ready -> {
            Column(modifier = modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                s.apps.forEachIndexed { index, app ->
                    HartmannPromoCard(app = app, placement = placement, rankPosition = index + 1)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        PromoUiState.Hidden -> Unit
    }
}

@Composable
fun rememberPromoController(placement: String, limit: Int): PromoUiController {
    val raw = limit.coerceIn(1, 6)
    return androidx.compose.runtime.remember(placement, raw) { PromoUiController(placement, raw) }
}
