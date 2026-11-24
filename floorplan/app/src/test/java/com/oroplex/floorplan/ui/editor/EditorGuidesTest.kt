package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Pure logic added for round 2: corner guides, snap neighbours, view animation, small helpers. */
class EditorGuidesTest {
    private fun rect(id: String, x: Double, y: Double, w: Double, h: Double, name: String = id) = Room(
        id = id,
        name = name,
        corners = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h)),
    )

    private fun angleAt(corners: List<Vec2>, k: Int): Double = Math.toDegrees(Polygons.interiorAngles(corners)[k])

    private fun assertNear(expected: Vec2, actual: Vec2, eps: Double = 1e-9) {
        assertTrue("expected $expected but was $actual", expected.approxEquals(actual, eps))
    }

    // ---- CornerGuides ----

    @Test
    fun nearlySquareCornerSnapsToTheRightAngleIntersection() {
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners
        val result = CornerGuides.snap(corners, 2, Vec2(4.03, 3.02), tolerance = 0.05, axisAngle = 0.0)
        assertNear(Vec2(4.0, 3.0), result.position)
        assertTrue(result.guides.all { it.kind == CornerGuides.Kind.RIGHT_ANGLE })
        assertEquals("RIGHT_ANGLE@1|RIGHT_ANGLE@2|RIGHT_ANGLE@3", result.key)
    }

    @Test
    fun rightAngleGuidesFollowAnAngledWallNotJustTheAxes() {
        // The bottom wall A-B is angled; squaring corner B means C must lie on B's perpendicular.
        val corners = listOf(Vec2(0.0, 0.0), Vec2(4.0, 1.0), Vec2(3.6, 3.1), Vec2(0.0, 3.0))
        val result = CornerGuides.snap(corners, 2, Vec2(3.53, 3.02), tolerance = 0.05, axisAngle = 0.0)
        assertNear(Vec2(3.5, 3.0), result.position, 1e-9)
        val moved = corners.toMutableList().also { it[2] = result.position }
        assertEquals(90.0, angleAt(moved, 1), 1e-6)
        assertEquals(90.0, angleAt(moved, 3), 1e-6)
    }

    @Test
    fun snappingWorksTheSameInARotatedRoom() {
        val angle = Math.toRadians(30.0)
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners.map { it.rotated(angle) }
        val result = CornerGuides.snap(corners, 2, Vec2(4.02, 2.97).rotated(angle), tolerance = 0.05)
        assertNear(Vec2(4.0, 3.0).rotated(angle), result.position)
        assertNotNull(result.key)
    }

    @Test
    fun cornerCanContinueANeighbouringWallInAStraightLine() {
        // B-C is an angled wall; D can be dragged so it continues that wall.
        val corners = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(5.0, 2.0), Vec2(5.4, 3.3), Vec2(0.0, 4.0))
        val result = CornerGuides.snap(corners, 3, Vec2(5.52, 2.99), tolerance = 0.05, axisAngle = 0.0)
        assertNear(Vec2(5.5, 3.0), result.position, 1e-9)
        assertEquals("STRAIGHT@2", result.key)
        // B, C and the snapped D are collinear.
        assertEquals(0.0, (corners[2] - corners[1]) cross (result.position - corners[2]), 1e-9)
    }

    @Test
    fun cornerLinesUpWithAFarCornerAndSquaresTheOtherWall() {
        // L-shaped room; dragging corner 4 near x = 4 lines it up with corners 1 and 2.
        val corners = listOf(
            Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0), Vec2(2.0, 2.0), Vec2(2.0, 4.0), Vec2(0.0, 4.0),
        )
        val result = CornerGuides.snap(corners, 4, Vec2(3.98, 4.01), tolerance = 0.05, axisAngle = 0.0)
        assertNear(Vec2(4.0, 4.0), result.position)
        val kinds = result.guides.map { it.kind }.toSet()
        assertEquals(setOf(CornerGuides.Kind.RIGHT_ANGLE, CornerGuides.Kind.ALIGN), kinds)
    }

    @Test
    fun secondaryGuidesUseASmallerTolerance() {
        val corners = listOf(
            Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0), Vec2(2.0, 2.0), Vec2(2.0, 4.0), Vec2(0.0, 4.0),
        )
        // 0.045 from the x = 4 alignment: inside the full tolerance but outside 3/4 of it.
        val result = CornerGuides.snap(corners, 4, Vec2(3.955, 4.2), tolerance = 0.05, axisAngle = 0.0)
        assertEquals(Vec2(3.955, 4.2), result.position)
        assertNull(result.key)
    }

    @Test
    fun aCornerFarFromEveryGuideMovesFreely() {
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners
        val target = Vec2(4.4, 3.5)
        val result = CornerGuides.snap(corners, 2, target, tolerance = 0.05, axisAngle = 0.0)
        assertEquals(target, result.position)
        assertTrue(result.guides.isEmpty())
        assertNull(result.key)
    }

    @Test
    fun badInputNeverSnapsOrThrows() {
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners
        assertNull(CornerGuides.snap(corners, 2, Vec2(4.01, 3.0), tolerance = 0.0).key)
        assertNull(CornerGuides.snap(corners, 2, Vec2(4.01, 3.0), tolerance = Double.NaN).key)
        assertNull(CornerGuides.snap(corners, 9, Vec2(4.01, 3.0), tolerance = 0.05).key)
        assertNull(CornerGuides.snap(corners.take(2), 1, Vec2(4.01, 3.0), tolerance = 0.05).key)
        val nan = Vec2(Double.NaN, 1.0)
        assertTrue(CornerGuides.snap(corners, 2, nan, tolerance = 0.05).position.x.isNaN())
        val broken = corners.toMutableList().also { it[0] = Vec2(Double.POSITIVE_INFINITY, 0.0) }
        assertTrue(CornerGuides.candidates(broken, 2, 0.0).isEmpty())
    }

    @Test
    fun coincidentGuidesAreMergedKeepingTheStrongestKind() {
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners
        val guides = CornerGuides.candidates(corners, 2, axisAngle = 0.0)
        val lines = guides.mapNotNull { (it.shape as? CornerGuides.Shape.Line)?.line }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val same = lines[i].sinAngleTo(lines[j]) < 1e-9 && lines[i].distance(lines[j].point) < 1e-9
                assertFalse("guides $i and $j are the same line", same)
            }
        }
        // The x = 4 line through corner 1 is both "square at corner 1" and "parallel to the axis": kept once, as RIGHT_ANGLE.
        val x4 = guides.filter { g -> (g.shape as? CornerGuides.Shape.Line)?.line?.let { it.distance(Vec2(4.0, 10.0)) < 1e-9 } == true }
        assertEquals(listOf(CornerGuides.Kind.RIGHT_ANGLE), x4.map { it.kind })
    }

    @Test
    fun rightAngleMarksAreReportedForSquareCornersNextToTheDraggedOne() {
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners
        val marks = CornerGuides.rightAngles(corners, 2)
        assertEquals(listOf(Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0)), marks.map { it.vertex })
        val skewed = corners.toMutableList().also { it[2] = Vec2(4.3, 3.2) }
        assertTrue(CornerGuides.rightAngles(skewed, 2).isEmpty())
        assertTrue(CornerGuides.rightAngles(corners, 7).isEmpty())
    }

    // ---- SnapNeighbour ----

    @Test
    fun snapNeighbourFindsTheRoomAcrossOneWallThickness() {
        val t = 0.1143
        val hall = rect("h", 0.0, 0.0, 4.0, 3.0, name = "Hallway")
        val bed = rect("b", 4.0 + t, 0.5, 3.0, 3.0, name = "Bedroom")
        val far = rect("f", 20.0, 0.0, 2.0, 2.0)
        val floor = Floor("f1", "Main", listOf(hall, bed, far))
        assertEquals("h", SnapNeighbour.find(floor, "b", t)?.id)
        assertEquals("b", SnapNeighbour.find(floor, "h", t)?.id)
        assertNull(SnapNeighbour.find(floor, "f", t))
        // 20 cm too far apart: not a shared wall.
        val apart = floor.copy(rooms = listOf(hall, rect("b", 4.0 + t + 0.2, 0.5, 3.0, 3.0)))
        assertNull(SnapNeighbour.find(apart, "b", t))
        assertNull(SnapNeighbour.find(floor, "missing", t))
    }

    @Test
    fun snapNeighbourPrefersTheLongestSharedWall() {
        val t = 0.1
        val a = rect("a", 0.0, 0.0, 4.0, 1.0) // its right wall faces c over 1 m
        val b = rect("b", 0.0, 1.0 + t, 4.0, 4.0) // its right wall faces c over 4 m
        val c = rect("c", 4.0 + t, 0.0, 3.0, 5.1)
        val floor = Floor("f", "F", listOf(a, b, c))
        assertEquals("b", SnapNeighbour.find(floor, "c", t)?.id)
    }

    // ---- ViewAnimation ----

    @Test
    fun interpolationStartsAndEndsExactlyAndZoomsGeometrically() {
        val from = ViewTransform(100.0, Vec2(0.0, 1000.0), 2.0)
        val to = ViewTransform(400.0, Vec2(-500.0, 1800.0), 2.0)
        assertEquals(from, ViewAnimation.interpolate(from, to, 0.0, 1000.0, 2000.0))
        assertEquals(to, ViewAnimation.interpolate(from, to, 1.0, 1000.0, 2000.0))
        assertEquals(to, ViewAnimation.interpolate(from, to, Double.NaN, 1000.0, 2000.0))
        val mid = ViewAnimation.interpolate(from, to, 0.5, 1000.0, 2000.0)
        assertEquals(200.0, mid.pxPerMeter, 1e-9)
        val center = Vec2(500.0, 1000.0)
        val expected = Vec2.lerp(from.toPlan(center), to.toPlan(center), 0.5)
        assertNear(expected, mid.toPlan(center), 1e-9)
    }

    @Test
    fun focusLeavesAVisibleRoomAloneAndNeverZoomsIn() {
        val view = ViewTransform(100.0, Vec2(0.0, 1000.0), 2.0) // plan (0..10, 0..10) -> screen (0..1000, 1000..0)
        val visible = Bounds(2.0, 2.0, 4.0, 4.0)
        assertEquals(view, ViewAnimation.focus(view, visible, 1000.0, 1000.0, 32.0))

        // Off screen but small: same zoom, centered.
        val offScreen = Bounds(20.0, 2.0, 22.0, 4.0)
        val centered = ViewAnimation.focus(view, offScreen, 1000.0, 1000.0, 32.0)
        assertEquals(100.0, centered.pxPerMeter, 1e-9)
        assertNear(Vec2(500.0, 500.0), centered.toScreen(offScreen.center), 1e-9)

        // Bigger than the screen at this zoom: zoom out just enough.
        val huge = Bounds(0.0, 0.0, 30.0, 10.0)
        val zoomedOut = ViewAnimation.focus(view, huge, 1000.0, 1000.0, 32.0)
        assertTrue(zoomedOut.pxPerMeter < 100.0)
        assertEquals((1000.0 - 64.0) / 30.0, zoomedOut.pxPerMeter, 1e-9)
        assertEquals(view, ViewAnimation.focus(view, Bounds.EMPTY, 1000.0, 1000.0, 32.0))
    }

    // ---- Small helpers ----

    @Test
    fun axisHintFollowsTheOtherRoomsOnTheFloor() {
        val angle = Math.toRadians(10.0)
        val a = rect("a", 0.0, 0.0, 4.0, 3.0).let { r -> r.copy(corners = r.corners.map { it.rotated(angle) }) }
        val b = rect("b", 5.0, 0.0, 3.0, 3.0).let { r -> r.copy(corners = r.corners.map { it.rotated(angle) }) }
        val c = rect("c", 0.0, 5.0, 2.0, 2.0)
        val floor = Floor("f", "F", listOf(a, b, c))
        assertEquals(angle, floorAxisHint(floor, "c") ?: Double.NaN, 1e-9)
        assertNull(floorAxisHint(Floor("f", "F", listOf(c)), "c"))
    }

    @Test
    fun theLaterRoomOfAnOverlapIsTheOneToFix() {
        val floor = Floor("f", "F", listOf(rect("a", 0.0, 0.0, 1.0, 1.0), rect("b", 0.5, 0.5, 1.0, 1.0)))
        assertEquals("b", roomToFix(floor, "a" to "b"))
        assertEquals("b", roomToFix(floor, "b" to "a"))
        assertEquals("a", roomToFix(floor, "a" to "gone"))
        assertNull(roomToFix(floor, "x" to "y"))
    }

    @Test
    fun ceilingTextMarksEstimatedHeights() {
        val settings = PlanSettings(unitSystem = UnitSystem.IMPERIAL)
        val room = rect("a", 0.0, 0.0, 1.0, 1.0)
        assertEquals("Not set", ceilingText(room, settings))
        assertEquals("8' 0\"", ceilingText(room.copy(ceilingHeight = 2.4384), settings))
        assertEquals("8' 0\" (est.)", ceilingText(room.copy(ceilingHeight = 2.4384, ceilingEstimated = true), settings))
        assertEquals("Not set", ceilingText(room.copy(ceilingHeight = Double.NaN), settings))
    }

    @Test
    fun guideOpsDrawTheGhostTheGuideLinesAndTheRightAngleMarks() {
        val view = ViewTransform(100.0, Vec2(0.0, 1000.0), 2.0)
        val guides = DragGuides(
            lines = listOf(Segment2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))),
            rightAngles = listOf(RightAngleMark(Vec2(1.0, 0.0), Vec2(-1.0, 0.0), Vec2(0.0, 1.0))),
            snapOutline = rect("a", 0.0, 0.0, 1.0, 1.0).corners,
        )
        val ops = guideOps(guides, view, overshootPx = 20.0, rightAnglePx = 10.0)
        assertEquals(1, ops.count { it is DrawOp.FillPolygon })
        val polylines = ops.filterIsInstance<DrawOp.Polyline>()
        assertEquals(3, polylines.size)
        // The guide line overshoots both ends by 20 px = 0.2 m.
        val guide = polylines.first { !it.closed && it.points.size == 2 }
        assertNear(Vec2(-0.2, 0.0), guide.points[0])
        assertNear(Vec2(1.2, 0.0), guide.points[1])
        val mark = polylines.first { it.points.size == 3 }
        assertNear(Vec2(0.9, 0.1), mark.points[1])
        assertTrue(guideOps(guides, view.copy(pxPerMeter = 0.0), 20.0, 10.0).isEmpty())
        assertTrue(guideOps(DragGuides(), view, 20.0, 10.0).isEmpty())
    }

    @Test
    fun sameGeometryToleratesRoundingOnly() {
        val a = rect("a", 0.0, 0.0, 4.0, 3.0)
        assertTrue(sameGeometry(a, a.copy(corners = a.corners.map { it + Vec2(0.00005, 0.0) })))
        assertFalse(sameGeometry(a, a.copy(corners = a.corners.map { it + Vec2(0.001, 0.0) })))
        assertFalse(sameGeometry(a, a.copy(corners = a.corners.drop(1))))
        val f = Floor("f", "F", listOf(a))
        assertTrue(sameGeometry(f, f))
        assertFalse(sameGeometry(f, f.copy(rooms = emptyList())))
    }

    @Test
    fun thalesSnapSquaresTheDraggedCornerItself() {
        // Corner 2's neighbours are not lined up with it, so only the circle over them can square it.
        val p = Vec2(4.0, 0.0)
        val n = Vec2(0.0, 3.0)
        val corners = listOf(Vec2(-1.0, -1.0), p, Vec2(1.5, 4.2), n)
        val center = Vec2.lerp(p, n, 0.5)
        val radius = p.distanceTo(n) / 2
        // A point 3 cm outside the circle, far from every line guide.
        val onCircle = center + Vec2.fromAngle(Math.toRadians(100.0), radius)
        val target = center + Vec2.fromAngle(Math.toRadians(100.0), radius + 0.03)
        val result = CornerGuides.snap(corners, 2, target, tolerance = 0.05, axisAngle = Math.toRadians(17.0))
        assertNear(onCircle, result.position, 1e-9)
        assertEquals("RIGHT_ANGLE@2", result.key)
        val moved = corners.toMutableList().also { it[2] = result.position }
        assertEquals(90.0, angleAt(moved, 2), 1e-6)
        assertEquals(radius, sqrt((result.position - center).lengthSquared), 1e-9)
        assertTrue(abs(result.position.distanceTo(center) - radius) < 1e-9)
    }
}
