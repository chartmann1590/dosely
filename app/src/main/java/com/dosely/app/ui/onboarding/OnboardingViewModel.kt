package com.dosely.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.ai.DownloadState
import com.dosely.app.ai.GemmaModelCatalog
import com.dosely.app.ai.ModelDownloadManager
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.domain.Medication
import com.dosely.app.domain.Medications
import com.dosely.app.domain.Units
import com.dosely.app.translate.AppLanguages
import com.dosely.app.translate.TranslationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate

data class OnboardingUi(
    val stage: Int = 0,
    val medId: String = "semaglutide",
    val intervalDays: Int = 7,
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    val firstDoseDay: Long = LocalDate.now().toEpochDay(),
    val firstDoseText: String = "",
    val pens: Int = 2,
    val startWeightDisplay: String = "",
    val goalWeightDisplay: String = "",
    val useImperial: Boolean = false,
    val langTag: String = "",
    val langBusy: Boolean = false,
    val langError: String? = null,
    val aiDownload: DownloadState = DownloadState.Idle,
    val finished: Boolean = false,
)

class OnboardingViewModel(
    private val settingsRepo: SettingsRepository,
    private val translationService: TranslationService,
    private val downloadManager: ModelDownloadManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui

    val meds: List<Medication> = Medications.all

    private var downloadJob: Job? = null

    fun update(transform: (OnboardingUi) -> OnboardingUi) {
        _ui.value = transform(_ui.value)
    }

    fun selectLanguage(tag: String) {
        _ui.value = _ui.value.copy(langTag = tag, langBusy = true, langError = null)
        viewModelScope.launch {
            translationService.activate(tag)
            // English needs no pack; mark ready when service settles.
            translationService.state.collect { st ->
                if (!st.busy) {
                    _ui.value = _ui.value.copy(
                        langBusy = false,
                        langError = st.error,
                    )
                    return@collect
                }
            }
        }
    }

    fun startModelDownload() {
        if (downloadJob?.isActive == true) return
        val option = GemmaModelCatalog.default
        downloadJob = viewModelScope.launch {
            downloadManager.download(option).collect { state ->
                _ui.value = _ui.value.copy(aiDownload = state)
            }
        }
    }

    fun complete() {
        viewModelScope.launch {
            val ui = _ui.value
            val med = Medications.byId(ui.medId)
            val displayToKg = { text: String ->
                val v = text.replace(',', '.').toDoubleOrNull() ?: 0.0
                Units.toKg(v, ui.useImperial)
            }
            settingsRepo.setMed(med.id)
            settingsRepo.setInterval(ui.intervalDays)
            settingsRepo.setReminderTime(ui.reminderHour, ui.reminderMinute)
            settingsRepo.setFirstDoseDay(ui.firstDoseDay)
            settingsRepo.setPens(ui.pens)
            settingsRepo.setImperial(ui.useImperial)
            settingsRepo.setStartWeightGrams((displayToKg(ui.startWeightDisplay) * 1000).toInt())
            settingsRepo.setGoalWeightGrams((displayToKg(ui.goalWeightDisplay) * 1000).toInt())
            settingsRepo.setAppLanguage(ui.langTag)
            settingsRepo.setOnboarded(true)
            _ui.value = ui.copy(finished = true)
        }
    }
}
