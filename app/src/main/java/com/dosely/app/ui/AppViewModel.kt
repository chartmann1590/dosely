package com.dosely.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.data.prefs.Settings
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.domain.DoseEngine
import com.dosely.app.translate.TranslationService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Holds app-wide state and activates the saved language on startup. */
class AppViewModel(
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
    private val translationService: TranslationService,
) : ViewModel() {

    val settings: StateFlow<Settings?> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val injections: StateFlow<List<InjectionEntity>> = repo.injections
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val weights: StateFlow<List<WeightEntryEntity>> = repo.weights
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                translationService.start(s.appLanguageTag)
            }
        }
    }

    fun onLanguageChanged(tag: String) {
        viewModelScope.launch { translationService.activate(tag) }
    }

    fun logDoseToday(site: String, mg: Double, notes: String) {
        viewModelScope.launch {
            val today = LocalDate.now()
            repo.logInjection(
                InjectionEntity(
                    epochDay = today.toEpochDay(),
                    takenAtMillis = System.currentTimeMillis(),
                    medId = settingsRepo.current().medId,
                    doseMg = mg,
                    site = site,
                    notes = notes,
                ),
            )
        }
    }

    fun logWeight(grams: Int) {
        viewModelScope.launch {
            val today = LocalDate.now()
            repo.upsertWeight(
                WeightEntryEntity(
                    epochDay = today.toEpochDay(),
                    grams = grams,
                    loggedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }
}
