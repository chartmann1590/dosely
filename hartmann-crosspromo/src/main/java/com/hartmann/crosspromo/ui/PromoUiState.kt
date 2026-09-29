package com.hartmann.crosspromo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.repository.CrossPromoRepository

/** What the promo UI should render. */
sealed interface PromoUiState {
    /** Nothing to show: no cached data and refresh failed or disabled. Render nothing. */
    data object Hidden : PromoUiState

    /** Cached (possibly stale) content visible instantly. */
    data class Ready(val apps: List<PromoApp>, val fromCache: Boolean) : PromoUiState
}

/**
 * Small controller connecting Compose UI to the repository.
 * Failure policy: ANY failure → Hidden. Never an error surface.
 */
class PromoUiController(
    private val placement: String,
    private val limit: Int = 3,
    private val repository: CrossPromoRepository = CrossPromoRepository(),
) {
    var state by mutableStateOf<PromoUiState>(PromoUiState.Hidden)
        private set

    /** Reads cache, starts SWR refresh, updates state as data arrives. */
    fun load() {
        val source = HartmannCrossPromo.sourcePackage()
        val session = HartmannCrossPromo.sessionId()
        repository.refreshIfNeeded(source, placement, limit, session)
        // Emit cache instantly (repository dedupes the network side).
        com.hartmann.crosspromo.internal.launchInIo {
            val cached = runCatching { repository.getCached(source, placement) }.getOrNull()
            com.hartmann.crosspromo.internal.onMain {
                state = when {
                    cached == null || cached.apps.isEmpty() -> PromoUiState.Hidden
                    else -> PromoUiState.Ready(cached.apps, fromCache = true)
                }
            }
        }
    }
}
