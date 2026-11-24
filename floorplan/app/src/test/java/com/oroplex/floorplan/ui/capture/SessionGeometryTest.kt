package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.DoorSwing
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

    @Test
    fun axisHintIsTheSessionRoomsDominantAxis() {
        val angle = Math.toRadians(12.0)
        val hint = sessionAxisHint(listOf(rect(4.0, 3.0, angle), rect(2.0, 2.5, angle, Vec2(5.0, 1.0))))
        assertNotNull(hint)
        assertEquals(angle, hint ?: Double.NaN, 1e-9)
    }

    @Test
    fun axisHintIsCanonicalModNinetyDegrees() {
        // A rectangle rotated by 100° has the same axis as one rotated by 10°.
        val hint = sessionAxisHint(listOf(rect(4.0, 3.0, Math.toRadians(100.0))))
        assertEquals(Math.toRadians(10.0), hint ?: Double.NaN, 1e-9)
    }

    @Test
    fun axisHintIsWeightedByEdgeLength() {
        // A long room at 5° outweighs a tiny closet at 30°.
        val hint = sessionAxisHint(listOf(rect(8.0, 6.0, Math.toRadians(5.0)), rect(0.4, 0.4, Math.toRadians(30.0), Vec2(20.0, 0.0))))
        val deg = Math.toDegrees(hint ?: Double.NaN)
        assertTrue("got $deg", deg in 5.0..7.0)
    }

    @Test
    fun noAxisHintWithoutUsableRoomsOrWhenAxesDisagree() {
        assertNull(sessionAxisHint(emptyList()))
        assertNull(sessionAxisHint(listOf(Room(id = "x", name = "x", corners = listOf(Vec2.ZERO, Vec2(1.0, 0.0))))))
        assertNull(sessionAxisHint(listOf(Room(id = "n", name = "n", corners = listOf(Vec2.ZERO, Vec2(Double.NaN, 0.0), Vec2(1.0, 1.0))))))
        // Equal squares at 0° and 45°: their 4θ directions cancel, so there is no common axis.
        assertNull(sessionAxisHint(listOf(rect(3.0, 3.0), rect(3.0, 3.0, PI / 4, Vec2(10.0, 0.0)))))
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
