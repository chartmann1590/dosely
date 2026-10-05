package com.dosely.app.ui.doses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.domain.Medications
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DosesUi(
    val loaded: Boolean = false,
    val medName: String = "",
    val intervalDays: Int = 7,
    val entries: List<InjectionEntity> = emptyList(),
    val suggestedDoseMg: Double = 0.25,
    val canSkip: Boolean = false,
    val availableDoses: List<Double> = emptyList(),
)

class DosesViewModel(
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
) : ViewModel() {

    val ui: StateFlow<DosesUi> = combine(
        settingsRepo.settings,
        repo.injections,
    ) { s, injections ->
        val today = LocalDate.now()
        val med = Medications.byId(s.medId)
        val suggested = injections.firstOrNull { !it.skipped && it.medId == s.medId }?.doseMg ?: med.doses.first()
        val takenToday = injections.any { it.epochDay == today.toEpochDay() && !it.skipped }
        DosesUi(
            loaded = s.onboarded,
            medName = med.brand,
            intervalDays = s.intervalDays,
            entries = injections,
            suggestedDoseMg = suggested,
            canSkip = !takenToday,
            availableDoses = med.doses,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DosesUi())

    fun logInjection(site: String, mg: Double, notes: String, date: LocalDate = LocalDate.now(), time: java.time.LocalTime = java.time.LocalTime.now(), existing: InjectionEntity? = null) {
        if (!mg.isFinite() || mg <= 0 || mg > 100 || date.isAfter(LocalDate.now())) return
        viewModelScope.launch {
            val entry = InjectionEntity(
                    id = existing?.id ?: 0,
                    epochDay = date.toEpochDay(),
                    takenAtMillis = date.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    medId = existing?.medId ?: settingsRepo.current().medId,
                    doseMg = mg,
                    site = site,
                    notes = notes,
                )
            if (existing == null) repo.logInjection(entry) else repo.updateInjection(entry)
        }
    }

    fun skipToday() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val s = settingsRepo.current()
            val history = repo.injections.first()
            val due = com.dosely.app.domain.DoseEngine.nextDose(today, s.intervalDays, s.firstDoseEpochDay,
                history.filter { it.medId == s.medId }).date.coerceAtMost(today)
            repo.logInjection(
                InjectionEntity(
                    epochDay = due.toEpochDay(),
                    takenAtMillis = System.currentTimeMillis(),
                    medId = settingsRepo.current().medId,
                    doseMg = 0.0,
                    site = "Other",
                    notes = "",
                    skipped = true,
                ),
            )
        }
    }

    fun delete(entry: InjectionEntity) = viewModelScope.launch { repo.deleteInjection(entry) }

    private fun weeksOn(today: LocalDate, firstDay: Long): Int {
        if (firstDay <= 0) return 0
        return ((today.toEpochDay() - firstDay) / 7).toInt().coerceAtLeast(0)
    }
}
