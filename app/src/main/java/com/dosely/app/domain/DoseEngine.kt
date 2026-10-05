package com.dosely.app.domain

import com.dosely.app.data.db.InjectionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** Pure scheduling / stock logic. No Android dependencies. */
object DoseEngine {

    data class NextDose(
        val date: LocalDate,
        val overdue: Boolean,
        val takenToday: Boolean,
    )

    /** The next scheduled injection day on or after today, based on history and anchor. */
    fun nextDose(
        today: LocalDate,
        intervalDays: Int,
        anchorDay: Long,
        injections: List<InjectionEntity>,
    ): NextDose {
        val takenDays = injections.filter { !it.skipped }.map { it.epochDay }.toSet()
        if (takenDays.contains(today.toEpochDay())) {
            val next = addIntervals(today, intervalDays)
            return NextDose(next, overdue = false, takenToday = true)
        }
        // Candidate 1: continue from the last taken dose.
        val lastTaken = injections.filter { !it.skipped }.maxOfOrNull { it.epochDay }
        var candidate: LocalDate = if (lastTaken != null) {
            addIntervals(LocalDate.ofEpochDay(lastTaken), intervalDays)
        } else {
            LocalDate.ofEpochDay(anchorDay)
        }
        // An unrecorded scheduled dose remains overdue until logged or skipped.
        val skippedDays = injections.filter { it.skipped }.map { it.epochDay }.toSet()
        while (candidate.toEpochDay() in skippedDays) candidate = addIntervals(candidate, intervalDays.coerceAtLeast(1))
        return NextDose(
            date = candidate,
            overdue = candidate.isBefore(today),
            takenToday = false,
        )
    }

    fun addIntervals(from: LocalDate, intervalDays: Int): LocalDate =
        when (intervalDays) {
            7 -> from.plusWeeks(1)
            14 -> from.plusWeeks(2)
            28 -> from.plusWeeks(4)
            30 -> from.plusMonths(1)
            else -> from.plusDays(intervalDays.coerceAtLeast(1).toLong())
        }

    fun atTime(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): LocalDateTime =
        LocalDateTime.of(date, LocalTime.of(hour, minute))

    /** Consecutive most-recent scheduled doses that were actually taken. */
    fun streak(today: LocalDate, intervalDays: Int, injections: List<InjectionEntity>): Int {
        val taken = injections.filter { !it.skipped }.map { it.epochDay }.toSet()
        if (taken.isEmpty()) return 0
        var day = today
        // If today's dose isn't taken yet, start checking from the last expected date <= today.
        var expected = today
        var count = 0
        // Walk backwards in interval steps up to 2 years.
        var guard = 0
        while (guard < 104) {
            if (taken.contains(expected.toEpochDay())) {
                count++
                expected = expected.minusDays(intervalDays.toLong())
            } else if (expected == today) {
                // Today not taken yet is fine; look at previous slot.
                expected = expected.minusDays(intervalDays.toLong())
            } else {
                break
            }
            guard++
        }
        return count
    }

    /** Fraction of scheduled doses taken in the last [weeks] weeks (0.0..1.0). */
    fun adherence(today: LocalDate, intervalDays: Int, injections: List<InjectionEntity>, weeks: Int = 8): Double {
        val since = today.minusWeeks(weeks.toLong()).toEpochDay()
        val expectedSlots = mutableListOf<Long>()
        val first = injections.minOfOrNull { it.epochDay } ?: return 1.0
        var slot = first
        var guard = 0
        while (slot <= today.toEpochDay() && guard < 104) {
            if (slot >= since) expectedSlots.add(slot)
            slot = addIntervals(LocalDate.ofEpochDay(slot), intervalDays).toEpochDay()
            guard++
        }
        if (expectedSlots.isEmpty()) return 1.0
        val taken = injections.filter { !it.skipped && it.epochDay >= since }.map { it.epochDay }.toSet()
        val hits = expectedSlots.count { slot -> taken.any { abs(it - slot) <= 2 } }
        return hits.toDouble() / expectedSlots.size
    }

    data class StockStatus(
        val pens: Int,
        val low: Boolean,
        val out: Boolean,
        /** Approximate date the stock runs out given the upcoming schedule. */
        val runsOutOn: LocalDate?,
    )

    fun stockStatus(
        today: LocalDate,
        pens: Int,
        lowThreshold: Int,
        intervalDays: Int,
        injections: List<InjectionEntity>,
    ): StockStatus {
        val next = nextDose(today, intervalDays, today.toEpochDay(), injections)
        var remaining = pens
        var date: LocalDate = next.date
        var runsOut: LocalDate? = if (remaining <= 0) date else null
        var guard = 0
        while (remaining > 0 && guard < 104) {
            remaining--
            if (remaining == 0) {
                runsOut = date
                break
            }
            date = addIntervals(date, intervalDays)
            guard++
        }
        return StockStatus(
            pens = pens,
            low = pens in 1..lowThreshold,
            out = pens <= 0,
            runsOutOn = runsOut,
        )
    }
}
