package com.dosely.app.domain

import kotlinx.serialization.Serializable

@Serializable
data class TitrationStep(val week: Int, val doseMg: Double)

@Serializable
data class Medication(
    val id: String,
    val brand: String,
    val active: String,
    /** Standard interval between doses in days. */
    val intervalDays: Int,
    /** Available pen strengths (mg per dose). */
    val doses: List<Double>,
    /** Standard titration ramp. */
    val titration: List<TitrationStep>,
    val penPerBox: Int,
)

object Medications {
    val all: List<Medication> = listOf(
        Medication(
            id = "semaglutide",
            brand = "Semaglutide",
            active = "Semaglutide",
            intervalDays = 7,
            doses = listOf(0.25, 0.5, 1.0, 1.7, 2.4),
            titration = listOf(
                TitrationStep(0, 0.25), TitrationStep(4, 0.5),
                TitrationStep(8, 1.0), TitrationStep(12, 1.7), TitrationStep(16, 2.4),
            ),
            penPerBox = 1,
        ),
        Medication(
            id = "tirzepatide",
            brand = "Tirzepatide",
            active = "Tirzepatide",
            intervalDays = 7,
            doses = listOf(2.5, 5.0, 7.5, 10.0, 12.5, 15.0),
            titration = listOf(
                TitrationStep(0, 2.5), TitrationStep(4, 5.0), TitrationStep(8, 7.5),
                TitrationStep(12, 10.0), TitrationStep(16, 12.5), TitrationStep(20, 15.0),
            ),
            penPerBox = 1,
        ),
        Medication(
            id = "dulaglutide",
            brand = "Dulaglutide",
            active = "Dulaglutide",
            intervalDays = 7,
            doses = listOf(0.75, 1.5, 3.0, 4.5),
            titration = listOf(TitrationStep(0, 0.75), TitrationStep(4, 1.5), TitrationStep(8, 3.0), TitrationStep(12, 4.5)),
            penPerBox = 2,
        ),
        Medication(
            id = "liraglutide",
            brand = "Liraglutide",
            active = "Liraglutide",
            intervalDays = 1,
            doses = listOf(0.6, 1.2, 1.8, 2.4, 3.0),
            titration = listOf(
                TitrationStep(0, 0.6), TitrationStep(1, 1.2), TitrationStep(2, 1.8),
                TitrationStep(3, 2.4), TitrationStep(4, 3.0),
            ),
            penPerBox = 3,
        ),
        Medication(
            id = "cagrilintide",
            brand = "CagriSema",
            active = "Cagrilintide + Semaglutide",
            intervalDays = 7,
            doses = listOf(1.2, 1.8, 2.4, 3.2),
            titration = listOf(TitrationStep(0, 1.2), TitrationStep(4, 1.8), TitrationStep(8, 2.4), TitrationStep(16, 3.2)),
            penPerBox = 1,
        ),
    )

    fun byId(id: String): Medication = all.firstOrNull { it.id == id } ?: all.first()

    /** Suggested dose for a given number of weeks on treatment. */
    fun suggestedDose(med: Medication, weeksOnTreatment: Int): Double =
        med.titration.last { weeksOnTreatment >= it.week }.doseMg
}
