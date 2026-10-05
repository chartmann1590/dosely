package com.dosely.app.domain

import com.dosely.app.data.db.InjectionEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DoseEngineTest {
    private val today = LocalDate.of(2026, 10, 5)
    private fun shot(day: LocalDate, skip: Boolean = false) = InjectionEntity(
        epochDay = day.toEpochDay(), takenAtMillis = 0, medId = "semaglutide", doseMg = 1.0, site = "Abdomen", skipped = skip)
    @Test fun overdueDoseIsNotSilentlyAdvanced() {
        val expected = today.minusDays(2)
        val next = DoseEngine.nextDose(today, 7, expected.toEpochDay(), listOf(shot(today.minusDays(9))))
        assertEquals(expected, next.date)
        assertTrue(next.overdue)
    }
    @Test fun explicitlySkippedSlotAdvances() {
        val next = DoseEngine.nextDose(today, 7, today.minusDays(2).toEpochDay(), listOf(shot(today.minusDays(2), true)))
        assertEquals(today.plusDays(5), next.date)
        assertFalse(next.overdue)
    }
    @Test fun takenTodayGoesToNextSlot() {
        val next = DoseEngine.nextDose(today, 7, today.toEpochDay(), listOf(shot(today)))
        assertEquals(today.plusDays(7), next.date)
        assertTrue(next.takenToday)
    }
    @Test fun monthlyScheduleHandlesFebruary() {
        assertEquals(LocalDate.of(2026, 2, 28), DoseEngine.addIntervals(LocalDate.of(2026, 1, 31), 30))
    }
}
