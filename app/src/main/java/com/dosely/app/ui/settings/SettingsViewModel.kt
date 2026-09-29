package com.dosely.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.ai.DownloadState
import com.dosely.app.ai.GemmaModelCatalog
import com.dosely.app.ai.ModelDownloadManager
import com.dosely.app.data.prefs.Settings
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.translate.AppLanguages
import com.dosely.app.translate.TranslationService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUi(
    val settings: Settings? = null,
    val langBusyTag: String? = null,
    val langError: String? = null,
    val downloadedPacks: List<String> = emptyList(),
    val aiState: DownloadState = DownloadState.Idle,
    val aiOnDeviceBytes: Long = 0,
    val engineState: com.dosely.app.ai.CoachEngine.EngineState? = null,
    val privacyOptionsRequired: Boolean = false,
)

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val translationService: TranslationService,
    private val downloadManager: ModelDownloadManager,
    private val coachEngine: com.dosely.app.ai.CoachEngine,
    private val adsManager: com.dosely.app.ads.AdsManager,
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui

    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            translationService.state.collect { st ->
                _ui.value = _ui.value.copy(
                    langBusyTag = if (st.busy) _ui.value.settings?.appLanguageTag else null,
                    langError = st.error,
                )
            }
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                downloadedPacks = translationService.downloadedPackTags(),
                aiOnDeviceBytes = downloadManager.onDeviceBytes(GemmaModelCatalog.default),
                engineState = coachEngine.state.value,
                privacyOptionsRequired = adsManager.isPrivacyOptionsRequired,
            )
        }
    }

    fun selectLanguage(tag: String) {
        viewModelScope.launch {
            settingsRepo.setAppLanguage(tag)
            translationService.activate(tag)
            _ui.value = _ui.value.copy(downloadedPacks = translationService.downloadedPackTags())
        }
    }

    fun removeLanguagePack(tag: String) {
        viewModelScope.launch {
            translationService.deletePack(tag)
            _ui.value = _ui.value.copy(downloadedPacks = translationService.downloadedPackTags())
        }
    }

    fun startModelDownload() {
        if (_ui.value.aiState is DownloadState.Downloading) return
        viewModelScope.launch {
            downloadManager.download(GemmaModelCatalog.default).collect { state ->
                _ui.value = _ui.value.copy(
                    aiState = state,
                    aiOnDeviceBytes = downloadManager.onDeviceBytes(GemmaModelCatalog.default),
                )
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            coachEngine.release()
            downloadManager.deleteModel(GemmaModelCatalog.default)
            _ui.value = _ui.value.copy(aiState = DownloadState.Idle, aiOnDeviceBytes = 0)
        }
    }

    fun setInterval(days: Int) = viewModelScope.launch { settingsRepo.setInterval(days) }
    fun setPens(n: Int) = viewModelScope.launch { settingsRepo.setPens(n) }
    fun setLowStockThreshold(n: Int) = viewModelScope.launch { settingsRepo.setLowStockThreshold(n) }
    fun setReminderTime(hour: Int, minute: Int) =
        viewModelScope.launch { settingsRepo.setReminderTime(hour, minute) }
    fun setRemindersEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setRemindersEnabled(v) }
    fun setRefillEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setRefillEnabled(v) }
    fun setWeeklyEnabled(v: Boolean) = viewModelScope.launch { settingsRepo.setWeeklyEnabled(v) }
    fun setImperial(v: Boolean) = viewModelScope.launch { settingsRepo.setImperial(v) }
    fun setTheme(mode: String) = viewModelScope.launch { settingsRepo.setTheme(mode) }

    fun showPrivacyOptions(context: android.content.Context) {
        val activity = context as? android.app.Activity ?: return
        adsManager.showPrivacyOptionsForm(activity) { }
        viewModelScope.launch {
            // Refresh requirement status after the user may have changed options.
            _ui.value = _ui.value.copy(privacyOptionsRequired = adsManager.isPrivacyOptionsRequired)
        }
    }

    fun requestPinWidget(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
                manager.requestPinGlanceAppWidget(
                    com.dosely.app.widget.DoselyWidgetReceiver::class.java,
                    com.dosely.app.widget.DoselyWidget,
                )
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
    fun setGoalWeightKg(kg: Double?) = viewModelScope.launch {
        settingsRepo.setGoalWeightGrams(((kg ?: 0.0) * 1000).toInt())
    }
    fun setMed(id: String) = viewModelScope.launch {
        settingsRepo.setMed(id)
        settingsRepo.setInterval(com.dosely.app.domain.Medications.byId(id).intervalDays)
    }

    fun refreshPacks() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(downloadedPacks = translationService.downloadedPackTags())
        }
    }
}
