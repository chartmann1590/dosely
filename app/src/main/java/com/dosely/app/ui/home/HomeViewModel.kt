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
    val injections: List<InjectionEntity> = emptyList(),
    val weights: List<WeightEntryEntity> = emptyList(),
    val medId: String = "",
    val waterMlToday: Int = 0,
    val proteinGramsToday: Int = 0,
    val recentSymptoms: List<String> = emptyList(),
    val lastSite: String = "",
    val nextSuggestedSite: String = "Abdomen · left",
    val daysUntilNextDose: Long? = null,
    val currentDoseMg: Double? = null,
)

class HomeViewModel(
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
) : ViewModel() {

    val ui: StateFlow<HomeUi> = combine(
        settingsRepo.settings,
        repo.injections,
        repo.weights,
        repo.journalEntries,
    ) { s, injections, weights, journal ->
        if (!s.onboarded) return@combine HomeUi(loaded = false)
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()
        val med = Medications.byId(s.medId)
        val next = DoseEngine.nextDose(today, s.intervalDays, s.firstDoseEpochDay, injections.filter { it.medId == s.medId })
        val stock = DoseEngine.stockStatus(today, s.pensOnHand, s.lowStockThreshold, s.intervalDays, injections)
        val current = weights.lastOrNull()?.grams?.toDouble()?.div(1000.0)
        val start = if (s.startWeightGrams > 0) s.startWeightGrams / 1000.0 else weights.firstOrNull()?.grams?.div(1000.0) ?: 0.0
        val goal = if (s.goalWeightGrams > 0) s.goalWeightGrams / 1000.0 else null
        val todayEntries = journal.filter { it.epochDay == todayEpoch }
        val water = todayEntries.sumOf { it.waterMl }
        val protein = todayEntries.sumOf { it.proteinGrams }
        val symptoms = todayEntries.map { it.symptom }.filter { it.isNotBlank() }

        val lastInj = injections.firstOrNull { !it.skipped && it.medId == s.medId }
        val lastSite = lastInj?.site ?: ""
        val allSites = com.dosely.sync.WatchContract.sites
        val siteIndex = allSites.indexOf(lastSite)
        val nextSite = if (siteIndex >= 0) allSites[(siteIndex + 1) % allSites.size] else allSites[0]

        val daysUntil = next.date?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }

        HomeUi(
            injections = injections,
            weights = weights,
            medId = s.medId,
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
            waterMlToday = water,
            proteinGramsToday = protein,
            recentSymptoms = symptoms,
            lastSite = lastSite,
            nextSuggestedSite = nextSite,
            daysUntilNextDose = daysUntil,
            currentDoseMg = lastInj?.doseMg ?: med.doses.firstOrNull() ?: 0.25,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUi())

    fun addWater(ml: Int = 250) {
        viewModelScope.launch { repo.addWater(ml) }
    }

    fun addProtein(grams: Int = 20) {
        viewModelScope.launch { repo.addProtein(grams) }
    }

    fun logQuickSymptom(name: String) {
        viewModelScope.launch { repo.logQuickSymptom(name) }
    }

    fun logDoseNow(site: String? = null, doseMg: Double? = null) {
        viewModelScope.launch {
            val s = settingsRepo.current()
            val today = LocalDate.now()
            val suggested = doseMg ?: Medications.suggestedDose(
                Medications.byId(s.medId),
                weeksOnTreatment = weeksSinceFirstDose(s.firstDoseEpochDay, today),
            )
            val selectedSite = site ?: ui.value.nextSuggestedSite
            repo.logInjection(
                com.dosely.app.data.db.InjectionEntity(
                    epochDay = today.toEpochDay(),
                    takenAtMillis = System.currentTimeMillis(),
                    medId = s.medId,
                    doseMg = suggested,
                    site = selectedSite,
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
