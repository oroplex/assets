package com.oroplex.floorplan.ui.common

import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.UnitSystem
import java.util.Locale
import kotlin.math.abs

/** Formats a length with the project's unit system and imperial precision. */
fun PlanSettings.formatLength(meters: Double): String = Units.formatLength(meters, unitSystem, imperialDenominator)

/** Formats an area (square meters) with the project's unit system. */
fun PlanSettings.formatArea(squareMeters: Double): String = Units.formatArea(squareMeters, unitSystem)

/** Formats a volume (cubic meters) with the project's unit system, see [formatVolume]. */
fun PlanSettings.formatVolume(cubicMeters: Double): String = formatVolume(cubicMeters, unitSystem)

private const val CUBIC_FEET_PER_CUBIC_METER = 35.314666721488590

/**
 * "1,152 cu ft" (whole cubic feet) or "32.6 m³" (one decimal), with comma thousands separators regardless of the
 * device locale (like Units.formatArea). Non-finite input gives "—".
 */
fun formatVolume(cubicMeters: Double, system: UnitSystem): String {
    if (!cubicMeters.isFinite()) return "—"
    val sign = if (cubicMeters < 0) "-" else ""
    return when (system) {
        UnitSystem.IMPERIAL -> {
            val cuft = Math.round(abs(cubicMeters) * CUBIC_FEET_PER_CUBIC_METER)
            (if (cuft == 0L) "" else sign) + "%,d".format(Locale.US, cuft) + " cu ft"
        }
        UnitSystem.METRIC -> {
            val tenths = Math.round(abs(cubicMeters) * 10)
            (if (tenths == 0L) "" else sign) + "%,d".format(Locale.US, tenths / 10) + ".${tenths % 10} m³"
        }
    }
}

/**
 * Parsing of typed lengths on top of [Units.parseLength], with two options the editor needs:
 *  * [smallUnits]: a bare number without any unit mark means inches (IMPERIAL) or centimeters (METRIC)
 *    instead of feet / meters. Used for things that are naturally quoted in small units (door widths,
 *    wall thickness, sill heights) so that typing "32" gives a 32" door rather than a 32 ft one.
 *  * [allowZero]: accept an explicit zero ("0", 0' 0", "0 cm"), which [Units.parseLength] rejects.
 */
object LengthInput {
    private const val UNIT_MARKS = "'\"′″‘’“”"
    private val ZERO_UNIT_WORDS = setOf("", "m", "cm", "mm", "ft", "in", "ftin", "feet", "foot", "inch", "inches")

    fun parse(text: String, system: UnitSystem, smallUnits: Boolean = false, allowZero: Boolean = false): Double? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (allowZero && isExplicitZero(trimmed)) return 0.0
        val input = if (smallUnits && isBareNumber(trimmed)) {
            when (system) {
                UnitSystem.IMPERIAL -> "$trimmed\""
                UnitSystem.METRIC -> "$trimmed cm"
            }
        } else {
            trimmed
        }
        return Units.parseLength(input, system)?.takeIf { it.isFinite() && it > 0.0 }
    }

    /** True when [text] has digits but no unit word or mark, e.g. "32", "4 1/2", "2.5". */
    internal fun isBareNumber(text: String): Boolean =
        text.any { it.isDigit() } && text.none { it.isLetter() || it in UNIT_MARKS }

    /** True for zero written with or without units: 0, 0.0, 0", 0' 0", 0 cm, 0ft 0in. */
    internal fun isExplicitZero(text: String): Boolean {
        if (text.none { it.isDigit() } || text.any { it.isDigit() && it != '0' } || '-' in text) return false
        val rest = text.lowercase().filterNot { it.isDigit() || it.isWhitespace() || it == '.' || it == ',' || it in UNIT_MARKS }
        return rest in ZERO_UNIT_WORDS
    }

    /** Example input shown as a placeholder. */
    fun example(system: UnitSystem, smallUnits: Boolean): String = when (system) {
        UnitSystem.IMPERIAL -> if (smallUnits) "e.g. 32\" or 2' 8\"" else "e.g. 12' 4 1/2\""
        UnitSystem.METRIC -> if (smallUnits) "e.g. 80 cm" else "e.g. 3.76 m"
    }
}
