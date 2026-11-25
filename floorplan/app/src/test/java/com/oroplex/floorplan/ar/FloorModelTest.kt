package com.oroplex.floorplan.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame-by-frame scenarios for the floor tracker's state machine (ARX-1, ARX-2, CAPX-3): what
 * [ArFrameState.floorY], [ArFrameState.localFloorY] and [ArFrameState.floorAutoLockable] come out as while a
 * user walks through a home. Heights are world Y in meters; the floor the user starts on is y = 0.
 */
class FloorModelTest {
    /** Feeds camera frames (30 fps) with the same planes and crosshair hit; returns the last state. */
    private class Walk(val model: FloorModel = FloorModel()) {
        var nowNs = 0L
        fun frames(
            count: Int,
            cameraY: Double,
            planes: List<FloorCandidate>,
            hitY: Double? = null,
            frameNs: Long = 33_333_333L,
        ): FloorState {
            var state = model.state
            repeat(count) {
                state = model.update(planes, cameraY, hitY, nowNs)
                nowNs += frameNs
            }
            return state
        }
    }

    @Test
    fun sunkenLivingRoomSeenThroughADoorway() {
        // Kitchen at y = 0, living room three steps down (0.45 m), open between them. Phone at 1.40 m.
        val walk = Walk()
        val kitchen = FloorCandidate(y = 0.0, area = 4.0, distance = 0.0)
        val living = FloorCandidate(y = -0.45, area = 9.0, distance = 2.5)

        // The living-room plane is tracked first (the user looked that way), the kitchen plane a moment later.
        var state = walk.frames(10, 1.40, listOf(living))
        assertNull("another level is not the floor under the user", state.local)
        assertFalse(state.autoLockable)
        assertEquals(-0.45, state.global!!.y, 1e-9)

        state = walk.frames(30, 1.40, listOf(living, kitchen))
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals("the session estimate comes back up to the floor the user stands on", 0.0, state.global!!.y, 1e-9)
        assertTrue(state.autoLockable)

        // Aiming the crosshair down the steps at the living-room floor: the kitchen stays the local floor
        // and is never vetoed (it is 1.4 m below the phone), but auto-lock leaves the decision to the user.
        state = walk.frames(30, 1.40, listOf(living, kitchen), hitY = -0.45)
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals(AutoLockBlock.HITS_BELOW, state.autoLockBlock)
        assertEquals(0, walk.model.vetoCount)
    }

    @Test
    fun stepDownOfEighteenCentimeters() {
        // Hall at y = 0, sunroom one step (0.18 m) lower. Both old 0.2 m margins missed this silently.
        val walk = Walk()
        val hall = FloorCandidate(y = 0.0, area = 3.0, distance = 0.2)
        val sunroom = FloorCandidate(y = -0.18, area = 6.0, distance = 1.9)
        // Hits on the sunroom floor are less than MAX_RAISE below the hall: no contradiction.
        val state = walk.frames(45, 1.40, listOf(sunroom, hall), hitY = -0.18)
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals(0.0, state.global!!.y, 1e-9)
        assertTrue(state.autoLockable)
    }

    @Test
    fun bedSeenBeforeTheFloor() {
        // Plain carpet (no floor plane yet), textured duvet: low bed top at 0.45 m, phone at 1.55 m.
        val walk = Walk()
        val bed = FloorCandidate(y = 0.45, area = 2.8, distance = 0.3)

        // 1. Entering with the phone aimed at the bed: no floor evidence exists, so no veto can know better.
        //    The bed is "the lowest plane near the user", but it must not lock by itself: it is at furniture
        //    height and the user stands beside it.
        var state = walk.frames(60, 1.55, listOf(bed), hitY = 0.45)
        assertEquals(0.45, state.local!!.y, 1e-9)
        assertEquals(AutoLockBlock.NOT_UNDER_USER, state.autoLockBlock)

        // 2. Half a second of crosshair on the floor beside the bed: the bed's height is vetoed.
        state = walk.frames(15, 1.55, listOf(bed), hitY = 0.0)
        assertNull(state.local)
        assertNull("an estimate that turned out to be furniture is withdrawn", state.global)
        assertEquals(1, walk.model.vetoCount)
        assertTrue(walk.model.isVetoed(0.45))

        // 3. A long sweep over the bed, far longer than the evidence memory: the veto is sticky.
        state = walk.frames(30 * 20, 1.55, listOf(bed), hitY = 0.45)
        assertNull(state.local)
        assertNull(state.global)
        assertFalse(state.autoLockable)

        // 4. The floor plane finally appears in front of the user: it is the floor, and it may lock.
        val floor = FloorCandidate(y = 0.0, area = 1.6, distance = 0.5)
        state = walk.frames(5, 1.55, listOf(bed, floor), hitY = 0.0)
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals(0.0, state.global!!.y, 1e-9)
        assertTrue(state.autoLockable)
    }

    @Test
    fun thirtyFloorHitsThenThirtyBedHitsInTwoSeconds() {
        // The review's wash-out: sweep the floor for a second, then the bed for a second.
        val walk = Walk()
        val bed = FloorCandidate(y = 0.45, area = 2.8, distance = 0.3)
        walk.frames(30, 1.55, listOf(bed), hitY = 0.0)
        val state = walk.frames(30, 1.55, listOf(bed), hitY = 0.45)
        assertTrue("the bed must still be vetoed", walk.model.isVetoed(0.45))
        assertNull(state.local)
        assertNull(state.global)
        assertFalse(state.autoLockable)
    }

    @Test
    fun aSingleBurstOfLowReadingsDoesNotVetoAFloorForTheSession() {
        // A short user's floor is 1.10 m below the phone, its plane half a meter ahead. Six consecutive
        // bad depth frames (a reflection in a glossy floor) read 0.5 m low.
        val walk = Walk()
        val floorAhead = FloorCandidate(y = 0.0, area = 3.0, distance = 0.5)
        walk.frames(6, 1.10, listOf(floorAhead), hitY = -0.5)
        assertEquals(0, walk.model.vetoCount)
        var state = walk.frames(1, 1.10, listOf(floorAhead))
        assertEquals(0.0, state.local!!.y, 1e-9)
        // While those readings are fresh auto-lock waits.
        assertEquals(AutoLockBlock.HITS_BELOW, state.autoLockBlock)
        // Ten seconds later they are forgotten; from this low the plane must be under the phone to lock.
        state = walk.frames(30 * 11, 1.10, listOf(floorAhead))
        assertEquals(AutoLockBlock.NOT_UNDER_USER, state.autoLockBlock)
        state = walk.frames(1, 1.10, listOf(floorAhead.copy(distance = 0.0)))
        assertTrue(state.autoLockable)
    }

    @Test
    fun theFloorTheUserStandsOnIsNeverVetoed() {
        // A short user on the upper level (floor 1.10 m below the phone, phone above its plane) aims down
        // the steps at the sunken living room for two seconds: plenty of floor-like hits 0.45 m lower.
        val walk = Walk()
        val upper = FloorCandidate(y = 0.0, area = 4.0, distance = 0.0)
        val lower = FloorCandidate(y = -0.45, area = 9.0, distance = 2.0)
        var state = walk.frames(60, 1.10, listOf(upper, lower), hitY = -0.45)
        assertEquals(0, walk.model.vetoCount)
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals(0.0, state.global!!.y, 1e-9)
        // Auto-lock still leaves it to the user while that evidence is fresh.
        assertEquals(AutoLockBlock.HITS_BELOW, state.autoLockBlock)
        state = walk.frames(30 * 11, 1.10, listOf(upper, lower))
        assertTrue(state.autoLockable)
    }

    @Test
    fun sofaFarBelowATallUsersPhoneDoesNotAutoLockWithFloorEvidenceBelowIt() {
        // Sofa seat at 0.42 m, phone at 1.67 m: 1.25 m below, outside the veto's furniture range.
        val walk = Walk()
        val sofa = FloorCandidate(y = 0.42, area = 2.0, distance = 0.0)
        var state = walk.frames(20, 1.67, listOf(sofa), hitY = 0.0)
        assertEquals(0, walk.model.vetoCount)
        assertEquals(AutoLockBlock.HITS_BELOW, state.autoLockBlock)
        // A few sofa readings afterwards do not wash the floor evidence out.
        state = walk.frames(60, 1.67, listOf(sofa), hitY = 0.42)
        assertEquals(AutoLockBlock.HITS_BELOW, state.autoLockBlock)
    }

    @Test
    fun furnitureThatIsNotNearTheUserNeverAutoLocks() {
        // A bed across the room, nothing else tracked, no evidence at all.
        val walk = Walk()
        val bed = FloorCandidate(y = 0.5, area = 3.0, distance = 2.2)
        val state = walk.frames(90, 1.60, listOf(bed))
        assertNull(state.local)
        assertEquals(AutoLockBlock.NO_LOCAL_FLOOR, state.autoLockBlock)
        // The session-wide estimate still reports the only plane there is (for "floor found" feedback).
        assertEquals(0.5, state.global!!.y, 1e-9)
    }

    @Test
    fun smallFloorPatchWaitsUntilItHasGrown() {
        val walk = Walk()
        var state = walk.frames(10, 1.40, listOf(FloorCandidate(0.0, 0.7, 0.3)))
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals(AutoLockBlock.TOO_SMALL, state.autoLockBlock)
        state = walk.frames(10, 1.40, listOf(FloorCandidate(0.0, 1.3, 0.3)))
        assertTrue(state.autoLockable)
    }

    @Test
    fun walkingDownALevel() {
        val walk = Walk()
        val kitchenUnderUser = FloorCandidate(y = 0.0, area = 4.0, distance = 0.0)
        val livingAhead = FloorCandidate(y = -0.45, area = 9.0, distance = 2.0)
        var state = walk.frames(30, 1.40, listOf(kitchenUnderUser, livingAhead))
        assertEquals(0.0, state.global!!.y, 1e-9)

        // On the steps: the kitchen plane behind, the living room coming closer; nothing changes yet.
        state = walk.frames(15, 1.20, listOf(kitchenUnderUser.copy(distance = 0.6), livingAhead.copy(distance = 1.2)))
        assertEquals(0.0, state.local!!.y, 1e-9)

        // Down in the living room: the local pick replaces the kept floor, in both estimates.
        val phone = -0.45 + 1.40
        state = walk.frames(30, phone, listOf(kitchenUnderUser.copy(distance = 2.4), livingAhead.copy(distance = 0.0)))
        assertEquals(-0.45, state.local!!.y, 1e-9)
        assertEquals(-0.45, state.global!!.y, 1e-9)
        assertTrue(state.autoLockable)
    }

    @Test
    fun walkingUpALevel() {
        // Sunken living room scanned first, then up into the dining room (CAPX-3's trap).
        val walk = Walk()
        val living = FloorCandidate(y = -0.45, area = 9.0, distance = 0.0)
        var state = walk.frames(30, -0.45 + 1.40, listOf(living))
        assertEquals(-0.45, state.global!!.y, 1e-9)

        val dining = FloorCandidate(y = 0.0, area = 3.0, distance = 0.0)
        state = walk.frames(30, 1.40, listOf(living.copy(distance = 2.5), dining))
        assertEquals("the floor under the user is the dining room", 0.0, state.local!!.y, 1e-9)
        assertEquals(0.0, state.global!!.y, 1e-9)
        assertTrue(state.autoLockable)
    }

    @Test
    fun anUpperLevelVetoedFromBelowComesBackOnceTheUserStandsOnIt() {
        // Scanning the sunken living room (y = -0.45) with the kitchen floor (y = 0) in view two steps up:
        // 0.95 m below the phone, beside the user, floor evidence below it. Indistinguishable from a bed.
        val walk = Walk()
        val phoneDown = -0.45 + 1.40
        val living = FloorCandidate(y = -0.45, area = 9.0, distance = 0.0)
        val kitchenSeenFromBelow = FloorCandidate(y = 0.0, area = 3.0, distance = 1.8)
        var state = walk.frames(60, phoneDown, listOf(living, kitchenSeenFromBelow), hitY = -0.45)
        assertTrue(walk.model.isVetoed(0.0))
        assertEquals(-0.45, state.local!!.y, 1e-9)

        // Up in the kitchen. For the first second the veto still holds ...
        val kitchen = FloorCandidate(y = 0.0, area = 4.0, distance = 0.0)
        val livingBehind = living.copy(distance = 2.5)
        state = walk.frames(15, 1.40, listOf(kitchen, livingBehind))
        assertNull(state.local)
        // ... then standing on it proves it is a floor: the level change is visible to the capture flow
        // (CAPX-3: "this room's floor looks 45 cm higher - re-lock?").
        state = walk.frames(30, 1.40, listOf(kitchen, livingBehind))
        assertFalse(walk.model.isVetoed(0.0))
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertEquals(0.0, state.global!!.y, 1e-9)
        // A height that was vetoed once is left to the user to lock.
        assertTrue(walk.model.wasEverVetoed(0.0))
        assertEquals(AutoLockBlock.VETOED_BEFORE, state.autoLockBlock)
        assertTrue(walk.model.lastVeto!!.lifted)
    }

    @Test
    fun aVetoedBedDoesNotComeBackFromThePhonePassingOverIt() {
        val walk = Walk()
        val bedBeside = FloorCandidate(y = 0.45, area = 2.8, distance = 0.3)
        val bedUnderPhone = bedBeside.copy(distance = 0.0)
        walk.frames(15, 1.55, listOf(bedBeside), hitY = 0.0)
        assertTrue(walk.model.isVetoed(0.45))

        // The phone crosses a corner of the bed three times, a third of a second each.
        repeat(3) {
            var state = walk.frames(10, 1.55, listOf(bedUnderPhone))
            assertNull(state.local)
            state = walk.frames(10, 1.55, listOf(bedBeside))
            assertNull(state.local)
        }
        assertTrue(walk.model.isVetoed(0.45))

        // Leaning over it for two seconds does lift the veto (it cannot be told from standing on a low
        // platform), but it never locks by itself, and seeing the floor again beside it vetoes it again.
        var state = walk.frames(60, 1.55, listOf(bedUnderPhone))
        assertEquals(0.45, state.local!!.y, 1e-9)
        assertEquals(AutoLockBlock.VETOED_BEFORE, state.autoLockBlock)
        state = walk.frames(15, 1.55, listOf(bedBeside), hitY = 0.0)
        assertNull(state.local)
        assertTrue(walk.model.isVetoed(0.45))
        assertEquals("the same height is one veto, however often it is set", 1, walk.model.vetoCount)
    }

    @Test
    fun trackingLossKeepsTheSessionEstimateButNotTheLocalFloor() {
        val walk = Walk()
        val floor = FloorCandidate(y = 0.0, area = 4.0, distance = 0.0)
        walk.frames(10, 1.40, listOf(floor))
        var state = walk.model.onTrackingLost()
        assertEquals(0.0, state.global!!.y, 1e-9)
        assertNull(state.local)
        assertFalse(state.autoLockable)
        // A non-finite camera height is treated the same way.
        walk.frames(10, 1.40, listOf(floor))
        state = walk.model.update(listOf(floor), Double.NaN, null, walk.nowNs)
        assertNull(state.local)
        assertEquals(0.0, state.global!!.y, 1e-9)
        // Tracking again: everything is back on the next frame.
        state = walk.frames(1, 1.40, listOf(floor))
        assertEquals(0.0, state.local!!.y, 1e-9)
        assertTrue(state.autoLockable)
    }

    @Test
    fun vetoRemembersManyHeightsAndOnlyThoseHeights() {
        val walk = Walk()
        val bed = FloorCandidate(y = 0.50, area = 2.8, distance = 0.3)
        walk.frames(15, 1.45, listOf(bed), hitY = 0.0)
        assertTrue(walk.model.isVetoed(0.50))
        assertTrue("same surface, refined height", walk.model.isVetoed(0.50 + FloorSelection.SAME_FLOOR_TOLERANCE))
        assertFalse(walk.model.isVetoed(0.50 + FloorSelection.SAME_FLOOR_TOLERANCE + 0.02))
        assertFalse(walk.model.isVetoed(0.0))
        assertFalse(walk.model.isVetoed(Double.NaN))
        assertEquals(0.50, walk.model.lastVeto!!.y, 1e-9)
        // The same plane is not vetoed twice.
        walk.frames(15, 1.45, listOf(bed), hitY = 0.0)
        assertEquals(1, walk.model.vetoCount)
    }
}
