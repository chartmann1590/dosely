package com.hartmann.crosspromo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hartmann.crosspromo.HartmannCrossPromo
import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.repository.CrossPromoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    /** Re-roll on every load() instead of only when the cache goes stale. */
    private val refreshEveryEntry: Boolean = true,
) {
    var state by mutableStateOf<PromoUiState>(PromoUiState.Hidden)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    /**
     * Shows the cached set instantly, then re-rolls in the background so a
     * fresh pick set replaces it — [refreshEveryEntry] makes each screen
     * entry show a different selection. The cache flow keeps the UI in sync
     * whichever response lands (also covers the cold-install first load).
     * Call [dispose] when the hosting screen goes away.
     */
    fun load() {
        val source = HartmannCrossPromo.sourcePackage()
        val session = HartmannCrossPromo.sessionId()
        repository.refreshIfNeeded(source, placement, limit, session, force = refreshEveryEntry)
        if (observing) return
        observing = true
        scope.launch {
            runCatching {
                repository.observe(source, placement).collect { response ->
                    com.hartmann.crosspromo.internal.onMain {
                        state = when {
                            response == null || response.apps.isEmpty() -> PromoUiState.Hidden
                            else -> PromoUiState.Ready(response.apps, fromCache = true)
                        }
                    }
                }
            }
        }
    }

    /** Stops cache observation. Optional: the scope is small and screen-scoped. */
    fun dispose() {
        scope.cancel()
    }
}
