package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.ReticleHit
import com.oroplex.floorplan.ar.SurfaceKind
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CaptureCoachTest {

    private val frameNs = 33_333_333L

    private fun input(
        step: CaptureStep = CaptureStep.WALLS,
        floorY: Double? = 0.0,
        bandTopY: Double? = null,
        ceilingMeasured: Boolean = false,
        measuring: Boolean = false,
        markingFloorPoints: Boolean = false,
        canRelockFloor: Boolean = false,
        units: UnitSystem = UnitSystem.IMPERIAL,
    ) = CoachInput(step, floorY, bandTopY, ceilingMeasured, measuring, markingFloorPoints, canRelockFloor, units)

    /** Camera view direction turned [degrees] about the vertical axis from straight ahead (-Z). */
    private fun forward(degrees: Double): Vec3 {
        val a = Math.toRadians(degrees)
        return Vec3(-sin(a), 0.0, -cos(a))
    }

    /** A wall straight ahead at [distance], hit at chest height. */
    private fun wallHit(distance: Double = 2.0, y: Double = 1.3, surface: SurfaceKind = SurfaceKind.DEPTH, normal: Vec3? = null) =
        ReticleHit(Vec3(0.0, y, -distance), surface, distance, normal)

    private fun frame(
        t: Long,
        camera: Vec3? = Vec3(0.0, 1.4, 0.0),
        reticle: ReticleHit? = wallHit(),
        tracking: TrackingStatus = TrackingStatus.TRACKING,
        problem: TrackingProblem = TrackingProblem.NONE,
        floorY: Double? = 0.0,
        floorArea: Double = 3.0,
        localFloorY: Double? = null,
        cameraForward: Vec3? = null,
    ) = ArFrameState(
        tracking = tracking,
        problem = problem,
        floorY = floorY,
        floorArea = floorArea,
        reticle = reticle,
        cameraPosition = camera,
        timestampNs = t,
        localFloorY = localFloorY,
        cameraForward = cameraForward,
    )

    /** Feed [count] frames from [startFrame]; [make] gets the frame index. Returns the last hint. */
    private fun CaptureCoach.run(count: Int, input: CoachInput, startFrame: Int = 0, make: (Int) -> ArFrameState): CoachingHint? {
        var hint: CoachingHint? = null
        for (i in startFrame until startFrame + count) hint = update(make(i), input)
        return hint
    }

    @Test
    fun steadyGoodAimGivesNoHint() {
        val coach = CaptureCoach()
        assertNull(coach.run(60, input()) { frame(it * frameNs) })
        assertEquals(0.0, coach.speed ?: Double.NaN, 1e-9)
    }

    @Test
    fun movingFastWarnsAfterOnsetOnly() {
        val coach = CaptureCoach()
        val fast = { i: Int -> frame(i * frameNs, camera = Vec3(i * 0.04, 1.4, 0.0)) } // 1.2 m/s
        // The first ~0.4 s: speed is being established and the onset has not passed yet.
        assertNull(coach.run(8, input(), make = fast))
        val hint = coach.run(20, input(), startFrame = 8, make = fast)
        assertEquals(CoachKind.TOO_FAST, hint?.kind)
        assertEquals(1.2, coach.speed ?: Double.NaN, 0.02)
    }

    @Test
    fun normalWalkingBetweenWallsDoesNotNag() {
        // 0.8 m/s: walking to the next wall at a normal indoor pace (above the old 0.6 m/s limit).
        val hint = CaptureCoach().run(40, input()) { frame(it * frameNs, camera = Vec3(it * 0.0267, 1.4, 0.0)) }
        assertNull(hint)
    }

    @Test
    fun walkingBetweenRoomsUsesAHigherSpeedLimit() {
        val coach = CaptureCoach()
        // 1.2 m/s is fine while walking to the next room (CEILING step) but not while scanning walls.
        val hint = coach.run(40, input(step = CaptureStep.CEILING, canRelockFloor = false)) {
            frame(it * frameNs, camera = Vec3(it * 0.04, 1.4, 0.0), reticle = wallHit(y = 2.5))
        }
        assertTrue(hint?.kind != CoachKind.TOO_FAST)
    }

    @Test
    fun turningFastWhileMeasuringSaysHoldStill() {
        // 30°/s of wrist turn with the phone in place: no translation, but the wall sample smears.
        val coach = CaptureCoach()
        val hint = coach.run(30, input(measuring = true)) { frame(it * frameNs, cameraForward = forward(it * 1.0)) }
        assertEquals(CoachKind.HOLD_STILL, hint?.kind)
        assertEquals(30.0, coach.angularSpeed ?: Double.NaN, 1.0)
        // A slow 10°/s drift while measuring is fine.
        assertNull(CaptureCoach().run(30, input(measuring = true)) { frame(it * frameNs, cameraForward = forward(it * 0.333)) })
    }

    @Test
    fun fastPanningWhileScanningSaysTurnSlowly() {
        val hint = CaptureCoach().run(30, input()) { frame(it * frameNs, cameraForward = forward(it * 3.0)) } // 90°/s
        assertEquals(CoachKind.TOO_FAST, hint?.kind)
        assertTrue(hint?.text?.contains("Turn") == true)
        // Turning at a normal 40°/s to look for the next wall is fine.
        assertNull(CaptureCoach().run(30, input()) { frame(it * frameNs, cameraForward = forward(it * 1.333)) })
        // Without a view direction there is no turning speed (and no hint).
        val noForward = CaptureCoach()
        noForward.run(30, input()) { frame(it * frameNs) }
        assertNull(noForward.angularSpeed)
    }

    @Test
    fun holdStillWhileMeasuring() {
        val coach = CaptureCoach()
        val hint = coach.run(30, input(measuring = true)) { frame(it * frameNs, camera = Vec3(it * 0.012, 1.4, 0.0)) } // 0.36 m/s
        assertEquals(CoachKind.HOLD_STILL, hint?.kind)
    }

    @Test
    fun poseJumpIsNotSpeed() {
        val coach = CaptureCoach()
        coach.run(10, input()) { frame(it * frameNs) }
        coach.update(frame(10 * frameNs, camera = Vec3(3.0, 1.4, 0.0)), input()) // 3 m in one frame
        assertNull(coach.speed)
        assertNull(coach.run(30, input(), startFrame = 11) { frame(it * frameNs, camera = Vec3(3.0, 1.4, 0.0)) })
    }

    @Test
    fun tooFarAndTooCloseFromTheWall() {
        val far = CaptureCoach().run(30, input()) { frame(it * frameNs, reticle = wallHit(distance = 4.6)) }
        assertEquals(CoachKind.TOO_FAR, far?.kind)
        assertTrue(far?.text?.contains("13 ft") == true)
        val close = CaptureCoach().run(30, input()) { frame(it * frameNs, reticle = wallHit(distance = 0.3)) }
        assertEquals(CoachKind.TOO_CLOSE, close?.kind)
        // Marking floor points: being close is fine.
        assertNull(CaptureCoach().run(30, input(step = CaptureStep.CORNERS, markingFloorPoints = true)) { frame(it * frameNs, reticle = wallHit(distance = 0.3)) })
    }

    @Test
    fun aimHigherOrLowerWhileCapturingWalls() {
        val low = CaptureCoach().run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 0.2)) }
        assertEquals(CoachKind.AIM_HIGHER, low?.kind)
        val floor = CaptureCoach().run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 0.0, surface = SurfaceKind.FLOOR)) }
        assertEquals(CoachKind.AIM_HIGHER, floor?.kind)
        val ceiling = CaptureCoach().run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 2.4, surface = SurfaceKind.CEILING)) }
        assertEquals(CoachKind.AIM_LOWER, ceiling?.kind)
        val nearMeasuredCeiling = CaptureCoach().run(30, input(bandTopY = 2.5, ceilingMeasured = true)) { frame(it * frameNs, reticle = wallHit(y = 2.4)) }
        assertEquals(CoachKind.AIM_LOWER, nearMeasuredCeiling?.kind)
        assertTrue(nearMeasuredCeiling?.text?.contains("below the ceiling") == true)
        // An assumed band top is not a ceiling claim.
        val nearBandTop = CaptureCoach().run(30, input(bandTopY = 3.04)) { frame(it * frameNs, reticle = wallHit(y = 2.9)) }
        assertEquals(CoachKind.AIM_LOWER, nearBandTop?.kind)
        assertTrue(nearBandTop?.text?.contains("ceiling") == false)
        // Above tall furniture but well inside the band: no hint.
        assertNull(CaptureCoach().run(30, input(bandTopY = 3.04)) { frame(it * frameNs, reticle = wallHit(y = 2.35)) })
        val facingDown = CaptureCoach().run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 2.2, normal = Vec3(0.0, -1.0, 0.0))) }
        assertEquals(CoachKind.AIM_LOWER, facingDown?.kind)
        // Marking the wall base is aiming at the floor on purpose.
        assertNull(CaptureCoach().run(30, input(markingFloorPoints = true)) { frame(it * frameNs, reticle = wallHit(y = 0.0, surface = SurfaceKind.FLOOR)) })
    }

    @Test
    fun noSurfaceUnderTheReticle() {
        val hint = CaptureCoach().run(40, input()) { frame(it * frameNs, reticle = null) }
        assertEquals(CoachKind.NO_SURFACE, hint?.kind)
    }

    @Test
    fun trackingProblemsShowImmediatelyAndWin() {
        val coach = CaptureCoach()
        val hint = coach.update(frame(0, tracking = TrackingStatus.PAUSED, problem = TrackingProblem.INSUFFICIENT_LIGHT, reticle = null), input())
        assertEquals(CoachKind.TRACKING, hint?.kind)
        assertEquals(CoachTone.CRITICAL, hint?.tone)
        assertEquals(TrackingProblem.INSUFFICIENT_LIGHT.message, hint?.text)
        // Starting up is advice, not an error.
        val starting = CaptureCoach().update(ArFrameState(), input(step = CaptureStep.FIND_FLOOR, floorY = null))
        assertEquals(CoachTone.INFO, starting?.tone)
    }

    @Test
    fun hintsDoNotFlicker() {
        val coach = CaptureCoach()
        coach.run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 0.2)) }
        // The condition clears: the hint stays for the hold time, then goes.
        val held = coach.run(10, input(), startFrame = 30) { frame(it * frameNs) } // ~0.33 s
        assertEquals(CoachKind.AIM_HIGHER, held?.kind)
        val gone = coach.run(30, input(), startFrame = 40) { frame(it * frameNs) } // ~1 s more
        assertNull(gone)
        // A single bad frame does not bring it back.
        assertNull(coach.update(frame(70 * frameNs, reticle = wallHit(y = 0.2)), input()))
    }

    @Test
    fun moreImportantHintReplacesALessImportantOne() {
        val coach = CaptureCoach()
        coach.run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 0.2)) }
        val hint = coach.run(30, input(), startFrame = 30) { i ->
            frame(i * frameNs, reticle = wallHit(y = 0.2), camera = Vec3((i - 30) * 0.04, 1.4, 0.0))
        }
        assertEquals(CoachKind.TOO_FAST, hint?.kind)
    }

    @Test
    fun stepChangeDropsTheOldHint() {
        val coach = CaptureCoach()
        assertNotNull(coach.run(30, input()) { frame(it * frameNs, reticle = wallHit(y = 0.2)) })
        assertNull(coach.update(frame(30 * frameNs, reticle = wallHit(y = 0.2)), input(step = CaptureStep.OPENINGS)))
    }

    @Test
    fun ceilingStepNudgesTheUserToAimUp() {
        val hint = CaptureCoach().run(40, input(step = CaptureStep.CEILING)) { frame(it * frameNs, reticle = wallHit(y = 1.3)) }
        assertEquals(CoachKind.AIM_UP, hint?.kind)
        assertNull(CaptureCoach().run(40, input(step = CaptureStep.CEILING)) { frame(it * frameNs, reticle = wallHit(distance = 1.1, y = 2.4)) })
    }

    @Test
    fun floorLevelChangeIsCoachedInBothDirections() {
        val ceilingAim = wallHit(distance = 1.0, y = 2.4)
        // Walked up into a room whose floor is 18 cm above the lock (a step-down room was scanned first).
        val higher = CaptureCoach().run(40, input(step = CaptureStep.CEILING, floorY = 0.0, canRelockFloor = true, units = UnitSystem.METRIC)) {
            frame(it * frameNs, reticle = ceilingAim, floorY = 0.0, localFloorY = 0.18)
        }
        assertEquals(CoachKind.FLOOR_LEVEL, higher?.kind)
        assertEquals("This room's floor looks 18 cm higher than the locked floor — re-lock?", higher?.text)

        // Locked on a bed at 0.6 m; the floor under the user is at 0.
        val lower = CaptureCoach().run(40, input(step = CaptureStep.CEILING, floorY = 0.6, canRelockFloor = true)) {
            frame(it * frameNs, reticle = ceilingAim, floorY = 0.0, localFloorY = 0.0)
        }
        assertEquals(CoachKind.FLOOR_LEVEL, lower?.kind)
        assertTrue(lower?.text?.contains("24 in lower") == true)

        // A lower level seen elsewhere (the tracker's global floor) is not the floor under the user: no hint.
        assertNull(
            CaptureCoach().run(40, input(step = CaptureStep.CEILING, floorY = 0.0, canRelockFloor = true)) {
                frame(it * frameNs, reticle = ceilingAim, floorY = -0.45, localFloorY = 0.0)
            },
        )
        // Small differences (rugs, thresholds) are not a level change.
        assertNull(
            CaptureCoach().run(40, input(step = CaptureStep.CEILING, floorY = 0.0, canRelockFloor = true)) {
                frame(it * frameNs, reticle = ceilingAim, localFloorY = 0.05)
            },
        )
        // Not offered once re-locking isn't possible.
        assertNull(
            CaptureCoach().run(40, input(step = CaptureStep.CEILING, floorY = 0.6, canRelockFloor = false)) {
                frame(it * frameNs, reticle = ceilingAim, localFloorY = 0.0)
            },
        )
    }

    @Test
    fun floorSearchHintAfterAWhile() {
        val coach = CaptureCoach()
        val early = coach.run(90, input(step = CaptureStep.FIND_FLOOR, floorY = null)) { frame(it * frameNs, floorY = null, floorArea = 0.0) }
        assertNull(early) // 3 s
        val late = coach.run(120, input(step = CaptureStep.FIND_FLOOR, floorY = null), startFrame = 90) { frame(it * frameNs, floorY = null, floorArea = 0.0) }
        assertEquals(CoachKind.FLOOR_SEARCH, late?.kind)
    }

    @Test
    fun repeatedOrBackwardTimestampsAreHarmless() {
        val coach = CaptureCoach()
        repeat(5) { assertNull(coach.update(frame(1_000L), input())) }
        // A restarted session's timeline starts over: no huge negative dt, no stale speed.
        assertNull(coach.update(frame(10L, camera = Vec3(5.0, 1.4, 0.0)), input()))
        assertNull(coach.speed)
    }
}
