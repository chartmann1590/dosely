package com.dosely.app.domain

import com.dosely.app.data.db.InjectionEntity
import kotlin.math.pow

/** Educational elimination-only model, not a measured blood concentration or dosing tool.
 * Ignores absorption, bioavailability and individual variation. Never combines different drugs.
 */
object MedicationLevels {
    fun halfLifeHours(medId: String): Double? = when (medId) {
        "semaglutide" -> 168.0
        "tirzepatide", "dulaglutide" -> 120.0
        "liraglutide" -> 13.0
        else -> null
    }
    fun estimate(medId: String, entries: List<InjectionEntity>, atMillis: Long): Double? {
        val halfLife = halfLifeHours(medId) ?: return null
        return entries.filter { !it.skipped && it.medId == medId && it.takenAtMillis <= atMillis && it.doseMg.isFinite() && it.doseMg > 0 }
            .sumOf { it.doseMg * 0.5.pow((atMillis - it.takenAtMillis) / 3_600_000.0 / halfLife) }
    }
    fun series(medId: String, entries: List<InjectionEntity>, now: Long): List<Double> =
        (0..112).map { estimate(medId, entries, now - 7 * 86_400_000L + it * 3 * 3_600_000L) ?: 0.0 }
}
