package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSolverTest {
    private val rectWalls = wallsOf(Shapes.rect)

    // ---------------------------------------------------------------- basic rectangles

    @Test fun rectangleCapturedCounterClockwise() {
        val r = assertNotNull(RoomSolver.solveWalls(rectWalls))
        assertPolygonEquals(Shapes.rect, r.corners)
        assertEquals(listOf(0, 1, 2, 3), r.edgeSources)
        assertEquals(0.0, r.dominantAngle, 1e-12)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun rectangleCapturedClockwiseIsNormalizedToCcw() {
        // Clockwise walk: bottom, left, top, right. Every observation is still interior-left.
        val cw = listOf(rectWalls[0], rectWalls[3], rectWalls[2], rectWalls[1])
        val r = assertNotNull(RoomSolver.solveWalls(cw))
        assertTrue(Polygons.isCounterClockwise(r.corners))
        assertPolygonEquals(Shapes.rect, r.corners)
        // Edge 0 is still the first captured wall; edge 1 (right side) is observation 3, etc.
        assertEquals(listOf(0, 3, 2, 1), r.edgeSources)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun edgeSourcesPointAtTheWallEachEdgeLiesOn() {
        val orders = listOf(listOf(0, 1, 2, 3), listOf(2, 3, 0, 1), listOf(0, 3, 2, 1), listOf(1, 0, 3, 2))
        for (order in orders) {
            val obs = order.map { rectWalls[it] }
            val r = assertNotNull(RoomSolver.solveWalls(obs), "order $order")
            assertEquals(4, r.corners.size)
            for (i in r.corners.indices) {
                val a = r.corners[i]
                val b = r.corners[(i + 1) % 4]
                val line = obs[r.edgeSources[i]].line
                assertEquals(0.0, line.distance(a), 1e-9, "order $order edge $i")
                assertEquals(0.0, line.distance(b), 1e-9, "order $order edge $i")
                // Edge direction matches the interior-left line direction (CCW outline).
                assertTrue(((b - a).normalized() dot line.direction) > 0.999, "order $order edge $i direction")
            }
        }
    }

    @Test fun inconsistentlyOrientedLinesAreReorientedByViewer() {
        val flipped = rectWalls.mapIndexed { i, o ->
            if (i % 2 == 0) o.copy(line = o.line.flipped(), extentStart = o.extentEnd, extentEnd = o.extentStart) else o
        }
        val r = assertNotNull(RoomSolver.solveWalls(flipped))
        assertPolygonEquals(Shapes.rect, r.corners)
        assertEquals(listOf(0, 1, 2, 3), r.edgeSources)
    }

    @Test fun rotatedAndTranslatedRoomKeepsShape() {
        val t = RigidTransform2(Math.toRadians(17.0), Vec2(5.0, -2.0))
        val obs = rectWalls.map { o ->
            o.copy(line = t.apply(o.line), extentStart = t.apply(o.extentStart), extentEnd = t.apply(o.extentEnd), viewer = t.apply(o.viewer))
        }
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(Shapes.rect.map { t.apply(it) }, r.corners, 1e-9)
        assertEquals(Math.toRadians(17.0), r.dominantAngle, 1e-9)
    }

    @Test fun fewerThanThreeObservationsOrUnusableDataGiveNull() {
        assertNull(RoomSolver.solveWalls(emptyList()))
        assertNull(RoomSolver.solveWalls(rectWalls.take(2)))
        val broken = rectWalls[1].copy(extentStart = Vec2(Double.NaN, 0.0))
        assertNull(RoomSolver.solveWalls(listOf(rectWalls[0], broken, rectWalls[2])))
        // A broken observation among enough good ones is skipped, and its index never appears.
        val r = assertNotNull(RoomSolver.solveWalls(listOf(rectWalls[0], rectWalls[1], broken.copy(line = Line2(Vec2(Double.NaN, 0.0), Vec2(1.0, 0.0))), rectWalls[2], rectWalls[3])))
        assertEquals(4, r.corners.size)
        assertTrue(2 !in r.edgeSources)
    }

    // ---------------------------------------------------------------- squaring

    @Test fun perturbedWallsAreSquaredToExactRightAngles() {
        val patterns = listOf(
            listOf(2.0, -2.0, 2.0, -2.0),
            listOf(1.5, 2.0, -1.0, 0.5),
            listOf(-2.0, -1.5, -2.0, -0.5),
            listOf(2.0, 2.0, 2.0, 2.0),
        )
        for (p in patterns) {
            val obs = rectWalls.mapIndexed { i, o -> perturb(o, p[i]) }
            val r = assertNotNull(RoomSolver.solveWalls(obs), "pattern $p")
            assertEquals(4, r.corners.size, "pattern $p")
            assertAllRightAngles(r.corners)
            val box = Polygons.orientedBoundingBox(r.corners)
            assertEquals(4.0, box.length, 0.005, "length for $p")
            assertEquals(3.0, box.width, 0.005, "width for $p")
            assertEquals(listOf(0, 1, 2, 3), r.edgeSources)
            assertTrue(r.warnings.isEmpty(), "warnings for $p: ${r.warnings}")
        }
    }

    @Test fun squaringOffPreservesObservedAngles() {
        val degs = listOf(2.0, -2.0, 1.0, -1.5)
        val obs = rectWalls.mapIndexed { i, o -> perturb(o, degs[i]) }
        val r = assertNotNull(RoomSolver.solveWalls(obs, SolverOptions(squareAngles = false)))
        assertEquals(4, r.corners.size)
        for (i in 0 until 4) {
            val dir = (r.corners[(i + 1) % 4] - r.corners[i]).normalized()
            val expected = obs[r.edgeSources[i]].line.direction
            assertTrue((dir dot expected) > 1 - 1e-12, "edge $i keeps its observed direction")
        }
        val angles = Polygons.interiorAngles(r.corners).map { Math.toDegrees(it) }
        assertTrue(angles.any { abs(it - 90.0) > 1.0 }, "angles should not be squared: $angles")
    }

    @Test fun wallsOutsideSquaringToleranceAreNotSnapped() {
        // A pentagon-ish room: one wall cut at 45 degrees across a corner.
        val corners = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 2.0), v(3.0, 3.0), v(0.0, 3.0))
        val r = assertNotNull(RoomSolver.solveWalls(wallsOf(corners)))
        assertPolygonEquals(corners, r.corners, 1e-9)
    }

    // ---------------------------------------------------------------- shapes

    @Test fun lShapedRoomFromSixWalls() {
        val r = assertNotNull(RoomSolver.solveWalls(wallsOf(Shapes.lShape)))
        assertPolygonEquals(Shapes.lShape, r.corners)
        assertEquals((0..5).toList(), r.edgeSources)
        assertEquals(24.0, Polygons.area(r.corners), 1e-9)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun lShapedRoomCapturedClockwise() {
        val walls = wallsOf(Shapes.lShape)
        val cw = listOf(0, 5, 4, 3, 2, 1).map { walls[it] }
        val r = assertNotNull(RoomSolver.solveWalls(cw))
        assertPolygonEquals(Shapes.lShape, r.corners)
        assertEquals(listOf(0, 5, 4, 3, 2, 1), r.edgeSources)
    }

    @Test fun jogBetweenSameFacingParallelWallsGetsConnector() {
        // Bottom wall steps up by 0.3 m at x = 2.
        val obs = listOf(
            wallObs(v(0.3, 0.0), v(1.8, 0.0)),
            wallObs(v(2.2, 0.3), v(3.7, 0.3)),
            wallObs(v(4.0, 0.6), v(4.0, 2.4)),
            wallObs(v(3.5, 3.0), v(0.5, 3.0)),
            wallObs(v(0.0, 2.5), v(0.0, 0.5)),
        )
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(
            listOf(v(0.0, 0.0), v(2.0, 0.0), v(2.0, 0.3), v(4.0, 0.3), v(4.0, 3.0), v(0.0, 3.0)),
            r.corners,
        )
        assertEquals(listOf(0, -1, 1, 2, 3, 4), r.edgeSources)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun jogInClockwiseCaptureIsPlacedTheSameWay() {
        val obs = listOf(
            wallObs(v(2.2, 0.3), v(3.7, 0.3)),
            wallObs(v(0.3, 0.0), v(1.8, 0.0)),
            wallObs(v(0.0, 2.5), v(0.0, 0.5)),
            wallObs(v(3.5, 3.0), v(0.5, 3.0)),
            wallObs(v(4.0, 0.6), v(4.0, 2.4)),
        )
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertEquals(6, r.corners.size)
        assertTrue(Polygons.isCounterClockwise(r.corners))
        assertEquals(4.0 * 3.0 - 2.0 * 0.3, Polygons.area(r.corners), 1e-9)
        assertTrue(r.corners.any { it.approxEquals(v(2.0, 0.0), 1e-9) })
        assertTrue(r.corners.any { it.approxEquals(v(2.0, 0.3), 1e-9) })
        assertEquals(1, r.edgeSources.count { it == -1 })
    }

    @Test fun duplicateCaptureOfTheSameWallIsMerged() {
        val obs = listOf(
            wallObs(v(0.5, 0.0), v(2.0, 0.0)),
            wallObs(v(1.5, 0.02), v(3.5, 0.02)),
            wallObs(v(4.0, 0.6), v(4.0, 2.4)),
            wallObs(v(3.5, 3.0), v(0.5, 3.0)),
            wallObs(v(0.0, 2.5), v(0.0, 0.5)),
        )
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertEquals(4, r.corners.size)
        // The heavier (longer) observation names the merged edge.
        assertEquals(listOf(1, 2, 3, 4), r.edgeSources)
        // Weighted face position: lengths 1.5 and 2.0 at y = 0 and 0.02.
        val y = 0.02 * 2.0 / 3.5
        assertPolygonEquals(listOf(v(0.0, y), v(4.0, y), v(4.0, 3.0), v(0.0, 3.0)), r.corners, 1e-9)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun duplicateAcrossTheWrapAroundIsMerged() {
        val obs = rectWalls + wallObs(v(1.0, 0.015), v(3.0, 0.015))
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertEquals(4, r.corners.size)
        assertEquals(setOf(1, 2, 3), r.edgeSources.filter { it in 1..3 }.toSet())
        assertTrue(r.edgeSources.single { it !in 1..3 } in setOf(0, 4))
    }

    @Test fun skippedWallBetweenFacingWallsGetsConnectorAndWarning() {
        val obs = rectWalls.take(3) // bottom, right, top: the left wall was never captured
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        // Connector at the far end of the observed extents (x = 0.8) so the room is not cut short.
        assertPolygonEquals(listOf(v(0.8, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(0.8, 3.0)), r.corners)
        assertEquals(listOf(0, 1, 2, -1), r.edgeSources)
        assertEquals(1, r.warnings.size, "${r.warnings}")
        assertTrue(r.warnings[0].contains("walls 3 and 1"), r.warnings[0])
        assertTrue(r.warnings.none { it == RoomSolver.REORDERED_WARNING })
    }

    @Test fun skippedWallInClockwiseCaptureGoesToTheCorrectEnd() {
        val obs = listOf(rectWalls[0], rectWalls[3], rectWalls[2]) // bottom, left, top (clockwise), right skipped
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(listOf(v(0.0, 0.0), v(3.2, 0.0), v(3.2, 3.0), v(0.0, 3.0)), r.corners)
        assertEquals(listOf(0, -1, 2, 1), r.edgeSources)
        assertTrue(r.warnings.single().contains("walls 3 and 1"), r.warnings.toString())
    }

    @Test fun badlyOrderedRectangleIsResortedWithWarning() {
        val obs = listOf(rectWalls[0], rectWalls[2], rectWalls[1], rectWalls[3]) // bottom, top, right, left
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(Shapes.rect, r.corners)
        assertEquals(listOf(0, 2, 1, 3), r.edgeSources)
        assertEquals(listOf(RoomSolver.REORDERED_WARNING), r.warnings)
    }

    @Test fun selfIntersectingCaptureOrderIsResorted() {
        // Regular hexagon (circumradius 3), walls captured out of order.
        val hex = (0 until 6).map { k -> Vec2.fromAngle(PI / 3 * k - PI / 2, 3.0) + v(5.0, 5.0) }
        val walls = wallsOf(hex)
        val obs = listOf(0, 2, 1, 3, 5, 4).map { walls[it] }
        val r = assertNotNull(RoomSolver.solveWalls(obs, SolverOptions(squareAngles = false)))
        assertTrue(RoomSolver.REORDERED_WARNING in r.warnings, r.warnings.toString())
        assertEquals(6, r.corners.size)
        assertEquals(Polygons.area(hex), Polygons.area(r.corners), 1e-9)
        assertTrue(Polygons.isSimple(r.corners) && Polygons.isCounterClockwise(r.corners))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), r.edgeSources.toSet())
    }

    @Test fun mergeVersusJogThreshold() {
        fun bottomPair(offset: Double) = listOf(
            wallObs(v(0.3, 0.0), v(1.8, 0.0)),
            wallObs(v(2.2, offset), v(3.7, offset)),
        ) + rectWalls.drop(1)
        val cases = listOf(0.0 to 4, 0.05 to 4, 0.09 to 4, 0.11 to 6, 0.3 to 6, -0.2 to 6)
        for ((offset, expectedCorners) in cases) {
            val r = assertNotNull(RoomSolver.solveWalls(bottomPair(offset)), "offset $offset")
            assertEquals(expectedCorners, r.corners.size, "offset $offset: ${r.corners}")
            assertAllRightAngles(r.corners)
        }
        // A custom threshold turns the 0.3 m step into a merge.
        val merged = assertNotNull(RoomSolver.solveWalls(bottomPair(0.3), SolverOptions(sameWallOffset = 0.5)))
        assertEquals(4, merged.corners.size)
    }

    @Test fun lAndUShapesWithFarCornersKeepCaptureOrder() {
        val u = listOf(
            v(0.0, 0.0), v(6.0, 0.0), v(6.0, 4.0), v(4.0, 4.0), v(4.0, 1.0), v(2.0, 1.0), v(2.0, 4.0), v(0.0, 4.0),
        )
        for (shape in listOf(Shapes.lShape, u)) {
            val obs = wallsOf(shape, viewerDistance = 0.5).toMutableList()
            // Wall 1 seen only near its start, wall 2 only near its end: corner 2 is far from both.
            obs[0] = wallObs(shape[0] + v(0.2, 0.0), shape[0] + v(1.0, 0.0), viewerDistance = 0.5)
            obs[1] = wallObs(shape[2] - v(0.0, 1.0), shape[2] - v(0.0, 0.2), viewerDistance = 0.5)
            val r = assertNotNull(RoomSolver.solveWalls(obs), "shape $shape")
            assertPolygonEquals(shape, r.corners)
            assertEquals(shape.indices.toList(), r.edgeSources)
            assertTrue(RoomSolver.REORDERED_WARNING !in r.warnings, r.warnings.toString())
            assertTrue(r.warnings.single().startsWith("Corner 2 is "), r.warnings.toString())
        }
    }

    @Test fun collinearWallsOnlyGiveNull() {
        val obs = listOf(
            wallObs(v(0.0, 0.0), v(1.0, 0.0)),
            wallObs(v(1.05, 0.01), v(2.0, 0.01)),
            wallObs(v(2.1, 0.0), v(3.0, 0.0)),
        )
        assertNull(RoomSolver.solveWalls(obs))
    }

    // ---------------------------------------------------------------- warnings

    @Test fun farCornerIsReported() {
        // 8 x 3 room; bottom observed only near its left end, right wall only near the top.
        val obs = listOf(
            wallObs(v(0.5, 0.0), v(2.0, 0.0)),
            wallObs(v(8.0, 2.5), v(8.0, 2.9)),
            wallObs(v(7.5, 3.0), v(0.5, 3.0)),
            wallObs(v(0.0, 2.5), v(0.0, 0.5)),
        )
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(listOf(v(0.0, 0.0), v(8.0, 0.0), v(8.0, 3.0), v(0.0, 3.0)), r.corners)
        val w = r.warnings.single()
        assertTrue(w.startsWith("Corner 2 is 2.50 m"), w)
        assertTrue(w.endsWith("check walls 1 and 2"), w)
    }

    @Test fun tinyRoomIsReported() {
        val tiny = listOf(v(0.0, 0.0), v(0.6, 0.0), v(0.6, 0.6), v(0.0, 0.6))
        val r = assertNotNull(RoomSolver.solveWalls(wallsOf(tiny, viewerDistance = 0.3)))
        assertTrue(r.warnings.any { it.startsWith("The room is very small (0.4 m²") }, r.warnings.toString())
    }

    @Test fun sharpSpikeIsReported() {
        val wedge = listOf(v(0.0, 0.0), v(5.0, 0.0), v(0.0, 1.5))
        val r = assertNotNull(RoomSolver.solveWalls(wallsOf(wedge, viewerDistance = 0.2), SolverOptions(squareAngles = false)))
        assertPolygonEquals(wedge, r.corners, 1e-9)
        assertTrue(r.warnings.any { it.startsWith("Corner 2 forms a very sharp spike; check walls 1 and 2") }, r.warnings.toString())
    }

    // ---------------------------------------------------------------- reversal mapping

    @Test fun reverseOutlineKeepsEdgeDataOnTheSameGeometricEdge() {
        val cw = listOf(v(0.0, 0.0), v(0.0, 2.0), v(1.0, 3.0), v(3.0, 2.0), v(2.0, 0.0))
        val data = listOf("a", "b", "c", "d", "e")
        val (rc, rd) = RoomSolver.reverseOutline(cw, data)
        val n = cw.size
        assertTrue(Polygons.isCounterClockwise(rc))
        assertEquals(cw[1], rc[0])
        assertEquals("a", rd[0])
        for (k in 0 until n) {
            // New edge k is the old edge carrying the same data, traversed backwards.
            val old = data.indexOf(rd[k])
            assertEquals(cw[old], rc[(k + 1) % n], "edge $k end")
            assertEquals(cw[(old + 1) % n], rc[k], "edge $k start")
            assertEquals((n - k) % n, old)
        }
        val (back, backData) = RoomSolver.reverseOutline(rc, rd)
        assertEquals(cw, back)
        assertEquals(data, backData)
    }

    // ---------------------------------------------------------------- dominant angle

    @Test fun dominantAngleTable() {
        data class Case(val degs: List<Double>, val expectedDeg: Double, val weights: List<Double>? = null)
        val cases = listOf(
            Case(listOf(30.0, 120.0), 30.0),
            Case(listOf(30.0, -60.0, 210.0), 30.0),
            Case(listOf(0.0, 90.0, 180.0, -90.0), 0.0),
            Case(listOf(1.0, 89.0), 0.0),
            Case(listOf(-1.0, 1.0), 0.0),
            Case(listOf(89.0, 91.0), 0.0),
            Case(listOf(179.0, -179.0), 0.0),
            Case(listOf(44.0, 46.0), 45.0),
            Case(listOf(10.0), 10.0),
            Case(listOf(-10.0), 80.0),
            Case(listOf(100.0), 10.0),
            Case(listOf(2.0, 92.0, 182.0, -88.0), 2.0),
            Case(listOf(0.0, 10.0), 0.0, listOf(1.0, 0.0)),
        )
        for (c in cases) {
            val r = RoomSolver.dominantAngle(c.degs.map(Math::toRadians), c.weights)
            assertTrue(r >= 0.0 && r < PI / 2, "range for ${c.degs}: $r")
            assertTrue(angleDistance(r, Math.toRadians(c.expectedDeg)) < 1e-9, "${c.degs} -> ${Math.toDegrees(r)}")
        }
    }

    @Test fun dominantAngleWeightsPullTowardsHeavierWalls() {
        val r = Math.toDegrees(RoomSolver.dominantAngle(listOf(0.0, Math.toRadians(20.0)), listOf(3.0, 1.0)))
        assertTrue(r > 0.0 && r < 10.0, "$r")
        val expected = Math.toDegrees(kotlin.math.atan2(sin(Math.toRadians(80.0)), 3 + cos(Math.toRadians(80.0))) / 4)
        assertEquals(expected, r, 1e-9)
    }

    @Test fun dominantAngleDegenerateInput() {
        assertEquals(0.0, RoomSolver.dominantAngle(emptyList()))
        assertEquals(0.0, RoomSolver.dominantAngle(listOf(Double.NaN)))
        assertEquals(0.0, RoomSolver.dominantAngle(listOf(0.0, Math.toRadians(45.0)))) // cancel out
        assertEquals(Math.toRadians(30.0), RoomSolver.dominantAngle(listOf(Math.toRadians(30.0)), listOf()), 1e-12)
    }

    // ---------------------------------------------------------------- corner taps

    @Test fun cornerTapsWithNoiseAreSquared() {
        val taps = listOf(v(0.004, -0.006), v(4.007, 0.003), v(3.995, 3.008), v(-0.005, 2.996))
        val r = assertNotNull(RoomSolver.solveCorners(taps))
        assertEquals(4, r.corners.size)
        assertAllRightAngles(r.corners)
        val box = Polygons.orientedBoundingBox(r.corners)
        assertEquals(4.0, box.length, 0.02)
        assertEquals(3.0, box.width, 0.02)
        assertEquals(listOf(0, 1, 2, 3), r.edgeSources)
        assertTrue(Polygons.isCounterClockwise(r.corners))
    }

    @Test fun cornerTapsClockwiseAreReversedWithMappedSources() {
        val taps = listOf(v(0.0, 0.0), v(0.0, 3.0), v(4.0, 3.0), v(4.0, 0.0))
        val r = assertNotNull(RoomSolver.solveCorners(taps))
        assertPolygonEquals(listOf(v(0.0, 3.0), v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0)), r.corners)
        assertEquals(listOf(0, 3, 2, 1), r.edgeSources)
    }

    @Test fun duplicateAndClosingTapsAreDropped() {
        val taps = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.02, 0.01), v(4.0, 3.0), v(0.0, 3.0), v(0.01, 0.01))
        val r = assertNotNull(RoomSolver.solveCorners(taps, SolverOptions(squareAngles = false)))
        assertPolygonEquals(Shapes.rect, r.corners)
        assertEquals(listOf(0, 2, 3, 4), r.edgeSources)
    }

    @Test fun collinearTapIsDropped() {
        val taps = listOf(v(0.0, 0.0), v(2.0, 0.005), v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0))
        val r = assertNotNull(RoomSolver.solveCorners(taps, SolverOptions(squareAngles = false)))
        assertPolygonEquals(Shapes.rect, r.corners)
        assertEquals(listOf(0, 2, 3, 4), r.edgeSources)
    }

    @Test fun slightBendIsMergedAfterSquaring() {
        val taps = listOf(v(0.0, 0.0), v(2.0, 0.06), v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0))
        val unsquared = assertNotNull(RoomSolver.solveCorners(taps, SolverOptions(squareAngles = false)))
        assertEquals(5, unsquared.corners.size)
        val r = assertNotNull(RoomSolver.solveCorners(taps))
        assertPolygonEquals(Shapes.rect, r.corners, 1e-9)
        assertEquals(4, r.edgeSources.size)
        assertEquals(listOf(2, 3, 4), r.edgeSources.drop(1))
        assertTrue(r.edgeSources[0] in setOf(0, 1))
    }

    @Test fun lShapedCornerTaps() {
        val taps = Shapes.lShape.mapIndexed { i, p -> p + v(0.01 * (i % 2), -0.008 * ((i + 1) % 2)) }
        val r = assertNotNull(RoomSolver.solveCorners(taps))
        assertEquals(6, r.corners.size)
        assertAllRightAngles(r.corners)
        assertEquals(24.0, Polygons.area(r.corners), 0.15)
        assertEquals((0..5).toList(), r.edgeSources)
    }

    @Test fun invalidCornerTapsGiveNull() {
        assertNull(RoomSolver.solveCorners(emptyList()))
        assertNull(RoomSolver.solveCorners(listOf(v(0.0, 0.0), v(1.0, 0.0))))
        assertNull(RoomSolver.solveCorners(listOf(v(0.0, 0.0), v(1.0, 0.0), v(1.01, 0.01))))
        assertNull(RoomSolver.solveCorners(listOf(v(0.0, 0.0), v(1.0, 0.0), v(2.0, 0.0), v(3.0, 0.0))))
        // Bow-tie: the outline crosses itself.
        assertNull(RoomSolver.solveCorners(listOf(v(0.0, 0.0), v(3.0, 3.0), v(3.0, 0.0), v(0.0, 3.0)), SolverOptions(squareAngles = false)))
        // Non-finite taps are ignored.
        val r = assertNotNull(RoomSolver.solveCorners(Shapes.rect + v(Double.NaN, 1.0)))
        assertEquals(4, r.corners.size)
    }

    @Test fun cornerSpikeWarning() {
        val taps = listOf(v(0.0, 0.0), v(3.0, 0.0), v(0.0, 0.5))
        val r = assertNotNull(RoomSolver.solveCorners(taps, SolverOptions(squareAngles = false)))
        assertTrue(r.warnings.any { it.startsWith("Corner 2 forms a very sharp spike") }, r.warnings.toString())
    }
}
