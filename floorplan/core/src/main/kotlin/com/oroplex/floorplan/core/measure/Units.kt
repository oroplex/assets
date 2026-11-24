package com.oroplex.floorplan.core.measure

import com.oroplex.floorplan.core.model.UnitSystem
import java.util.Locale
import kotlin.math.abs

object Units {
    const val METERS_PER_INCH = 0.0254
    const val METERS_PER_FOOT = 0.3048
    const val SQFT_PER_SQM = 10.763910416709722

    /** Shown for lengths/areas that are not finite numbers (corrupt data). */
    const val INVALID = "—"

    /**
     * IMPERIAL: feet-inches rounded to 1/[imperialDenominator] inch, e.g. 12' 4 1/2", 10' 0", 12' 0 1/2".
     *   Below one foot (after rounding) only inches are shown, e.g. 11 1/2", 1/2", 0".
     *   Rounding happens once on the total, so it carries correctly (11 63/64" at 1/2" precision -> 1' 0").
     *   Fractions are reduced (2/4 -> 1/2). Denominators below 1 are treated as 1 (whole inches).
     * METRIC: meters with 2 decimals, e.g. "3.76 m"; below 1 m (after rounding to whole cm) use centimeters,
     *   e.g. "85 cm".
     * Negative values are formatted with a leading "-" (unless they round to zero). Output never depends on
     * the device locale. Non-finite input gives [INVALID].
     */
    fun formatLength(meters: Double, system: UnitSystem, imperialDenominator: Int = 2): String {
        if (!meters.isFinite()) return INVALID
        return when (system) {
            UnitSystem.IMPERIAL -> formatImperial(meters, imperialDenominator.coerceIn(1, 1024))
            UnitSystem.METRIC -> formatMetric(meters)
        }
    }

    /** "152 sq ft" (rounded to whole sq ft) or "14.1 m²" (1 decimal). Non-finite input gives [INVALID]. */
    fun formatArea(squareMeters: Double, system: UnitSystem): String {
        if (!squareMeters.isFinite()) return INVALID
        return when (system) {
            UnitSystem.IMPERIAL -> {
                val sqft = Math.round(abs(squareMeters) * SQFT_PER_SQM)
                "${sign(squareMeters, sqft)}$sqft sq ft"
            }
            UnitSystem.METRIC -> {
                val tenths = Math.round(abs(squareMeters) * 10)
                "${sign(squareMeters, tenths)}${tenths / 10}.${tenths % 10} m²"
            }
        }
    }

    /**
     * Parse user input into meters. Accepts e.g. 12'4", 12' 4 1/2", 12'-4", 12ft 4in, 12 ft, 4 in, 148", 148 in,
     * 4-1/2", 4½", 12.5', 3.76m, 376cm, 3760mm, 1 m 20 cm, unicode primes (′ ″) and curly quotes, and a bare
     * number (feet for IMPERIAL, meters for METRIC). Two bare numbers ("12 4") mean feet and inches in
     * IMPERIAL. A comma is a decimal separator when the text has no dot ("3,76 m"), otherwise a thousands
     * separator. Returns null for unparseable, negative, or zero input.
     */
    fun parseLength(text: String, system: UnitSystem): Double? {
        val tokens = tokenize(normalize(text)) ?: return null
        val groups = group(tokens) ?: return null
        val meters = interpret(groups, system) ?: return null
        return if (meters.isFinite() && meters > 0.0) meters else null
    }

    // ---------------------------------------------------------------------------------------------

    private fun sign(value: Double, roundedMagnitude: Long) = if (value < 0 && roundedMagnitude != 0L) "-" else ""

    private fun formatImperial(meters: Double, denominator: Int): String {
        val units = Math.round(abs(meters) / METERS_PER_INCH * denominator)
        val sign = sign(meters, units)
        val perFoot = 12L * denominator
        val feet = units / perFoot
        val rest = units % perFoot
        val inches = rest / denominator
        val fraction = fractionText(rest % denominator, denominator.toLong())
        val inchText = when {
            fraction.isEmpty() -> "$inches\""
            inches == 0L && feet == 0L -> "$fraction\""
            else -> "$inches $fraction\""
        }
        return if (feet >= 1) "$sign$feet' $inchText" else "$sign$inchText"
    }

    /** Reduced "n/d", or "" for zero. */
    private fun fractionText(numerator: Long, denominator: Long): String {
        if (numerator == 0L) return ""
        val g = gcd(numerator, denominator)
        return "${numerator / g}/${denominator / g}"
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun formatMetric(meters: Double): String {
        val cm = Math.round(abs(meters) * 100)
        val sign = sign(meters, cm)
        return if (cm >= 100) "$sign${cm / 100}.${(cm % 100).toString().padStart(2, '0')} m" else "$sign$cm cm"
    }

    private enum class LengthUnit(val meters: Double) {
        FOOT(METERS_PER_FOOT), INCH(METERS_PER_INCH), METER(1.0), CENTIMETER(0.01), MILLIMETER(0.001)
    }

    private sealed interface Token {
        data class Number(val value: Double, val isInteger: Boolean) : Token
        data class Fraction(val value: Double) : Token
        data class Unit(val unit: LengthUnit) : Token
    }

    private data class Quantity(val value: Double, val unit: LengthUnit?)

    private val UNIT_WORDS: Map<String, LengthUnit> = buildMap {
        listOf("ft", "feet", "foot").forEach { put(it, LengthUnit.FOOT) }
        listOf("in", "inch", "inches").forEach { put(it, LengthUnit.INCH) }
        listOf("m", "meter", "meters", "metre", "metres").forEach { put(it, LengthUnit.METER) }
        listOf("cm", "centimeter", "centimeters", "centimetre", "centimetres").forEach { put(it, LengthUnit.CENTIMETER) }
        listOf("mm", "millimeter", "millimeters", "millimetre", "millimetres").forEach { put(it, LengthUnit.MILLIMETER) }
    }

    private val VULGAR_FRACTIONS = mapOf(
        '½' to " 1/2", '¼' to " 1/4", '¾' to " 3/4", '⅛' to " 1/8", '⅜' to " 3/8", '⅝' to " 5/8", '⅞' to " 7/8",
    )

    /** Lower-case, map typographic primes/quotes to ' and ", vulgar fractions to " n/d", and settle commas. */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text.trim().lowercase(Locale.ROOT)) {
            when (ch) {
                '′', '’', '‘', 'ʹ', '´', '`' -> sb.append('\'')
                '″', '“', '”', 'ʺ', '„' -> sb.append('"')
                ' ', ' ', ' ' -> sb.append(' ')
                else -> sb.append(VULGAR_FRACTIONS[ch] ?: ch)
            }
        }
        var s = sb.toString().replace("''", "\"")
        s = if ('.' in s) s.replace(",", "") else s.replace(',', '.')
        return s
    }

    /** Split into numbers, fractions and units; null on any character that does not belong in a length. */
    private fun tokenize(s: String): List<Token>? {
        val out = ArrayList<Token>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || (ch == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    val start = i
                    while (i < s.length && s[i].isDigit()) i++
                    var isInteger = true
                    if (i < s.length && s[i] == '.') {
                        isInteger = false
                        i++
                        while (i < s.length && s[i].isDigit()) i++
                    }
                    val value = s.substring(start, i).toDoubleOrNull() ?: return null
                    if (i < s.length && s[i] == '.') return null // "1.2.3"
                    var j = i
                    while (j < s.length && s[j] == ' ') j++
                    if (j < s.length && s[j] == '/') {
                        if (!isInteger) return null
                        j++
                        while (j < s.length && s[j] == ' ') j++
                        val denStart = j
                        while (j < s.length && s[j].isDigit()) j++
                        if (j == denStart || (j < s.length && s[j] == '.')) return null
                        val den = s.substring(denStart, j).toDoubleOrNull() ?: return null
                        if (den == 0.0) return null
                        out += Token.Fraction(value / den)
                        i = j
                    } else {
                        out += Token.Number(value, isInteger)
                    }
                }
                ch.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    out += Token.Unit(UNIT_WORDS[s.substring(start, i)] ?: return null)
                    // Abbreviation dot ("ft."), but not a decimal point of a following number.
                    if (i < s.length && s[i] == '.' && !(i + 1 < s.length && s[i + 1].isDigit())) i++
                }
                ch == '\'' -> { out += Token.Unit(LengthUnit.FOOT); i++ }
                ch == '"' -> { out += Token.Unit(LengthUnit.INCH); i++ }
                // Separator as in 12'-4" or 4-1/2"; a leading minus is a negative length -> rejected.
                ch == '-' -> if (out.isEmpty()) return null else i++
                ch == '+' && out.isEmpty() -> i++
                else -> return null
            }
        }
        return out
    }

    /** Group tokens into quantities: number [fraction] [unit] (mixed numbers like 4 1/2), or fraction [unit]. */
    private fun group(tokens: List<Token>): List<Quantity>? {
        val out = ArrayList<Quantity>()
        var k = 0
        while (k < tokens.size) {
            var value: Double
            when (val t = tokens[k]) {
                is Token.Number -> {
                    value = t.value
                    k++
                    val next = tokens.getOrNull(k)
                    if (next is Token.Fraction) {
                        if (!t.isInteger) return null
                        value += next.value
                        k++
                    }
                }
                is Token.Fraction -> {
                    value = t.value
                    k++
                }
                is Token.Unit -> return null // unit without a number, or two units in a row
            }
            val unit = (tokens.getOrNull(k) as? Token.Unit)?.unit
            if (unit != null) k++
            out += Quantity(value, unit)
        }
        return out
    }

    private fun interpret(q: List<Quantity>, system: UnitSystem): Double? {
        val default = if (system == UnitSystem.IMPERIAL) LengthUnit.FOOT else LengthUnit.METER
        return when (q.size) {
            1 -> q[0].value * (q[0].unit ?: default).meters
            2 -> {
                val (a, b) = q
                val au = a.unit
                val bu = b.unit
                val feetInches = (au == LengthUnit.FOOT || au == null) &&
                    (bu == LengthUnit.INCH || bu == null) &&
                    // Two bare numbers only mean feet + inches in IMPERIAL.
                    !(au == null && bu == null && system != UnitSystem.IMPERIAL)
                val metricPair = (au == LengthUnit.METER && (bu == LengthUnit.CENTIMETER || bu == LengthUnit.MILLIMETER)) ||
                    (au == LengthUnit.CENTIMETER && bu == LengthUnit.MILLIMETER)
                when {
                    feetInches -> a.value * METERS_PER_FOOT + b.value * METERS_PER_INCH
                    metricPair && au != null && bu != null -> a.value * au.meters + b.value * bu.meters
                    else -> null
                }
            }
            else -> null
        }
    }
}
