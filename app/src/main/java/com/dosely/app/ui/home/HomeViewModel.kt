package com.dosely.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.domain.DoseEngine
import com.dosely.app.domain.Medications
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUi(
    val loaded: Boolean = false,
    val medName: String = "",
    val nextDoseDate: LocalDate? = null,
    val overdue: Boolean = false,
    val takenToday: Boolean = false,
    val pens: Int = 0,
    val lowStock: Boolean = false,
    val outOfStock: Boolean = false,
    val runsOutOn: LocalDate? = null,
    val currentWeightKg: Double? = null,
    val startWeightKg: Double = 0.0,
    val goalWeightKg: Double? = null,
    val totalChangeKg: Double? = null,
    val toGoalKg: Double? = null,
    val useImperial: Boolean = false,
    val streak: Int = 0,
    val adherencePct: Int = 100,
)

class HomeViewModel(
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
) : ViewModel() {

    val ui: StateFlow<HomeUi> = combine(
        settingsRepo.settings,
        repo.injections,
        repo.weights,
    ) { s, injections, weights ->
        if (!s.onboarded) return@combine HomeUi(loaded = false)
        val today = LocalDate.now()
        val med = Medications.byId(s.medId)
        val next = DoseEngine.nextDose(today, s.intervalDays, s.firstDoseEpochDay, injections)
        val stock = DoseEngine.stockStatus(today, s.pensOnHand, s.lowStockThreshold, s.intervalDays, injections)
        val current = weights.lastOrNull()?.grams?.toDouble()?.div(1000.0)
        val start = if (s.startWeightGrams > 0) s.startWeightGrams / 1000.0 else weights.firstOrNull()?.grams?.div(1000.0) ?: 0.0
        val goal = if (s.goalWeightGrams > 0) s.goalWeightGrams / 1000.0 else null
        HomeUi(
            loaded = true,
            medName = med.brand,
            nextDoseDate = next.date,
            overdue = next.overdue,
            takenToday = next.takenToday,
            pens = stock.pens,
            lowStock = stock.low,
            outOfStock = stock.out,
            runsOutOn = stock.runsOutOn,
            currentWeightKg = current,
            startWeightKg = start,
            goalWeightKg = goal,
            totalChangeKg = current?.let { if (start > 0) it - start else null },
            toGoalKg = goal?.let { g -> current?.let { g - it } },
            useImperial = s.useImperial,
            streak = DoseEngine.streak(today, s.intervalDays, injections),
            adherencePct = (DoseEngine.adherence(today, s.intervalDays, injections) * 100).toInt(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())

    fun logDoseNow() {
        viewModelScope.launch {
            val s = settingsRepo.current()
            val today = LocalDate.now()
            val injections = repo.injections.first()
            val suggested = Medications.suggestedDose(
                Medications.byId(s.medId),
                weeksOnTreatment = weeksSinceFirstDose(s.firstDoseEpochDay, today),
            )
            repo.logInjection(
                com.dosely.app.data.db.InjectionEntity(
                    epochDay = today.toEpochDay(),
                    takenAtMillis = System.currentTimeMillis(),
                    medId = s.medId,
                    doseMg = suggested,
                    site = "Abdomen",
                    notes = "",
                ),
            )
        }
    }

    fun logWeight(grams: Int) {
        viewModelScope.launch {
            repo.upsertWeight(
                com.dosely.app.data.db.WeightEntryEntity(
                    epochDay = LocalDate.now().toEpochDay(),
                    grams = grams,
                    loggedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun weeksSinceFirstDose(firstDay: Long, today: LocalDate): Int {
        if (firstDay <= 0) return 0
        val days = (today.toEpochDay() - firstDay).toInt()
        return (days / 7).coerceAtLeast(0)
    }
}
