package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.Fixtures.rect
import com.oroplex.floorplan.core.render.Fixtures.room
import com.oroplex.floorplan.core.render.Fixtures.settings
import com.oroplex.floorplan.core.render.Fixtures.t
import com.oroplex.floorplan.core.render.Fixtures.withDimensions
import com.oroplex.floorplan.core.solve.RoomOps
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverallDimensionsTest {
    private val box = room("a", rect(0.0, 0.0, 4.0, 3.0))

    /** Default exterior wall thickness (6.5"): overall dimensions measure to the outside of walls this thick. */
    private val e: Double = PlanGeometry.exteriorWallThickness(withDimensions)

    private fun dims(dl: DrawList, kind: String) = dl.ops.filter { it.tag == "dimension:$kind" }
    private fun lines(dl: DrawList, kind: String) = dims(dl, kind).filterIsInstance<DrawOp.Polyline>()
    private fun label(dl: DrawList, kind: String) = dims(dl, kind).filterIsInstance<DrawOp.Text>().single()

    /** The dimension line itself: the thinnest-but-one... i.e. the 0.75 pt line. */
    private fun mainLine(dl: DrawList, kind: String) = lines(dl, kind).single { it.widthPt == 0.75 }

    @Test fun onByDefaultAndSwitchable() {
        val on = PlanRenderer.render(listOf(box), withDimensions)
        assertEquals(1, dims(on, "width").count { it is DrawOp.Text })
        assertEquals(1, dims(on, "depth").count { it is DrawOp.Text })
        val off = PlanRenderer.render(listOf(box), withDimensions.copy(showOverallDimensions = false))
        assertTrue(off.ops.none { it.tag?.startsWith("dimension:") == true })
        // The render option overrides the setting both ways.
        assertTrue(PlanRenderer.render(listOf(box), withDimensions, RenderOptions(showOverallDimensions = false)).ops.none { it.tag?.startsWith("dimension:") == true })
        assertTrue(PlanRenderer.render(listOf(box), settings, RenderOptions(showOverallDimensions = true)).ops.any { it.tag == "dimension:width" })
    }

    @Test fun measuresTheOuterFacesAndLabelsThem() {
        val dl = PlanRenderer.render(listOf(box), withDimensions)
        val width = label(dl, "width")
        val depth = label(dl, "depth")
        assertEquals(Units.formatLength(4.0 + 2 * e, UnitSystem.IMPERIAL), width.text)
        assertEquals(Units.formatLength(3.0 + 2 * e, UnitSystem.IMPERIAL), depth.text)
        for (l in listOf(width, depth)) {
            assertTrue(l.bold)
            assertNear(8.0, l.sizePt)
            assertEquals(PlanColors.TEXT, l.color)
            assertEquals(PlanColors.BACKGROUND, l.haloColor)
            assertEquals(TextAlign.CENTER, l.align)
        }
        val metric = PlanRenderer.render(listOf(box), withDimensions.copy(unitSystem = UnitSystem.METRIC))
        assertEquals("4.33 m", label(metric, "width").text)
        // Text scale applies to the labels.
        assertNear(6.4, label(PlanRenderer.render(listOf(box), withDimensions, RenderOptions(textScale = 0.8)), "width").sizePt)
    }

    @Test fun widthAboveAndDepthLeftWithExtensionLinesAndTicks() {
        val dl = PlanRenderer.render(listOf(box), withDimensions)
        val w = mainLine(dl, "width")
        val d = mainLine(dl, "depth")
        // Width: horizontal, spanning the outer faces, above the top wall band.
        assertNear(w.points[0].y, w.points[1].y)
        assertNear(-e, minOf(w.points[0].x, w.points[1].x))
        assertNear(4 + e, maxOf(w.points[0].x, w.points[1].x))
        val y = w.points[0].y
        assertTrue(y > 3 + e + 0.1, "clear of the wall band: $y")
        // Depth: vertical, left of the left wall band.
        assertNear(d.points[0].x, d.points[1].x)
        assertNear(-e, minOf(d.points[0].y, d.points[1].y))
        assertNear(3 + e, maxOf(d.points[0].y, d.points[1].y))
        val x = d.points[0].x
        assertTrue(x < -e - 0.1, "clear of the wall band: $x")
        // Offset ~24 pt at the nominal page scale.
        assertNear(24.0 * (4 + 2 * t) / OverallDimensions.NOMINAL_PLAN_PT, y - (3 + e), 1e-9)

        // Two extension lines per dimension, perpendicular to it, from just off the building to past the line.
        val wExt = lines(dl, "width").filter { it.widthPt == 0.5 }
        assertEquals(2, wExt.size)
        for (ext in wExt) {
            assertNear(ext.points[0].x, ext.points[1].x)
            val lo = minOf(ext.points[0].y, ext.points[1].y)
            val hi = maxOf(ext.points[0].y, ext.points[1].y)
            assertTrue(lo > 3 + e && lo < y, "starts off the outer face")
            assertTrue(hi > y, "runs past the dimension line")
        }
        assertEquals(setOf(-e, 4 + e).map { "%.9f".format(it) }.toSet(), wExt.map { "%.9f".format(it.points[0].x) }.toSet())
        // 45° slashes centered on both ends of each line.
        for (kind in listOf("width", "depth")) {
            val main = mainLine(dl, kind)
            val ticks = lines(dl, kind).filter { it.widthPt == 1.0 }
            assertEquals(2, ticks.size)
            for ((tick, end) in ticks.zip(main.points)) {
                assertNear(end, Vec2.lerp(tick.points[0], tick.points[1], 0.5), 1e-9)
                val dir = tick.points[1] - tick.points[0]
                assertNear(PI / 4, abs(dir.angle()) % (PI / 2), 1e-9)
            }
        }
    }

    @Test fun labelsSitOutsideTheirLines() {
        val dl = PlanRenderer.render(listOf(box), withDimensions)
        val width = label(dl, "width")
        val depth = label(dl, "depth")
        assertNear(0.0, width.angle)
        assertNear(PI / 2, abs(depth.angle))
        assertTrue(width.angle > -PI / 2 && width.angle <= PI / 2 && depth.angle > -PI / 2 && depth.angle <= PI / 2, "upright")
        assertNear(Vec2(2.0, mainLine(dl, "width").points[0].y), width.anchor, 1e-9)
        assertNear(Vec2(mainLine(dl, "depth").points[0].x, 1.5), depth.anchor, 1e-9)
        // The label's offset pushes it away from the plan: up for the width, left for the depth.
        fun shift(l: DrawOp.Text) = Vec2.fromAngle(l.angle + PI / 2) * l.offsetPt.y
        assertTrue(shift(width).y > 0)
        assertTrue(shift(depth).x < 0)
        assertNear(6.0, abs(width.offsetPt.y)) // 2 pt gap + half the 8 pt text
    }

    @Test fun boundsIncludeTheDimensions() {
        val dl = PlanRenderer.render(listOf(box), withDimensions)
        val b = dl.bounds.expanded(1e-9)
        for (op in dl.ops.filter { it.tag?.startsWith("dimension:") == true }) {
            when (op) {
                is DrawOp.Polyline -> op.points.forEach { assertTrue(b.contains(it), "$it in $b") }
                is DrawOp.Text -> {
                    assertTrue(b.contains(op.anchor))
                    // Room for the label itself beyond its anchor.
                    val shifted = op.anchor + Vec2.fromAngle(op.angle + PI / 2) * (op.offsetPt.y * (4 + 2 * t) / OverallDimensions.NOMINAL_PLAN_PT)
                    assertTrue(b.contains(shifted))
                }
                else -> Unit
            }
        }
        val plain = PlanRenderer.render(listOf(box), settings).bounds
        assertTrue(dl.bounds.minX < plain.minX && dl.bounds.maxY > plain.maxY)
        assertNear(4 + e, dl.bounds.maxX, 0.05)
        // An SVG of it keeps every dimension on the page.
        val svg = SvgExporter.export(dl, 612.0, 792.0)
        assertTrue(svg.contains("<polyline"))
    }

    @Test fun followsTheFloorsOwnAxis() {
        // The same house tilted 30°: dimensions run along its walls and measure the same lengths.
        val turn = RigidTransform2.rotationAround(Vec2(2.0, 1.5), PI / 6)
        val tilted = RoomOps.transform(box, turn)
        val dl = PlanRenderer.render(listOf(tilted), withDimensions)
        assertEquals(Units.formatLength(4.0 + 2 * e, UnitSystem.IMPERIAL), label(dl, "width").text)
        assertEquals(Units.formatLength(3.0 + 2 * e, UnitSystem.IMPERIAL), label(dl, "depth").text)
        val w = mainLine(dl, "width")
        assertNear(PI / 6, (w.points[1] - w.points[0]).angle(), 1e-9)
        assertNear(4.0 + 2 * e, w.points[0].distanceTo(w.points[1]), 1e-9)
        assertNear(PI / 6, label(dl, "width").angle, 1e-9)
        // Tilted 60°: still dimensioned along the walls, the more horizontal direction is the "width".
        val steep = RoomOps.transform(box, RigidTransform2.rotationAround(Vec2(2.0, 1.5), PI / 3))
        val sw = mainLine(PlanRenderer.render(listOf(steep), withDimensions), "width")
        assertNear(-PI / 6, Polygons.normalizeAngle((sw.points[1] - sw.points[0]).angle()), 1e-9)
    }

    @Test fun extensionLinesStartAtTheOutermostWallAtEachEnd() {
        // L-shaped floor: the right end is only 3 m tall, the left 5 m.
        val l = room("l", Fixtures.lShape)
        val dl = PlanRenderer.render(listOf(l), withDimensions)
        val y = mainLine(dl, "width").points[0].y
        val ext = lines(dl, "width").filter { it.widthPt == 0.5 }
        val right = ext.single { it.points[0].x > 3.0 }
        val left = ext.single { it.points[0].x < 3.0 }
        assertTrue(minOf(right.points[0].y, right.points[1].y) < 3 + e + 0.1, "right one starts at the 3 m arm")
        assertTrue(minOf(left.points[0].y, left.points[1].y) > 5 + e, "left one starts above the 5 m wall")
        assertTrue(maxOf(right.points[0].y, right.points[1].y) > y)
        assertEquals(Units.formatLength(6.0 + 2 * e, UnitSystem.IMPERIAL), label(dl, "width").text)
        assertEquals(Units.formatLength(5.0 + 2 * e, UnitSystem.IMPERIAL), label(dl, "depth").text)
    }

    @Test fun wholeSampleHouseAndOrdering() {
        val sample = SampleProjects.demo(1L)
        val rooms = sample.floors.first().rooms
        val dl = PlanRenderer.render(rooms, sample.settings, RenderOptions(selectedRoomId = rooms[1].id, highlightRoomIds = setOf(rooms[2].id)))
        // Interior extents plus one exterior wall on each side.
        val inner = Bounds.of(rooms.flatMap { it.corners })
        val ext = PlanGeometry.exteriorWallThickness(sample.settings)
        assertEquals(Units.formatLength(inner.width + 2 * ext, UnitSystem.IMPERIAL), label(dl, "width").text)
        assertEquals(Units.formatLength(inner.height + 2 * ext, UnitSystem.IMPERIAL), label(dl, "depth").text)
        // Order: selection highlight < warning highlight < dimension lines < dimension labels < room labels.
        val ops = dl.ops
        val selection = ops.indexOfFirst { it is DrawOp.Polyline && it.color == PlanColors.SELECTION }
        val highlight = ops.indexOfFirst { it is DrawOp.Polyline && it.color == PlanColors.WARNING }
        val firstDimLine = ops.indexOfFirst { it is DrawOp.Polyline && it.tag?.startsWith("dimension:") == true }
        val lastDimLine = ops.indexOfLast { it is DrawOp.Polyline && it.tag?.startsWith("dimension:") == true }
        val firstDimLabel = ops.indexOfFirst { it is DrawOp.Text && it.tag?.startsWith("dimension:") == true }
        val firstRoomLabel = ops.indexOfFirst { it is DrawOp.Text && it.tag == null }
        assertTrue(selection in 0 until highlight, "selection before highlight")
        assertTrue(highlight < firstDimLine && lastDimLine < firstDimLabel && firstDimLabel < firstRoomLabel)
    }

    @Test fun highlightedRoomsGetADashedWarningOutline() {
        val a = box
        val b = room("b", rect(4.0 + t, 0.0, 7.0, 3.0))
        val dl = PlanRenderer.render(listOf(a, b), settings, RenderOptions(highlightRoomIds = setOf("b", "missing")))
        val outline = dl.opsOf<DrawOp.Polyline>().single { it.color == PlanColors.WARNING }
        assertEquals(b.corners, outline.points)
        assertTrue(outline.closed)
        assertNear(2.0, outline.widthPt)
        assertTrue(outline.dashPt > 0)
        assertTrue(PlanRenderer.render(listOf(a, b), settings).ops.none { it.colorOrNull() == PlanColors.WARNING })
        // A highlighted broken room keeps its own warning outline and gets the highlight too, without crashing.
        val broken = Room(id = "x", name = "X", corners = listOf(Vec2(0.0, 0.0), Vec2(Double.NaN, 1.0), Vec2(1.0, 1.0)))
        val withBroken = PlanRenderer.render(listOf(a, broken), settings, RenderOptions(highlightRoomIds = setOf("x")))
        assertTrue(withBroken.opsOf<DrawOp.Polyline>().filter { it.color == PlanColors.WARNING }.all { p -> p.points.all { it.isFinite() } })
    }

    @Test fun degenerateFloorsHaveNoDimensions() {
        val tiny = room("t", rect(0.0, 0.0, 0.05, 0.05))
        val dl = PlanRenderer.render(listOf(tiny), withDimensions.copy(wallThickness = 0.01))
        assertTrue(dl.ops.none { it.tag?.startsWith("dimension:") == true }, "under 30 cm: nothing to dimension")
        val broken = Room(id = "x", name = "X", corners = listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0)))
        assertTrue(PlanRenderer.render(listOf(broken), withDimensions).ops.none { it.tag?.startsWith("dimension:") == true })
        assertTrue(PlanRenderer.render(emptyList(), withDimensions).ops.isEmpty())
    }

    @Test fun svgExportOfDimensionsIsWellFormed() {
        val sample = SampleProjects.demo(1L)
        val dl = PlanRenderer.render(sample.floors.first().rooms, sample.settings, RenderOptions(textScale = 0.8))
        val svg = SvgExporter.export(dl, 792.0, 612.0, 36.0, title = "Sample \"House\" <Main> & Co")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
        val texts = doc.getElementsByTagNameNS("http://www.w3.org/2000/svg", "text").let { list -> (0 until list.length).map { list.item(it) as Element } }
        val width = label(dl, "width").text
        val depth = label(dl, "depth").text
        assertTrue('"' in width && '\'' in width, "imperial labels carry feet and inch marks: $width")
        val widthEl = texts.single { it.textContent == width }
        assertEquals("bold", widthEl.getAttribute("font-weight"))
        val depthEl = texts.single { it.textContent == depth }
        assertTrue(depthEl.getAttribute("transform").contains("rotate(-90)") || depthEl.getAttribute("transform").contains("rotate(90)"))
        // Every dimension line is on the page.
        val polylines = doc.getElementsByTagNameNS("http://www.w3.org/2000/svg", "polyline")
        for (i in 0 until polylines.length) {
            val pts = (polylines.item(i) as Element).getAttribute("points").split(' ').map { p -> p.split(',').map { it.toDouble() } }
            assertTrue(pts.all { (x, y) -> x in 0.0..792.0 && y in 0.0..612.0 })
        }
        assertTrue(texts.any { it.textContent == "Sample \"House\" <Main> & Co" })
    }

    @Test fun measuresToTheOutsideOfTheExteriorWalls() {
        // MATH-9: a house 12.00 m wide inside with 0.30 m exterior walls is 12.60 m outside.
        val house = room("h", rect(0.0, 0.0, 12.0, 8.0))
        val thick = withDimensions.copy(unitSystem = UnitSystem.METRIC, exteriorWallThickness = 0.30)
        val dl = PlanRenderer.render(listOf(house), thick)
        assertEquals("12.60 m", label(dl, "width").text)
        assertEquals("8.60 m", label(dl, "depth").text)
        val w = mainLine(dl, "width")
        assertNear(-0.30, minOf(w.points[0].x, w.points[1].x))
        assertNear(12.30, maxOf(w.points[0].x, w.points[1].x))
        // The drawn wall bands stay at the interior thickness (it is also the snapping gap).
        val band = dl.opsOf<DrawOp.FillPolygon>().first { it.color == PlanColors.WALL }
        assertNear(-t, band.points.minOf { it.x }, 1e-9)
        // null: measure with the interior wall thickness; 0: the interior extents; bad numbers fall back too.
        val metric = withDimensions.copy(unitSystem = UnitSystem.METRIC)
        assertEquals("12.23 m", label(PlanRenderer.render(listOf(house), metric.copy(exteriorWallThickness = null)), "width").text)
        assertEquals("12.00 m", label(PlanRenderer.render(listOf(house), metric.copy(exteriorWallThickness = 0.0)), "width").text)
        for (bad in listOf(Double.NaN, -0.1, Double.POSITIVE_INFINITY)) {
            assertEquals("12.23 m", label(PlanRenderer.render(listOf(house), metric.copy(exteriorWallThickness = bad)), "width").text, "$bad")
        }
        // The default is 6.5".
        assertNear(0.1651, e, 1e-12)
    }

    @Test fun pointsPerMeterSizesTheDimensionsAtTheOutputScale() {
        // 1:50 on paper is 72 / 0.0254 / 50 ≈ 56.7 pt per plan meter.
        val ppm = 72 / 0.0254 / 50
        val dl = PlanRenderer.render(listOf(box), withDimensions, RenderOptions(pointsPerMeter = ppm))
        val w = mainLine(dl, "width")
        // The offset from the outer face is 4.5 % of the floor's larger extent on the output, between 12 and
        // 24 pt, at exactly that scale. This small room is about 240 pt wide at 1:50: 12 pt.
        fun offsetPt(scale: Double) = (0.045 * (4 + 2 * e) * scale).coerceIn(12.0, 24.0)
        assertNear(12.0, offsetPt(ppm), 1e-9)
        assertNear(offsetPt(ppm) / ppm, w.points[0].y - (3 + e), 1e-9)
        val d = mainLine(dl, "depth")
        assertNear(-offsetPt(ppm) / ppm, d.points[0].x + e, 1e-9)
        // Enlarged until it is as wide as a page, the offset reaches its full 24 pt and stays there.
        for (zoom in listOf(2.5, 4.0)) {
            val big = mainLine(PlanRenderer.render(listOf(box), withDimensions, RenderOptions(pointsPerMeter = ppm * zoom)), "width")
            assertNear(24.0 / (ppm * zoom), big.points[0].y - (3 + e), 1e-9, "zoom $zoom")
        }
        // Ticks are 8 pt long.
        val tick = lines(dl, "width").first { it.widthPt == 1.0 }
        assertNear(8.0 / ppm, tick.points[0].distanceTo(tick.points[1]), 1e-9)
        // Extension lines start 3 pt off the outer face and overshoot the line by 3 pt.
        val ext = lines(dl, "width").filter { it.widthPt == 0.5 }
        for (x in ext) {
            assertNear(3 + e + 3.0 / ppm, minOf(x.points[0].y, x.points[1].y), 1e-9)
            assertNear(w.points[0].y + 3.0 / ppm, maxOf(x.points[0].y, x.points[1].y), 1e-9)
        }
        // A smaller output scale moves the line farther out in plan meters (it stays 12 pt on the output).
        val small = mainLine(PlanRenderer.render(listOf(box), withDimensions, RenderOptions(pointsPerMeter = ppm / 2)), "width")
        assertNear(24.0 / ppm, small.points[0].y - (3 + e), 1e-9)
        // Text scale still applies on top.
        val scaled = mainLine(PlanRenderer.render(listOf(box), withDimensions, RenderOptions(pointsPerMeter = ppm, textScale = 0.5)), "width")
        assertNear(6.0 / ppm, scaled.points[0].y - (3 + e), 1e-9)
        // Unusable scales fall back to the nominal fit-to-page scale.
        val nominal = mainLine(PlanRenderer.render(listOf(box), withDimensions), "width").points[0].y
        for (bad in listOf(0.0, -10.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNear(nominal, mainLine(PlanRenderer.render(listOf(box), withDimensions, RenderOptions(pointsPerMeter = bad)), "width").points[0].y, 1e-12, "$bad")
        }
        // The bounds cover the label box at that scale.
        val widthLabel = label(dl, "width")
        val top = widthLabel.anchor.y + (widthLabel.offsetPt.y + widthLabel.sizePt / 2) / ppm
        assertTrue(dl.bounds.maxY >= top - 1e-9, "${dl.bounds.maxY} >= $top")
    }

    private fun DrawOp.colorOrNull(): Int? = when (this) {
        is DrawOp.FillPolygon -> color
        is DrawOp.Polyline -> color
        is DrawOp.Arc -> color
        is DrawOp.Dot -> fillColor
        is DrawOp.Text -> color
    }
}
