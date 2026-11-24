package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.SurfaceKind
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.UnitSystem

/** What the live coaching banner is about. Declared from most to least important. */
enum class CoachKind {
    TRACKING,
    HOLD_STILL,
    TOO_FAST,
    LOWER_FLOOR,
    TOO_FAR,
    TOO_CLOSE,
    AIM_HIGHER,
    AIM_LOWER,
    AIM_UP,
    NO_SURFACE,
    FLOOR_SEARCH,
}

/** How the banner is colored: neutral advice, a caution, or a problem that blocks accurate capture. */
enum class CoachTone { INFO, WARNING, CRITICAL }

/** One live coaching hint, e.g. "Slow down…". */
data class CoachingHint(val kind: CoachKind, val tone: CoachTone, val text: String)

/** The parts of the capture state the coach needs besides the AR frame. */
internal data class CoachInput(
    val step: CaptureStep,
    /** Locked floor height (world Y), if any. */
    val floorY: Double?,
    /** Measured ceiling height in world Y (floorY + measured height), or null when not measured. */
    val ceilingY: Double?,
    val measuring: Boolean,
    /** The user is marking points on the floor (baseline taps, corners, door edges), not a wall surface. */
    val markingFloorPoints: Boolean,
    /** "Re-lock floor" is available right now (CEILING step, nothing captured yet). */
    val canRelockFloor: Boolean,
    val units: UnitSystem,
)

/**
 * Turns the stream of AR frames into at most one short coaching hint at a time: tracking problems, moving
 * too fast (camera speed from successive camera positions and frame timestamps), being too far from or too
 * close to the wall, aiming at the floor or ceiling while capturing walls, and a few step-specific nudges.
 *
 * Hints are debounced so the banner never flickers: a condition must persist for a short onset time
 * (per kind) before it shows, a shown hint is kept while its condition holds and for [HOLD_NS] after it
 * clears, and a more important hint replaces a less important one as soon as it is due. Feed every frame
 * to [update]; it is deterministic for a given frame sequence (time comes from [ArFrameState.timestampNs]).
 */
internal class CaptureCoach {
    private class Sample(val t: Long, val p: Vec3)

    private val samples = ArrayDeque<Sample>()
    private var nowNs = 0L
    private var lastTimestampNs = Long.MIN_VALUE
    private var lastStep: CaptureStep? = null
    private var findFloorSinceNs: Long? = null
    private var shown: CoachingHint? = null
    private var shownLastActiveNs = 0L
    private var pendingKind: CoachKind? = null
    private var pendingSinceNs = 0L

    /** Smoothed camera speed in m/s over the last [SPEED_WINDOW_NS], or null while unknown. */
    var speed: Double? = null
        private set

    fun update(frame: ArFrameState, input: CoachInput): CoachingHint? {
        // A monotonic clock that advances by frame deltas, so timestamps that repeat or (after a session
        // restart) jump backwards never freeze or rewind the debounce timers.
        val ts = frame.timestampNs
        if (lastTimestampNs != Long.MIN_VALUE && ts > lastTimestampNs) nowNs += ts - lastTimestampNs
        lastTimestampNs = ts
        if (input.step != lastStep) {
            lastStep = input.step
            shown = null
            pendingKind = null
            findFloorSinceNs = null
        }
        updateSpeed(frame)
        return select(conditions(frame, input))
    }

    private fun updateSpeed(frame: ArFrameState) {
        val p = frame.cameraPosition
        if (frame.tracking != TrackingStatus.TRACKING || p == null || !p.isFinite()) {
            samples.clear()
            speed = null
            return
        }
        val last = samples.lastOrNull()
        if (last != null && frame.timestampNs == last.t) return // same camera image
        if (last != null && frame.timestampNs < last.t) samples.clear() // new timeline (session restarted)
        samples.addLast(Sample(frame.timestampNs, p))
        while (samples.size > 1 && frame.timestampNs - samples.first().t > SPEED_WINDOW_NS) samples.removeFirst()
        val first = samples.first()
        val dt = (frame.timestampNs - first.t) / 1e9
        val v = if (dt >= MIN_SPEED_SPAN_S) p.distanceTo(first.p) / dt else null
        if (v != null && v > MAX_PLAUSIBLE_SPEED) {
            // A pose jump (ARCore relocalized), not real motion: start over from this pose.
            samples.clear()
            samples.addLast(Sample(frame.timestampNs, p))
            speed = null
        } else {
            speed = v
        }
    }

    /** Every condition that holds on this frame, most important first. */
    private fun conditions(frame: ArFrameState, input: CoachInput): List<CoachingHint> {
        val trackingText = trackingBannerText(frame.tracking, frame.problem)
        if (trackingText != null) {
            val starting = frame.problem == TrackingProblem.INITIALIZING || frame.tracking == TrackingStatus.NOT_STARTED
            return listOf(CoachingHint(CoachKind.TRACKING, if (starting) CoachTone.INFO else CoachTone.CRITICAL, trackingText))
        }
        if (input.step == CaptureStep.REVIEW) return emptyList()
        val out = ArrayList<CoachingHint>(3)
        val units = input.units
        val v = speed
        val scanning = input.step == CaptureStep.WALLS || input.step == CaptureStep.CORNERS || input.step == CaptureStep.OPENINGS
        if (input.measuring && v != null && v > HOLD_STILL_SPEED) {
            out += CoachingHint(CoachKind.HOLD_STILL, CoachTone.WARNING, "Hold still until the ring fills.")
        } else if (v != null && v > (if (scanning) MAX_SCAN_SPEED else MAX_WALK_SPEED)) {
            out += CoachingHint(
                CoachKind.TOO_FAST,
                CoachTone.WARNING,
                if (scanning) "Slow down. Move the phone gently so tracking keeps up." else "Walk a little slower so tracking keeps up.",
            )
        }

        val floorY = input.floorY
        val trackedFloor = frame.floorY
        if (input.step == CaptureStep.CEILING && input.canRelockFloor && floorY != null && trackedFloor != null &&
            trackedFloor.isFinite() && frame.floorArea >= CaptureViewModel.AUTO_LOCK_MIN_AREA &&
            floorY - trackedFloor > LOWER_FLOOR_MARGIN
        ) {
            out += CoachingHint(
                CoachKind.LOWER_FLOOR,
                CoachTone.WARNING,
                "The floor may have locked onto furniture. Tap Re-lock floor and aim at open floor.",
            )
        }

        val hit = frame.reticle?.takeIf { it.world.isFinite() }
        val distance = hit?.distance?.takeIf { it.isFinite() && it >= 0.0 }
        val wallSurface = input.step == CaptureStep.WALLS && !input.markingFloorPoints
        if (scanning && distance != null) {
            when {
                distance > MAX_ACCURATE_DISTANCE -> out += CoachingHint(
                    CoachKind.TOO_FAR,
                    CoachTone.WARNING,
                    if (wallSurface) {
                        "Too far for an accurate wall. Move within ${approxDistance(MAX_ACCURATE_DISTANCE, units)}."
                    } else {
                        "Too far to mark accurately. Move within ${approxDistance(MAX_ACCURATE_DISTANCE, units)}."
                    },
                )
                wallSurface && distance < MIN_WALL_DISTANCE -> out += CoachingHint(
                    CoachKind.TOO_CLOSE,
                    CoachTone.WARNING,
                    "Too close to the wall. Step back to about ${approxDistance(COMFORT_WALL_DISTANCE, units)}.",
                )
            }
        }

        if (wallSurface && hit != null && floorY != null) {
            val aboveFloor = hit.world.y - floorY
            val normalDown = hit.normal?.let { it.isFinite() && it.y < -FACING_NORMAL_Y } == true
            val nearCeiling = input.ceilingY?.let { it - hit.world.y < CEILING_MARGIN } == true
            when {
                hit.surface == SurfaceKind.FLOOR || aboveFloor < MIN_AIM_HEIGHT -> out += CoachingHint(
                    CoachKind.AIM_HIGHER,
                    CoachTone.INFO,
                    "Aim higher, at the wall above furniture.",
                )
                hit.surface == SurfaceKind.CEILING || normalDown || nearCeiling -> out += CoachingHint(
                    CoachKind.AIM_LOWER,
                    CoachTone.INFO,
                    "Aim a little lower, at the wall below the ceiling.",
                )
            }
        }
        if (wallSurface && hit == null) {
            out += CoachingHint(CoachKind.NO_SURFACE, CoachTone.INFO, "Can't see a surface here. Aim at a textured part of the wall.")
        }

        if (input.step == CaptureStep.CEILING) {
            val camera = frame.cameraPosition?.takeIf { it.isFinite() }
            if (camera != null && (hit == null || hit.world.y < camera.y + MIN_CEILING_AIM_RISE)) {
                out += CoachingHint(CoachKind.AIM_UP, CoachTone.INFO, "Tilt the phone up and aim at a clear spot on the ceiling.")
            }
        }

        if (input.step == CaptureStep.FIND_FLOOR && floorY == null && trackedFloor == null) {
            val since = findFloorSinceNs ?: nowNs.also { findFloorSinceNs = it }
            if (nowNs - since >= FLOOR_SEARCH_HINT_NS) {
                out += CoachingHint(
                    CoachKind.FLOOR_SEARCH,
                    CoachTone.INFO,
                    "Still looking for the floor. Sweep slowly over a patterned or textured spot, with the lights on.",
                )
            }
        } else {
            findFloorSinceNs = null
        }
        return out.sortedBy { it.kind.ordinal }
    }

    private fun select(active: List<CoachingHint>): CoachingHint? {
        val top = active.firstOrNull()
        if (top == null) {
            pendingKind = null
        } else if (top.kind != pendingKind) {
            pendingKind = top.kind
            pendingSinceNs = nowNs
        }
        val due = top?.takeIf { nowNs - pendingSinceNs >= onsetNs(it.kind) }

        val current = shown
        val currentActive = current?.let { c -> active.firstOrNull { it.kind == c.kind } }
        if (currentActive != null) shownLastActiveNs = nowNs
        val next = when {
            due != null && (current == null || due.kind.ordinal <= current.kind.ordinal) -> due
            currentActive != null -> currentActive // keep it, with refreshed text
            current != null && nowNs - shownLastActiveNs < HOLD_NS -> current
            else -> due
        }
        if (next?.kind != current?.kind) shownLastActiveNs = nowNs
        shown = next
        return next
    }

    private fun onsetNs(kind: CoachKind): Long = when (kind) {
        CoachKind.TRACKING, CoachKind.FLOOR_SEARCH -> 0L
        CoachKind.HOLD_STILL -> 150_000_000L
        CoachKind.TOO_FAST -> 300_000_000L
        CoachKind.TOO_FAR, CoachKind.TOO_CLOSE -> 400_000_000L
        CoachKind.LOWER_FLOOR, CoachKind.AIM_HIGHER, CoachKind.AIM_LOWER -> 500_000_000L
        CoachKind.NO_SURFACE, CoachKind.AIM_UP -> 700_000_000L
    }

    internal companion object {
        /** Camera speed is measured over this window of frames. */
        const val SPEED_WINDOW_NS = 400_000_000L

        /** Speeds over less than this time span are too jittery to use. */
        const val MIN_SPEED_SPAN_S = 0.1

        /** Faster than this between two frames is a tracking jump, not motion. */
        const val MAX_PLAUSIBLE_SPEED = 4.0

        /** While scanning walls / corners / openings: "slow down" above this camera speed (m/s). */
        const val MAX_SCAN_SPEED = 0.6

        /** Elsewhere (finding the floor, walking to the next room): "walk slower" above this (m/s). */
        const val MAX_WALK_SPEED = 1.1

        /** During a measurement: "hold still" above this camera speed (m/s). */
        const val HOLD_STILL_SPEED = 0.25

        /** Walls are sampled poorly from closer than this (meters). */
        const val MIN_WALL_DISTANCE = 0.5

        /** Where "step back" sends the user (meters). */
        const val COMFORT_WALL_DISTANCE = 1.0

        /** Wall aim points lower than this above the floor are floor, baseboard or furniture (meters). */
        const val MIN_AIM_HEIGHT = 0.5

        /** Wall aim points this close below a measured ceiling are on the ceiling edge (meters). */
        const val CEILING_MARGIN = 0.2

        /** A hit normal with y below minus this faces down (ceiling). */
        const val FACING_NORMAL_Y = 0.7

        /** In CEILING the aim point must be at least this far above the camera (meters). */
        const val MIN_CEILING_AIM_RISE = 0.3

        /** The tracked floor this far below the locked one suggests the lock is on furniture (meters). */
        const val LOWER_FLOOR_MARGIN = 0.2

        /** No floor after this long in FIND_FLOOR: suggest how to help ARCore find it. */
        const val FLOOR_SEARCH_HINT_NS = 6_000_000_000L

        /** A shown hint stays at least this long after its condition clears, so it can be read. */
        const val HOLD_NS = 900_000_000L
    }
}
