package com.oroplex.floorplan.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class FloorSelectionTest {

    // ---------------------------------------------------------------------------------------------
    // Polygon math
    // ---------------------------------------------------------------------------------------------

    @Test
    fun polygonArea_unitSquare() {
        val square = FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertEquals(1.0, FloorSelection.polygonArea(square), 1e-9)
    }

    @Test
    fun polygonArea_isOrientationIndependent() {
        val ccw = floatArrayOf(-1f, -0.5f, 1f, -0.5f, 1f, 0.5f, -1f, 0.5f)
        val cw = floatArrayOf(-1f, 0.5f, 1f, 0.5f, 1f, -0.5f, -1f, -0.5f)
        assertEquals(2.0, FloorSelection.polygonArea(FloatBuffer.wrap(ccw)), 1e-9)
        assertEquals(2.0, FloorSelection.polygonArea(FloatBuffer.wrap(cw)), 1e-9)
    }

    @Test
    fun polygonArea_triangle() {
        val tri = FloatBuffer.wrap(floatArrayOf(0f, 0f, 4f, 0f, 0f, 3f))
        assertEquals(6.0, FloorSelection.polygonArea(tri), 1e-9)
    }

    @Test
    fun polygonArea_degenerateInputsAreZero() {
        assertEquals(0.0, FloorSelection.polygonArea(FloatBuffer.wrap(FloatArray(0))), 0.0)
        assertEquals(0.0, FloorSelection.polygonArea(FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 1f))), 0.0)
        // Odd trailing float is ignored (2.5 vertices -> 2 vertices).
        assertEquals(0.0, FloorSelection.polygonArea(FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 1f, 3f))), 0.0)
    }

    @Test
    fun polygonArea_readsFromPositionWithoutConsuming() {
        val data = floatArrayOf(99f, 99f, 0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f)
        val buffer = FloatBuffer.wrap(data)
        buffer.position(2)
        assertEquals(4.0, FloorSelection.polygonArea(buffer), 1e-9)
        assertEquals(2, buffer.position())
    }

    /** A 4 m x 2 m rectangle centered on the origin, like an ARCore plane polygon in its local frame. */
    private fun rectangle() = FloatBuffer.wrap(floatArrayOf(-2f, -1f, 2f, -1f, 2f, 1f, -2f, 1f))

    @Test
    fun distanceToPolygon_isZeroInsideAndOnTheBoundary() {
        assertEquals(0.0, FloorSelection.distanceToPolygon(rectangle(), 0.0, 0.0), 0.0)
        assertEquals(0.0, FloorSelection.distanceToPolygon(rectangle(), 1.9, -0.9), 0.0)
        assertEquals(0.0, FloorSelection.distanceToPolygon(rectangle(), 2.0, 0.0), 1e-9)
    }

    @Test
    fun distanceToPolygon_measuresToTheNearestEdgeOrCorner() {
        assertEquals(0.5, FloorSelection.distanceToPolygon(rectangle(), 2.5, 0.0), 1e-9)
        assertEquals(1.5, FloorSelection.distanceToPolygon(rectangle(), 0.3, -2.5), 1e-9)
        // Diagonally off a corner: distance to the vertex (3-4-5 triangle scaled by 0.2).
        assertEquals(1.0, FloorSelection.distanceToPolygon(rectangle(), 2.6, 1.8), 1e-6)
    }

    @Test
    fun distanceToPolygon_isOrientationIndependentAndHandlesNonConvexOutlines() {
        val clockwise = FloatBuffer.wrap(floatArrayOf(-2f, 1f, 2f, 1f, 2f, -1f, -2f, -1f))
        assertEquals(0.0, FloorSelection.distanceToPolygon(clockwise, 0.0, 0.0), 0.0)
        assertEquals(0.5, FloorSelection.distanceToPolygon(clockwise, -2.5, 0.0), 1e-9)
        // L-shape: the notch (upper right quadrant) is outside.
        val l = FloatBuffer.wrap(floatArrayOf(0f, 0f, 2f, 0f, 2f, 1f, 1f, 1f, 1f, 2f, 0f, 2f))
        assertEquals(0.0, FloorSelection.distanceToPolygon(l, 0.5, 1.5), 0.0)
        assertEquals(0.5, FloorSelection.distanceToPolygon(l, 1.5, 1.5), 1e-9)
    }

    @Test
    fun distanceToPolygon_unknownIsNeverNear() {
        val line = FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 1f))
        assertEquals(Double.POSITIVE_INFINITY, FloorSelection.distanceToPolygon(line, 0.0, 0.0), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, FloorSelection.distanceToPolygon(rectangle(), Double.NaN, 0.0), 0.0)
        val broken = FloatBuffer.wrap(floatArrayOf(-2f, -1f, Float.NaN, -1f, 2f, 1f, -2f, 1f))
        assertEquals(Double.POSITIVE_INFINITY, FloorSelection.distanceToPolygon(broken, 0.0, 0.0), 0.0)
        assertFalse(FloorCandidate(0.0, 3.0, Double.POSITIVE_INFINITY).isNearUser)
        assertFalse(FloorCandidate(0.0, 3.0, Double.NaN).isNearUser)
    }

    @Test
    fun distanceToPolygon_readsFromPositionWithoutConsuming() {
        val buffer = FloatBuffer.wrap(floatArrayOf(99f, 99f, -2f, -1f, 2f, -1f, 2f, 1f, -2f, 1f))
        buffer.position(2)
        assertEquals(0.0, FloorSelection.distanceToPolygon(buffer, 0.0, 0.0), 0.0)
        assertEquals(2, buffer.position())
    }

    // ---------------------------------------------------------------------------------------------
    // Session-wide selection (planes whose distance to the user is unknown or large)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun isInFloorRange_requiresPlausibleDrop() {
        assertTrue(FloorSelection.isInFloorRange(planeY = -1.4, cameraY = 0.0))
        assertTrue(FloorSelection.isInFloorRange(planeY = -0.8, cameraY = 0.0))
        assertTrue(FloorSelection.isInFloorRange(planeY = -2.2, cameraY = 0.0))
        assertFalse("table 0.65 m below the phone", FloorSelection.isInFloorRange(planeY = -0.65, cameraY = 0.0))
        assertFalse("lower storey / reflection", FloorSelection.isInFloorRange(planeY = -2.3, cameraY = 0.0))
        assertFalse(FloorSelection.isInFloorRange(planeY = 0.2, cameraY = 0.0))
        assertFalse(FloorSelection.isInFloorRange(planeY = Double.NaN, cameraY = 0.0))
        assertFalse(FloorSelection.isInFloorRange(planeY = -1.0, cameraY = Double.POSITIVE_INFINITY))
    }

    @Test
    fun select_lowestPlaneBeatsLargerBed() {
        // Phone at 1.40 m: bed top at 0.55 m (0.85 m below, big), floor patch at 0 (small but >= min area).
        val cameraY = 1.40
        val bed = FloorCandidate(y = 0.55, area = 3.2)
        val floor = FloorCandidate(y = 0.0, area = 0.9)
        assertEquals(floor, FloorSelection.select(listOf(bed, floor), cameraY))
        assertEquals(floor, FloorSelection.select(listOf(floor, bed), cameraY))
    }

    @Test
    fun select_ignoresIslandsAndTablesTooCloseBelowThePhone() {
        val cameraY = 1.45
        val island = FloorCandidate(y = 0.90, area = 2.5) // 0.55 m below the phone
        val table = FloorCandidate(y = 0.75, area = 1.8) // 0.70 m below the phone
        assertNull(FloorSelection.select(listOf(island, table), cameraY))
        val floor = FloorCandidate(y = 0.0, area = 0.6)
        assertEquals(floor, FloorSelection.select(listOf(island, table, floor), cameraY))
    }

    @Test
    fun select_smallLowPatchesDoNotWin() {
        // A 0.3 m² glossy-floor reflection below the floor must not become the floor.
        val cameraY = 0.0
        val reflection = FloorCandidate(y = -1.9, area = 0.3)
        val floor = FloorCandidate(y = -1.40, area = 4.0)
        assertEquals(floor, FloorSelection.select(listOf(reflection, floor), cameraY))
        assertNull(FloorSelection.select(listOf(reflection), cameraY))
    }

    @Test
    fun select_largestPieceOfTheLowestFloorBacksTheEstimate() {
        val cameraY = 0.0
        val floorPatch = FloorCandidate(y = -1.43, area = 0.7)
        val bigFloor = FloorCandidate(y = -1.40, area = 6.5)
        val rug = FloorCandidate(y = -1.37, area = 2.0)
        val step = FloorCandidate(y = -1.20, area = 9.0) // > SAME_FLOOR_TOLERANCE above: ignored
        assertEquals(bigFloor, FloorSelection.select(listOf(floorPatch, step, rug, bigFloor), cameraY))
    }

    @Test
    fun select_ignoresNonFiniteAreas() {
        val bad = FloorCandidate(y = -1.6, area = Double.NaN)
        val good = FloorCandidate(y = -1.4, area = 0.5)
        assertEquals(good, FloorSelection.select(listOf(bad, good), cameraY = 0.0))
    }

    @Test
    fun select_returnsNullWithoutCandidates() {
        assertNull(FloorSelection.select(emptyList(), cameraY = 0.0))
        assertNull(FloorSelection.select(listOf(FloorCandidate(y = -0.2, area = 3.0)), cameraY = 0.0))
    }

    // ---------------------------------------------------------------------------------------------
    // The floor under the user (ARX-1, CAPX-3)
    // ---------------------------------------------------------------------------------------------

    private val phone = 1.40

    @Test
    fun local_sunkenLivingRoomSeenThroughADoorwayNeverWins() {
        // Standing in the kitchen (y = 0); the living room three steps down is in view.
        val kitchen = FloorCandidate(y = 0.0, area = 4.0, distance = 0.0)
        val living = FloorCandidate(y = -0.45, area = 9.0, distance = 2.6)
        val planes = listOf(living, kitchen)
        assertEquals(kitchen, FloorSelection.selectLocal(planes, phone))
        // The session-wide estimate stays at the user's level too.
        assertEquals(kitchen, FloorSelection.select(planes, phone))
        assertEquals(FloorEstimate(0.0, 4.0), FloorSelection.next(null, planes, phone))
        // Without the kitchen plane nothing is local, and only then the lowest plane anywhere is the estimate.
        assertNull(FloorSelection.selectLocal(listOf(living), phone))
        assertEquals(living, FloorSelection.select(listOf(living), phone))
    }

    @Test
    fun local_aSingleStepDownIsAnotherLevelToo() {
        // 0.18 m step down into a sunroom: below both old 0.2 m margins, so it used to win silently.
        val hall = FloorCandidate(y = 0.0, area = 3.0, distance = 0.3)
        val sunroom = FloorCandidate(y = -0.18, area = 5.0, distance = 1.8)
        assertEquals(hall, FloorSelection.selectLocal(listOf(sunroom, hall), phone))
        assertEquals(hall, FloorSelection.select(listOf(sunroom, hall), phone))
    }

    @Test
    fun local_lowestStillBeatsFurnitureNextToTheUser() {
        val bed = FloorCandidate(y = 0.55, area = 3.2, distance = 0.2)
        val floor = FloorCandidate(y = 0.0, area = 0.9, distance = 0.6)
        assertEquals(floor, FloorSelection.selectLocal(listOf(bed, floor), phone))
        assertEquals(floor, FloorSelection.select(listOf(bed, floor), phone))
    }

    @Test
    fun local_needsAPlaneWithinTheRadius() {
        val near = FloorCandidate(y = 0.0, area = 2.0, distance = FloorSelection.LOCAL_RADIUS)
        val far = FloorCandidate(y = 0.0, area = 2.0, distance = FloorSelection.LOCAL_RADIUS + 0.01)
        assertEquals(near, FloorSelection.selectLocal(listOf(near), phone))
        assertNull(FloorSelection.selectLocal(listOf(far), phone))
        // Too small, out of range or vetoed planes are never the local floor.
        assertNull(FloorSelection.selectLocal(listOf(FloorCandidate(0.0, 0.3, 0.0)), phone))
        assertNull(FloorSelection.selectLocal(listOf(FloorCandidate(0.9, 3.0, 0.0)), phone))
        assertNull(FloorSelection.selectLocal(listOf(near), phone) { true })
    }

    @Test
    fun local_otherPatchesOfTheSameFloorStillBackTheSessionEstimate() {
        // The floor continues beyond the user's patch, 5 cm lower by ARCore's reckoning: same floor.
        val here = FloorCandidate(y = 0.0, area = 1.2, distance = 0.0)
        val there = FloorCandidate(y = -0.05, area = 7.0, distance = 2.0)
        assertEquals(here, FloorSelection.selectLocal(listOf(here, there), phone))
        assertEquals(there, FloorSelection.select(listOf(here, there), phone))
    }

    // ---------------------------------------------------------------------------------------------
    // Session-wide estimate over time
    // ---------------------------------------------------------------------------------------------

    @Test
    fun next_keepsTheEstimateWhenNothingQualifies() {
        val previous = FloorEstimate(y = 0.0, area = 3.0)
        // Crouching: the floor is only 0.6 m below the phone, no candidate.
        assertEquals(previous, FloorSelection.next(previous, emptyList(), cameraY = 0.6))
        assertNull(FloorSelection.next(null, emptyList(), cameraY = 1.4))
    }

    @Test
    fun next_neverRaisesTheFloorOntoFurniture() {
        val previous = FloorEstimate(y = 0.0, area = 3.0)
        // The floor plane stopped tracking; only the bed is left.
        val bed = FloorCandidate(y = 0.55, area = 3.2)
        assertEquals(previous, FloorSelection.next(previous, listOf(bed), cameraY = 1.40))
        // ... also when the user stands right next to it: beside furniture, not on it.
        val bedBeside = FloorCandidate(y = 0.55, area = 3.2, distance = 0.3)
        assertEquals(previous, FloorSelection.next(previous, listOf(bedBeside), cameraY = 1.40))
        // Phone raised to the ceiling: the floor is 2.3 m below (out of range) but a table is a candidate.
        val table = FloorCandidate(y = 0.75, area = 1.5)
        assertEquals(previous, FloorSelection.next(previous, listOf(table), cameraY = 2.30))
    }

    @Test
    fun next_followsLowerFloorsAndSmallRefinements() {
        val bed = FloorEstimate(y = 0.55, area = 3.2) // locked onto furniture before the floor was seen
        val floor = FloorCandidate(y = 0.0, area = 0.8)
        assertEquals(FloorEstimate(0.0, 0.8), FloorSelection.next(bed, listOf(floor), cameraY = 1.40))

        val previous = FloorEstimate(y = 0.0, area = 1.2)
        val refined = FloorCandidate(y = 0.012, area = 2.5)
        assertEquals(FloorEstimate(0.012, 2.5), FloorSelection.next(previous, listOf(refined), cameraY = 1.40))
    }

    @Test
    fun next_acceptsAHigherFloorAfterGoingUpstairs() {
        val downstairs = FloorEstimate(y = 0.0, area = 8.0)
        val upstairs = FloorCandidate(y = 2.75, area = 5.0)
        // Phone 1.4 m above the upper floor: the old floor is 4.15 m below, no longer plausible.
        assertEquals(FloorEstimate(2.75, 5.0), FloorSelection.next(downstairs, listOf(upstairs), cameraY = 4.15))
    }

    @Test
    fun next_walkingDownALevelTheLocalPickReplacesTheKeptFloor() {
        // Scanned the kitchen (y = 0), then walked down into the living room.
        val kept = FloorEstimate(y = 0.0, area = 4.0)
        val cameraY = -0.45 + 1.40
        val living = FloorCandidate(y = -0.45, area = 9.0, distance = 0.0)
        val kitchen = FloorCandidate(y = 0.0, area = 4.0, distance = 2.2)
        val planes = listOf(kitchen, living)
        assertEquals(living, FloorSelection.selectLocal(planes, cameraY))
        assertEquals(FloorEstimate(-0.45, 9.0), FloorSelection.next(kept, planes, cameraY))
    }

    @Test
    fun next_walkingUpALevelTheFloorTheUserStandsOnReplacesTheKeptFloor() {
        // Scanned the sunken living room first (y = -0.45), then walked up into the dining room.
        val kept = FloorEstimate(y = -0.45, area = 9.0)
        val living = FloorCandidate(y = -0.45, area = 9.0, distance = 2.4)
        val diningUnderUser = FloorCandidate(y = 0.0, area = 3.0, distance = 0.0)
        assertEquals(FloorEstimate(0.0, 3.0), FloorSelection.next(kept, listOf(living, diningUnderUser), phone))
        // While the dining-room plane is only in front of the user, the session estimate is not raised yet
        // (it could still be furniture), but the local floor already says "dining room".
        val diningAhead = FloorCandidate(y = 0.0, area = 3.0, distance = 0.6)
        assertEquals(kept, FloorSelection.next(kept, listOf(living, diningAhead), phone))
        assertEquals(diningAhead, FloorSelection.selectLocal(listOf(living, diningAhead), phone))
    }

    // ---------------------------------------------------------------------------------------------
    // Furniture veto and auto-lock gate (ARX-2)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun isContradicted_onlyAmbiguousPlanesWithEnoughEvidence() {
        // Tall user, phone at 1.65 m, bed top at 0.55 m (1.10 m below: ambiguous).
        assertTrue(FloorSelection.isContradicted(planeY = 0.55, cameraY = 1.65, hitsWellBelow = 5))
        assertFalse(FloorSelection.isContradicted(planeY = 0.55, cameraY = 1.65, hitsWellBelow = 4))
        // A single burst of readings (one time slot) is not enough for a session-long veto.
        assertFalse(FloorSelection.isContradicted(planeY = 0.55, cameraY = 1.65, hitsWellBelow = 9, slotsWellBelow = 1))
        // A floor seen from a normal height is never vetoed (glossy-floor reflections look "lower").
        assertFalse(FloorSelection.isContradicted(planeY = 0.0, cameraY = 1.40, hitsWellBelow = 30))
        assertFalse(FloorSelection.isContradicted(planeY = Double.NaN, cameraY = 1.40, hitsWellBelow = 30))
    }

    @Test
    fun select_vetoedBedLosesBeforeTheFloorPlaneExists() {
        val cameraY = 1.65
        val bed = FloorCandidate(y = 0.55, area = 3.0)
        val evidence = FloorEvidence()
        repeat(6) { evidence.add(0.01 * it, nowNs = it * 33_000_000L) }
        val now = 200_000_000L
        val contradicted = { y: Double ->
            FloorSelection.isContradicted(y, cameraY, evidence.countBelow(y - FloorSelection.MAX_RAISE, now))
        }
        assertEquals(bed, FloorSelection.select(listOf(bed), cameraY))
        assertNull(FloorSelection.select(listOf(bed), cameraY, contradicted))
        // An estimate that turned out to be the bed is withdrawn rather than kept.
        assertNull(FloorSelection.next(FloorEstimate(0.55, 3.0), listOf(bed), cameraY, contradicted))
        // Once the floor plane appears it wins as usual.
        val floor = FloorCandidate(y = 0.0, area = 0.7)
        assertEquals(FloorEstimate(0.0, 0.7), FloorSelection.next(null, listOf(bed, floor), cameraY, contradicted))
    }

    @Test
    fun autoLockBlock_needsALargeLocalUncontradictedFloor() {
        val floor = FloorCandidate(y = 0.0, area = 2.5, distance = 0.4)
        assertEquals(AutoLockBlock.NONE, FloorSelection.autoLockBlock(floor, phone, hitsWellBelow = 0))
        assertEquals(AutoLockBlock.NONE, FloorSelection.autoLockBlock(floor, phone, hitsWellBelow = 1))
        assertEquals(AutoLockBlock.NO_LOCAL_FLOOR, FloorSelection.autoLockBlock(null, phone, hitsWellBelow = 0))
        assertEquals(AutoLockBlock.NO_LOCAL_FLOOR, FloorSelection.autoLockBlock(floor, Double.NaN, hitsWellBelow = 0))
        assertEquals(AutoLockBlock.TOO_SMALL, FloorSelection.autoLockBlock(floor.copy(area = 0.8), phone, 0))
        assertEquals(AutoLockBlock.TOO_SMALL, FloorSelection.autoLockBlock(floor.copy(area = Double.NaN), phone, 0))
        // Two floor-like hits well below it are enough to leave the decision to the user, at ANY drop:
        // the old 1.2 m exemption let a sofa 1.25 m below a tall user's phone lock by itself.
        assertEquals(AutoLockBlock.HITS_BELOW, FloorSelection.autoLockBlock(floor, phone, hitsWellBelow = 2))
        val sofa = FloorCandidate(y = 0.42, area = 2.0, distance = 0.0)
        assertEquals(AutoLockBlock.HITS_BELOW, FloorSelection.autoLockBlock(sofa, cameraY = 1.67, hitsWellBelow = 2))
        // A height that was vetoed as furniture earlier is never locked without the user, however good it looks.
        assertEquals(AutoLockBlock.VETOED_BEFORE, FloorSelection.autoLockBlock(floor, phone, hitsWellBelow = 0, vetoedBefore = true))
        assertEquals(AutoLockBlock.NO_LOCAL_FLOOR, FloorSelection.autoLockBlock(null, phone, hitsWellBelow = 0, vetoedBefore = true))
    }

    @Test
    fun autoLockBlock_aPlaneAtFurnitureHeightMustBeUnderThePhone() {
        // Bed top 1.05 m below the phone, the user standing beside it, no floor evidence yet.
        val bedBeside = FloorCandidate(y = 0.50, area = 2.8, distance = 0.3)
        assertEquals(AutoLockBlock.NOT_UNDER_USER, FloorSelection.autoLockBlock(bedBeside, cameraY = 1.55, hitsWellBelow = 0))
        // A real floor seen from a low phone locks once the phone is above its plane.
        val lowPhoneFloor = FloorCandidate(y = 0.0, area = 2.8, distance = 0.0)
        assertEquals(AutoLockBlock.NONE, FloorSelection.autoLockBlock(lowPhoneFloor, cameraY = 1.10, hitsWellBelow = 0))
        // From a normal height the floor a little ahead of the user is enough.
        val ahead = FloorCandidate(y = 0.0, area = 2.8, distance = 0.7)
        assertEquals(AutoLockBlock.NONE, FloorSelection.autoLockBlock(ahead, cameraY = 1.25, hitsWellBelow = 0))
    }

    // ---------------------------------------------------------------------------------------------
    // Evidence memory
    // ---------------------------------------------------------------------------------------------

    private val second = 1_000_000_000L
    private val frame = 33_333_333L

    @Test
    fun floorEvidence_countsRecentHitsBelowAHeight() {
        val evidence = FloorEvidence(maxAgeNs = 10 * second)
        evidence.add(0.0, 0)
        evidence.add(0.02, 1 * second)
        evidence.add(0.6, 2 * second)
        evidence.add(Double.NaN, 3 * second) // ignored
        assertEquals(2, evidence.countBelow(0.3, 3 * second))
        assertEquals(3, evidence.countBelow(1.0, 3 * second))
        assertEquals(2, evidence.slotsBelow(0.3, 3 * second))
        // The first entry expires after 10 s; nothing else removes it.
        assertEquals(2, evidence.countBelow(0.3, 10 * second))
        assertEquals(1, evidence.countBelow(0.3, 10 * second + 1))
        assertEquals(0, evidence.countBelow(0.3, 11 * second + 1))
        assertEquals(0, evidence.countBelow(Double.NaN, 3 * second))
    }

    @Test
    fun floorEvidence_aBurstOfFurnitureHitsDoesNotEvictFloorEvidence() {
        // 30 floor hits, then 30 bed hits, all within 2 s at 30 fps: the old 30-entry FIFO forgot the floor.
        val evidence = FloorEvidence()
        var t = 0L
        repeat(30) {
            evidence.add(0.0 + 0.002 * (it % 5), t)
            t += frame
        }
        repeat(30) {
            evidence.add(0.45 + 0.002 * (it % 5), t)
            t += frame
        }
        assertEquals(30, evidence.countBelow(0.45 - FloorSelection.MAX_RAISE, t))
        assertTrue(evidence.slotsBelow(0.45 - FloorSelection.MAX_RAISE, t) >= 4)
        // Minutes of sweeping over the bed later, only time has removed the floor readings.
        repeat(600) {
            evidence.add(0.45, t)
            t += frame
        }
        assertEquals(0, evidence.countBelow(0.45 - FloorSelection.MAX_RAISE, t))
    }

    @Test
    fun floorEvidence_keepsTenSecondsAtCameraFrameRates() {
        for (fps in listOf(30, 60)) {
            val evidence = FloorEvidence()
            val step = second / fps
            var t = 0L
            // One second of floor, then nine seconds of bed.
            repeat(fps) {
                evidence.add(0.0, t)
                t += step
            }
            repeat(9 * fps - 1) {
                evidence.add(0.5, t)
                t += step
            }
            assertTrue("$fps fps", evidence.countBelow(0.25, t) >= 15)
        }
    }

    @Test
    fun floorEvidence_toleratesOddInputs() {
        val evidence = FloorEvidence()
        evidence.add(0.0, -5 * second) // negative monotonic times are legal
        assertEquals(1, evidence.countBelow(0.3, -5 * second + 1))
        // A sample "from the future" (clock went backwards) is not counted.
        assertEquals(0, evidence.countBelow(0.3, -6 * second))
        val disabled = FloorEvidence(maxAgeNs = 10 * second, slotNs = 0, perSlot = 0)
        disabled.add(0.0, 0)
        assertEquals(0, disabled.countBelow(1.0, 0))
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------------

    @Test
    fun floorReport_keyIgnoresJitterButSeesRealChanges() {
        val state = FloorState(FloorEstimate(0.0, 3.0), FloorEstimate(0.0, 3.0), AutoLockBlock.NONE)
        val key = FloorReport.key(state, vetoChanges = 0)
        assertEquals(key, FloorReport.key(state.copy(global = FloorEstimate(0.004, 3.4)), 0))
        assertFalse(key == FloorReport.key(state.copy(local = FloorEstimate(0.18, 3.0)), 0))
        assertFalse(key == FloorReport.key(state.copy(autoLockBlock = AutoLockBlock.HITS_BELOW), 0))
        assertFalse(key == FloorReport.key(state.copy(local = null), 0))
        assertFalse(key == FloorReport.key(state, vetoChanges = 1))
    }

    @Test
    fun floorReport_linesAreCompletePlainAscii() {
        val state = FloorState(FloorEstimate(-1.412, 3.4), FloorEstimate(-1.412, 3.4), AutoLockBlock.HITS_BELOW)
        val planes = listOf(
            FloorCandidate(-1.412, 3.4, 0.0),
            FloorCandidate(-0.86, 2.8, 0.4),
            FloorCandidate(-1.9, 6.0),
        )
        val line = FloorReport.state(state, cameraY = 0.02, candidates = planes, vetoed = { it > -1.0 }, hitsBelowLocal = 3)
        assertTrue(line, line.contains("global y=-1.41 (3.4 m2)"))
        assertTrue(line, line.contains("auto-lock no (HITS_BELOW)"))
        assertTrue(line, line.contains("1.43 m above local"))
        assertTrue(line, line.contains("floor hits below local: 3"))
        assertTrue(line, line.contains("[y=-1.41 3.4 m2 under phone]"))
        assertTrue(line, line.contains("[y=-0.86 2.8 m2 0.4 m away VETOED]"))
        assertTrue(line, line.contains("distance n/a"))
        val veto = FloorReport.veto(FloorVeto(y = -0.86, dropBelowCamera = 0.88, hitsBelow = 7))
        assertTrue(veto, veto.startsWith("vetoed height y=-0.86") && veto.contains("7 floor-like hits"))
        val lifted = FloorReport.veto(FloorVeto(y = -0.86, dropBelowCamera = 1.41, hitsBelow = 0, lifted = true))
        assertTrue(lifted, lifted.startsWith("veto on height y=-0.86 lifted") && lifted.contains("1.41 m below"))
        val empty = FloorReport.state(FloorState(), 0.0, emptyList(), { false }, 0)
        assertTrue(empty, empty.contains("global none") && empty.contains("NO_LOCAL_FLOOR") && empty.contains("none in floor range"))
        for (text in listOf(line, veto, lifted, empty)) assertTrue(text, text.all { it.code in 32..126 })
    }
}
