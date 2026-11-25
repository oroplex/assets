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
        // The step's position along the wall is a guess (halfway between the extents): say so, 1-based.
        assertEquals(
            listOf("A step was assumed between walls 1 and 2; capture the short wall there for an exact position"),
            r.warnings,
        )
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
            // Extrapolating a squared wall to a far corner is normal, not a warning.
            assertEquals(emptyList(), r.warnings)
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

    @Test fun farCornersOfCentreAimedCapturesAreNotWarnings() {
        // Review round 1 (Core math AR-3): every corner of these rooms used to get "Corner k is 1.6 m beyond
        // what was measured" although the scan is perfect: a capture only sees ~0.4 m of wall around the aim.
        val rooms = listOf(
            listOf(v(0.0, 0.0), v(3.6, 0.0), v(3.6, 3.6), v(0.0, 3.6)),
            listOf(v(0.0, 0.0), v(4.5, 0.0), v(4.5, 3.5), v(0.0, 3.5)),
        )
        for (room in rooms) {
            val r = assertNotNull(RoomSolver.solveWalls(patchWallsOf(room, 0.4, viewerDistance = 1.5)))
            assertPolygonEquals(room, r.corners)
            assertEquals(emptyList(), r.warnings, "room $room")
        }
        // 8 x 3 room; bottom observed only near its left end, right wall only near the top.
        val obs = listOf(
            wallObs(v(0.5, 0.0), v(2.0, 0.0)),
            wallObs(v(8.0, 2.5), v(8.0, 2.9)),
            wallObs(v(7.5, 3.0), v(0.5, 3.0)),
            wallObs(v(0.0, 2.5), v(0.0, 0.5)),
        )
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(listOf(v(0.0, 0.0), v(8.0, 0.0), v(8.0, 3.0), v(0.0, 3.0)), r.corners)
        assertEquals(emptyList(), r.warnings)
    }

    @Test fun shallowCornerIsReported() {
        // Walls 1 and 2 bend by only 15 degrees: their intersection is ill-conditioned.
        val corners = listOf(v(0.0, 0.0), v(3.0, 0.0), v(6.0, 3.0 * kotlin.math.tan(Math.toRadians(15.0))), v(6.0, 4.0), v(0.0, 4.0))
        val r = assertNotNull(RoomSolver.solveWalls(wallsOf(corners)))
        assertPolygonEquals(corners, r.corners, 1e-9)
        assertEquals(listOf("Walls 1 and 2 meet at a shallow angle; the corner position is uncertain"), r.warnings)
        // A 45-degree chamfer is an ordinary corner.
        val chamfer = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 2.0), v(3.0, 3.0), v(0.0, 3.0))
        assertEquals(emptyList(), assertNotNull(RoomSolver.solveWalls(wallsOf(chamfer))).warnings)
    }

    // ---------------------------------------------------------------- robust squaring axis

    @Test fun angledWallDoesNotPullTheSquaringAxis() {
        // Review round 1 (Core math AR-1): a 30-degree wall used to rotate the axis by -4.8 degrees and with it
        // all square walls (corners off by up to 0.4 m). Exact data, 0.4 m centred extents.
        val quad = listOf(v(0.0, 0.0), v(6.0, 0.0), v(6.0, 3.0), v(0.0, 6.0 * kotlin.math.tan(Math.toRadians(30.0)) + 3.0))
        val r = assertNotNull(RoomSolver.solveWalls(patchWallsOf(quad)))
        assertPolygonEquals(quad, r.corners, 1e-9)
        assertEquals(0.0, r.dominantAngle, 1e-12)
        // Corner taps of the same room.
        val c = assertNotNull(RoomSolver.solveCorners(quad))
        assertPolygonEquals(quad, c.corners, 1e-9)
        assertEquals(0.0, c.dominantAngle, 1e-12)
    }

    @Test fun heavyAngledPlaneCannotTakeTheAxisOver() {
        // A 20-degree wall seen as a 3 m ARCore plane (weight ~8x each 0.4 m depth fit) used to set the axis to
        // 74.5 degrees: the square walls stayed unsquared and the angled wall was snapped by 4.5 degrees.
        val quad = listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 3.0), v(0.0, 3.0 + 5.0 * kotlin.math.tan(Math.toRadians(20.0))))
        val obs = patchWallsOf(quad).toMutableList()
        val (a, b) = quad[2] to quad[3]
        obs[2] = wallObs(Vec2.lerp(a, b, 0.1), Vec2.lerp(a, b, 0.7)).copy(source = com.oroplex.floorplan.core.model.WallSource.DETECTED_PLANE, confidence = 0.8)
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(quad, r.corners, 1e-9)
        assertEquals(0.0, r.dominantAngle, 1e-12)
    }

    @Test fun eightDegreeWallLeavesTheSquareWallsAlone() {
        val quad = listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 3.0), v(0.0, 3.0 + 5.0 * kotlin.math.tan(Math.toRadians(8.0))))
        val r = assertNotNull(RoomSolver.solveWalls(patchWallsOf(quad)))
        assertPolygonEquals(quad, r.corners, 1e-9)
        assertEquals(0.0, r.dominantAngle, 1e-12)
        val c = assertNotNull(RoomSolver.solveCorners(quad))
        assertPolygonEquals(quad, c.corners, 1e-9)
    }

    @Test fun robustAxisTable() {
        fun axisDeg(degs: List<Double>, support: List<Double>? = null, precision: List<Double>? = null): Double {
            val s = support ?: degs.map { 1.0 }
            val p = precision ?: degs.map { 1.0 }
            return Math.toDegrees(RoomSolver.robustAxis(degs.map(Math::toRadians), s, p, Math.toRadians(5.0)))
        }
        // Square walls win over an angled one, whatever its angle; the result is in [0, 90).
        assertEquals(0.0, axisDeg(listOf(0.0, 90.0, 180.0, 150.0)), 1e-9)
        assertEquals(0.0, axisDeg(listOf(0.0, 90.0, 180.0, 270.0, 45.0, 135.0)), 1e-9)
        assertEquals(10.0, axisDeg(listOf(10.0, 100.0, 190.0, 45.0)), 1e-9)
        // Within the supporting walls, precision decides: the precise wall dominates the refined axis.
        val refined = axisDeg(listOf(1.0, 91.0, -1.0, 89.0), precision = listOf(100.0, 100.0, 1.0, 1.0))
        assertTrue(refined > 0.9 && refined < 1.0, "$refined")
        // Bounded support: one heavily supported angled wall loses against three square ones.
        assertEquals(0.0, axisDeg(listOf(0.0, 90.0, 180.0, 20.0), support = listOf(1.0, 1.0, 1.0, 2.5)), 1e-9)
        // Degenerate input.
        assertEquals(0.0, RoomSolver.robustAxis(emptyList(), emptyList(), emptyList(), 0.1))
        assertEquals(0.0, RoomSolver.robustAxis(listOf(Double.NaN), listOf(1.0), listOf(1.0), 0.1))
        assertEquals(Math.toRadians(30.0), RoomSolver.robustAxis(listOf(Math.toRadians(30.0), Double.NaN), listOf(1.0, 1.0), listOf(1.0, -1.0), 0.1), 1e-12)
    }

    @Test fun axisHintIsUsedWhenTheRoomAgrees() {
        // Walls of a slightly rotated room (+0.6 degrees) and the session's axis 0.9 degrees, given as 90.9.
        val t = RigidTransform2(Math.toRadians(0.6), Vec2.ZERO)
        val obs = rectWalls.map { o -> o.copy(line = t.apply(o.line), extentStart = t.apply(o.extentStart), extentEnd = t.apply(o.extentEnd), viewer = t.apply(o.viewer)) }
        val hint = Math.toRadians(90.9)
        val r = assertNotNull(RoomSolver.solveWalls(obs, SolverOptions(axisHint = hint)))
        assertEquals(Math.toRadians(0.9), r.dominantAngle, 1e-12)
        for (i in r.corners.indices) {
            val d = r.corners[(i + 1) % r.corners.size] - r.corners[i]
            assertTrue(angleDistance(d.angle(), Math.toRadians(0.9)) < 1e-9, "edge $i is on the hinted axis")
        }
        // Corner taps use the hint the same way.
        val c = assertNotNull(RoomSolver.solveCorners(Shapes.rect.map { t.apply(it) }, SolverOptions(axisHint = hint)))
        assertEquals(Math.toRadians(0.9), c.dominantAngle, 1e-12)
        // A hint the room does not agree with is ignored.
        val other = assertNotNull(RoomSolver.solveWalls(obs, SolverOptions(axisHint = Math.toRadians(20.0))))
        assertEquals(Math.toRadians(0.6), other.dominantAngle, 1e-9)
        assertNotNull(RoomSolver.solveWalls(obs, SolverOptions(axisHint = Double.NaN)))
    }

    /** A perfect 4 x 3.5 m room captured with exact data, aiming at 25 % / 75 % along alternate walls. */
    private val offCentreRect = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.5), v(0.0, 3.5))
    private val offCentreWalls = offCentreRect.indices.map { i ->
        patchObs(offCentreRect[i], offCentreRect[(i + 1) % 4], length = 0.4, at = if (i % 2 == 0) 0.25 else 0.75)
    }

    private fun edgeLengths(corners: List<Vec2>) = corners.indices.map { corners[it].distanceTo(corners[(it + 1) % corners.size]) }

    @Test fun axisHintNeverChangesTheRoomsDimensions() {
        // Review round 2 (MATH-2): the hint replaced the room's axis whenever it was within 5 degrees, and each
        // wall was snapped onto it about its own pivot: a 1.6 degree hint turned this room into 3.949 x 3.555 m.
        val plain = assertNotNull(RoomSolver.solveWalls(offCentreWalls))
        assertPolygonEquals(offCentreRect, plain.corners)
        for (deg in listOf(0.6, 1.0, 1.6, 3.0, -1.6, 4.9)) {
            val r = assertNotNull(RoomSolver.solveWalls(offCentreWalls, SolverOptions(axisHint = Math.toRadians(deg))), "hint $deg")
            // Exact data: the room's own axis is known far better than 0.5 degrees, so these hints are ignored.
            assertPolygonEquals(offCentreRect, r.corners)
            assertEquals(0.0, r.dominantAngle, 1e-12, "hint $deg")
            assertEquals(emptyList(), r.warnings)
        }
        // A hint the room agrees with (0.3 degrees) turns the finished room rigidly: same lengths, right angles.
        for (deg in listOf(0.3, -0.3, 90.3, 0.5)) {
            val hint = Math.toRadians(deg)
            val r = assertNotNull(RoomSolver.solveWalls(offCentreWalls, SolverOptions(axisHint = hint)), "hint $deg")
            val turn = angleDistance(hint, 0.0).let { if (deg < 0) -it else it }
            assertEquals((hint % (PI / 2) + PI / 2) % (PI / 2), r.dominantAngle, 1e-12, "hint $deg")
            edgeLengths(r.corners).zip(edgeLengths(offCentreRect)).forEach { (a, b) -> assertEquals(b, a, 1e-9, "hint $deg changed a length") }
            assertAllRightAngles(r.corners)
            for (i in r.corners.indices) {
                val d = r.corners[(i + 1) % r.corners.size] - r.corners[i]
                assertTrue(angleDistance(d.angle(), hint) < 1e-9, "hint $deg: edge $i is not on the hinted axis")
            }
            // Turned about where the room was measured (the walls' mean pivot), not about the origin.
            val pivot = offCentreWalls.map { Vec2.lerp(it.extentStart, it.extentEnd, 0.5) }.fold(Vec2.ZERO) { a, m -> a + m } / 4.0
            val expected = offCentreRect.map { pivot + (it - pivot).rotated(turn) }
            assertSameOutline(expected, r.corners, 1e-9)
        }
        // Without squaring there is no axis to share: the hint does nothing.
        val unsquared = assertNotNull(RoomSolver.solveWalls(offCentreWalls, SolverOptions(squareAngles = false, axisHint = Math.toRadians(0.3))))
        assertPolygonEquals(offCentreRect, unsquared.corners)
        assertEquals(0.0, unsquared.dominantAngle, 1e-12)
    }

    @Test fun axisHintToleranceFollowsTheRoomsOwnUncertainty() {
        // Short, noisy captures leave the room's axis open by about a degree: a hint 1.5 degrees away may then
        // settle it. The room is still only turned, never resized.
        val vague = offCentreWalls.map { it.copy(rmsResidual = 0.03, pointCount = 50) }
        val plain = assertNotNull(RoomSolver.solveWalls(vague))
        val hinted = assertNotNull(RoomSolver.solveWalls(vague, SolverOptions(axisHint = Math.toRadians(1.5))))
        assertEquals(Math.toRadians(1.5), hinted.dominantAngle, 1e-12)
        edgeLengths(hinted.corners).zip(edgeLengths(plain.corners)).forEach { (a, b) -> assertEquals(b, a, 1e-9) }
        assertAllRightAngles(hinted.corners)
        // Beyond the squaring tolerance no hint is ever taken, however vague the room.
        val far = assertNotNull(RoomSolver.solveWalls(vague, SolverOptions(axisHint = Math.toRadians(6.0))))
        assertPolygonEquals(plain.corners, far.corners)
        // Precise captures of the same room refuse the same 1.5 degree hint.
        val precise = assertNotNull(RoomSolver.solveWalls(offCentreWalls, SolverOptions(axisHint = Math.toRadians(1.5))))
        assertEquals(0.0, precise.dominantAngle, 1e-12)
    }

    @Test fun axisHintTurnsCornerTapsRigidly() {
        val plain = assertNotNull(RoomSolver.solveCorners(Shapes.lShape))
        val hint = Math.toRadians(0.4)
        val hinted = assertNotNull(RoomSolver.solveCorners(Shapes.lShape, SolverOptions(axisHint = hint)))
        assertEquals(hint, hinted.dominantAngle, 1e-12)
        assertEquals(plain.edgeSources, hinted.edgeSources)
        edgeLengths(hinted.corners).zip(edgeLengths(plain.corners)).forEach { (a, b) -> assertEquals(b, a, 1e-9) }
        val center = plain.corners.fold(Vec2.ZERO) { a, c -> a + c } / plain.corners.size.toDouble()
        assertPolygonEquals(plain.corners.map { center + (it - center).rotated(hint) }, hinted.corners, 1e-9)
        // Taps 5 cm apart in direction leave the axis open by about 0.2 degrees per 4 m edge: a 3 degree hint is refused.
        val refused = assertNotNull(RoomSolver.solveCorners(Shapes.lShape, SolverOptions(axisHint = Math.toRadians(3.0))))
        assertPolygonEquals(plain.corners, refused.corners)
        assertEquals(0.0, refused.dominantAngle, 1e-12)
    }

    // ---------------------------------------------------------------- shared axis of a session

    /** 4 x 5 m room with a 1.5 m wall at 30 degrees across one corner (review round 2, SEAM-2). */
    private val chamfered: List<Vec2> = run {
        val cut = Vec2.fromAngle(Math.toRadians(150.0), 1.5)
        listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 5.0 + cut.y), v(4.0 + cut.x, 5.0), v(0.0, 5.0))
    }

    @Test fun sharedAxisIsNotPulledByAnAngledWall() {
        for (deg in listOf(0.0, 17.0, 63.0, 89.5)) {
            val t = RigidTransform2(Math.toRadians(deg), Vec2(2.0, -1.0))
            val outline = chamfered.map { t.apply(it) }
            val shared = assertNotNull(RoomSolver.sharedAxis(listOf(outline)))
            assertTrue(angleDistance(shared, Math.toRadians(deg)) < 1e-9, "$deg: shared axis ${Math.toDegrees(shared)}")
            // The plain mean, which the capture flow used as a hint, is off by more than a degree.
            val plain = RoomSolver.dominantAngle(outline.indices.map { (outline[(it + 1) % outline.size] - outline[it]).angle() }, outline.indices.map { outline[it].distanceTo(outline[(it + 1) % outline.size]) })
            assertTrue(angleDistance(plain, Math.toRadians(deg)) > Math.toRadians(1.0), "$deg: plain mean ${Math.toDegrees(plain)}")
            // The next room of the session, squared with that hint, comes out exactly parallel to this one.
            val next = wallsOf(Shapes.rect).map { o ->
                o.copy(line = t.apply(o.line), extentStart = t.apply(o.extentStart), extentEnd = t.apply(o.extentEnd), viewer = t.apply(o.viewer))
            }
            val solved = assertNotNull(RoomSolver.solveWalls(next, SolverOptions(axisHint = shared)))
            assertEquals(shared, solved.dominantAngle, 1e-9)
            edgeLengths(solved.corners).zip(listOf(4.0, 3.0, 4.0, 3.0)).forEach { (a, b) -> assertEquals(b, a, 1e-9) }
        }
    }

    @Test fun sharedAxisIsTheConsensusOfTheRooms() {
        val turned = { deg: Double, shift: Vec2 -> Shapes.rect.map { RigidTransform2(Math.toRadians(deg), shift).apply(it) } }
        // Three rooms of one session and one stray room at 37 degrees: the session's axis, exactly.
        val session = listOf(turned(10.0, v(0.0, 0.0)), turned(10.0, v(6.0, 0.0)), turned(100.0, v(0.0, 6.0)), turned(47.0, v(9.0, 9.0)))
        assertEquals(Math.toRadians(10.0), assertNotNull(RoomSolver.sharedAxis(session)), 1e-9)
        // Slightly different headings (drift) average, weighted by length, among the supporters only.
        val drift = listOf(turned(0.0, v(0.0, 0.0)), turned(1.0, v(6.0, 0.0)), turned(30.0, v(12.0, 0.0)))
        assertEquals(Math.toRadians(0.5), assertNotNull(RoomSolver.sharedAxis(drift)), 1e-9)
        // One 40 m wall does not outvote two rooms: its vote is capped (3 m), theirs add up.
        val longWall = listOf(v(0.0, 20.0), v(40.0, 20.0)).map { RigidTransform2(Math.toRadians(20.0), Vec2.ZERO).apply(it) }
        val withLongWall = RoomSolver.sharedAxis(listOf(Shapes.rect, turned(0.0, v(6.0, 0.0)), longWall))
        // ... but then the supporting length is less than half of the total: no coherent axis.
        assertNull(withLongWall)
        // No coherent axis: a regular pentagon, two rooms 45 degrees apart with equal walls, nothing usable.
        val pentagon = (0 until 5).map { Vec2.fromAngle(2 * PI * it / 5, 2.0) }
        assertNull(RoomSolver.sharedAxis(listOf(pentagon)))
        assertNull(RoomSolver.sharedAxis(emptyList()))
        assertNull(RoomSolver.sharedAxis(listOf(emptyList(), listOf(v(1.0, 1.0)), listOf(v(1.0, 1.0), v(1.0, 1.0)))))
        assertNull(RoomSolver.sharedAxis(listOf(listOf(v(Double.NaN, 0.0), v(1.0, 0.0), v(1.0, Double.POSITIVE_INFINITY)))))
        // Unusable corners are skipped, the rest still counts; the result is always in [0, 90 degrees).
        val damaged = Shapes.rect + v(Double.NaN, 1.0)
        assertEquals(0.0, assertNotNull(RoomSolver.sharedAxis(listOf(damaged))), 1e-12)
        val quarter = assertNotNull(RoomSolver.sharedAxis(listOf(turned(-0.2, Vec2.ZERO))))
        assertTrue(quarter >= 0.0 && quarter < PI / 2, "$quarter")
        assertEquals(Math.toRadians(89.8), quarter, 1e-9)
    }

    // ---------------------------------------------------------------- capture order and duplicates

    /** 5 x 4 m room with a 0.4 m closet return (wall 4) between two parallel walls (walls 3 and 5). */
    private val closetRoom = listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 4.0), v(1.4, 4.0), v(1.4, 3.6), v(0.0, 3.6))

    @Test fun stepBetweenParallelWallsIsNotMistakenForFurniture() {
        // Review round 2 (MATH-1): wall 3's observed extent, stretched past the return by range outliers,
        // overlapped wall 5 "40 cm apart", and the correctly measured wall 5 was deleted: a 5 x 4 m rectangle.
        val walls = wallsOf(closetRoom).toMutableList()
        walls[2] = wallObs(v(4.5, 4.0), v(0.5, 4.0))
        val r = assertNotNull(RoomSolver.solveWalls(walls))
        assertPolygonEquals(closetRoom, r.corners)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), r.edgeSources)
        assertEquals(listOf("Walls 3 and 5 overlap at the step along wall 4; check that corner"), r.warnings)
        // Any capture order, and the return measured as a short patch at either end, give the same room.
        for (order in listOf(listOf(0, 1, 2, 4, 3, 5), listOf(3, 0, 1, 2, 4, 5), listOf(5, 4, 3, 2, 1, 0))) {
            val again = assertNotNull(RoomSolver.solveWalls(order.map { walls[it] }), "order $order")
            assertSameOutline(closetRoom, again.corners)
        }
        // Robust extents that do not reach past the return need no warning at all.
        assertEquals(emptyList(), assertNotNull(RoomSolver.solveWalls(wallsOf(closetRoom))).warnings)
        // Without a captured step between them the rule still drops the capture that sits in front (furniture).
        val withoutReturn = walls.filterIndexed { i, _ -> i != 3 }
        val flat = assertNotNull(RoomSolver.solveWalls(withoutReturn))
        assertPolygonEquals(listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 4.0), v(0.0, 4.0)), flat.corners)
        assertTrue(flat.warnings.single().startsWith("Walls 3 and 4 are the same wall measured"), "${flat.warnings}")
        // A wall elsewhere in the room, or one parallel to the pair, is not a step between them.
        val sofa = wallObs(v(3.0, 3.5), v(2.0, 3.5))
        val furnished = assertNotNull(RoomSolver.solveWalls(wallsOf(Shapes.rect.map { it * 1.25 }) + sofa))
        assertEquals(4, furnished.corners.size)
        assertTrue(furnished.warnings.any { "same wall measured" in it }, "${furnished.warnings}")
    }

    @Test fun notchedRoomWithSwappedWallsIsRecovered() {
        // Review round 1 (Core math AR-4, 1): walls 2 and 3 swapped became two silent fake steps (20.5 m² vs 22).
        val notched = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 1.0), v(6.0, 1.0), v(6.0, 4.0), v(0.0, 4.0))
        val walls = patchWallsOf(notched)
        val obs = listOf(0, 2, 1, 3, 4, 5).map { walls[it] }
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(notched, r.corners, 1e-9)
        assertEquals(listOf(RoomSolver.REORDERED_WARNING), r.warnings)
        assertEquals(listOf(0, 2, 1, 3, 4, 5), r.edgeSources)
    }

    @Test fun recaptureOnFurnitureUsesTheOuterWall() {
        // Review round 1 (Core math AR-4, 2): wall 1 captured again on a sofa back 15 cm out made a fake step.
        val rect = Shapes.rect
        val sofa = wallObs(v(1.2, 0.15), v(2.6, 0.15))
        val obs = listOf(patchObs(rect[0], rect[1], 1.5), sofa) + patchWallsOf(rect).drop(1)
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertPolygonEquals(rect, r.corners, 1e-9)
        assertEquals(listOf(0, 2, 3, 4), r.edgeSources)
        assertEquals(
            listOf("Walls 1 and 2 are the same wall measured 15 cm (6\") apart; the outer one (wall 1) was used"),
            r.warnings,
        )
    }

    @Test fun uShapedRoomsCapturedInOrderKeepTheirOutline() {
        // Review round 1 (Core math AR-3 verifier): with centred 0.4 m extents the angular re-ordering used to
        // win with three fake steps (7.5 m² instead of 11).
        val rooms = listOf(
            listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.5), v(3.0, 3.5), v(3.0, 2.0), v(1.0, 2.0), v(1.0, 3.5), v(0.0, 3.5)),
            listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 4.0), v(3.5, 4.0), v(3.5, 2.5), v(1.5, 2.5), v(1.5, 4.0), v(0.0, 4.0)),
            listOf(v(0.0, 0.0), v(7.0, 0.0), v(7.0, 5.0), v(5.0, 5.0), v(5.0, 2.0), v(2.0, 2.0), v(2.0, 5.0), v(0.0, 5.0)),
        )
        for (room in rooms) {
            val r = assertNotNull(RoomSolver.solveWalls(patchWallsOf(room, 0.4, viewerDistance = 0.4)), "$room")
            assertPolygonEquals(room, r.corners, 1e-9)
            assertEquals(room.indices.toList(), r.edgeSources)
            assertEquals(emptyList(), r.warnings)
        }
    }

    @Test fun everyAdjacentSwapOfAUShapedRoomIsRecovered() {
        val room = listOf(v(0.0, 0.0), v(6.0, 0.0), v(6.0, 4.0), v(4.0, 4.0), v(4.0, 1.5), v(2.0, 1.5), v(2.0, 4.0), v(0.0, 4.0))
        val walls = patchWallsOf(room, 0.4, at = 0.35, viewerDistance = 0.4)
        for (k in room.indices) {
            val order = room.indices.toMutableList()
            val j = (k + 1) % room.size
            order[k] = j
            order[j] = k
            val r = assertNotNull(RoomSolver.solveWalls(order.map { walls[it] }), "swap $k/$j")
            assertSameOutline(room, r.corners, 1e-9)
            assertTrue(r.edgeSources.none { it < 0 }, "swap $k/$j: ${r.edgeSources}")
        }
    }

    @Test fun lShapedRoomOutOfOrderOrRecapturedLaterIsRecovered() {
        // Review round 1 (simulation SIM-2): orders [0,2,1,3,4,5] and [0,1,2,3,1,4,5] gave 8-corner outlines.
        val l = Shapes.lShape
        val walls = patchWallsOf(l, 0.5, at = 0.4, viewerDistance = 0.6)
        val swapped = assertNotNull(RoomSolver.solveWalls(listOf(0, 2, 1, 3, 4, 5).map { walls[it] }))
        assertPolygonEquals(l, swapped.corners, 1e-9)
        assertTrue(RoomSolver.REORDERED_WARNING in swapped.warnings, swapped.warnings.toString())
        // Wall 2 captured again after wall 4, elsewhere on the wall (extents do not overlap).
        val again = patchObs(l[1], l[2], 0.5, at = 0.85, viewerDistance = 0.6)
        val late = assertNotNull(RoomSolver.solveWalls(walls.take(4) + again + walls.drop(4)))
        assertPolygonEquals(l, late.corners, 1e-9)
        assertEquals(emptyList(), late.warnings, "a re-capture merged into its wall is not a re-order")
        assertEquals(6, late.edgeSources.size)
        assertTrue(late.edgeSources[1] in setOf(1, 4), "${late.edgeSources}")
        // Captured again at the very end (wrap-around), overlapping the first capture.
        val closing = assertNotNull(RoomSolver.solveWalls(walls + patchObs(l[0], l[1], 0.5, at = 0.45)))
        assertPolygonEquals(l, closing.corners, 1e-9)
        assertEquals(emptyList(), closing.warnings)
    }

    @Test fun sixteenWallRoomWithTwoSwapsIsRecoveredQuickly() {
        // A stepped 16-wall room; walls 3/4 and 10/11 (1-based) captured in swapped order.
        val room = listOf(
            v(0.0, 0.0), v(2.0, 0.0), v(2.0, 0.5), v(4.0, 0.5), v(4.0, 1.0), v(6.0, 1.0), v(6.0, 1.5), v(8.0, 1.5),
            v(8.0, 6.0), v(6.0, 6.0), v(6.0, 5.5), v(4.0, 5.5), v(4.0, 5.0), v(2.0, 5.0), v(2.0, 4.5), v(0.0, 4.5),
        )
        val walls = patchWallsOf(room, 0.3, viewerDistance = 0.25)
        val order = room.indices.toMutableList().also {
            it[2] = 3; it[3] = 2
            it[9] = 10; it[10] = 9
        }
        val obs = order.map { walls[it] }
        assertNotNull(RoomSolver.solveWalls(obs)) // warm-up (JIT)
        val t0 = System.nanoTime()
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        val ms = (System.nanoTime() - t0) / 1e6
        assertSameOutline(room, r.corners, 1e-9)
        assertTrue(RoomSolver.REORDERED_WARNING in r.warnings, r.warnings.toString())
        assertTrue(ms < 500, "solveWalls took $ms ms")
    }

    @Test fun overlappingDuplicateAnywhereInTheCaptureIsMerged() {
        // The bottom wall captured first and again third (non-consecutive), with overlapping extents.
        val w = rectWalls
        val obs = listOf(w[0], w[1], wallObs(v(1.0, 0.01), v(2.5, 0.01)), w[2], w[3])
        val r = assertNotNull(RoomSolver.solveWalls(obs))
        assertEquals(4, r.corners.size)
        assertEquals(emptyList(), r.warnings)
        assertTrue(r.edgeSources[0] in setOf(0, 2), "${r.edgeSources}")
        assertEquals(listOf(1, 3, 4), r.edgeSources.drop(1))
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
