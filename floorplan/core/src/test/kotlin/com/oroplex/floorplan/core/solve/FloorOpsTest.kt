package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.PolygonClipping
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.Ops.T
import com.oroplex.floorplan.core.solve.Ops.door
import com.oroplex.floorplan.core.solve.Ops.drawn
import com.oroplex.floorplan.core.solve.Ops.floor
import com.oroplex.floorplan.core.solve.Ops.moved
import com.oroplex.floorplan.core.solve.Ops.rect
import com.oroplex.floorplan.core.solve.Ops.room
import com.oroplex.floorplan.core.solve.Ops.scanned
import com.oroplex.floorplan.core.solve.Ops.tapped
import com.oroplex.floorplan.core.solve.Ops.turned
import com.oroplex.floorplan.core.solve.Ops.window
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FloorOpsTest {
    // ---------------------------------------------------------------- calibration (Core math AR-6)

    private val living = scanned(
        "living", rect(0.0, 0.0, 5.0, 4.0), name = "Living",
        openings = listOf(door("d", 0, 1.0), window("w", 2, 0.5)),
    )
    private val bath = tapped("bath", rect(5.0 + T, 0.0, 7.0, 2.0), name = "Bath")
        .copy(ceilingHeight = 2.4384, ceilingEstimated = true)
    private val closet = drawn("closet", rect(5.0 + T, 2.0 + T, 6.2, 2.9), name = "Closet")
    private val mixed = floor(living, bath, closet)

    private fun scaledAbout(origin: Vec2, factor: Double, p: Vec2) = origin + (p - origin) * factor

    @Test fun calibrateScalesOnlyArMeasuredQuantities() {
        val c = FloorOps.calibrate(mixed, "living", 0, 5.1, now = 7L)
        val factor = 5.1 / 5.0
        val cal = assertNotNull(c.calibration)
        // Origin: bounds center of the scanned rooms only (the drawn closet does not move the pivot).
        val origin = Bounds.of(living.corners + bath.corners).center
        assertVecEquals(origin, cal.origin, 1e-12)
        assertEquals(factor, cal.factor, 1e-12)

        val l = c.room("living")
        l.corners.forEachIndexed { i, p -> assertVecEquals(scaledAbout(origin, factor, living.corners[i]), p, 1e-12) }
        assertEquals(5.1, l.corners[0].distanceTo(l.corners[1]), 1e-12)
        // Measured ceiling scales; opening positions / widths scale; typed / default heights and sills do not.
        assertEquals(2.4 * factor, assertNotNull(l.ceilingHeight), 1e-12)
        val d = l.openings.single { it.id == "d" }
        assertEquals(1.0 * factor, d.offset, 1e-12)
        assertEquals(0.8 * factor, d.width, 1e-12)
        assertEquals(2.032, d.height, 0.0)
        val w = l.openings.single { it.id == "w" }
        assertEquals(1.2192, w.height, 0.0)
        assertEquals(0.9144, w.sillHeight, 0.0)
        // Raw observations follow, so a re-solve reproduces the calibrated outline.
        l.observations.forEachIndexed { i, o ->
            val src = living.observations[i]
            assertVecEquals(scaledAbout(origin, factor, src.extentStart), o.extentStart, 1e-12)
            assertVecEquals(scaledAbout(origin, factor, src.viewer), o.viewer, 1e-12)
            assertEquals(src.rmsResidual * factor, o.rmsResidual, 1e-15)
        }
        assertPolygonEquals(l.corners, RoomOps.resolve(l, SolverOptions()).corners, 1e-9)

        // Corner-tap room: corners and taps scale, its estimated (default) ceiling does not.
        val b = c.room("bath")
        b.corners.forEachIndexed { i, p -> assertVecEquals(scaledAbout(origin, factor, bath.corners[i]), p, 1e-12) }
        b.cornerTaps.forEachIndexed { i, p -> assertVecEquals(scaledAbout(origin, factor, bath.cornerTaps[i]), p, 1e-12) }
        assertEquals(2.4384, b.ceilingHeight)
        assertTrue(b.ceilingEstimated)
        // The drawn closet keeps its typed size exactly, but stays snapped to both scanned neighbours.
        val k = c.room("closet")
        assertSameShape(closet.corners, k.corners)
        assertEquals(T, k.corners[0].x - l.corners[1].x, 1e-9, "one wall from the living room")
        assertEquals(T, k.corners[0].y - b.corners[2].y, 1e-9, "one wall from the bath")
        assertEquals(closet.ceilingHeight, k.ceilingHeight)
    }

    @Test fun calibrateRefusesDrawnRooms() {
        val e = assertFailsWith<IllegalArgumentException> { FloorOps.calibrate(mixed, "closet", 0, 1.2, 0L) }
        assertTrue(e.message.orEmpty().contains("Closet was drawn by hand"), e.message)
        assertTrue(e.message.orEmpty().contains("scanned room"), e.message)
    }

    @Test fun recalibrationReusesTheOriginSoTheTotalIsOneUniformScale() {
        val once = FloorOps.calibrate(mixed, "living", 0, 5.1, now = 1L)
        val origin = assertNotNull(once.calibration).origin
        val twice = FloorOps.calibrate(once, "bath", 1, 2.1, now = 2L)
        val cal = assertNotNull(twice.calibration)
        assertVecEquals(origin, cal.origin, 0.0)
        val total = cal.factor
        assertEquals((5.1 / 5.0) * (2.1 / (2.0 * 5.1 / 5.0)), total, 1e-12)
        // Every scanned room is exactly the raw room scaled once by the total factor about the origin.
        for (raw in listOf(living, bath)) {
            twice.room(raw.id).corners.forEachIndexed { i, p -> assertVecEquals(scaledAbout(origin, total, raw.corners[i]), p, 1e-12) }
        }
        assertEquals(2.1, twice.room("bath").let { it.corners[1].distanceTo(it.corners[2]) }, 1e-12)
        val k = twice.room("closet")
        assertSameShape(closet.corners, k.corners)
        assertEquals(T, k.corners[0].x - twice.room("living").corners[1].x, 1e-9)
        assertEquals(T, k.corners[0].y - twice.room("bath").corners[2].y, 1e-9)
    }

    @Test fun applyCalibrationScalesALaterScanLikeItsNeighbours() {
        val calibrated = FloorOps.calibrate(mixed, "living", 0, 5.1, now = 1L)
        val cal = assertNotNull(calibrated.calibration)
        // Same AR frame as the living room, scanned after calibrating (e.g. the forgotten laundry room).
        val laundry = scanned("laundry", rect(-2.0 - T, 0.0, -T, 2.5), name = "Laundry", ceiling = 2.3, openings = listOf(window("lw", 0, 0.4)))
        val added = FloorOps.applyCalibration(calibrated, laundry)
        added.corners.forEachIndexed { i, p -> assertVecEquals(scaledAbout(cal.origin, cal.factor, laundry.corners[i]), p, 1e-12) }
        assertEquals(2.3 * cal.factor, assertNotNull(added.ceilingHeight), 1e-12)
        assertEquals(0.4 * cal.factor, added.openings.single().offset, 1e-12)
        assertEquals(1.2192, added.openings.single().height, 0.0)
        // It lines up with its calibrated neighbour exactly as it did before calibration (gap scaled too).
        val l = calibrated.room("living")
        assertEquals(T * cal.factor, l.corners[0].x - added.corners[1].x, 1e-12)
        // Same result as if it had been on the floor when calibrating.
        val together = FloorOps.calibrate(floor(living, bath, closet, laundry).copy(calibration = null), "living", 0, 5.1, now = 1L)
        assertEquals(together.room("laundry").corners[0].distanceTo(together.room("laundry").corners[1]), added.corners[0].distanceTo(added.corners[1]), 1e-12)
    }

    @Test fun applyCalibrationLeavesEverythingElseAlone() {
        val calibrated = FloorOps.calibrate(mixed, "living", 0, 5.1, now = 1L)
        val newDrawn = drawn("pantry", rect(10.0, 10.0, 11.0, 11.0))
        assertSame(newDrawn, FloorOps.applyCalibration(calibrated, newDrawn), "drawn rooms keep typed sizes")
        val newScan = scanned("bed", rect(10.0, 10.0, 13.0, 13.0), ceilingEstimated = true, ceiling = 2.4384)
        assertSame(newScan, FloorOps.applyCalibration(mixed, newScan), "uncalibrated floor")
        // A room already on the floor is already calibrated; scaling it again would double the correction.
        val existing = calibrated.room("living")
        assertSame(existing, FloorOps.applyCalibration(calibrated, existing))
        // An estimated ceiling is never scaled.
        assertEquals(2.4384, FloorOps.applyCalibration(calibrated, newScan).ceilingHeight)
        // Factor 1 or corrupt calibrations are no-ops.
        val unit = calibrated.copy(calibration = cal(1.0))
        assertSame(newScan, FloorOps.applyCalibration(unit, newScan))
        for (bad in listOf(Double.NaN, 0.0, -1.0)) assertSame(newScan, FloorOps.applyCalibration(calibrated.copy(calibration = cal(bad)), newScan))
        // A non-finite stored origin falls back to the plan origin.
        val noOrigin = calibrated.copy(calibration = cal(1.1).copy(origin = Vec2(Double.NaN, 0.0)))
        assertVecEquals(newScan.corners[2] * 1.1, FloorOps.applyCalibration(noOrigin, newScan).corners[2], 1e-12)
    }

    private fun cal(factor: Double) = Calibration(factor, "living", 0, 5.0, 5.0 * factor, 1L, Vec2(1.0, 1.0))

    // ---------------------------------------------------------------- calibration provenance (SEAM-5, MATH-8, APPX-2)

    @Test fun typedAndEstimatedCeilingsKeepTheirValues() {
        val typed = living.copy(ceilingHeight = 2.70, ceilingTyped = true)
        val c = FloorOps.calibrate(floor(typed, bath), "living", 0, 5.15, now = 1L)
        assertEquals(2.70, c.room("living").ceilingHeight, "a tape-typed ceiling is not rescaled")
        assertTrue(c.room("living").ceilingTyped)
        assertEquals(2.4384, c.room("bath").ceilingHeight, "an estimated ceiling is not rescaled")
        // A measured (AR) ceiling is.
        assertEquals(2.4 * 1.03, assertNotNull(FloorOps.calibrate(floor(living, bath), "living", 0, 5.15, 1L).room("living").ceilingHeight), 1e-12)
    }

    @Test fun typedOpeningsKeepTheirWidthAndMoveWithTheirWall() {
        val typedDoor = door("td", 0, 1.0, width = 0.8128).copy(typed = true)
        val arDoor = door("ad", 2, 2.0, width = 0.9)
        val room = living.copy(openings = listOf(typedDoor, arDoor))
        val f = 1.03
        val c = FloorOps.calibrate(floor(room, bath), "living", 0, 5.0 * f, now = 1L).room("living")
        val td = c.openings.single { it.id == "td" }
        // The 32" door stays 32"; its centre keeps its place along the scaled wall.
        assertEquals(0.8128, td.width, 0.0)
        assertEquals((1.0 + 0.8128 / 2) * f, td.offset + td.width / 2, 1e-12)
        assertTrue(td.typed)
        // An AR-marked opening scales with the wall.
        val ad = c.openings.single { it.id == "ad" }
        assertEquals(0.9 * f, ad.width, 1e-12)
        assertEquals(2.0 * f, ad.offset, 1e-12)
        // A typed opening at the very end of a wall that shrinks stays inside it.
        val atEnd = living.copy(openings = listOf(door("e", 0, 5.0 - 0.8128, 0.8128).copy(typed = true)))
        val shrunk = FloorOps.calibrate(floor(atEnd, bath), "living", 0, 4.85, now = 1L).room("living")
        val e = shrunk.openings.single()
        assertEquals(0.8128, e.width, 0.0)
        assertTrue(e.offset + e.width <= shrunk.corners[0].distanceTo(shrunk.corners[1]) + 1e-9)
    }

    @Test fun typedWallLengthsSurviveCalibration() {
        // The user tapes the bedroom's south wall at 3.500 m, then calibrates the floor from the living room (x1.02).
        val bedroom = scanned("bed", rect(5.0 + T, 0.0, 8.6, 3.0), name = "Bedroom")
        val typed = RoomOps.setWallLength(bedroom, 0, 3.5)
        assertEquals(3.5, typed.corners[0].distanceTo(typed.corners[1]), 1e-12)
        val c = FloorOps.calibrate(floor(living, typed), "living", 0, 5.1, now = 1L)
        val bed = c.room("bed")
        assertEquals(3.5, bed.corners[0].distanceTo(bed.corners[1]), 1e-9, "the tape value is kept")
        assertEquals(3.0 * 1.02, bed.corners[1].distanceTo(bed.corners[2]), 1e-9, "AR walls are scaled")
        assertEquals(3.5, RoomOps.handSetWallFor(bed, 0)?.typedLength)
        assertTrue(Polygons.isCounterClockwise(bed.corners))
        // Two typed walls at right angles both survive (indices are found before either is restored).
        val both = RoomOps.setWallLength(typed, 1, 2.9)
        val c2 = FloorOps.calibrate(floor(living, both), "living", 0, 5.1, now = 1L).room("bed")
        assertEquals(3.5, c2.corners[0].distanceTo(c2.corners[1]), 1e-9)
        assertEquals(2.9, c2.corners[1].distanceTo(c2.corners[2]), 1e-9)
        assertEquals(3.5, RoomOps.handSetWallFor(c2, 0)?.typedLength)
        assertEquals(2.9, RoomOps.handSetWallFor(c2, 1)?.typedLength)
        // A wall set by hand is not the app's measurement any more, so it cannot be the calibration reference:
        // neither the typed wall nor the opposite wall that was stretched with it.
        for (handSet in listOf(0, 2)) {
            val refused = assertFailsWith<IllegalArgumentException> {
                FloorOps.calibrate(floor(living, typed), "bed", handSet, 3.55, now = 1L)
            }
            assertTrue("set by hand" in refused.message.orEmpty(), refused.message)
        }
        // An untouched wall of the same room still works: it is scaled, and the typed wall keeps its tape value.
        val fromUntouched = FloorOps.calibrate(floor(living, typed), "bed", 3, 3.06, now = 1L)
        assertEquals(1.02, fromUntouched.calibration!!.factor, 1e-9)
        val bed3 = fromUntouched.room("bed")
        assertEquals(3.06, bed3.corners[3].distanceTo(bed3.corners[0]), 1e-9)
        assertEquals(3.5, bed3.corners[0].distanceTo(bed3.corners[1]), 1e-9)
        // And a later room calibrated on arrival (applyCalibration) follows the same rule.
        val calibrated = FloorOps.calibrate(floor(living), "living", 0, 5.1, now = 1L)
        val arriving = FloorOps.applyCalibration(calibrated, typed.copy(ceilingHeight = 2.5, ceilingTyped = true))
        assertEquals(3.5, arriving.corners[0].distanceTo(arriving.corners[1]), 1e-9)
        assertEquals(2.5, arriving.ceilingHeight)
    }

    // ---------------------------------------------------------------- drawn rooms under calibration (SEAM-6, APPX-3)

    @Test fun drawnRoomsMoveWithTheScaledPlanAndStaySnapped() {
        // A scanned bedroom 6 m from the floor centre and a drawn closet snapped to its east wall.
        val west = scanned("w", rect(-7.0, -2.0, -3.0, 2.0), name = "West")
        val bed = scanned("bed", rect(3.0, -2.0, 6.0, 2.0), name = "Bedroom")
        val drawnCloset = drawn("c", rect(6.0 + T, 0.2, 6.0 + T + 0.9, 1.4), name = "Closet")
        val f0 = floor(west, bed, drawnCloset)
        for (factor in listOf(1.03, 0.97)) {
            val c = FloorOps.calibrate(f0, "bed", 1, 4.0 * factor, now = 1L)
            val b = c.room("bed")
            val k = c.room("c")
            assertSameShape(drawnCloset.corners, k.corners)
            assertEquals(T, k.corners[0].x - b.corners[1].x, 1e-9, "x$factor: still one wall thickness from the bedroom")
            assertTrue(FloorChecks.overlappingRooms(c).isEmpty(), "x$factor: no overlap")
            // It moved along the wall with the plan too (its centre keeps its relative place).
            val origin = assertNotNull(c.calibration).origin
            val before = Polygons.centroid(drawnCloset.corners)
            assertEquals(origin.y + (before.y - origin.y) * factor, Polygons.centroid(k.corners).y, 1e-9)
        }
        // A drawn room attached to nothing just moves with the plan: by (f - 1) x (centroid - origin).
        val shed = drawn("shed", rect(20.0, 10.0, 22.0, 12.0), name = "Shed")
        val c = FloorOps.calibrate(floor(west, bed, shed), "bed", 1, 4.12, now = 1L)
        val origin = assertNotNull(c.calibration).origin
        val centroid = Polygons.centroid(shed.corners)
        val expected = origin + (centroid - origin) * 1.03
        assertVecEquals(expected, Polygons.centroid(c.room("shed").corners), 1e-9)
        assertSameShape(shed.corners, c.room("shed").corners)
    }

    @Test fun chainsOfDrawnRoomsStayTogether() {
        // Bedroom (scanned) <- drawn closet <- drawn shelf alcove snapped to the closet.
        val bed = scanned("bed", rect(3.0, -2.0, 6.0, 2.0), name = "Bedroom")
        val other = scanned("o", rect(-6.0, -2.0, -2.0, 2.0), name = "Other")
        val closet = drawn("c", rect(6.0 + T, -0.6, 7.0, 0.6), name = "Closet")
        val alcove = drawn("a", rect(7.0 + T, -0.4, 7.6, 0.4), name = "Alcove")
        val c = FloorOps.calibrate(floor(other, bed, closet, alcove), "bed", 1, 4.12, now = 1L)
        assertEquals(T, c.room("c").corners[0].x - c.room("bed").corners[1].x, 1e-9)
        assertEquals(T, c.room("a").corners[0].x - c.room("c").corners[1].x, 1e-9)
        assertSameShape(alcove.corners, c.room("a").corners)
    }

    // ---------------------------------------------------------------- placement frames

    private val sessionRooms = listOf(
        scanned("b1", rect(0.0, 0.0, 3.0, 3.0), session = "s2"),
        scanned("b2", rect(3.0 + T, 0.0, 6.0, 3.0), session = "s2"),
        scanned("b3", rect(6.0 + 2 * T, 0.0, 9.0, 3.0), session = "s2"),
    )

    @Test fun movingOneRoomOfAScanAloneLeavesItsFrame() {
        val f = floor(*sessionRooms.toTypedArray())
        val shift = RigidTransform2(0.0, v(0.0, 1.0))
        val one = FloorOps.transformRooms(f, setOf("b2"), shift)
        assertEquals("b2", one.room("b2").frameId)
        assertNull(one.room("b1").frameId)
        assertNull(one.room("b3").frameId)
        // Two of three moved together share a new frame of their own.
        val two = FloorOps.transformRooms(f, setOf("b1", "b2"), shift)
        assertEquals("frame:b1", two.room("b1").frameId)
        assertEquals("frame:b1", two.room("b2").frameId)
        assertNull(two.room("b3").frameId)
        // The whole scan moved as one keeps its frame.
        val all = FloorOps.transformRooms(f, setOf("b1", "b2", "b3"), shift)
        assertTrue(all.rooms.all { it.frameId == null })
        assertVecEquals(v(0.0, 1.0), all.room("b1").corners[0])
        // Moving b2 alone again from its own frame keeps it; the identity and unknown ids change nothing.
        assertEquals("b2", FloorOps.transformRooms(one, setOf("b2"), shift).room("b2").frameId)
        assertSame(f, FloorOps.transformRooms(f, setOf("b2"), RigidTransform2.IDENTITY))
        assertSame(f, FloorOps.transformRooms(f, setOf("zzz"), shift))
        assertSame(f, FloorOps.transformRooms(f, emptySet(), shift))
    }

    @Test fun snappingOneRoomOfAScanLeavesItsFrameButAGroupSnapKeepsIt() {
        // A second scan of three rooms (not touching each other), its first room 30 cm from the kitchen.
        val kitchen = scanned("k", rect(-4.0, 0.0, -0.3, 3.0), name = "Kitchen", session = "s1")
        val scan = listOf(
            scanned("b1", rect(0.0, 0.0, 3.0, 3.0), session = "s2"),
            scanned("b2", rect(0.0, 5.0, 3.0, 8.0), session = "s2"),
            scanned("b3", rect(5.0, 5.0, 8.0, 8.0), session = "s2"),
        )
        val f = floor(kitchen, *scan.toTypedArray())
        val single = assertNotNull(FloorOps.snapRoom(f, "b1", T))
        assertEquals(-0.3 + T, single.room("b1").corners[0].x, 1e-9)
        assertEquals("b1", single.room("b1").frameId)
        assertNull(single.room("b2").frameId)
        assertSame(f.room("b2"), single.room("b2"))
        val group = assertNotNull(FloorOps.snapGroup(f, "b1", setOf("b1", "b2", "b3"), T))
        assertTrue(group.rooms.all { it.frameId == null })
        // The group moved rigidly: b2 moved exactly as b1 did.
        assertEquals(-0.3 + T, group.room("b1").corners[0].x, 1e-9)
        assertEquals(group.room("b1").corners[0].x, group.room("b2").corners[0].x, 1e-9)
    }

    // ---------------------------------------------------------------- floor axis

    @Test fun floorAxisFollowsTheMajorityOfWallsNotTheirMean() {
        // Two scans of one floor: the main one (5 rooms) square, a second one (3 rooms) turned 37°.
        val main = (0 until 5).map { i -> scanned("m$i", rect(i * 4.2, 0.0, i * 4.2 + 4.0, 4.0)) }
        val turnedScan = (0 until 3).map { i -> turned(scanned("t$i", rect(i * 3.7, 8.0, i * 3.7 + 3.5, 11.5), session = "s2"), 37.0) }
        val axis = assertNotNull(FloorAxis.of(main + turnedScan))
        assertTrue(angleDistance(axis, 0.0) < 1e-9, "axis ${Math.toDegrees(axis)}°")
        // A plain 4θ mean lands between the two and matches neither.
        val all = main + turnedScan
        val plain = RoomSolver.dominantAngle(
            all.flatMap { r -> Polygons.edges(r.corners).map { (it.b - it.a).angle() } },
            all.flatMap { r -> Polygons.edges(r.corners).map { it.length } },
        )
        assertTrue(angleDistance(plain, 0.0) > Math.toRadians(3.0), "plain mean ${Math.toDegrees(plain)}°")
        // Straightening such a floor turns nothing (the main scan is already square).
        val f = floor(*all.toTypedArray())
        assertSame(f, FloorOps.straighten(f))
        // Tilted by 23°, it comes back square.
        val tilted = FloorOps.rotate(f, Math.toRadians(23.0))
        assertEquals(23.0, Math.toDegrees(FloorAxis.tilt(assertNotNull(FloorAxis.of(tilted.rooms)))), 1e-9)
        val back = FloorOps.straighten(tilted)
        for (r in back.rooms.take(5)) for (e in Polygons.edges(r.corners)) assertTrue(offAxis(e.b - e.a) < 1e-9)
        assertNull(FloorAxis.of(emptyList()))
    }

    // ---------------------------------------------------------------- rotate / straighten

    private val house: Floor = floor(
        scanned("a", rect(0.0, 0.0, 4.0, 3.0)),
        scanned("b", rect(4.0 + T, 0.0, 7.0, 3.0)),
        scanned("l", listOf(v(0.0, 3.0 + T), v(7.0, 3.0 + T), v(7.0, 5.0), v(3.0, 5.0), v(3.0, 7.0), v(0.0, 7.0))),
    ).copy(calibration = Calibration(1.02, "a", 0, 4.0, 4.08, 1L, Vec2(2.0, 2.0)))

    private fun allCorners(f: Floor) = f.rooms.flatMap { it.corners }

    @Test fun rotateTurnsTheWholeFloorRigidlyAboutItsCenter() {
        val center = Bounds.of(allCorners(house)).center
        val r = FloorOps.rotate(house, Math.toRadians(23.0))
        val t = RigidTransform2.rotationAround(center, Math.toRadians(23.0))
        for ((i, room) in r.rooms.withIndex()) {
            val src = house.rooms[i]
            room.corners.forEachIndexed { k, p -> assertVecEquals(t.apply(src.corners[k]), p, 1e-12) }
            room.observations.forEachIndexed { k, o ->
                assertVecEquals(t.apply(src.observations[k].viewer), o.viewer, 1e-12)
                assertTrue(o.line.signedDistance(o.viewer) > 0, "interior-left convention kept")
            }
            assertTrue(Polygons.isCounterClockwise(room.corners))
            assertEquals(src.openings, room.openings)
        }
        // Rooms keep their layout relative to each other (unlike rotating each about its own centroid).
        assertSameShape(allCorners(house), allCorners(r))
        assertVecEquals(t.apply(Vec2(2.0, 2.0)), assertNotNull(r.calibration).origin, 1e-12)
        // No-ops.
        assertSame(house, FloorOps.rotate(house, 0.0))
        assertSame(house, FloorOps.rotate(house, Double.NaN))
        val empty = floor()
        assertSame(empty, FloorOps.rotate(empty, 1.0))
    }

    @Test fun rotateRoundTrips() {
        // Quarter turns about the bounds center keep that center, so they undo exactly.
        val back = FloorOps.rotate(FloorOps.rotate(house, PI / 2), -PI / 2)
        for ((i, room) in back.rooms.withIndex()) assertPolygonEquals(house.rooms[i].corners, room.corners, 1e-9)
        assertVecEquals(Vec2(2.0, 2.0), assertNotNull(back.calibration).origin, 1e-9)
        // Any angle on a floor whose bounds are symmetric under rotation (one rectangle) undoes exactly too.
        val single = floor(scanned("a", rect(1.0, 2.0, 5.0, 5.0)))
        val turned = FloorOps.rotate(FloorOps.rotate(single, 0.4), -0.4)
        assertPolygonEquals(single.rooms[0].corners, turned.rooms[0].corners, 1e-9)
        turned.rooms[0].observations.forEachIndexed { k, o -> assertVecEquals(single.rooms[0].observations[k].extentStart, o.extentStart, 1e-9) }
    }

    @Test fun straightenUsesTheSmallestRotation() {
        for (deg in listOf(23.0, -17.0, 44.0, 70.0, 135.0 + 12.0, -100.0)) {
            val tilted = FloorOps.rotate(house, Math.toRadians(deg))
            val straight = FloorOps.straighten(tilted)
            for (room in straight.rooms) for (e in Polygons.edges(room.corners)) {
                assertTrue(offAxis(e.b - e.a) < 1e-9, "$deg°: edge ${e.b - e.a} is axis-aligned")
            }
            // The rotation applied is the smallest one (|angle| <= 45°).
            val before = (tilted.rooms[0].corners[1] - tilted.rooms[0].corners[0]).angle()
            val after = (straight.rooms[0].corners[1] - straight.rooms[0].corners[0]).angle()
            assertTrue(abs(Polygons.normalizeAngle(after - before)) <= PI / 4 + 1e-9, "$deg°")
            assertSameShape(allCorners(tilted), allCorners(straight))
        }
        // Exactly 45° off: still at most 45°.
        val diagonal = FloorOps.straighten(FloorOps.rotate(house, PI / 4))
        assertTrue(diagonal.rooms.all { r -> Polygons.edges(r.corners).all { offAxis(it.b - it.a) < 1e-9 } })
    }

    @Test fun straightenIgnoresAngledWalls() {
        // A bay room with two 30° walls (deliberately angled) must not tilt the result.
        // One corner cut at 30° (a single angled wall, so a plain 4θ mean would be pulled by about 0.2°).
        val bay = scanned(
            "bay",
            listOf(v(0.0, -4.0), v(4.0, -4.0), v(4.0, -T), v(1.0, -T), v(0.0, -T - kotlin.math.tan(Math.toRadians(30.0)))),
        )
        val plain = RoomSolver.dominantAngle(
            (house.rooms + bay).flatMap { r -> Polygons.edges(r.corners).map { (it.b - it.a).angle() } },
            (house.rooms + bay).flatMap { r -> Polygons.edges(r.corners).map { it.length } },
        )
        assertTrue(angleDistance(plain, 0.0) > Math.toRadians(0.1), "the angled wall does bias a plain mean")
        assertTrue(Polygons.isSimple(bay.corners) && Polygons.isCounterClockwise(bay.corners))
        val f = floor(*house.rooms.toTypedArray(), bay)
        val straight = FloorOps.straighten(FloorOps.rotate(f, Math.toRadians(10.0)))
        val a = straight.room("a")
        for (e in Polygons.edges(a.corners)) assertTrue(offAxis(e.b - e.a) < 1e-9, "square room stays exactly square to the page")
    }

    @Test fun straightenNoOps() {
        assertSame(house, FloorOps.straighten(house))
        val empty = floor()
        assertSame(empty, FloorOps.straighten(empty))
        val broken = floor(scanned("x", listOf(v(Double.NaN, 0.0), v(1.0, 0.0))))
        assertSame(broken, FloorOps.straighten(broken))
    }

    // ---------------------------------------------------------------- snapRoom

    private val kitchen = scanned("k", rect(0.0, 0.0, 4.0, 3.0), name = "Kitchen", session = "s1")

    /** A dining room from another scan, placed by hand next to the kitchen: 0.3 m gap, turned 3°. */
    private val dining = turned(scanned("d", rect(4.3, 0.2, 7.3, 3.2), name = "Dining", session = "s2"), 3.0)

    @Test fun snapMakesTheFacingWallsParallelAtOneWallThickness() {
        val f = floor(kitchen, dining)
        val snapped = assertNotNull(FloorOps.snapRoom(f, "d", T))
        val d = snapped.room("d")
        // Dining's west wall (edge 3) is now exactly parallel to the kitchen's east wall and one wall away.
        val west = d.corners[0] - d.corners[3]
        assertTrue(offAxis(west) < 1e-9, "parallel")
        assertEquals(4.0 + T, d.corners[0].x, 1e-9)
        assertEquals(4.0 + T, d.corners[3].x, 1e-9)
        assertSameShape(dining.corners, d.corners)
        assertEquals(dining.openings, d.openings)
        // It turned about the middle of the shared stretch, so it did not slide along the wall.
        val beforeMid = Vec2.lerp(dining.corners[3], dining.corners[0], 0.5).y
        val afterMid = Vec2.lerp(d.corners[3], d.corners[0], 0.5).y
        assertEquals(beforeMid, afterMid, 0.01)
        // Only the snapped room moved.
        assertSame(kitchen, snapped.room("k"))
        // The two scans are now aligned for the pre-export check.
        assertTrue(FloorChecks.check(snapped, com.oroplex.floorplan.core.model.PlanSettings()).none { "scanned separately" in it.message })
    }

    @Test fun snapPullsAnOverlappingRoomOut() {
        val overlapping = moved(scanned("d", rect(3.8, 0.0, 6.8, 3.0), session = "s2"), 0.0, 0.3)
        val f = floor(kitchen, overlapping)
        assertTrue(FloorChecks.overlappingRooms(f).isNotEmpty())
        val snapped = assertNotNull(FloorOps.snapRoom(f, "d", T))
        assertEquals(4.0 + T, snapped.room("d").corners[0].x, 1e-9)
        assertTrue(FloorChecks.overlappingRooms(snapped).isEmpty())
    }

    @Test fun snapReturnsNullWithoutACandidate() {
        assertNull(FloorOps.snapRoom(floor(kitchen, moved(dining, 2.0, 0.0)), "d", T), "too far away")
        assertNull(FloorOps.snapRoom(floor(kitchen, turned(dining, 25.0)), "d", T), "angle too large")
        assertNull(FloorOps.snapRoom(floor(kitchen, moved(dining, 0.0, 6.0)), "d", T), "walls do not face each other")
        assertNull(FloorOps.snapRoom(floor(kitchen), "k", T), "no neighbour")
        assertNull(FloorOps.snapRoom(floor(kitchen, dining), "missing", T), "unknown room")
        assertNull(FloorOps.snapRoom(floor(kitchen, dining), "d", Double.NaN))
        assertNull(FloorOps.snapRoom(floor(kitchen, dining), "d", T, maxDistance = -1.0))
        val broken = drawn("x", listOf(v(0.0, 0.0), v(1.0, 1.0), v(1.0, 0.0), v(0.0, 1.0)))
        assertNull(FloorOps.snapRoom(floor(kitchen, broken), "x", T), "unusable outline")
    }

    @Test fun snapOfAnAlreadySnappedRoomIsANoOp() {
        val f = floor(kitchen, scanned("d", rect(4.0 + T, 0.5, 7.0, 3.5), session = "s2"))
        assertSame(f, FloorOps.snapRoom(f, "d", T))
        // Snapping twice is the same as snapping once.
        val once = assertNotNull(FloorOps.snapRoom(floor(kitchen, dining), "d", T))
        assertSame(once, FloorOps.snapRoom(once, "d", T))
    }

    @Test fun snapPrefersTheLongestSharedWall() {
        // Bedroom tucked into the corner between a long hall wall (gap 0.25 m along 3.9 m) and a closet
        // (almost exactly one wall away, along 1.15 m): the long shared wall wins.
        val hall = scanned("h", rect(0.0, 0.0, 6.0, 1.2), name = "Hall")
        val closet = scanned("c", rect(4.0 + T, 1.2 + T, 6.0, 2.6), name = "Closet")
        val bed = scanned("b", rect(0.0, 1.2 + 0.25, 3.998, 5.0), name = "Bed", session = "s2")
        val snapped = assertNotNull(FloorOps.snapRoom(floor(hall, closet, bed), "b", T))
        assertEquals(1.2 + T, snapped.room("b").corners[0].y, 1e-9, "snapped onto the hall")
    }

    @Test fun snapNeverCreatesAnOverlap() {
        // Snapping to the kitchen would push the dining room into a closet sitting in the gap: it snaps to
        // the closet instead.
        val closet = scanned("c", rect(4.2, 1.0, 4.45, 2.0), name = "Closet")
        val d = scanned("d", rect(4.5, 0.0, 7.5, 3.0), session = "s2")
        val f = floor(kitchen, closet, d)
        val snapped = assertNotNull(FloorOps.snapRoom(f, "d", T))
        val moved = snapped.room("d")
        assertEquals(4.45 + T, moved.corners[0].x, 1e-9)
        for (other in listOf(kitchen, closet)) assertEquals(0.0, PolygonClipping.intersectionArea(moved.corners, other.corners), 1e-9)
        // Wedged between the closet and a room 2 cm to the east: every snap would collide, so none happens.
        val east = scanned("e", rect(7.52, 0.0, 9.0, 3.0), name = "Den")
        assertNull(FloorOps.snapRoom(floor(kitchen, closet, d, east), "d", T))
    }

    // ---------------------------------------------------------------- upsert keeps working with calibration

    @Test fun upsertDoesNotApplyCalibrationByItself() {
        val calibrated = FloorOps.calibrate(mixed, "living", 0, 5.1, now = 1L)
        val raw = scanned("new", rect(20.0, 0.0, 22.0, 2.0))
        val f = FloorOps.upsertRoom(calibrated, raw)
        assertSame(raw, f.room("new"))
        assertNotSame(raw, FloorOps.applyCalibration(calibrated, raw))
    }
}
