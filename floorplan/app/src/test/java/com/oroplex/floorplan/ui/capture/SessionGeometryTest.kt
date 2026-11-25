package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class SessionGeometryTest {

    private fun rect(w: Double, h: Double, angle: Double = 0.0, at: Vec2 = Vec2.ZERO): Room {
        val corners = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, h), Vec2(0.0, h)).map { it.rotated(angle) + at }
        return Room(id = "r$w$h$angle", name = "Room", corners = corners)
    }

    private val noCorrections = emptyMap<String, RigidTransform2>()

    private fun turn(angle: Double) = RigidTransform2(angle, Vec2.ZERO)

    /** A 4 × 5 m room with one 1.5 m wall at 30° across its top-right corner (a chamfer). */
    private fun chamfered(id: String = "c"): Room {
        val cut = 1.5
        val dx = cut * kotlin.math.cos(PI / 6)
        val dy = cut * kotlin.math.sin(PI / 6)
        return Room(
            id = id,
            name = "Room",
            corners = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 5.0 - dy), Vec2(4.0 - dx, 5.0), Vec2(0.0, 5.0)),
        )
    }

    @Test
    fun sessionAxisIsTheFirstRoomsSquaringAxisNotAMean() {
        // The first room squared to 0°; its 30° chamfer must not tilt the axis later rooms share.
        val hint = sessionAxisHint(SessionAxis(0.0, "c"), listOf(chamfered()), noCorrections, "draft")
        assertEquals(0.0, hint ?: Double.NaN, 1e-12)
        // The plain length-weighted 4θ mean of the same room is pulled by over a degree (what round 2 used).
        val c = chamfered().corners
        val angles = c.indices.map { (c[(it + 1) % c.size] - c[it]).angle() }
        val weights = c.indices.map { c[(it + 1) % c.size].distanceTo(c[it]) }
        val mean = com.oroplex.floorplan.core.solve.RoomSolver.dominantAngle(angles, weights)
        val off = kotlin.math.min(mean, PI / 2 - mean)
        assertTrue("mean is ${Math.toDegrees(off)}° off", Math.toDegrees(off) > 0.5)
    }

    @Test
    fun sessionAxisIsBroughtIntoTheDraftsFrame() {
        val axis = SessionAxis(Math.toRadians(12.0), "r1")
        // ARCore turned room 1's frame by +0.01 rad since its capture, and the draft's by +0.004 rad.
        val corrections = mapOf("r1" to turn(0.01), "draft" to turn(0.004))
        val hint = sessionAxisHint(axis, emptyList(), corrections, "draft")
        assertEquals(Math.toRadians(12.0) + 0.006, hint ?: Double.NaN, 1e-12)
        // Missing or broken corrections count as none.
        assertEquals(Math.toRadians(12.0), sessionAxisHint(axis, emptyList(), mapOf("r1" to turn(Double.NaN)), "draft") ?: Double.NaN, 1e-12)
    }

    @Test
    fun sessionAxisIsCanonicalModNinetyDegrees() {
        assertEquals(Math.toRadians(10.0), sessionAxisHint(SessionAxis(Math.toRadians(100.0), "r"), emptyList(), noCorrections, null) ?: Double.NaN, 1e-12)
        // Just below 0 wraps to just below 90°, the same squaring axis.
        val hint = sessionAxisHint(SessionAxis(0.0, "r"), emptyList(), mapOf("d" to turn(0.002)), "d") ?: Double.NaN
        assertEquals(PI / 2 - 0.002, hint, 1e-12)
        assertEquals(0.0, canonicalAxis(Double.NaN), 0.0)
        assertEquals(0.0, canonicalAxis(PI / 2), 1e-15)
        assertEquals(PI / 4, canonicalAxis(-PI / 4), 1e-15)
    }

    @Test
    fun withoutAStoredAxisTheRobustSharedAxisOfTheSessionRoomsIsUsed() {
        val angle = Math.toRadians(12.0)
        val hint = sessionAxisHint(null, listOf(rect(4.0, 3.0, angle), rect(2.0, 2.5, angle, Vec2(5.0, 1.0))), noCorrections, null)
        assertNotNull(hint)
        assertEquals(angle, hint ?: Double.NaN, 1e-9)
        // Each saved room is first turned by its own correction into the current frame.
        val turned = sessionAxisHint(null, listOf(rect(4.0, 3.0, angle).copy(id = "a")), mapOf("a" to turn(0.01)), null)
        assertEquals(angle + 0.01, turned ?: Double.NaN, 1e-9)
    }

    @Test
    fun noAxisHintWithoutAnythingToGoBy() {
        assertNull(sessionAxisHint(null, emptyList(), noCorrections, "d"))
        assertNull(sessionAxisHint(null, listOf(Room(id = "x", name = "x", corners = listOf(Vec2.ZERO, Vec2(1.0, 0.0)))), noCorrections, "d"))
        assertNull(sessionAxisHint(null, listOf(Room(id = "n", name = "n", corners = listOf(Vec2.ZERO, Vec2(Double.NaN, 0.0), Vec2(1.0, 1.0)))), noCorrections, "d"))
        // A stored axis that is not a number falls back to the rooms (none here).
        assertNull(sessionAxisHint(SessionAxis(Double.NaN, "r"), emptyList(), noCorrections, "d"))
    }

    @Test
    fun straightensOnlyAFloorHoldingJustThisSessionsRooms() {
        val tilted = rect(4.0, 3.0, 0.2).copy(id = "a", sessionId = "s1", captureMethod = CaptureMethod.WALLS)
        val floor = Floor(id = "f", name = "Main", rooms = listOf(tilted))
        val straight = straightenedIfOnlySession(floor, "s1")
        val e = straight.rooms.single().corners.let { it[1] - it[0] }
        assertEquals(0.0, kotlin.math.abs(kotlin.math.sin(2 * e.angle())), 1e-9)

        assertSame(floor, straightenedIfOnlySession(floor, "s2"))
        assertSame(floor, straightenedIfOnlySession(floor, null))
        val withDrawn = floor.copy(rooms = floor.rooms + rect(1.0, 1.0, 0.0, Vec2(9.0, 0.0)))
        assertSame(withDrawn, straightenedIfOnlySession(withDrawn, "s1"))
        val empty = Floor(id = "f", name = "Main")
        assertSame(empty, straightenedIfOnlySession(empty, "s1"))
    }

    @Test
    fun appliedScaleMapsRawPointsOntoTheCalibratedFloor() {
        val s = AppliedScale(1.02, Vec2(1.0, -1.0))
        assertTrue(s.map(Vec2(1.0, -1.0)).approxEquals(Vec2(1.0, -1.0), 1e-12))
        assertTrue(s.map(Vec2(2.0, 1.0)).approxEquals(Vec2(2.02, 1.04), 1e-12))
    }

    @Test
    fun appliedScaleOfCalibration() {
        assertNull(AppliedScale.of(null))
        val c = Calibration(factor = 1.02, roomId = "r", wallIndex = 0, measuredLength = 3.0, trueLength = 3.06, appliedAt = 0L, origin = Vec2(1.0, 2.0))
        assertEquals(AppliedScale(1.02, Vec2(1.0, 2.0)), AppliedScale.of(c))
        assertNull(AppliedScale.of(c.copy(factor = 1.0)))
        assertNull(AppliedScale.of(c.copy(factor = Double.NaN)))
        assertNull(AppliedScale.of(c.copy(factor = -1.0)))
        assertNull(AppliedScale.of(c.copy(origin = Vec2(Double.NaN, 0.0))))
    }

    @Test
    fun correctionForScaledRoomEqualsScaleAfterCorrection() {
        val t = RigidTransform2(0.07, Vec2(0.3, -0.2))
        val scale = AppliedScale(1.025, Vec2(2.0, -1.0))
        fun s(p: Vec2) = scale.origin + (p - scale.origin) * scale.factor
        val conjugated = t.forScaledRoom(scale)
        for (p in listOf(Vec2.ZERO, Vec2(3.0, 4.0), Vec2(-2.5, 7.0))) {
            // Correcting the scaled room must give the scaled corrected room: T'(S(p)) == S(T(p)).
            val expected = s(t.apply(p))
            val actual = conjugated.apply(s(p))
            assertTrue("$expected vs $actual", expected.approxEquals(actual, 1e-12))
        }
        assertEquals(t.angle, conjugated.angle, 0.0)
    }

    @Test
    fun correctionUnchangedWithoutValidScale() {
        val t = RigidTransform2(0.1, Vec2(1.0, 1.0))
        assertSame(t, t.forScaledRoom(null))
        assertSame(t, t.forScaledRoom(AppliedScale(Double.NaN, Vec2.ZERO)))
        assertSame(t, t.forScaledRoom(AppliedScale(0.0, Vec2.ZERO)))
    }

    @Test
    fun parkedOpeningsAreRestoredOnTheReSolvedRoom() {
        val door = Opening("d", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.8, height = 2.0, swing = DoorSwing.RIGHT_OUT)
        val window = Opening("w", OpeningType.WINDOW, wallIndex = 2, offset = 0.5, width = 1.0, height = 1.2, sillHeight = 0.9)
        val room = rect(4.0, 3.0).copy(openings = listOf(door, window))
        val parked = parkOpenings(room)
        assertEquals(2, parked.size)
        assertTrue(parked[0].start.approxEquals(Vec2(1.0, 0.0), 1e-9))
        assertTrue(parked[0].end.approxEquals(Vec2(1.8, 0.0), 1e-9))

        // The room was re-solved slightly larger and starts at another corner: indices change, positions don't.
        val resolved = Room(
            id = room.id,
            name = room.name,
            corners = listOf(Vec2(4.02, 3.01), Vec2(0.0, 3.01), Vec2(0.0, 0.0), Vec2(4.02, 0.0)),
        )
        val restored = restoreOpenings(resolved, parked)
        assertTrue(restored.dropped.isEmpty())
        val d = restored.openings.single { it.id == "d" }
        val w = restored.openings.single { it.id == "w" }
        assertEquals(2, d.wallIndex) // bottom edge is now (0,0) -> (4.02,0)
        assertEquals(1.0, d.offset, 1e-9)
        assertEquals(0.8, d.width, 1e-9)
        assertEquals(DoorSwing.RIGHT_OUT, d.swing)
        assertEquals(0, w.wallIndex) // top edge is now edge 0
        assertEquals(0.9, w.sillHeight, 1e-12)
        assertEquals(1.2, w.height, 1e-12)
    }

    @Test
    fun openingWhoseWallIsGoneIsDropped() {
        val door = Opening("d", OpeningType.DOOR, wallIndex = 1, offset = 1.0, width = 0.8, height = 2.0)
        val parked = parkOpenings(rect(4.0, 3.0).copy(openings = listOf(door)))
        // The right wall moved 2 m inwards: the door is no longer near any wall.
        val smaller = rect(2.0, 3.0)
        val restored = restoreOpenings(smaller, parked)
        assertTrue(restored.openings.isEmpty())
        assertEquals(listOf(door), restored.dropped)
    }

    @Test
    fun overlappingRestoredOpeningIsDropped() {
        val a = Opening("a", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.8, height = 2.0)
        val b = Opening("b", OpeningType.DOOR, wallIndex = 0, offset = 1.2, width = 0.8, height = 2.0)
        val room = rect(4.0, 3.0)
        val restored = restoreOpenings(room, parkOpenings(room.copy(openings = listOf(a, b))))
        assertEquals(listOf("a"), restored.openings.map { it.id })
        assertEquals(listOf("b"), restored.dropped.map { it.id })
    }
}
