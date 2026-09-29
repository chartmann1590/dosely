package com.dosely.app.domain

import com.dosely.app.data.db.InjectionEntity
import com.dosely.app.data.db.WeightEntryEntity
import java.time.LocalDate

/** Pure computation of journey-level analytics. */
object InsightsEngine {

    data class Insights(
        val totalInjections: Int = 0,
        val skippedCount: Int = 0,
        val daysOnTreatment: Int = 0,
        val longestStreak: Int = 0,
        val currentStreak: Int = 0,
        val avgWeeklyChangeKg: Double? = null,
        val projectedGoalDate: LocalDate? = null,
        val monthInjections: Int = 0,
        val monthSkipped: Int = 0,
        val monthWeighIns: Int = 0,
        val monthChangeKg: Double? = null,
    )

    fun compute(
        today: LocalDate,
        intervalDays: Int,
        firstDoseDay: Long,
        injections: List<InjectionEntity>,
        weights: List<WeightEntryEntity>,
        goalWeightGrams: Int,
    ): Insights {
        val taken = injections.filter { !it.skipped }.sortedBy { it.epochDay }
        val skipped = injections.count { it.skipped }

        // Longest consecutive scheduled doses taken.
        var longest = 0
        var run = 0
        var expected: Long? = taken.firstOrNull()?.epochDay
        val takenDays = taken.map { it.epochDay }.toSet()
        expected?.let { start ->
            var cursor = start
            var guard = 0
            while (guard < 260) {
                if (takenDays.contains(cursor)) {
                    run++
                    longest = maxOf(longest, run)
                    cursor += intervalDays.toLong()
                } else {
                    run = 0
                    cursor += intervalDays.toLong()
                }
                guard++
                if (cursor > today.toEpochDay()) break
            }
        }

        // Average weekly change from logged weights (first -> last over span).
        var avgWeekly: Double? = null
        var projected: LocalDate? = null
        if (weights.size >= 2) {
            val first = weights.first()
            val last = weights.last()
            val spanDays = (last.epochDay - first.epochDay).coerceAtLeast(1)
            val changeKg = (last.grams - first.grams) / 1000.0
            avgWeekly = changeKg * 7.0 / spanDays
            if (goalWeightGrams > 0 && avgWeekly < -0.05) {
                val remainingKg = (last.grams - goalWeightGrams) / 1000.0
                if (remainingKg > 0) {
                    val weeks = remainingKg / -avgWeekly
                    projected = today.plusDays((weeks * 7).toLong())
                }
            }
        }

        // Month stats.
        val monthStart = today.withDayOfMonth(1).toEpochDay()
        val monthInj = injections.filter { it.epochDay >= monthStart }
        val monthWeights = weights.filter { it.epochDay >= monthStart }
        val monthChange = if (monthWeights.size >= 2) {
            (monthWeights.last().grams - monthWeights.first().grams) / 1000.0
        } else null

        return Insights(
            totalInjections = taken.size,
            skippedCount = skipped,
            daysOnTreatment = if (firstDoseDay > 0) (today.toEpochDay() - firstDoseDay).toInt().coerceAtLeast(0) else 0,
            longestStreak = longest,
            currentStreak = DoseEngine.streak(today, intervalDays, injections),
            avgWeeklyChangeKg = avgWeekly,
            projectedGoalDate = projected,
            monthInjections = monthInj.count { !it.skipped },
            monthSkipped = monthInj.count { it.skipped },
            monthWeighIns = monthWeights.size,
            monthChangeKg = monthChange,
        )
    }
}
