package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Choosing one standard scale and orientation for all plan sheets of a PDF (PREM-5). */
class PlanScaleTest {

    private fun plan(width: Double, height: Double) = Bounds(0.0, 0.0, width, height)

    /** Bounds that do not depend on the scale (no dimension lines). */
    private fun choose(system: UnitSystem, paper: PaperSize, vararg floors: Bounds) =
        PlanScales.choose(system, paper) { floors.toList() }

    private val quarterInch = PlanScales.IMPERIAL[0]

    @Test
    fun `the standard scales are the architectural ones, largest first`() {
        assertEquals(listOf(48, 64, 96, 128, 192), PlanScales.IMPERIAL.map { it.denominator })
        assertEquals(
            listOf("1/4\" = 1'-0\"", "3/16\" = 1'-0\"", "1/8\" = 1'-0\"", "3/32\" = 1'-0\"", "1/16\" = 1'-0\""),
            PlanScales.IMPERIAL.map { it.label },
        )
        assertEquals(listOf(50, 75, 100, 150, 200), PlanScales.METRIC.map { it.denominator })
        assertEquals(listOf("1:50", "1:75", "1:100", "1:150", "1:200"), PlanScales.METRIC.map { it.label })
        assertEquals(PlanScales.IMPERIAL, PlanScales.standard(UnitSystem.IMPERIAL))
        assertEquals(PlanScales.METRIC, PlanScales.standard(UnitSystem.METRIC))
    }

    @Test
    fun `a scale is real paper size`() {
        // 1/4" = 1'-0": one foot (0.3048 m) is a quarter inch = 18 pt. 1:50: one meter is 2 cm = 56.69 pt.
        assertEquals(18.0, quarterInch.pointsPerMeter * 0.3048, 1e-9)
        assertEquals(9.0, PlanScales.IMPERIAL[2].pointsPerMeter * 0.3048, 1e-9) // 1/8"
        assertEquals(72.0 / 2.54 * 2.0, PlanScales.METRIC[0].pointsPerMeter, 1e-9)
        assertEquals(72.0 / 2.54, PlanScales.METRIC[2].pointsPerMeter, 1e-9) // 1:100: 1 m = 1 cm
        // The printed ratio of each scale is its own denominator.
        (PlanScales.IMPERIAL + PlanScales.METRIC).forEach {
            assertEquals(it.denominator.toLong(), ScaleBars.ratio(it.pointsPerMeter))
        }
    }

    @Test
    fun `fits compares the drawing with the plan area minus its margin`() {
        // Letter landscape: plan area 720 x 414 pt, 14 pt margin on each side -> 692 x 386 pt available.
        val ppm = quarterInch.pointsPerMeter
        assertTrue(PlanScales.fits(plan(692.0 / ppm, 386.0 / ppm), quarterInch, PaperSize.LETTER, landscape = true))
        assertFalse(PlanScales.fits(plan(693.0 / ppm, 386.0 / ppm), quarterInch, PaperSize.LETTER, landscape = true))
        assertFalse(PlanScales.fits(plan(692.0 / ppm, 387.0 / ppm), quarterInch, PaperSize.LETTER, landscape = true))
        // Letter portrait: 540 x 594 -> 512 x 566 available.
        assertTrue(PlanScales.fits(plan(512.0 / ppm, 566.0 / ppm), quarterInch, PaperSize.LETTER, landscape = false))
        assertFalse(PlanScales.fits(plan(513.0 / ppm, 566.0 / ppm), quarterInch, PaperSize.LETTER, landscape = false))
        // Nothing to draw never "fits".
        assertFalse(PlanScales.fits(Bounds.EMPTY, quarterInch, PaperSize.LETTER, landscape = true))
        assertFalse(PlanScales.fits(plan(Double.NaN, 3.0), quarterInch, PaperSize.LETTER, landscape = true))
    }

    @Test
    fun `a small home gets the largest scale, on a page shaped like the plan`() {
        // 8 x 5 m fits at 1/4" both ways; it is wider than tall, so landscape.
        assertEquals(SheetScale(quarterInch, landscape = true), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(8.0, 5.0)))
        // Square-ish plans stay portrait (the same 1.15 aspect rule as fit-to-page sheets).
        assertEquals(SheetScale(quarterInch, landscape = false), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(6.0, 5.5)))
        assertEquals(SheetScale(quarterInch, landscape = false), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(5.0, 8.0)))
    }

    @Test
    fun `the orientation that allows the larger scale wins`() {
        // 11 x 6 m at 1/4" is 650 x 354 pt: only landscape holds it.
        assertEquals(SheetScale(quarterInch, landscape = true), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(11.0, 6.0)))
        // 6 x 9 m at 1/4" is 354 x 531 pt: only portrait holds it.
        assertEquals(SheetScale(quarterInch, landscape = false), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(6.0, 9.0)))
        // 8.5 x 9.5 m at 1/4" is 502 x 561 pt: portrait (512 x 566 pt available) only just holds it.
        assertEquals(SheetScale(quarterInch, landscape = false), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(8.5, 9.5)))
    }

    @Test
    fun `a larger home steps down to the next standard scale`() {
        // 14 x 9 m: 827 pt at 1/4" and 620 x 399 pt at 3/16" fit neither way; 1/8" (413 x 266 pt) does.
        assertEquals(
            SheetScale(PlanScales.IMPERIAL[2], landscape = true),
            choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(14.0, 9.0)),
        )
        // Metric on A4: 10 x 8 m is 567 x 454 pt at 1:50 (too big both ways), 378 x 302 pt at 1:75.
        assertEquals(
            SheetScale(PlanScales.METRIC[1], landscape = true),
            choose(UnitSystem.METRIC, PaperSize.A4, plan(10.0, 8.0)),
        )
        // The paper matters: 12.5 m is 709 pt at 1:50, which A4 landscape (742 pt) holds and Letter (692 pt) does not.
        assertEquals(PlanScales.METRIC[0], choose(UnitSystem.METRIC, PaperSize.A4, plan(12.5, 6.0))?.scale)
        assertEquals(PlanScales.METRIC[1], choose(UnitSystem.METRIC, PaperSize.LETTER, plan(12.5, 6.0))?.scale)
    }

    @Test
    fun `all floors share the scale of the largest one`() {
        // The main floor needs 1/8"; the small upper floor would fit at 1/4" but gets 1/8" too.
        val both = choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(14.0, 9.0), plan(7.0, 6.0))
        assertEquals(SheetScale(PlanScales.IMPERIAL[2], landscape = true), both)
        // Order does not matter.
        assertEquals(both, choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(7.0, 6.0), plan(14.0, 9.0)))
    }

    @Test
    fun `all floors share one orientation, even when that costs a scale step`() {
        // At 1/4" the wide floor needs landscape and the tall one portrait: no common orientation.
        // At 3/16" (487 x 266 and 266 x 399 pt) portrait holds both; landscape still cannot hold the tall one.
        val wide = plan(11.0, 6.0)
        val tall = plan(6.0, 9.0)
        assertEquals(SheetScale(quarterInch, landscape = true), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, wide))
        assertEquals(SheetScale(quarterInch, landscape = false), choose(UnitSystem.IMPERIAL, PaperSize.LETTER, tall))
        assertEquals(
            SheetScale(PlanScales.IMPERIAL[1], landscape = false),
            choose(UnitSystem.IMPERIAL, PaperSize.LETTER, wide, tall),
        )
    }

    @Test
    fun `when both orientations hold every floor the pages follow the overall shape`() {
        // Both sets fit A4 at 1:50 either way. Widest floor 8.5 m, tallest 5 m: landscape.
        // Widest 6 m, tallest 6 m: portrait.
        val wideSet = arrayOf(plan(8.5, 4.0), plan(5.0, 5.0))
        val squareSet = arrayOf(plan(6.0, 4.0), plan(4.0, 6.0))
        for (set in listOf(wideSet, squareSet)) {
            for (landscape in listOf(true, false)) {
                assertTrue(set.all { PlanScales.fits(it, PlanScales.METRIC[0], PaperSize.A4, landscape) })
            }
        }
        assertEquals(SheetScale(PlanScales.METRIC[0], landscape = true), choose(UnitSystem.METRIC, PaperSize.A4, *wideSet))
        assertEquals(SheetScale(PlanScales.METRIC[0], landscape = false), choose(UnitSystem.METRIC, PaperSize.A4, *squareSet))
        assertTrue(PlanScales.prefersLandscape(listOf(plan(9.0, 4.0), plan(5.0, 5.0))))
        assertFalse(PlanScales.prefersLandscape(listOf(plan(6.0, 4.0), plan(4.0, 6.0))))
        assertFalse(PlanScales.prefersLandscape(emptyList()))
    }

    @Test
    fun `a building too large for the smallest standard scale falls back to fit to page`() {
        // 60 m is 886 pt at 1/16" and 850 pt at 1:200: wider than any page.
        assertNull(choose(UnitSystem.IMPERIAL, PaperSize.LETTER, plan(60.0, 40.0)))
        assertNull(choose(UnitSystem.METRIC, PaperSize.A4, plan(60.0, 40.0)))
        // One oversized floor sends the whole set to fit-to-page: floors must not have different scales.
        assertNull(choose(UnitSystem.METRIC, PaperSize.A4, plan(8.0, 5.0), plan(60.0, 40.0)))
        // Just inside the smallest scale: 48 m at 1:200 is 680 pt.
        assertEquals(PlanScales.METRIC[4], choose(UnitSystem.METRIC, PaperSize.A4, plan(48.0, 20.0))?.scale)
    }

    @Test
    fun `nothing to draw has no scale`() {
        assertNull(PlanScales.choose(UnitSystem.METRIC, PaperSize.A4) { emptyList() })
        assertNull(choose(UnitSystem.METRIC, PaperSize.A4, Bounds.EMPTY))
        // Unusable bounds are ignored next to a real floor.
        assertEquals(PlanScales.METRIC[0], choose(UnitSystem.METRIC, PaperSize.A4, Bounds.EMPTY, plan(8.0, 5.0))?.scale)
    }

    @Test
    fun `bounds are asked per candidate scale, largest first, and no further than needed`() {
        val asked = ArrayList<PlanScale>()
        // Dimension lines sit a fixed number of points outside the walls: more meters at a smaller scale.
        val chosen = PlanScales.choose(UnitSystem.METRIC, PaperSize.A4) { scale ->
            asked += scale
            val extra = 60.0 / scale.pointsPerMeter
            listOf(plan(12.8 + extra, 6.0 + extra))
        }
        // 1:50: (12.8 + 1.06) m = 786 pt > 742. 1:75: (12.8 + 1.59) m = 544 pt, fits.
        assertEquals(PlanScales.METRIC[1], chosen?.scale)
        assertEquals(listOf(PlanScales.METRIC[0], PlanScales.METRIC[1]), asked)
    }

    @Test
    fun `plan view draws at the fixed scale, centered in the plan area`() {
        val area = SheetLayout.planArea(792.0, 612.0) // Letter landscape: 720 x 414 pt
        val bounds = Bounds(2.0, -3.0, 12.0, 5.0)
        val ppm = PlanScales.METRIC[2].pointsPerMeter // 1:100
        for (pxPerPt in listOf(1.0, 0.31, 3.79)) { // PDF, phone preview, PNG
            val view = SheetLayout.planView(bounds, area, ppm, pxPerPt)
            assertEquals(ppm * pxPerPt, view.pxPerMeter, 1e-9)
            assertEquals(pxPerPt, view.pxPerPt, 0.0)
            val center = view.toScreen(bounds.center)
            assertEquals(area.width * pxPerPt / 2, center.x, 1e-9)
            assertEquals(area.height * pxPerPt / 2, center.y, 1e-9)
            // One meter on the plan is exactly one centimeter of paper at 1:100.
            val a = view.toScreen(Vec2(2.0, 0.0))
            val b = view.toScreen(Vec2(3.0, 0.0))
            assertEquals(72.0 / 2.54 * pxPerPt, b.x - a.x, 1e-9)
            // Plan y points up, the sheet's y down.
            assertTrue(view.toScreen(Vec2(2.0, 5.0)).y < view.toScreen(Vec2(2.0, -3.0)).y)
        }
    }

    @Test
    fun `plan view without a scale fits the plan to the area`() {
        val area = SheetLayout.planArea(612.0, 792.0)
        val bounds = Bounds(0.0, 0.0, 10.0, 8.0)
        val fitted = ViewTransform.fit(bounds, area.width * 2, area.height * 2, SheetLayout.PLAN_FIT_MARGIN * 2, 2.0)
        assertEquals(fitted, SheetLayout.planView(bounds, area, null, 2.0))
        assertEquals(fitted, SheetLayout.planView(bounds, area, Double.NaN, 2.0))
        assertEquals(fitted, SheetLayout.planView(bounds, area, 0.0, 2.0))
        // Bounds without a size cannot be placed at a scale either: the fit fallback handles them.
        val point = Bounds(1.0, 1.0, 1.0, 1.0)
        assertEquals(
            ViewTransform.fit(point, area.width, area.height, SheetLayout.PLAN_FIT_MARGIN, 1.0),
            SheetLayout.planView(point, area, 50.0, 1.0),
        )
    }

    @Test
    fun `the scale note names the standard scale, or the fitted ratio`() {
        assertEquals("Scale 1/4\" = 1'-0\" when printed at 100%", ScaleBars.scaleNote(quarterInch, quarterInch.pointsPerMeter))
        assertEquals("Scale 1:50 when printed at 100%", ScaleBars.scaleNote(PlanScales.METRIC[0], null))
        // Fit to page: the rounded ratio of the scale actually drawn (2834.65 / 38.83 = 73).
        assertEquals("Scale 1:73 when printed at 100%", ScaleBars.scaleNote(null, 38.83))
        assertNull(ScaleBars.scaleNote(null, null))
        assertNull(ScaleBars.scaleNote(null, Double.NaN))
        assertNull(ScaleBars.scaleNote(null, 0.0))
    }

    @Test
    fun `the scale bar agrees with the scale`() {
        // At 1/4" = 1'-0" a 10 ft bar is exactly 2.5 in = 180 pt; at 1:100 a 5 m bar is 5 cm.
        val imperial = ScaleBars.choose(quarterInch.pointsPerMeter, 720.0, UnitSystem.IMPERIAL, 230.0)!!
        assertEquals("10 ft", imperial.label)
        assertEquals(180.0, imperial.lengthPt, 1e-9)
        assertEquals(quarterInch.pointsPerMeter, imperial.lengthPt / imperial.lengthMeters, 1e-9)
        val metric = ScaleBars.choose(PlanScales.METRIC[2].pointsPerMeter, 720.0, UnitSystem.METRIC, 230.0)!!
        assertEquals("5 m", metric.label)
        assertEquals(5.0 * 72.0 / 2.54, metric.lengthPt, 1e-9)
        assertEquals(100L, ScaleBars.ratio(metric.lengthPt / metric.lengthMeters))
    }
}

/** The scale choice applied to real rendered floors by the PDF exporter. */
class PdfScaleTest {

    private fun room(id: String, x: Double, y: Double = 0.0, size: Double = 3.0) = Room(
        id = id,
        name = "Room $id",
        corners = listOf(Vec2(x, y), Vec2(x + size, y), Vec2(x + size, y + size), Vec2(x, y + size)),
    )

    /** [rooms] 3 m rooms in a row, 0.5 m apart: about 3.5 m of plan per room. */
    private fun floor(id: String, rooms: Int) = Floor(id, "Floor $id", rooms = (0 until rooms).map { room("$id$it", it * 3.5) })

    private fun project(system: UnitSystem, vararg floors: Floor) = Project(
        id = "p",
        name = "House",
        createdAt = 0,
        updatedAt = 0,
        floors = floors.toList(),
        settings = PlanSettings(unitSystem = system),
    )

    private fun prepare(project: Project, paper: PaperSize, labels: PlanLabels = PlanLabels()) =
        PdfExporter.prepare(project, project.floors, PdfOptions(paper = paper, labels = labels), "d")

    @Test
    fun `a small metric plan prints at 1 to 50`() {
        val prepared = prepare(project(UnitSystem.METRIC, floor("a", 2)), PaperSize.A4)
        val scale = prepared.sheetScale
        assertNotNull(scale)
        assertEquals("1:50", scale!!.scale.label)
        assertTrue(scale.landscape) // 6.5 m wide, 3 m deep
        val sheet = prepared.labeledSheets.single()
        assertEquals(scale.scale, sheet.scale)
        assertTrue(sheet.showScaleRatio)
        assertTrue(prepared.isLandscape(sheet))
        // What was rendered really fits the page at that scale (dimension lines included).
        assertTrue(PlanScales.fits(sheet.drawList!!.bounds, scale.scale, PaperSize.A4, scale.landscape))
        assertEquals("A4 landscape · 1:50 · 2 pages", prepared.summary)
    }

    @Test
    fun `an imperial plan is labelled in inches per foot`() {
        val prepared = prepare(project(UnitSystem.IMPERIAL, floor("a", 2)), PaperSize.LETTER)
        assertEquals("1/4\" = 1'-0\"", prepared.sheetScale?.scale?.label)
        assertEquals("Letter landscape · 1/4\" = 1'-0\" · 2 pages", prepared.summary)
        assertEquals(
            "Scale 1/4\" = 1'-0\" when printed at 100%",
            ScaleBars.scaleNote(prepared.labeledSheets.single().scale, null),
        )
    }

    @Test
    fun `every floor of a set gets the same scale and orientation`() {
        // The 28 m floor needs 1:150 on A4; the 6.5 m floor alone would print at 1:50.
        val small = floor("a", 2)
        val large = floor("b", 8)
        assertEquals("1:50", prepare(project(UnitSystem.METRIC, small), PaperSize.A4).sheetScale?.scale?.label)
        val prepared = prepare(project(UnitSystem.METRIC, small, large), PaperSize.A4)
        val scale = prepared.sheetScale!!
        assertEquals("1:150", scale.scale.label)
        assertEquals(listOf(scale.scale, scale.scale), prepared.labeledSheets.map { it.scale })
        assertEquals(listOf(scale.landscape, scale.landscape), prepared.labeledSheets.map { prepared.isLandscape(it) })
        prepared.labeledSheets.forEach {
            assertTrue(PlanScales.fits(it.drawList!!.bounds, scale.scale, PaperSize.A4, scale.landscape))
        }
        assertEquals("A4 landscape · 1:150 · 3 pages", prepared.summary)
    }

    @Test
    fun `a very large floor falls back to fit to page, sheet by sheet`() {
        // 70 rooms in a row are 245 m: nothing standard fits. Each sheet is then fitted, in its own orientation.
        val wide = floor("a", 70)
        val tall = Floor("t", "Tower", rooms = (0 until 70).map { room("t$it", 0.0, it * 3.5) })
        val prepared = prepare(project(UnitSystem.METRIC, wide, tall), PaperSize.A4)
        assertNull(prepared.sheetScale)
        assertTrue(prepared.labeledSheets.all { it.scale == null && it.drawList != null })
        assertEquals(listOf(true, false), prepared.labeledSheets.map { prepared.isLandscape(it) })
        assertTrue(prepared.summary, prepared.summary.startsWith("A4 landscape · fit to page · "))
        // The note under the bar then gives the fitted ratio instead of a standard scale.
        assertEquals("Scale 1:1250 when printed at 100%", ScaleBars.scaleNote(null, PlanScales.POINTS_PER_METER / 1250))
    }

    @Test
    fun `dimension lines are sized for the chosen scale`() {
        // The overall dimension line sits a distance measured on paper outside the walls (4.5 % of the floor's
        // size on the page, between 12 and 24 pt), so in plan meters it is much farther out at 1:200 than at
        // 1:50. The renderer only does that when told the scale.
        val p = project(UnitSystem.METRIC, floor("a", 2))
        val floor = p.floors.single()
        fun height(scale: PlanScale?) =
            FloorSheets.renderFloor(p, floor, PlanLabels(), pointsPerMeter = scale?.pointsPerMeter)!!.bounds.height
        val at50 = height(PlanScales.METRIC[0])
        val at200 = height(PlanScales.METRIC[4])
        assertTrue("1:50 $at50 m, 1:200 $at200 m", at200 > at50 + 0.8)
        // In points on paper the space taken outside the building (3 m of rooms plus an exterior wall on each
        // side, which is what the dimension measures) stays in a narrow band at both scales, a little smaller
        // for the smaller drawing; it is the building that scales.
        val building = 3.0 + 2 * p.settings.exteriorWallThickness!!
        val offsetPt50 = (at50 - building) * PlanScales.METRIC[0].pointsPerMeter
        val offsetPt200 = (at200 - building) * PlanScales.METRIC[4].pointsPerMeter
        assertTrue("offset at 1:50 $offsetPt50 pt", offsetPt50 in 20.0..36.0)
        assertTrue("offset at 1:200 $offsetPt200 pt", offsetPt200 in 14.0..offsetPt50)
        // The sheets carry exactly these renders.
        val sheet = prepare(p, PaperSize.A4).labeledSheets.single()
        assertEquals(at50, sheet.drawList!!.bounds.height, 1e-9)
    }

    @Test
    fun `the labels chosen on the export screen reach the scaled render`() {
        val p = project(UnitSystem.METRIC, floor("a", 2))
        val withDims = prepare(p, PaperSize.A4).labeledSheets.single().drawList!!
        val without = prepare(p, PaperSize.A4, PlanLabels(showOverallDimensions = false)).labeledSheets.single().drawList!!
        assertTrue(withDims.ops.any { it.tag?.startsWith("dimension:") == true })
        assertTrue(without.ops.none { it.tag?.startsWith("dimension:") == true })
        assertTrue(withDims.bounds.height > without.bounds.height)
    }
}
