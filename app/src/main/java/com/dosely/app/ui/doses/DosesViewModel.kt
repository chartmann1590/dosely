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
        val suggested = Medications.suggestedDose(med, weeksOn(today, s.firstDoseEpochDay))
        val takenToday = injections.any { it.epochDay == today.toEpochDay() && !it.skipped }
        DosesUi(
            loaded = s.onboarded,
            medName = med.brand,
            intervalDays = s.intervalDays,
            entries = injections,
            suggestedDoseMg = suggested,
            canSkip = !takenToday,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DosesUi())

    fun logInjection(site: String, mg: Double, notes: String) {
        viewModelScope.launch {
            repo.logInjection(
                InjectionEntity(
                    epochDay = LocalDate.now().toEpochDay(),
                    takenAtMillis = System.currentTimeMillis(),
                    medId = settingsRepo.current().medId,
                    doseMg = mg,
                    site = site,
                    notes = notes,
                ),
            )
        }
    }

    fun skipToday() {
        viewModelScope.launch {
            val today = LocalDate.now()
            repo.logInjection(
                InjectionEntity(
                    epochDay = today.toEpochDay(),
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
