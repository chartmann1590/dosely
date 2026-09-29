package com.dosely.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.db.WeightEntryEntity
import com.dosely.app.data.prefs.SettingsRepository
import com.dosely.app.data.repo.DoselyRepository
import com.dosely.app.domain.InsightsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class DayCell(
    val date: LocalDate,
    val inMonth: Boolean,
    val hasInjection: Boolean,
    val hasSkip: Boolean,
    val hasWeighIn: Boolean,
)

data class CalendarUi(
    val loaded: Boolean = false,
    val month: YearMonth = YearMonth.now(),
    val cells: List<DayCell> = emptyList(),
    val selected: LocalDate? = null,
    val selectedInjection: InjectionEntity? = null,
    val selectedWeight: WeightEntryEntity? = null,
    val insights: InsightsEngine.Insights = InsightsEngine.Insights(),
    val useImperial: Boolean = false,
)

class CalendarViewModel(
    private val settingsRepo: SettingsRepository,
    private val repo: DoselyRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())
    private val selected = MutableStateFlow<LocalDate?>(null)

    val ui: StateFlow<CalendarUi> = combine(
        settingsRepo.settings,
        repo.injections,
        repo.weights,
        month,
        selected,
    ) { s, injections, weights, m, sel ->
        if (!s.onboarded) return@combine CalendarUi(loaded = false)

        val injByDay = injections.groupBy { it.epochDay }
        val weightByDay = weights.associateBy { it.epochDay }

        val firstOfMonth = m.atDay(1)
        // Monday-first grid, 6 weeks to keep height stable.
        val lead = (firstOfMonth.dayOfWeek.value + 6) % 7
        val gridStart = firstOfMonth.minusDays(lead.toLong())
        val cells = (0 until 42).map { i ->
            val date = gridStart.plusDays(i.toLong())
            val injList = injByDay[date.toEpochDay()]
            DayCell(
                date = date,
                inMonth = date.month == m.month,
                hasInjection = injList?.any { !it.skipped } ?: false,
                hasSkip = injList?.any { it.skipped } ?: false,
                hasWeighIn = weightByDay.containsKey(date.toEpochDay()),
            )
        }

        val selDate = sel ?: LocalDate.now().takeIf { it.month == m.month } ?: m.atEndOfMonth()
        val selInj = injByDay[selDate.toEpochDay()]?.maxByOrNull { it.takenAtMillis }
        val selWeight = weightByDay[selDate.toEpochDay()]

        val insights = InsightsEngine.compute(
            today = LocalDate.now(),
            intervalDays = s.intervalDays,
            firstDoseDay = s.firstDoseEpochDay,
            injections = injections,
            weights = weights,
            goalWeightGrams = s.goalWeightGrams,
        )

        CalendarUi(
            loaded = true,
            month = m,
            cells = cells,
            selected = selDate,
            selectedInjection = selInj,
            selectedWeight = selWeight,
            insights = insights,
            useImperial = s.useImperial,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CalendarUi())

    fun selectMonth(m: YearMonth) {
        month.value = m
        selected.value = null
    }

    fun nextMonth() = selectMonth(month.value.plusMonths(1))
    fun prevMonth() = selectMonth(month.value.minusMonths(1))

    fun selectDay(date: LocalDate) {
        selected.value = date
    }
}
