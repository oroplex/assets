package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Room
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomOpsTest {
    private fun opening(id: String, wall: Int, offset: Double, width: Double, type: OpeningType = OpeningType.DOOR) =
        Opening(id = id, type = type, wallIndex = wall, offset = offset, width = width, height = 2.0)

    private val rect = Room(id = "r", name = "Bedroom", corners = Shapes.rect, ceilingHeight = 2.4)
    private val lRoom = Room(id = "l", name = "Living", corners = Shapes.lShape)

    private fun seg(room: Room, id: String) = RoomOps.openingSegment(room, room.openings.single { it.id == id })

    private fun assertOpeningValid(room: Room) {
        val n = room.corners.size
        for (o in room.openings) {
            assertTrue(o.wallIndex in 0 until n, "${o.id} wall index")
            val len = room.corners[o.wallIndex].distanceTo(room.corners[(o.wallIndex + 1) % n])
            assertTrue(o.offset >= -1e-12 && o.offset + o.width <= len + 1e-9, "${o.id} inside its edge: $o len=$len")
        }
    }

    // ---------------------------------------------------------------- openingSegment

    @Test fun openingSegmentRunsAlongTheEdge() {
        val room = rect.copy(openings = listOf(opening("top", 2, 0.5, 0.9), opening("right", 1, 1.0, 1.2)))
        val (s, e) = seg(room, "top")
        assertVecEquals(v(3.5, 3.0), s)
        assertVecEquals(v(2.6, 3.0), e)
        val (s2, e2) = seg(room, "right")
        assertVecEquals(v(4.0, 1.0), s2)
        assertVecEquals(v(4.0, 2.2), e2)
        // Bad data degrades to a zero-length segment instead of crashing.
        val bad = RoomOps.openingSegment(room, opening("x", 9, 0.0, 1.0))
        assertEquals(bad.first, bad.second)
        assertEquals(Vec2.ZERO to Vec2.ZERO, RoomOps.openingSegment(rect.copy(corners = emptyList()), opening("x", 0, 0.0, 1.0)))
    }

    // ---------------------------------------------------------------- setWallLength

    @Test fun setWallLengthStretchesRectangleAndKeepsItSquare() {
        val room = rect.copy(openings = listOf(opening("top", 2, 0.5, 0.9), opening("right", 1, 1.0, 1.0)))
        val before = seg(room, "top")
        val r = RoomOps.setWallLength(room, 0, 5.0)
        assertPolygonEquals(listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 3.0), v(0.0, 3.0)), r.corners)
        assertAllRightAngles(r.corners)
        // The door on the top wall keeps its place in the world (anchored at the unmoved corner).
        val after = seg(r, "top")
        assertVecEquals(before.first, after.first)
        assertVecEquals(before.second, after.second)
        // The window on the moved right wall moves with it.
        assertVecEquals(v(5.0, 1.0), seg(r, "right").first)
        assertOpeningValid(r)
    }

    @Test fun setWallLengthOnOtherEdgesMovesOnlyTheFarSide() {
        val table = listOf(
            Triple(1, 3.5, listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.5), v(0.0, 3.5))),
            Triple(2, 3.0, listOf(v(1.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(1.0, 3.0))),
            Triple(3, 2.0, listOf(v(0.0, 1.0), v(4.0, 1.0), v(4.0, 3.0), v(0.0, 3.0))),
        )
        for ((wall, len, expected) in table) {
            val r = RoomOps.setWallLength(rect, wall, len)
            assertPolygonEquals(expected, r.corners)
            assertEquals(len, r.corners[wall].distanceTo(r.corners[(wall + 1) % 4]), 1e-12)
        }
    }

    @Test fun setWallLengthStretchesLShape() {
        val longer = RoomOps.setWallLength(lRoom, 0, 7.0)
        assertPolygonEquals(
            listOf(v(0.0, 0.0), v(7.0, 0.0), v(7.0, 3.0), v(3.0, 3.0), v(3.0, 5.0), v(0.0, 5.0)),
            longer.corners,
        )
        val taller = RoomOps.setWallLength(lRoom, 3, 3.0)
        assertPolygonEquals(
            listOf(v(0.0, 0.0), v(6.0, 0.0), v(6.0, 3.0), v(3.0, 3.0), v(3.0, 6.0), v(0.0, 6.0)),
            taller.corners,
        )
        // Notch wall (6,3)->(3,3) heading -x: everything at or beyond its far end shifts by -1 in x.
        val notch = RoomOps.setWallLength(lRoom, 2, 4.0)
        assertPolygonEquals(
            listOf(v(-1.0, 0.0), v(6.0, 0.0), v(6.0, 3.0), v(2.0, 3.0), v(2.0, 5.0), v(-1.0, 5.0)),
            notch.corners,
        )
    }

    @Test fun setWallLengthRejectsInvalidInput() {
        for (len in listOf(0.05, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertSame(rect, RoomOps.setWallLength(rect, 0, len), "length $len")
        }
        assertSame(rect, RoomOps.setWallLength(rect, 4, 2.0))
        assertSame(rect, RoomOps.setWallLength(rect, -1, 2.0))
        assertNotSame(rect, RoomOps.setWallLength(rect, 0, 0.06))
    }

    @Test fun setWallLengthClampsOpeningsOnShortenedWall() {
        val room = rect.copy(openings = listOf(opening("d", 0, 2.5, 1.0)))
        val r = RoomOps.setWallLength(room, 0, 3.0)
        assertEquals(2.0, r.openings.single().offset, 1e-12)
        assertEquals(1.0, r.openings.single().width, 1e-12)
        val tiny = RoomOps.setWallLength(room, 0, 0.6)
        assertEquals(0.0, tiny.openings.single().offset, 1e-12)
        assertEquals(0.6, tiny.openings.single().width, 1e-12)
        assertOpeningValid(tiny)
    }

    // ---------------------------------------------------------------- moveCorner

    @Test fun moveCornerKeepsOpeningsAnchoredAndInside() {
        val room = rect.copy(openings = listOf(opening("right", 1, 1.0, 1.0), opening("top", 2, 0.5, 0.9), opening("bottom", 0, 1.0, 1.0)))
        val r = RoomOps.moveCorner(room, 2, v(4.5, 3.5))
        assertVecEquals(v(4.5, 3.5), r.corners[2])
        // Right wall: start corner (4,0) did not move, so the offset is kept.
        assertEquals(1.0, r.openings.single { it.id == "right" }.offset, 1e-12)
        // Top wall: its start moved; the far gap to the unmoved corner (0,3) is kept.
        val top = r.openings.single { it.id == "top" }
        val newLen = v(4.5, 3.5).distanceTo(v(0.0, 3.0))
        assertEquals(4.0 - 0.5 - 0.9, newLen - top.offset - top.width, 1e-12)
        // Unaffected edge.
        assertEquals(room.openings.single { it.id == "bottom" }, r.openings.single { it.id == "bottom" })
        assertOpeningValid(r)
    }

    @Test fun moveCornerClampsOpeningIntoShorterEdge() {
        val room = rect.copy(openings = listOf(opening("w", 1, 2.0, 0.9)))
        val r = RoomOps.moveCorner(room, 2, v(4.0, 1.5))
        val o = r.openings.single()
        assertEquals(0.6, o.offset, 1e-12)
        assertEquals(0.9, o.width, 1e-12)
        assertOpeningValid(r)
    }

    @Test fun moveCornerRejectsInvalidMoves() {
        assertSame(rect, RoomOps.moveCorner(rect, 7, v(1.0, 1.0)))
        assertSame(rect, RoomOps.moveCorner(rect, 0, v(Double.NaN, 1.0)))
        // Dragging corner 0 across the opposite wall would make the outline cross itself.
        assertSame(rect, RoomOps.moveCorner(rect, 0, v(5.0, 1.5)))
    }

    // ---------------------------------------------------------------- insert / delete corner

    @Test fun insertCornerSplitsEdgeAndReassignsOpenings() {
        val room = rect.copy(
            openings = listOf(
                opening("near", 0, 0.2, 0.6),
                opening("far", 0, 2.0, 0.8),
                opening("straddle", 0, 1.2, 0.8),
                opening("top", 2, 0.5, 0.9),
            ),
        )
        val r = RoomOps.insertCorner(room, 0, v(1.5, 0.3))
        assertPolygonEquals(listOf(v(0.0, 0.0), v(1.5, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0)), r.corners)
        val byId = r.openings.associateBy { it.id }
        assertEquals(0, byId.getValue("near").wallIndex)
        assertEquals(1, byId.getValue("far").wallIndex)
        assertEquals(0.5, byId.getValue("far").offset, 1e-12)
        assertEquals(3, byId.getValue("top").wallIndex)
        assertEquals(1, byId.getValue("straddle").wallIndex)
        assertEquals(0.0, byId.getValue("straddle").offset, 1e-12)
        for (id in listOf("near", "far", "top")) {
            val a = seg(room, id)
            val b = seg(r, id)
            assertVecEquals(a.first, b.first, 1e-9, id)
            assertVecEquals(a.second, b.second, 1e-9, id)
        }
        assertOpeningValid(r)
    }

    @Test fun insertCornerOnLastEdgeAndRejections() {
        val r = RoomOps.insertCorner(rect, 3, v(-0.2, 1.0))
        assertPolygonEquals(listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0), v(0.0, 1.0)), r.corners)
        assertSame(rect, RoomOps.insertCorner(rect, 0, v(0.02, 0.0)))
        assertSame(rect, RoomOps.insertCorner(rect, 0, v(3.99, 0.0)))
        assertSame(rect, RoomOps.insertCorner(rect, 0, v(-1.0, 0.0)))
        assertSame(rect, RoomOps.insertCorner(rect, 4, v(1.0, 0.0)))
        assertSame(rect, RoomOps.insertCorner(rect, 0, v(Double.NaN, 0.0)))
    }

    @Test fun deleteCornerMergesEdgesAndReprojectsOpenings() {
        val base = rect.copy(openings = listOf(opening("bottom", 0, 0.5, 0.8), opening("top", 2, 0.5, 0.9)))
        val five = RoomOps.insertCorner(base, 0, v(2.0, 0.0)).let { RoomOps.moveCorner(it, 1, v(2.0, -0.1)) }
        assertEquals(5, five.corners.size)
        val beforeTop = seg(five, "top")
        val beforeBottom = seg(five, "bottom")
        val r = RoomOps.deleteCorner(five, 1)
        assertPolygonEquals(Shapes.rect, r.corners)
        val bottom = r.openings.single { it.id == "bottom" }
        assertEquals(0, bottom.wallIndex)
        // The midpoint is kept (projected onto the merged edge).
        val mid = Vec2.lerp(beforeBottom.first, beforeBottom.second, 0.5)
        val (s, e) = seg(r, "bottom")
        assertEquals(mid.x, Vec2.lerp(s, e, 0.5).x, 1e-9)
        assertEquals(0.8, bottom.width, 1e-12)
        assertEquals(2, r.openings.single { it.id == "top" }.wallIndex)
        assertVecEquals(beforeTop.first, seg(r, "top").first)
        assertOpeningValid(r)
    }

    @Test fun deleteCornerZeroMergesLastAndFirstEdges() {
        val room = rect.copy(openings = listOf(opening("left", 3, 1.0, 0.8), opening("right", 1, 1.0, 0.8)))
        val r = RoomOps.deleteCorner(room, 0)
        assertPolygonEquals(listOf(v(4.0, 0.0), v(4.0, 3.0), v(0.0, 3.0)), r.corners)
        val left = r.openings.single { it.id == "left" }
        assertEquals(2, left.wallIndex) // merged edge (0,3) -> (4,0)
        assertEquals(0, r.openings.single { it.id == "right" }.wallIndex)
        assertOpeningValid(r)
    }

    @Test fun deleteCornerRejections() {
        val triangle = Room(id = "t", name = "T", corners = listOf(v(0.0, 0.0), v(3.0, 0.0), v(0.0, 3.0)))
        assertSame(triangle, RoomOps.deleteCorner(triangle, 0))
        assertSame(rect, RoomOps.deleteCorner(rect, 4))
        val u = Room(
            id = "u", name = "U",
            corners = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(3.0, 3.0), v(3.0, 1.0), v(1.0, 1.0), v(1.0, 3.0), v(0.0, 3.0)),
        )
        // Chord (0,0)->(4,3) would cut through the U's arm.
        assertSame(u, RoomOps.deleteCorner(u, 1))
    }

    // ---------------------------------------------------------------- transform / scale

    private val wallsRoom: Room = run {
        val obs = wallsOf(Shapes.rect)
        Room(
            id = "w", name = "Walls", corners = Shapes.rect, captureMethod = CaptureMethod.WALLS, observations = obs,
            cornerTaps = Shapes.rect, ceilingHeight = 2.5,
            openings = listOf(opening("d", 0, 1.0, 0.9).copy(sillHeight = 0.1)),
        )
    }

    @Test fun transformMovesEverythingAndRoundTrips() {
        val t = RigidTransform2(0.7, v(3.0, -1.5))
        val moved = RoomOps.transform(wallsRoom, t)
        moved.corners.forEachIndexed { i, p -> assertVecEquals(t.apply(Shapes.rect[i]), p) }
        moved.observations.forEachIndexed { i, o ->
            val src = wallsRoom.observations[i]
            assertVecEquals(t.apply(src.extentStart), o.extentStart)
            assertVecEquals(t.apply(src.viewer), o.viewer)
            assertEquals(0.0, o.line.distance(o.extentEnd), 1e-9)
            assertTrue(o.line.signedDistance(o.viewer) > 0)
        }
        assertEquals(wallsRoom.openings, moved.openings)
        val (s, _) = RoomOps.openingSegment(moved, moved.openings[0])
        assertVecEquals(t.apply(RoomOps.openingSegment(wallsRoom, wallsRoom.openings[0]).first), s)

        val back = RoomOps.transform(moved, t.inverse())
        back.corners.forEachIndexed { i, p -> assertVecEquals(wallsRoom.corners[i], p) }
        back.cornerTaps.forEachIndexed { i, p -> assertVecEquals(wallsRoom.cornerTaps[i], p) }
        back.observations.forEachIndexed { i, o ->
            val src = wallsRoom.observations[i]
            assertVecEquals(src.line.point, o.line.point)
            assertVecEquals(src.line.direction, o.line.direction)
            assertVecEquals(src.extentEnd, o.extentEnd)
            assertVecEquals(src.viewer, o.viewer)
        }
        // A re-solve of the moved room gives the moved outline.
        val resolved = assertNotNull(RoomSolver.solveWalls(moved.observations))
        assertPolygonEquals(moved.corners, resolved.corners, 1e-9)
    }

    @Test fun scaleMultipliesEverythingAndRoundTrips() {
        val origin = v(1.0, 1.0)
        val s = RoomOps.scale(wallsRoom, 1.1, origin)
        assertVecEquals(v(1.0 + (4.0 - 1.0) * 1.1, 1.0 + (3.0 - 1.0) * 1.1), s.corners[2])
        assertEquals(2.75, assertNotNull(s.ceilingHeight), 1e-12)
        val o = s.openings.single()
        assertEquals(1.1, o.offset, 1e-12)
        assertEquals(0.99, o.width, 1e-12)
        assertEquals(2.2, o.height, 1e-12)
        assertEquals(0.11, o.sillHeight, 1e-12)
        s.observations.forEach { obs ->
            assertEquals(0.0, obs.line.distance(obs.extentStart), 1e-9)
            assertEquals(0.0055, obs.rmsResidual, 1e-12)
        }
        assertEquals(12.0 * 1.21, Polygons.area(s.corners), 1e-9)

        val back = RoomOps.scale(s, 1 / 1.1, origin)
        back.corners.forEachIndexed { i, p -> assertVecEquals(wallsRoom.corners[i], p) }
        back.cornerTaps.forEachIndexed { i, p -> assertVecEquals(wallsRoom.cornerTaps[i], p) }
        assertEquals(0.9, back.openings.single().width, 1e-12)
        assertEquals(2.5, assertNotNull(back.ceilingHeight), 1e-12)
        back.observations.forEachIndexed { i, obs -> assertVecEquals(wallsRoom.observations[i].viewer, obs.viewer) }

        for (bad in listOf(0.0, -1.0, Double.NaN)) assertSame(wallsRoom, RoomOps.scale(wallsRoom, bad, origin))
    }

    // ---------------------------------------------------------------- placeOpening

    @Test fun placeOpeningOnNearestWall() {
        val o = assertNotNull(RoomOps.placeOpening(rect, v(1.0, 0.1), v(2.0, -0.05), OpeningType.DOOR, 2.03, 0.0, "d1"))
        assertEquals(0, o.wallIndex)
        assertEquals(1.0, o.offset, 1e-12)
        assertEquals(1.0, o.width, 1e-12)
        assertEquals("d1", o.id)
        assertEquals(OpeningType.DOOR, o.type)
        // Points in either order, on another wall.
        val w = assertNotNull(RoomOps.placeOpening(rect, v(3.9, 2.0), v(4.05, 0.8), OpeningType.WINDOW, 1.2, 0.9, "w1"))
        assertEquals(1, w.wallIndex)
        assertEquals(0.8, w.offset, 1e-12)
        assertEquals(1.2, w.width, 1e-12)
        assertEquals(0.9, w.sillHeight, 1e-12)
        val (s, e) = RoomOps.openingSegment(rect, w)
        assertVecEquals(v(4.0, 0.8), s)
        assertVecEquals(v(4.0, 2.0), e)
    }

    @Test fun placeOpeningIsClampedWithinTheEdge() {
        val o = assertNotNull(RoomOps.placeOpening(rect, v(3.5, 0.0), v(4.3, 0.2), OpeningType.OPENING, 2.0, 0.0, "o"))
        assertEquals(0, o.wallIndex)
        assertEquals(3.5, o.offset, 1e-12)
        assertEquals(0.5, o.width, 1e-12)
    }

    @Test fun placeOpeningRejections() {
        fun place(a: Vec2, b: Vec2, h: Double = 2.0) = RoomOps.placeOpening(rect, a, b, OpeningType.DOOR, h, 0.0, "x")
        assertNull(place(v(1.0, 0.6), v(2.0, 0.7)), "too far from any wall")
        assertNull(place(v(1.0, 0.0), v(1.1, 0.0)), "too narrow")
        assertNull(place(v(1.0, 0.0), v(1.0, 0.3)), "zero width along the wall")
        assertNull(place(v(4.2, -0.2), v(4.4, -0.4)), "beyond the end of the wall")
        assertNull(place(v(1.0, 0.0), v(2.0, 0.0), h = 0.0), "no height")
        assertNull(place(v(Double.NaN, 0.0), v(2.0, 0.0)), "bad point")
        assertNull(RoomOps.placeOpening(rect.copy(corners = Shapes.rect.take(2)), v(1.0, 0.0), v(2.0, 0.0), OpeningType.DOOR, 2.0, 0.0, "x"))
    }

    // ---------------------------------------------------------------- resolve

    @Test fun resolveSquaresWallsRoomAndRemapsOpenings() {
        val degs = listOf(2.0, -1.5, 1.0, -2.0)
        val obs = wallsOf(Shapes.rect).mapIndexed { i, o -> perturb(o, degs[i]) }
        val loose = assertNotNull(RoomSolver.solveWalls(obs, SolverOptions(squareAngles = false)))
        val room = Room(
            id = "w", name = "W", corners = loose.corners, captureMethod = CaptureMethod.WALLS, observations = obs,
            openings = listOf(opening("door", 0, 1.0, 0.9), opening("win", 2, 1.5, 1.0, OpeningType.WINDOW)),
        )
        val before = room.openings.associate { it.id to RoomOps.openingSegment(room, it) }
        val r = RoomOps.resolve(room, SolverOptions(squareAngles = true))
        assertAllRightAngles(r.corners)
        for (o in r.openings) {
            val (s, e) = RoomOps.openingSegment(r, o)
            val (s0, e0) = before.getValue(o.id)
            assertTrue(Vec2.lerp(s, e, 0.5).distanceTo(Vec2.lerp(s0, e0, 0.5)) < 0.05, "${o.id} stays in place")
        }
        assertEquals(0, r.openings.single { it.id == "door" }.wallIndex)
        assertEquals(2, r.openings.single { it.id == "win" }.wallIndex)
        assertOpeningValid(r)
    }

    @Test fun resolveCornersAndManualRooms() {
        val taps = listOf(v(0.01, 0.0), v(4.0, 0.02), v(4.02, 3.0), v(0.0, 2.99))
        val cornersRoom = Room(id = "c", name = "C", corners = taps, captureMethod = CaptureMethod.CORNERS, cornerTaps = taps)
        assertAllRightAngles(RoomOps.resolve(cornersRoom, SolverOptions()).corners)

        val manual = Room(id = "m", name = "M", corners = taps, openings = listOf(opening("d", 0, 1.0, 0.9)))
        assertSame(manual, RoomOps.resolve(manual, SolverOptions(squareAngles = false)))
        val squared = RoomOps.resolve(manual, SolverOptions())
        assertAllRightAngles(squared.corners)
        assertEquals(0, squared.openings.single().wallIndex)

        // A WALLS room whose observations cannot be solved is left untouched.
        val broken = Room(
            id = "b", name = "B", corners = Shapes.rect, captureMethod = CaptureMethod.WALLS,
            observations = listOf(wallObs(v(0.0, 0.0), v(1.0, 0.0)), wallObs(v(1.0, 0.0), v(2.0, 0.0)), wallObs(v(2.0, 0.0), v(3.0, 0.0))),
        )
        assertSame(broken, RoomOps.resolve(broken, SolverOptions()))
    }

    // ---------------------------------------------------------------- FloorOps

    private fun floor(): Floor {
        val a = rect.copy(id = "a")
        val b = Room(id = "b", name = "Bath", corners = listOf(v(4.1, 0.0), v(6.1, 0.0), v(6.1, 2.0), v(4.1, 2.0)))
        return Floor(id = "f", name = "Ground", rooms = listOf(a, b))
    }

    @Test fun calibrateScalesWholeFloorAndPreservesRelativePlacement() {
        val f = floor()
        val c = FloorOps.calibrate(f, "a", 0, 4.1, now = 1000L)
        val a = c.rooms.single { it.id == "a" }
        val b = c.rooms.single { it.id == "b" }
        assertEquals(4.1, a.corners[0].distanceTo(a.corners[1]), 1e-12)
        val factor = 4.1 / 4.0
        // The gap between the rooms and the bathroom's size scale by the same factor.
        assertEquals(0.1 * factor, b.corners[0].x - a.corners[1].x, 1e-12)
        assertEquals(2.0 * factor, b.corners[0].distanceTo(b.corners[1]), 1e-12)
        // Scaled about the floor's bounds center (3.05, 1.5).
        val center = v(3.05, 1.5)
        assertVecEquals(center + (f.rooms[0].corners[0] - center) * factor, a.corners[0], 1e-12)
        assertEquals(2.4 * factor, assertNotNull(a.ceilingHeight), 1e-12)
        val cal = assertNotNull(c.calibration)
        assertEquals(factor, cal.factor, 1e-12)
        assertEquals(4.0, cal.measuredLength, 1e-12)
        assertEquals(4.1, cal.trueLength, 1e-12)
        assertEquals("a", cal.roomId)
        assertEquals(0, cal.wallIndex)
        assertEquals(1000L, cal.appliedAt)
    }

    @Test fun calibrateComposesFactors() {
        val once = FloorOps.calibrate(floor(), "a", 0, 4.1, now = 1L)
        val twice = FloorOps.calibrate(once, "b", 1, 2.0, now = 2L)
        val b = twice.rooms.single { it.id == "b" }
        assertEquals(2.0, b.corners[1].distanceTo(b.corners[2]), 1e-12)
        val cal = assertNotNull(twice.calibration)
        assertEquals((4.1 / 4.0) * (2.0 / 2.05), cal.factor, 1e-12)
        assertEquals(2.05, cal.measuredLength, 1e-12)
        assertEquals("b", cal.roomId)
        // Room a is scaled by the second factor too.
        val a = twice.rooms.single { it.id == "a" }
        assertEquals(4.1 * 2.0 / 2.05, a.corners[0].distanceTo(a.corners[1]), 1e-12)
    }

    @Test fun calibrateRejectsBadInput() {
        val f = floor()
        for (len in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("length $len") { FloorOps.calibrate(f, "a", 0, len, 0L) }
        }
        val tooBig = assertFailsWith<IllegalArgumentException> { FloorOps.calibrate(f, "a", 0, 8.5, 0L) }
        assertTrue(tooBig.message.orEmpty().contains("4.00 m"), tooBig.message)
        assertFailsWith<IllegalArgumentException> { FloorOps.calibrate(f, "a", 0, 1.9, 0L) }
        assertFailsWith<IllegalArgumentException> { FloorOps.calibrate(f, "missing", 0, 4.0, 0L) }
        assertFailsWith<IllegalArgumentException> { FloorOps.calibrate(f, "a", 4, 4.0, 0L) }
        // Exactly at the limits is accepted.
        FloorOps.calibrate(f, "a", 0, 8.0, 0L)
        FloorOps.calibrate(f, "a", 0, 2.0, 0L)
    }

    @Test fun upsertAndRemoveRooms() {
        val f = floor()
        val renamed = f.rooms[0].copy(name = "Primary")
        val u = FloorOps.upsertRoom(f, renamed)
        assertEquals(listOf("a", "b"), u.rooms.map { it.id })
        assertEquals("Primary", u.rooms[0].name)
        val added = FloorOps.upsertRoom(u, rect.copy(id = "c"))
        assertEquals(listOf("a", "b", "c"), added.rooms.map { it.id })
        val removed = FloorOps.removeRoom(added, "b")
        assertEquals(listOf("a", "c"), removed.rooms.map { it.id })
        assertSame(removed, FloorOps.removeRoom(removed, "zzz"))
    }

    @Test fun rotateRoomByQuarterTurnsKeepsDimensions() {
        val t = RigidTransform2.rotationAround(Polygons.centroid(rect.corners), PI / 2)
        val r = RoomOps.transform(rect, t)
        val box = Polygons.orientedBoundingBox(r.corners)
        assertEquals(4.0, box.length, 1e-9)
        assertEquals(3.0, box.width, 1e-9)
        assertTrue(Polygons.isCounterClockwise(r.corners))
    }
}
