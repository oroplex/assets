package com.oroplex.floorplan.core.measure

import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.UnitSystem.IMPERIAL
import com.oroplex.floorplan.core.model.UnitSystem.METRIC
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class UnitsTest {
    private fun inches(i: Double) = i * Units.METERS_PER_INCH

    @Test fun formatImperialTable() {
        data class Case(val meters: Double, val denominator: Int, val expected: String)
        val cases = listOf(
            Case(inches(148.5), 2, "12' 4 1/2\""),
            Case(inches(120.0), 2, "10' 0\""),
            Case(3.048, 2, "10' 0\""),
            Case(inches(144.5), 2, "12' 0 1/2\""),
            Case(inches(11.5), 2, "11 1/2\""),
            Case(inches(11.0), 2, "11\""),
            Case(inches(0.5), 2, "1/2\""),
            Case(0.0, 2, "0\""),
            Case(0.001, 2, "0\""),
            Case(-0.001, 2, "0\""),
            Case(inches(12.0), 2, "1' 0\""),
            // Carry: rounding the fraction up must roll over inches and feet.
            Case(inches(11 + 63.0 / 64), 2, "1' 0\""),
            Case(inches(11.8), 1, "1' 0\""),
            Case(inches(143.9), 4, "12' 0\""),
            Case(inches(23.76), 2, "2' 0\""),
            // Reduced fractions at finer precision.
            Case(inches(2.5), 4, "2 1/2\""),
            Case(inches(2.25), 4, "2 1/4\""),
            Case(inches(6.375), 8, "6 3/8\""),
            Case(inches(6.75), 8, "6 3/4\""),
            Case(inches(5 + 7.0 / 16), 16, "5 7/16\""),
            Case(inches(5.45), 8, "5 1/2\""),
            Case(inches(37.3), 1, "3' 1\""),
            Case(inches(37.3), 0, "3' 1\""),
            // Negatives.
            Case(-inches(148.5), 2, "-12' 4 1/2\""),
            Case(-inches(6.5), 2, "-6 1/2\""),
        )
        for (c in cases) assertEquals(c.expected, Units.formatLength(c.meters, IMPERIAL, c.denominator), "${c.meters} m @1/${c.denominator}")
    }

    @Test fun formatImperialDefaultDenominatorIsHalfInch() {
        assertEquals("12' 4 1/2\"", Units.formatLength(inches(148.4), IMPERIAL))
    }

    @Test fun formatMetricTable() {
        val cases = listOf(
            3.76 to "3.76 m",
            3.7649 to "3.76 m",
            10.5 to "10.50 m",
            1.0 to "1.00 m",
            0.996 to "1.00 m",
            0.994 to "99 cm",
            0.85 to "85 cm",
            0.004 to "0 cm",
            0.0 to "0 cm",
            -3.76 to "-3.76 m",
            -0.85 to "-85 cm",
            -0.004 to "0 cm",
            123.456 to "123.46 m",
        )
        for ((m, expected) in cases) assertEquals(expected, Units.formatLength(m, METRIC), "$m m")
    }

    @Test fun formatIsLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("3.76 m", Units.formatLength(3.76, METRIC))
            assertEquals("14.1 m²", Units.formatArea(14.06, METRIC))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun formatInvalidValues() {
        for (system in UnitSystem.entries) {
            assertEquals(Units.INVALID, Units.formatLength(Double.NaN, system))
            assertEquals(Units.INVALID, Units.formatLength(Double.POSITIVE_INFINITY, system))
            assertEquals(Units.INVALID, Units.formatArea(Double.NaN, system))
        }
    }

    @Test fun formatAreaTable() {
        val cases = listOf(
            Triple(14.1214, IMPERIAL, "152 sq ft"),
            Triple(1.0, IMPERIAL, "11 sq ft"),
            Triple(0.0, IMPERIAL, "0 sq ft"),
            Triple(185.8, IMPERIAL, "2000 sq ft"),
            Triple(14.06, METRIC, "14.1 m²"),
            Triple(14.04, METRIC, "14.0 m²"),
            Triple(0.0, METRIC, "0.0 m²"),
            Triple(0.36, METRIC, "0.4 m²"),
            Triple(120.0, METRIC, "120.0 m²"),
            Triple(-14.06, METRIC, "-14.1 m²"),
            Triple(-0.01, METRIC, "0.0 m²"),
        )
        for ((sqm, system, expected) in cases) assertEquals(expected, Units.formatArea(sqm, system), "$sqm $system")
    }

    @Test fun parseImperialTable() {
        val cases = listOf(
            "12'4\"" to inches(148.0),
            "12' 4\"" to inches(148.0),
            "12' 4 1/2\"" to inches(148.5),
            "12'-4 1/2\"" to inches(148.5),
            "12ft 4in" to inches(148.0),
            "12 ft 4 in" to inches(148.0),
            "12 feet 4 inches" to inches(148.0),
            "12 ft. 4 in." to inches(148.0),
            "12 ft" to inches(144.0),
            "12'" to inches(144.0),
            "4 in" to inches(4.0),
            "4\"" to inches(4.0),
            "148\"" to inches(148.0),
            "148 in" to inches(148.0),
            "12.5'" to inches(150.0),
            ".5'" to inches(6.0),
            "4 1/2\"" to inches(4.5),
            "4-1/2\"" to inches(4.5),
            "4½\"" to inches(4.5),
            "1/2\"" to inches(0.5),
            "12′ 4″" to inches(148.0),
            "12’ 4”" to inches(148.0),
            "12‘4“" to inches(148.0),
            "12''" to inches(12.0),
            "12' 4" to inches(148.0),
            "12 4" to inches(148.0),
            "12 4 1/2" to inches(148.5),
            "12-4" to inches(148.0),
            "12" to inches(144.0),
            "12 1/2" to inches(150.0),
            "12,5" to inches(150.0),
            "  12 ft  " to inches(144.0),
            "+12'" to inches(144.0),
            "12 FT 4 IN" to inches(148.0),
            "5' 14\"" to inches(74.0),
            // Explicit metric units work in either system.
            "3.76m" to 3.76,
            "376cm" to 3.76,
            "3760mm" to 3.76,
            "3.76 meters" to 3.76,
            "1 m 20 cm" to 1.2,
        )
        for ((text, expected) in cases) {
            val parsed = assertNotNull(Units.parseLength(text, IMPERIAL), "\"$text\"")
            assertEquals(expected, parsed, 1e-12, "\"$text\"")
        }
    }

    @Test fun parseMetricTable() {
        val cases = listOf(
            "3.76" to 3.76,
            "3,76" to 3.76,
            "3,76 m" to 3.76,
            "3.76m" to 3.76,
            "3.76 metres" to 3.76,
            "376cm" to 3.76,
            "376 cm" to 3.76,
            "3760mm" to 3.76,
            "3760 millimeters" to 3.76,
            "1,234.5 mm" to 1.2345,
            "85 cm" to 0.85,
            "1m 5mm" to 1.005,
            "37 cm 5 mm" to 0.375,
            // Explicit imperial units work in either system.
            "12'4\"" to inches(148.0),
            "12 ft" to inches(144.0),
            "12 4\"" to inches(148.0),
        )
        for ((text, expected) in cases) {
            val parsed = assertNotNull(Units.parseLength(text, METRIC), "\"$text\"")
            assertEquals(expected, parsed, 1e-12, "\"$text\"")
        }
    }

    @Test fun parseRejectsGarbage() {
        val garbage = listOf(
            "", "   ", "abc", "ft", "\"", "12 ft ft", "1/0\"", "-3 m", "- 3", "0", "0'0\"", "0 cm",
            "1.2.3", "12' 4\" 5", "12'4\"3", "1e5", "NaN", "Infinity", "5 kg", "4/", "/4", "1 2 3",
            "3m 76", "12 m 4 ft", "4.5 1/2", "1/2/3", "12'#", "--4", "m 3",
        )
        for (text in garbage) {
            assertNull(Units.parseLength(text, IMPERIAL), "IMPERIAL \"$text\"")
            assertNull(Units.parseLength(text, METRIC), "METRIC \"$text\"")
        }
        // Two bare numbers only mean feet + inches in IMPERIAL.
        assertNull(Units.parseLength("12 4", METRIC))
    }

    @Test fun formatThenParseRoundTrips() {
        for (m in listOf(0.3, 1.0, 2.4384, 3.7719, 7.25, 12.0)) {
            val imperial = Units.formatLength(m, IMPERIAL, 8)
            assertEquals(m, assertNotNull(Units.parseLength(imperial, IMPERIAL), imperial), inches(1.0 / 16) + 1e-12)
            val metric = Units.formatLength(m, METRIC)
            assertEquals(m, assertNotNull(Units.parseLength(metric, METRIC), metric), 0.005 + 1e-12)
        }
    }
}
