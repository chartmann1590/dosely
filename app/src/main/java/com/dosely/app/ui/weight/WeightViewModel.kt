package com.dosely.app.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class WeightUi(
    val loaded: Boolean = false,
    val entries: List<WeightEntryEntity> = emptyList(),
    val currentKg: Double? = null,
    val startKg: Double = 0.0,
    val goalKg: Double? = null,
    val changeKg: Double? = null,
    val toGoalKg: Double? = null,
    val weekAvgKg: Double? = null,
    val useImperial: Boolean = false,
)

class WeightViewModel(
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
) : ViewModel() {

    val ui: StateFlow<WeightUi> = combine(
        settingsRepo.settings,
        repo.weights,
    ) { s, weights ->
        val current = weights.lastOrNull()?.grams?.toDouble()?.div(1000.0)
        val start = if (s.startWeightGrams > 0) s.startWeightGrams / 1000.0
        else weights.firstOrNull()?.grams?.div(1000.0) ?: 0.0
        val goal = if (s.goalWeightGrams > 0) s.goalWeightGrams / 1000.0 else null
        val weekAgo = LocalDate.now().minusDays(7).toEpochDay()
        val week = weights.filter { it.epochDay >= weekAgo }
        val weekAvg = if (week.isNotEmpty()) week.sumOf { it.grams } / week.size / 1000.0 else null
        WeightUi(
            loaded = s.onboarded,
            entries = weights,
            currentKg = current,
            startKg = start,
            goalKg = goal,
            changeKg = current?.let { if (start > 0) it - start else null },
            toGoalKg = goal?.let { g -> current?.let { g - it } },
            weekAvgKg = weekAvg,
            useImperial = s.useImperial,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUi())

    fun logWeight(kg: Double) {
        viewModelScope.launch {
            repo.upsertWeight(
                WeightEntryEntity(
                    epochDay = LocalDate.now().toEpochDay(),
                    grams = (kg * 1000).toInt(),
                    loggedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteWeight(id) }
}
