package com.dosely.app.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.ai.CoachEngine
import com.dosely.app.data.prefs.Settings
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ChatBubble(
    val id: Long,
    val isUser: Boolean,
    val text: String,
)

data class CoachUi(
    val engineState: CoachEngine.EngineState = CoachEngine.EngineState.NoModel,
    val generating: Boolean = false,
    val partial: String = "",
    val error: String? = null,
)

class CoachViewModel(
    private val engine: CoachEngine,
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(CoachUi())
    val ui: StateFlow<CoachUi> = _ui

    val messages: StateFlow<List<ChatBubble>> = repo.chat
        .map { list -> list.map { ChatBubble(it.id, it.isUser, it.text) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var respondJob: Job? = null

    init {
        // Mirror the engine state into the UI (NoModel -> Loading -> Ready/Error).
        viewModelScope.launch {
            engine.state.collect { st ->
                _ui.value = _ui.value.copy(engineState = st)
            }
        }
    }

    fun prepare() {
        viewModelScope.launch {
            val s = settingsRepo.current()
            engine.ensureLoaded(systemInstruction = buildSystemInstruction(s))
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _ui.value.generating) return
        respondJob = viewModelScope.launch {
            _ui.value = _ui.value.copy(generating = true, partial = "", error = null)
            repo.addChatMessage(isUser = true, text = trimmed)
            try {
                // Await engine readiness (no-op if already loaded; queued behind load otherwise).
                engine.ensureLoaded(systemInstruction = buildSystemInstruction(settingsRepo.current()))
                engine.respond(trimmed).collect { partial ->
                    _ui.value = _ui.value.copy(partial = partial)
                }
                // Final message collected via last emitted full text
                repo.addChatMessage(isUser = false, text = _ui.value.partial.ifBlank { "…" })
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(error = t.message ?: t.javaClass.simpleName)
            } finally {
                _ui.value = _ui.value.copy(generating = false, partial = "")
            }
        }
    }

    fun acknowledgeDisclaimer() {
        viewModelScope.launch { settingsRepo.setCoachDisclaimerAck(true) }
    }

    suspend fun acknowledged(): Boolean = settingsRepo.current().coachDisclaimerAck

    /** Play gen-AI policy: user can flag/remove any AI-generated message in-app. */
    fun reportMessage(bubble: ChatBubble) {
        viewModelScope.launch {
            repo.deleteChatMessage(bubble.id)
            engine.reset(buildSystemInstruction(settingsRepo.current()))
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            repo.clearChat()
            engine.reset(buildSystemInstruction(settingsRepo.current()))
        }
    }

    fun downloadHint(): Boolean = engine.state.value is CoachEngine.EngineState.NoModel

    private suspend fun buildSystemInstruction(s: Settings): String {
        val today = LocalDate.now()
        val injections = repo.injections.first()
        val next = com.dosely.app.domain.DoseEngine.nextDose(today, s.intervalDays, s.firstDoseEpochDay, injections)
        val stock = com.dosely.app.domain.DoseEngine.stockStatus(today, s.pensOnHand, s.lowStockThreshold, s.intervalDays, injections)
        val weights = repo.weights.first()
        val med = com.dosely.app.domain.Medications.byId(s.medId)
        val lastWeight = weights.lastOrNull()?.grams?.div(1000.0)
        val start = if (s.startWeightGrams > 0) s.startWeightGrams / 1000.0 else null
        return """
            You are Dosely Coach, a supportive AI companion inside a GLP-1 injection tracking app.
            You are NOT a doctor and must never present yourself as one. Encourage the user to
            consult their healthcare provider for medical decisions. Never provide dosing
            instructions; the app's medication data below is informational only.

            User context (local data, may be incomplete):
            - Medication: ${med.brand} (${med.active}), standard interval ${med.intervalDays} days
            - Today: $today
            - Next scheduled injection: ${next.date}${if (next.overdue) " (overdue)" else ""}
            - Pens on hand: ${stock.pens}${if (stock.low) " (low)" else ""}
            - Start weight: ${start ?: "unknown"} kg; latest logged weight: ${lastWeight ?: "none"} kg
            - Reminder time: ${"%02d:%02d".format(s.reminderHour, s.reminderMinute)}

            Style: warm, concise, practical. Use short paragraphs or a few bullets.
            Respond in the same language the user writes in.
        """.trimIndent()
    }
}
