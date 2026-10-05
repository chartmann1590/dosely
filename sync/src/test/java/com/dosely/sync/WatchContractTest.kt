package com.dosely.sync

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class WatchContractTest {
    private val now = 1_750_000_000_000L
    private fun dose() = WatchEvent(kind = "dose", atMillis = now, epochDay = now / 86_400_000,
        medId = "semaglutide", doseMg = 0.5, site = WatchContract.sites.first())
    @Test fun roundTripPreservesIdForDeduplication() {
        val event = dose()
        val replay = WatchContract.json.decodeFromString<WatchEvent>(WatchContract.json.encodeToString(event))
        assertEquals(event, replay)
        assertTrue(replay.isValid(now))
    }
    @Test fun offlineEventsRemainValidWhenDeliveredLater() { assertTrue(dose().isValid(now + 14 * 86_400_000)) }
    @Test fun rejectsInvalidIdentityAndVersion() {
        assertFalse(dose().copy(id = "../snapshot").isValid(now))
        assertFalse(dose().copy(version = 2).isValid(now))
    }
    @Test fun rejectsImpossibleDoseAndSite() {
        listOf(0.0, -1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertFalse(dose().copy(doseMg = it).isValid(now)) }
        assertFalse(dose().copy(site = "Unknown").isValid(now))
    }
    @Test fun rejectsFutureOrInconsistentDates() {
        assertFalse(dose().copy(atMillis = now + 600_000).isValid(now))
        assertFalse(dose().copy(epochDay = 1).isValid(now))
    }
    @Test fun validatesWeightAndWaterBounds() {
        assertTrue(dose().copy(kind = "weight", grams = 80_000).isValid(now))
        assertFalse(dose().copy(kind = "weight", grams = 0).isValid(now))
        assertTrue(dose().copy(kind = "water", waterMl = 250).isValid(now))
        assertFalse(dose().copy(kind = "water", waterMl = -1).isValid(now))
        assertFalse(dose().copy(kind = "delete").isValid(now))
    }
}
