package com.dosely.app.domain

import java.util.Locale

/** Weight unit conversions. Internal canonical unit is kilograms (grams in DB). */
object Units {
    const val LB_PER_KG = 2.2046226218

    fun label(imperial: Boolean): String = if (imperial) "lb" else "kg"

    /** Converts a canonical kg value to the display unit. */
    fun fromKg(kg: Double, imperial: Boolean): Double =
        if (imperial) kg * LB_PER_KG else kg

    /** Converts a user-entered value in the display unit to canonical kg. */
    fun toKg(value: Double, imperial: Boolean): Double =
        if (imperial) value / LB_PER_KG else value

    /** Formats a canonical kg value without unit for input editing, e.g. "209.8". */
    fun formatValue(kg: Double, imperial: Boolean): String =
        String.format(Locale.US, "%.1f", fromKg(kg, imperial))

    /** Formats a canonical kg value for display, e.g. "209.8 lb" or "95.2 kg". */
    fun format(kg: Double?, imperial: Boolean): String {
        if (kg == null) return "—"
        return String.format(Locale.US, "%.1f %s", fromKg(kg, imperial), label(imperial))
    }

    /** Formats a signed canonical change, e.g. "-12.4 lb". */
    fun formatChange(kgChange: Double?, imperial: Boolean): String {
        if (kgChange == null) return "—"
        return String.format(Locale.US, "%+.1f %s", fromKg(kgChange, imperial), label(imperial))
    }
}
