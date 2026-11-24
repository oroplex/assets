package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleBarsTest {

    @Test
    fun `imperial bar for a typical house is a nice length near a fifth of the plan width`() {
        // A 12 m wide plan fitted into a 540 pt wide area.
        val ptPerMeter = 540.0 / 12.0
        val bar = ScaleBars.choose(ptPerMeter, 540.0, UnitSystem.IMPERIAL, maxLengthPt = 200.0)!!
        assertEquals("10 ft", bar.label)
        assertEquals(10 * 0.3048, bar.lengthMeters, 1e-9)
        assertEquals(bar.lengthMeters * ptPerMeter, bar.lengthPt, 1e-9)
        assertEquals(5, bar.segments)
    }

    @Test
    fun `metric bar picks two meters for the same plan`() {
        val bar = ScaleBars.choose(45.0, 540.0, UnitSystem.METRIC, maxLengthPt = 200.0)!!
        assertEquals("2 m", bar.label)
        assertEquals(2.0, bar.lengthMeters, 1e-12)
        assertEquals(90.0, bar.lengthPt, 1e-9)
        assertEquals(4, bar.segments)
    }

    @Test
    fun `bar stays roughly between 12 and 32 percent of the plan width across scales`() {
        // With 1-2-5 steps the closest candidate (in ratio) is within a factor sqrt(2.5) of the target.
        for (system in UnitSystem.entries) {
            var ptPerMeter = 3.0
            while (ptPerMeter < 300.0) {
                val bar = ScaleBars.choose(ptPerMeter, 540.0, system, maxLengthPt = 1_000.0)!!
                val fraction = bar.lengthPt / 540.0
                assertTrue("$system @ $ptPerMeter pt/m -> $fraction", fraction in 0.12..0.32)
                ptPerMeter *= 1.17
            }
        }
    }

    @Test
    fun `small plans use sub-unit bars and never exceed the maximum when a candidate fits`() {
        val imperial = ScaleBars.choose(1_000.0, 540.0, UnitSystem.IMPERIAL, maxLengthPt = 200.0)!!
        assertEquals("6 in", imperial.label)
        assertTrue(imperial.lengthPt <= 200.0)

        val metric = ScaleBars.choose(1_000.0, 540.0, UnitSystem.METRIC, maxLengthPt = 200.0)!!
        assertEquals("10 cm", metric.label)
        assertTrue(metric.lengthPt <= 200.0)
    }

    @Test
    fun `falls back to the smallest candidate when nothing fits`() {
        val bar = ScaleBars.choose(100_000.0, 540.0, UnitSystem.METRIC, maxLengthPt = 50.0)!!
        assertEquals("10 cm", bar.label)
    }

    @Test
    fun `invalid scales produce no bar`() {
        assertNull(ScaleBars.choose(0.0, 540.0, UnitSystem.METRIC, 200.0))
        assertNull(ScaleBars.choose(-1.0, 540.0, UnitSystem.METRIC, 200.0))
        assertNull(ScaleBars.choose(Double.NaN, 540.0, UnitSystem.METRIC, 200.0))
        assertNull(ScaleBars.choose(Double.POSITIVE_INFINITY, 540.0, UnitSystem.METRIC, 200.0))
        assertNull(ScaleBars.choose(45.0, 0.0, UnitSystem.METRIC, 200.0))
    }

    @Test
    fun `segments and labels`() {
        assertEquals(5, ScaleBars.segmentsFor(1.0))
        assertEquals(4, ScaleBars.segmentsFor(2.0))
        assertEquals(5, ScaleBars.segmentsFor(5.0))
        assertEquals(5, ScaleBars.segmentsFor(10.0))
        assertEquals(4, ScaleBars.segmentsFor(20.0))
        assertEquals(5, ScaleBars.segmentsFor(0.5))
        assertEquals(4, ScaleBars.segmentsFor(0.2))
        assertEquals(5, ScaleBars.segmentsFor(0.1))

        assertEquals("6 in", ScaleBars.label(0.5, UnitSystem.IMPERIAL))
        assertEquals("1 ft", ScaleBars.label(1.0, UnitSystem.IMPERIAL))
        assertEquals("20 ft", ScaleBars.label(20.0, UnitSystem.IMPERIAL))
        assertEquals("50 cm", ScaleBars.label(0.5, UnitSystem.METRIC))
        assertEquals("10 cm", ScaleBars.label(0.1, UnitSystem.METRIC))
        assertEquals("5 m", ScaleBars.label(5.0, UnitSystem.METRIC))
    }

    @Test
    fun `ratio of a drawing printed at full size`() {
        // 1 m drawn as 1 cm of paper = 1:100; 1 cm = 72 / 2.54 pt.
        assertEquals(100L, ScaleBars.ratio(72.0 / 2.54))
        assertEquals(50L, ScaleBars.ratio(2 * 72.0 / 2.54))
        assertNull(ScaleBars.ratio(0.0))
        assertNull(ScaleBars.ratio(Double.NaN))
    }
}

class PaginationTest {

    @Test
    fun `empty input yields one empty page`() {
        assertEquals(listOf(emptyList<Int>()), Pagination.paginate(emptyList<Int>(), 5, 5))
    }

    @Test
    fun `rows are split by capacity and keep their order`() {
        val pages = Pagination.paginate((1..10).toList(), 4, 4)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7, 8), listOf(9, 10)), pages)
    }

    @Test
    fun `first page may hold a different number of rows`() {
        val pages = Pagination.paginate((1..7).toList(), 2, 3)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4, 5), listOf(6, 7)), pages)
    }

    @Test
    fun `a heading is never stranded at the bottom of a page`() {
        val rows = listOf("H1", "a", "b", "H2", "c", "d")
        val pages = Pagination.paginate(rows, 4, 4) { it.startsWith("H") }
        assertEquals(listOf(listOf("H1", "a", "b"), listOf("H2", "c", "d")), pages)
    }

    @Test
    fun `consecutive headings move together`() {
        val rows = listOf("a", "b", "H1", "H2", "c")
        val pages = Pagination.paginate(rows, 4, 4) { it.startsWith("H") }
        assertEquals(listOf(listOf("a", "b"), listOf("H1", "H2", "c")), pages)
    }

    @Test
    fun `tiny capacities still terminate and lose nothing`() {
        val rows = listOf("H1", "a", "H2", "b")
        val pages = Pagination.paginate(rows, 0, -3) { it.startsWith("H") }
        assertEquals(rows, pages.flatten())
        assertTrue(pages.all { it.size == 1 })
    }

    @Test
    fun `a trailing heading stays when it is the last row`() {
        val pages = Pagination.paginate(listOf("a", "b", "H"), 3, 3) { it == "H" }
        assertEquals(listOf(listOf("a", "b", "H")), pages)
    }
}

class SheetLayoutTest {

    @Test
    fun `wide plans go landscape`() {
        assertTrue(SheetLayout.isLandscape(Bounds(0.0, 0.0, 12.0, 8.0)))
        assertFalse(SheetLayout.isLandscape(Bounds(0.0, 0.0, 8.0, 12.0)))
        assertFalse(SheetLayout.isLandscape(Bounds(0.0, 0.0, 11.0, 10.0))) // 1.1 < 1.15
        assertFalse(SheetLayout.isLandscape(Bounds(0.0, 0.0, 5.0, 0.0))) // degenerate
        assertFalse(SheetLayout.isLandscape(Bounds.EMPTY))
        assertFalse(SheetLayout.isLandscape(null))
    }

    @Test
    fun `plan area sits between header and footer inside the margins`() {
        for (paper in PaperSize.entries) {
            for (landscape in listOf(false, true)) {
                val w = paper.widthPt(landscape).toDouble()
                val h = paper.heightPt(landscape).toDouble()
                val plan = SheetLayout.planArea(w, h)
                val header = SheetLayout.header(w)
                val footer = SheetLayout.footer(w, h)
                assertTrue(plan.top > header.bottom)
                assertTrue(plan.bottom < footer.top)
                assertEquals(SheetLayout.MARGIN, plan.left, 1e-9)
                assertEquals(w - SheetLayout.MARGIN, plan.right, 1e-9)
                assertTrue(plan.height > 300)
            }
        }
    }

    @Test
    fun `paper sizes swap for landscape`() {
        assertEquals(792, PaperSize.LETTER.widthPt(landscape = true))
        assertEquals(612, PaperSize.LETTER.heightPt(landscape = true))
        assertEquals(595, PaperSize.A4.widthPt(landscape = false))
        assertEquals(842, PaperSize.A4.heightPt(landscape = false))
    }

    @Test
    fun `png sheet height follows the plan aspect within limits`() {
        val w = 792.0
        val chrome = SheetLayout.CHROME_HEIGHT
        assertEquals(chrome + SheetLayout.PNG_EMPTY_PLAN_HEIGHT, SheetLayout.pngSheetHeight(w, null), 1e-9)
        assertEquals(chrome + SheetLayout.PNG_EMPTY_PLAN_HEIGHT, SheetLayout.pngSheetHeight(w, Bounds.EMPTY), 1e-9)
        // Very flat plan -> minimum, very tall plan -> maximum.
        assertEquals(chrome + SheetLayout.PNG_MIN_PLAN_HEIGHT, SheetLayout.pngSheetHeight(w, Bounds(0.0, 0.0, 30.0, 1.0)), 1e-9)
        assertEquals(chrome + SheetLayout.PNG_MAX_PLAN_HEIGHT, SheetLayout.pngSheetHeight(w, Bounds(0.0, 0.0, 1.0, 30.0)), 1e-9)
        // Square plan: plan area as tall as it is wide.
        val square = SheetLayout.pngSheetHeight(w, Bounds(0.0, 0.0, 10.0, 10.0)) - chrome
        assertEquals(w - 2 * SheetLayout.MARGIN, square, 1e-9)
    }

    @Test
    fun `png pixel height of the tallest sheet stays bounded`() {
        val maxHeightPx = (SheetLayout.CHROME_HEIGHT + SheetLayout.PNG_MAX_PLAN_HEIGHT) * PngExporter.PX_PER_PT
        assertTrue(maxHeightPx < 4_500)
        assertEquals(3000.0, PngExporter.SHEET_WIDTH_PT * PngExporter.PX_PER_PT, 1e-9)
    }
}

class ExportFileNamesTest {

    @Test
    fun `whitespace becomes underscores and separators are dropped`() {
        assertEquals("Smith_House_Main", ExportFileNames.sanitize("  Smith House / Main ", "x"))
        assertEquals("a_b", ExportFileNames.sanitize("a\t\n b", "x"))
        assertEquals("Unit_4B_(rear)", ExportFileNames.sanitize("Unit 4B (rear)", "x"))
    }

    @Test
    fun `no hidden files or path traversal`() {
        assertEquals("etcpasswd", ExportFileNames.sanitize("../etc/passwd", "x"))
        assertEquals("plan", ExportFileNames.sanitize(".plan", "x"))
        assertEquals("abcd", ExportFileNames.sanitize("a:b*c?d", "x"))
    }

    @Test
    fun `non latin letters are kept and empty names fall back`() {
        assertEquals("Café_Ñandú", ExportFileNames.sanitize("Café Ñandú", "x"))
        assertEquals("Дом", ExportFileNames.sanitize("Дом", "x"))
        assertEquals("fallback", ExportFileNames.sanitize("", "fallback"))
        assertEquals("fallback", ExportFileNames.sanitize("  /// ", "fallback"))
        assertEquals("fallback", ExportFileNames.sanitize("🏠", "fallback")) // emoji only
    }

    @Test
    fun `long names are truncated`() {
        val name = ExportFileNames.sanitize("x".repeat(500), "f")
        assertEquals(60, name.length)
    }

    @Test
    fun `file names combine project and floor`() {
        assertEquals("Smith_House-Main_Floor.pdf", ExportFileNames.fileName("Smith House", "Main Floor", "pdf"))
        assertEquals("Smith_House.json", ExportFileNames.fileName("Smith House", null, "json"))
        assertEquals("Floor_plan-Floor.png", ExportFileNames.fileName("??", "", "png"))
    }
}
