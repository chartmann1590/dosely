package com.dosely.app.domain

import com.dosely.app.data.db.InjectionEntity
import org.junit.Assert.*
import org.junit.Test

class MedicationLevelsTest {
    private val now = 1_750_000_000_000L
    private fun shot(med: String = "semaglutide", at: Long = now, mg: Double = 1.0, skipped: Boolean = false) =
        InjectionEntity(epochDay = at / 86_400_000, takenAtMillis = at, medId = med, doseMg = mg, site = "Abdomen", skipped = skipped)

    @Test fun halvesAfterOneHalfLife() {
        assertEquals(0.5, MedicationLevels.estimate("semaglutide", listOf(shot()), now + 7 * 86_400_000)!!, 0.00001)
    }
    @Test fun addsResidualFromPreviousShots() {
        assertEquals(1.5, MedicationLevels.estimate("semaglutide", listOf(shot(), shot(at = now - 7 * 86_400_000)), now)!!, 0.00001)
    }
    @Test fun ignoresOtherDrugsSkippedAndFutureEntries() {
        val entries = listOf(shot(), shot("tirzepatide", mg = 15.0), shot(skipped = true), shot(at = now + 1))
        assertEquals(1.0, MedicationLevels.estimate("semaglutide", entries, now)!!, 0.00001)
    }
    @Test fun unknownDrugHasNoInventedEstimate() { assertNull(MedicationLevels.estimate("cagrilintide", listOf(shot()), now)) }
    @Test fun invalidValuesDoNotPoisonChart() {
        assertEquals(1.0, MedicationLevels.estimate("semaglutide", listOf(shot(), shot(mg = Double.NaN), shot(mg = -1.0)), now)!!, 0.00001)
    }
    @Test fun futureProjectionDeclinesWithoutInventingPlannedDoses() {
        val series = MedicationLevels.series("semaglutide", listOf(shot()), now)
        assertEquals(113, series.size)
        assertEquals(1.0, series[56], 0.00001)
        assertEquals(0.5, series.last(), 0.00001)
    }
}
