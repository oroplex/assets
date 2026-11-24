package com.oroplex.floorplan.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class FloorSelectionTest {

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
    fun isContradicted_onlyAmbiguousPlanesWithEnoughEvidence() {
        // Tall user, phone at 1.65 m, bed top at 0.55 m (1.10 m below: ambiguous).
        assertTrue(FloorSelection.isContradicted(planeY = 0.55, cameraY = 1.65, hitsWellBelow = 5))
        assertFalse(FloorSelection.isContradicted(planeY = 0.55, cameraY = 1.65, hitsWellBelow = 4))
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
    fun floorEvidence_countsRecentHitsBelowAHeight() {
        val s = 1_000_000_000L
        val evidence = FloorEvidence(capacity = 4, maxAgeNs = 10 * s)
        evidence.add(0.0, 0)
        evidence.add(0.02, 1 * s)
        evidence.add(0.6, 2 * s)
        evidence.add(Double.NaN, 3 * s) // ignored
        assertEquals(2, evidence.countBelow(0.3, 3 * s))
        assertEquals(3, evidence.countBelow(1.0, 3 * s))
        // The first entry expires after 10 s.
        assertEquals(1, evidence.countBelow(0.3, 10 * s + 1))
        // Ring buffer: the oldest entries are overwritten.
        evidence.add(0.9, 4 * s)
        evidence.add(0.9, 5 * s)
        assertEquals(1, evidence.countBelow(0.3, 5 * s))
        assertEquals(0, evidence.countBelow(Double.NaN, 5 * s))
    }
}
