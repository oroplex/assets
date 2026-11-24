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
        // The drawn closet keeps its typed size exactly.
        assertSame(closet, c.room("closet"))
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
        assertSame(closet, twice.room("closet"))
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
