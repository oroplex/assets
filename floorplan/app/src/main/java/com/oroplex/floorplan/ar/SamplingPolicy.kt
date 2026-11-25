package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.fit.RobustStats
import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/*
 * Pure decision rules of the AR samplers (no ARCore types), so they can be unit-tested on the JVM.
 * The samplers in MeasurementEngine.kt / HitTesting.kt translate ARCore hits into these inputs.
 */

/** How much a hit-test result can say about the surface it landed on. */
internal enum class SurfaceEvidence {
    /** A detected HORIZONTAL_DOWNWARD_FACING plane (inside its polygon, seen from its front side). */
    DOWNWARD_PLANE,

    /** A DepthPoint, or a feature Point with ESTIMATED_SURFACE_NORMAL: the hit pose's +Y is the surface normal. */
    POINT_WITH_NORMAL,

    /** A feature Point with INITIALIZED_TO_IDENTITY orientation: its +Y is just "up", not the surface normal. */
    POINT_WITHOUT_NORMAL,
}

/**
 * Ceiling height sampling (AR layer AR-2). The ceiling is the highest roughly horizontal surface above
 * the user, so a sample must be a downward-facing plane, or a depth / feature hit on a roughly horizontal
 * surface reached by a STEEP upward ray; and across frames the UPPER cluster wins, so cabinet tops, door
 * heads and lamps that the ray grazes in some frames do not drag the result down.
 */
internal object CeilingSampling {
    /** Ceilings are at least this high above the floor (rejects counters, shelves, wall cabinets), m. */
    const val MIN_HEIGHT_ABOVE_FLOOR = 1.8

    /** Above this (m) a hit is a glitch rather than a residential ceiling. */
    const val MAX_HEIGHT_ABOVE_FLOOR = 7.0

    /** Depth / feature hits need the ray at least this steep: y component of its unit direction (~44°). */
    const val MIN_RAY_UP = 0.7

    /** Feature points without a surface normal need a near-vertical ray (~64°). */
    const val MIN_RAY_UP_WITHOUT_NORMAL = 0.9

    /** |normal.y| of an accepted depth / feature hit: the surface is within ~45° of horizontal. */
    const val MIN_NORMAL_VERTICAL = 0.7

    /** Sorted heights further apart than this (m) start a new cluster. */
    const val CLUSTER_GAP = 0.05

    /** A cluster needs at least this many samples, and [MIN_CLUSTER_FRACTION] of all samples, to count. */
    const val MIN_CLUSTER_SIZE = 3
    const val MIN_CLUSTER_FRACTION = 0.2

    /**
     * Whether a hit at height [hitY] is a usable ceiling sample. [rayUp] is the y component of the unit
     * direction from the camera to the hit; [normalY] the y component of the hit's unit surface normal
     * (only read for [SurfaceEvidence.POINT_WITH_NORMAL]; its sign is ignored).
     */
    fun isCeilingSample(
        evidence: SurfaceEvidence,
        hitY: Double,
        cameraY: Double,
        floorY: Double,
        rayUp: Double,
        normalY: Double?,
    ): Boolean {
        if (!hitY.isFinite() || !cameraY.isFinite() || !floorY.isFinite()) return false
        if (hitY <= floorY + MIN_HEIGHT_ABOVE_FLOOR || hitY > floorY + MAX_HEIGHT_ABOVE_FLOOR) return false
        // A ceiling is seen from below.
        if (hitY <= cameraY) return false
        return when (evidence) {
            SurfaceEvidence.DOWNWARD_PLANE -> true
            SurfaceEvidence.POINT_WITH_NORMAL ->
                rayUp.isFinite() && rayUp >= MIN_RAY_UP &&
                    normalY != null && normalY.isFinite() && abs(normalY) >= MIN_NORMAL_VERTICAL
            SurfaceEvidence.POINT_WITHOUT_NORMAL -> rayUp.isFinite() && rayUp >= MIN_RAY_UP_WITHOUT_NORMAL
        }
    }

    /**
     * The highest well-supported cluster of [heights]: heights are sorted, split wherever neighbours are
     * more than [CLUSTER_GAP] apart, and the uppermost cluster with at least
     * max([MIN_CLUSTER_SIZE], [MIN_CLUSTER_FRACTION] × n) samples is returned (ascending). A lone spike
     * above the ceiling is skipped; lower surfaces seen in a minority of frames are ignored. Empty when
     * no cluster is supported well enough (non-finite values are dropped first).
     */
    fun upperCluster(heights: List<Double>): List<Double> {
        val sorted = heights.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return emptyList()
        val needed = max(MIN_CLUSTER_SIZE, ceil(MIN_CLUSTER_FRACTION * sorted.size).toInt())
        var end = sorted.size // exclusive end of the cluster being examined, scanning from the top
        while (end > 0) {
            var start = end - 1
            while (start > 0 && sorted[start] - sorted[start - 1] <= CLUSTER_GAP) start--
            if (end - start >= needed) return sorted.subList(start, end)
            end = start
        }
        return emptyList()
    }
}

/**
 * Floor-lock fallback when ARCore has not detected a floor plane (Capture flow AR-4): the median height
 * of depth / feature hits at the crosshair over several frames, accepted only while the phone points
 * down at the floor and only when the readings agree.
 */
internal object FloorLockFallback {
    /** The crosshair ray must point at least 30° below the horizon (y of its unit direction ≤ −0.5). */
    const val MIN_RAY_DOWN = 0.5

    /** A floor sample lies this far below the camera, meters (crouching users allowed down to 0.5 m). */
    const val MIN_DROP = 0.5
    const val MAX_DROP = FloorSelection.MAX_DROP_BELOW_CAMERA

    /** |normal.y| of a hit with a surface normal: the surface is within ~45° of horizontal. */
    const val MIN_NORMAL_VERTICAL = 0.7

    /** Samples to collect before deciding. */
    const val TARGET_SAMPLES = 10

    /** Readings agree when their median absolute deviation is at most this, meters (σ ≈ 3 cm). */
    const val MAX_MAD = 0.02

    /** Give up early (with a hint) when this many tracking frames produced no usable sample at all. */
    const val FRAMES_WITHOUT_SAMPLE_LIMIT = 20

    /** Never sample longer than this many tracking frames (~1.5 s at 30 fps). */
    const val MAX_FRAMES = 45

    /** Why a frame produced no floor sample; drives the hint shown when the lock gives up. */
    enum class Rejection { NONE, NOT_POINTING_DOWN, NOT_FLOOR_LIKE }

    /**
     * Classifies one crosshair hit. [rayDownY] is the y component of the unit direction from the camera
     * to the hit (negative = downward); [normalY] the y component of the surface normal when known (its
     * sign is ignored), null for feature points without one.
     */
    fun classify(hitY: Double, cameraY: Double, rayDownY: Double, normalY: Double?): Rejection {
        if (!rayDownY.isFinite() || rayDownY > -MIN_RAY_DOWN) return Rejection.NOT_POINTING_DOWN
        if (!hitY.isFinite() || !cameraY.isFinite()) return Rejection.NOT_FLOOR_LIKE
        val drop = cameraY - hitY
        if (drop < MIN_DROP || drop > MAX_DROP) return Rejection.NOT_FLOOR_LIKE
        if (normalY != null && !(abs(normalY) >= MIN_NORMAL_VERTICAL)) return Rejection.NOT_FLOOR_LIKE
        return Rejection.NONE
    }

    /**
     * Whether a floor-like crosshair hit at [hitY] proves that the tracked floor at [trackedY] is a raised
     * surface: the tracked plane is ambiguous (less than [FloorSelection.AMBIGUOUS_DROP] below the camera)
     * and the hit lies more than [FloorSelection.MAX_RAISE] below it.
     */
    fun contradictsTrackedFloor(trackedY: Double, cameraY: Double, hitY: Double): Boolean =
        trackedY.isFinite() && cameraY.isFinite() && hitY.isFinite() &&
            cameraY - trackedY < FloorSelection.AMBIGUOUS_DROP && hitY < trackedY - FloorSelection.MAX_RAISE

    /** Outcome of [evaluate]: [floorY] is the median height, null when the readings disagree. */
    data class Evaluation(val floorY: Double?, val mad: Double)

    /** Median height of [samples] if they agree (MAD ≤ [MAX_MAD]); null floorY otherwise or when empty. */
    fun evaluate(samples: List<Double>): Evaluation {
        val finite = samples.filter { it.isFinite() }
        if (finite.isEmpty()) return Evaluation(null, Double.NaN)
        val median = RobustStats.median(finite)
        val mad = RobustStats.mad(finite)
        return Evaluation(median.takeIf { mad <= MAX_MAD && it.isFinite() }, mad)
    }
}

/**
 * Wall sampling policy: how many frames to sample, the height band wall points must lie in, and which point
 * the user aimed at. Depth-from-motion noise grows with range (≈ 5 mm + 1.2 % of the distance), so far walls
 * get more frames.
 */
internal object WallSampling {
    /** Beyond this crosshair distance (m) the sampler collects [LONG_RANGE_FRAMES] frames. */
    const val LONG_RANGE_DISTANCE = 2.5
    const val LONG_RANGE_FRAMES = 25

    /**
     * Extra frames are only collected while sampling has run for less than this (the measurement
     * deadline is 4 s after the request; a slow frame rate must not turn a good capture into a timeout).
     */
    const val LONG_RANGE_BUDGET_NS = 2_500_000_000L

    /** At least this many in-band crosshair hits (and a third of the frames) define the aim point. */
    const val MIN_AIM_SAMPLES = 3

    /** Frames to collect for a crosshair at [aimDistance] meters (null = unknown). */
    fun targetFrames(baseFrames: Int, aimDistance: Double?): Int =
        if (aimDistance != null && aimDistance.isFinite() && aimDistance > LONG_RANGE_DISTANCE) {
            max(baseFrames, LONG_RANGE_FRAMES)
        } else {
            baseFrames
        }

    /** The most frames a wall capture requested with [baseFrames] can ever collect (the long-range count). */
    fun maxFrames(baseFrames: Int): Int = max(baseFrames, LONG_RANGE_FRAMES)

    /**
     * Whether sampling is done after [frames] frames: never before [baseFrames]; then once the range-based
     * target is reached, or when [elapsedNs] since the first sampled frame exceeds [LONG_RANGE_BUDGET_NS].
     */
    fun isComplete(frames: Int, baseFrames: Int, aimDistance: Double?, elapsedNs: Long): Boolean {
        if (frames < baseFrames) return false
        if (frames >= targetFrames(baseFrames, aimDistance)) return true
        return elapsedNs >= LONG_RANGE_BUDGET_NS
    }

    /**
     * THE completion rule of a wall capture (the sampler calls nothing else, ARX-3): done after [baseFrames]
     * frames for a wall within [LONG_RANGE_DISTANCE], after [LONG_RANGE_FRAMES] for a farther one (so far
     * walls really get the extra frames), earlier than that only when the time budget ran out, and in any
     * case at the hard cap [maxFrames]. [aimDistance] is the median crosshair distance so far (null =
     * unknown).
     */
    fun isDone(frames: Int, baseFrames: Int, aimDistance: Double?, elapsedNs: Long): Boolean =
        frames >= maxFrames(baseFrames) || isComplete(frames, baseFrames, aimDistance, elapsedNs)

    /**
     * Height band (world Y) in which wall points are used, from a [MeasureRequest.Wall]'s floorY / ceilingY.
     * [ceilingY] is the band TOP the capture flow chose (a measured ceiling, or a generous assumed top when
     * the ceiling was not measured); the band runs from floorY + minHeightAboveFloor up to
     * ceilingY − minDistanceBelowCeiling. Without a usable [ceilingY] (null, non-finite, or leaving no
     * band) the top is floorY + assumedCeilingHeight − minDistanceBelowCeiling.
     *
     * This is the same band [com.oroplex.floorplan.core.fit.WallEstimator.fromPoints] filters with for the
     * same floorY / ceilingY (a unit test holds the two together), so the aim point, the fit and the
     * diagnostics always agree on what counts as "on the wall" (CAPX-8).
     */
    fun wallBand(floorY: Double, ceilingY: Double?, config: WallEstimatorConfig = WallEstimatorConfig()): ClosedFloatingPointRange<Double> {
        val low = floorY + config.minHeightAboveFloor
        val measuredHigh = ceilingY?.takeIf { it.isFinite() }?.let { it - config.minDistanceBelowCeiling }
        val high = if (measuredHigh != null && measuredHigh > low) {
            measuredHigh
        } else {
            floorY + config.assumedCeilingHeight - config.minDistanceBelowCeiling
        }
        return low..high
    }

    /**
     * Where the user aimed: the component-wise median of the per-frame crosshair hits inside [band].
     * Null when fewer than max([MIN_AIM_SAMPLES], ⌈frames / 3⌉) hits are available, because a handful of
     * hits (glass, a blank wall) is not a reliable statement of intent.
     */
    fun aimPoint(centerHits: List<Vec3>, frames: Int, band: ClosedFloatingPointRange<Double>): Vec3? {
        val inBand = centerHits.filter { it.isFinite() && it.y in band }
        if (inBand.size < max(MIN_AIM_SAMPLES, (frames + 2) / 3)) return null
        return MeasurementMath.componentMedian(inBand).takeIf { it.isFinite() }
    }
}

/** What a wall sample was measured from. */
internal enum class WallSampleKind { DEPTH, PLANE, FEATURE }

/** One wall sample: a world point and the kind of ARCore hit it came from. */
internal class WallSample(val world: Vec3, val kind: WallSampleKind)

/**
 * Which hit along one ray becomes the wall sample (MATH-5).
 *
 * ARCore sorts a ray's hits nearest first, and with depth enabled a ray that crosses a detected wall plane
 * yields BOTH a DepthPoint and a plane hit. Taking the first usable hit therefore returns
 * min(depth, plane): depth errors toward the viewer are kept, errors away from it are replaced by the plane
 * point. That one-sided censoring pulls every fitted wall toward the camera (about 0.5–1.7 cm at 1.5–3.5 m,
 * on both of two opposite walls, so rooms come out small). So the choice must not depend on which hit is
 * nearer:
 * - a DepthPoint always wins: it is the measurement, with unbiased noise the estimator models, and it is
 *   what is really along the ray (ARCore plane polygons are convex hulls and span doorways);
 * - without one, a VERTICAL plane hit (front side, inside the polygon) is used;
 * - feature points are only used when depth is disabled for the session, and then after a plane hit.
 */
internal object WallSampleRule {
    /**
     * [depth], [plane], [feature]: the first usable hit of each kind along the ray (nearest of its kind),
     * or null. [depthEnabled] is whether the session runs the Depth API.
     */
    fun choose(depth: Vec3?, plane: Vec3?, feature: Vec3?, depthEnabled: Boolean): WallSample? = when {
        depth != null -> WallSample(depth, WallSampleKind.DEPTH)
        plane != null -> WallSample(plane, WallSampleKind.PLANE)
        feature != null && !depthEnabled -> WallSample(feature, WallSampleKind.FEATURE)
        else -> null
    }
}

/**
 * Keeps wall sampling inside the frame budget (pure; GL thread only).
 *
 * A wall capture hit-tests a grid around the crosshair on every camera frame: 80 ARCore hit tests for the
 * full 9 × 9 grid. How long one hit test takes is only known on the device, and a frame that takes longer
 * than a camera frame (33 ms at 30 fps) drops images and stretches the capture. The governor measures the
 * cost per hit test (smoothed over frames, remembered across captures) and picks the densest grid of
 * [WallSampleGrid.SIZES] whose predicted cost fits [budgetNs]; it never goes below the smallest grid.
 */
internal class WallGridGovernor(private val budgetNs: Long = DEFAULT_BUDGET_NS) {
    /** Smoothed cost of one hit test in nanoseconds; 0 until the first frame was measured. */
    var costPerHitNs: Double = 0.0
        private set

    private var current = WallSampleGrid.SIZES.first()

    /** Grid size (points per side) to use for the next sampled frame. */
    fun gridSize(): Int {
        val cost = costPerHitNs
        if (cost <= 0.0) return current
        var chosen = WallSampleGrid.SIZES.last()
        for (size in WallSampleGrid.SIZES) {
            // Growing back needs headroom, so the grid does not flip between two sizes on every frame.
            val limit = if (size > current) budgetNs * GROW_HEADROOM else budgetNs.toDouble()
            if (cost * (size * size - 1) <= limit) {
                chosen = size
                break
            }
        }
        current = chosen
        return chosen
    }

    /** Records that [hitTests] hit tests of one frame took [elapsedNs]. */
    fun onFrameSampled(hitTests: Int, elapsedNs: Long) {
        if (hitTests <= 0 || elapsedNs < 0) return
        val sample = elapsedNs.toDouble() / hitTests
        costPerHitNs = if (costPerHitNs <= 0.0) sample else costPerHitNs * (1 - SMOOTHING) + sample * SMOOTHING
    }

    companion object {
        /**
         * Time one frame's grid may take. A 30 fps camera frame is 33 ms; Session.update() and drawing the
         * camera image take roughly a third of that on a current phone, the rest is headroom.
         */
        const val DEFAULT_BUDGET_NS = 12_000_000L
        private const val SMOOTHING = 0.3
        private const val GROW_HEADROOM = 0.8
    }
}

/**
 * The one-line-per-measurement device log (pure formatting, plain ASCII; see [ArLog]). A wall capture reads:
 *
 *     measure wall ok 612 ms frames=15 | samples 1198 of 1215 rays (depth 1102, plane 96, feature 0) grid 9
 *       sampling 6.8 ms/frame | aim 2.41 m | band y=-1.21..1.19 | fit DEPTH_FIT angle 91.32 deg offset
 *       2.4130 m viewer 2.4130 m length 1.84 m used 987 conf 0.82 rms 6.1 mm | sigma 11.2 mm (expected
 *       33.9 mm, limit 50.9 mm) | plane angle 90.95 deg offset 2.4212 m viewer 2.4210 m length 2.10 m
 *       (plane - fit: +8.0 mm, -0.37 deg)
 *
 * "samples" counts the rays that produced a wall sample, by the kind of ARCore hit ([WallSampleRule]);
 * "grid" is the smallest grid used (below 9 means the phone could not afford the full grid in a frame).
 * Reading a wall line against a tape measure: "viewer" is the perpendicular distance from the phone to the
 * wall; "offset" is the signed distance of the world origin from the wall line (positive on the room side),
 * so the distance between two opposite, parallel walls is the SUM of their offsets, wherever they were
 * captured from; "angle" is the direction of the wall line in the plan (degrees, 0–360). "sigma" is the
 * robust per-sample noise seen on this wall, next to what the estimator expects at that range and the limit
 * above which it rejects the capture. "plane - fit" compares ARCore's detected wall plane under the
 * crosshair with the fitted wall: positive means the plane lies farther from the phone.
 */
internal object MeasurementReport {
    fun kind(request: MeasureRequest): String = when (request) {
        MeasureRequest.LockFloor -> "floor-lock"
        is MeasureRequest.Wall -> "wall"
        is MeasureRequest.FloorPoint -> "floor-point"
        is MeasureRequest.SurfacePoint -> "surface-point"
        is MeasureRequest.CeilingHeight -> "ceiling"
    }

    /**
     * The whole line. [result] is null when the request ended without a result from the engine (the caller
     * cancelled it, or the session paused). [frames] counts the tracking frames the sampler saw; [detail]
     * is the sampler's own description and may be empty.
     */
    fun line(request: MeasureRequest, result: MeasureResult?, durationNs: Long, frames: Int, detail: String): String {
        val sb = StringBuilder(240)
        sb.append("measure ").append(kind(request)).append(' ').append(outcome(result))
        sb.append(' ').append((durationNs / 1e6).fmt(0)).append(" ms frames=").append(frames)
        if (detail.isNotEmpty()) sb.append(" | ").append(detail)
        return sb.toString()
    }

    private fun outcome(result: MeasureResult?): String = when (result) {
        null -> "DROPPED (cancelled or session paused)"
        is MeasureResult.FloorLocked -> "ok y=${result.floorY.fmt(3)}"
        is MeasureResult.WallMeasured -> {
            val failure = result.fit.failure
            if (result.fit.observation != null && failure == null) "ok" else "FAILED (${failure?.name ?: "no wall"})"
        }
        is MeasureResult.Point ->
            "ok at (${result.world.x.fmt(3)}, ${result.world.y.fmt(3)}, ${result.world.z.fmt(3)}) " +
                "samples=${result.samples} spread=${result.spread.fmtMm()}"
        is MeasureResult.Height ->
            "ok height=${result.height.fmt(3)} m samples=${result.samples} spread=${result.spread.fmtMm()}"
        is MeasureResult.Failed -> "FAILED (${result.reason})"
    }

    /** Direction angle (degrees, 0–360), signed origin offset and viewer distance of a wall line. */
    fun wallLine(line: Line2, viewer: Vec2): String {
        var degrees = Math.toDegrees(line.angle()) % 360.0
        if (degrees < 0) degrees += 360.0
        return "angle ${degrees.fmt(2)} deg offset ${line.signedDistance(Vec2.ZERO).fmt(4)} m " +
            "viewer ${line.signedDistance(viewer).fmt(4)} m"
    }

    /**
     * "(plane - fit: +8.0 mm, -0.37 deg)": how far ARCore's [plane] line lies behind the fitted [fit] line as
     * seen from the room side, measured at [at] (a point on or near the fitted wall), and the angle between
     * the two. Both lines must be oriented room-left, as every WallObservation is.
     */
    fun planeVersusFit(fit: Line2, plane: Line2, at: Vec2): String {
        val behind = plane.signedDistance(fit.project(at))
        var turn = Math.toDegrees(plane.angle() - fit.angle()) % 360.0
        if (turn > 180.0) turn -= 360.0
        if (turn <= -180.0) turn += 360.0
        return "(plane - fit: ${signed(behind * 1000.0, 1)} mm, ${signed(turn, 2)} deg)"
    }

    /**
     * Robust spread (1.4826 × MAD, meters) of the plan distances from [line] of the [points] inside [band]
     * that lie within [window] of the line: the per-sample noise actually seen on this wall, to hold against
     * the estimator's expected sigma. NaN with fewer than [MIN_SIGMA_POINTS] such points.
     */
    fun robustSigma(points: List<Vec3>, band: ClosedFloatingPointRange<Double>, line: Line2, window: Double): Double {
        val residuals = ArrayList<Double>(points.size)
        for (p in points) {
            if (!p.isFinite() || p.y !in band) continue
            val d = line.signedDistance(p.toPlan())
            if (abs(d) <= window) residuals += d
        }
        if (residuals.size < MIN_SIGMA_POINTS) return Double.NaN
        return RobustStats.MAD_TO_SIGMA * RobustStats.mad(residuals)
    }

    /** "+8.0" / "-0.37"; a value that rounds to zero is "+0.0", never "-0.0". */
    private fun signed(value: Double, decimals: Int): String {
        if (!value.isFinite()) return "n/a"
        val magnitude = abs(value).fmt(decimals)
        val roundsToZero = magnitude.all { it == '0' || it == '.' }
        return (if (value >= 0 || roundsToZero) "+" else "-") + magnitude
    }

    const val MIN_SIGMA_POINTS = 10
}

/**
 * What a wall capture returns, given the depth fit, the crosshair evidence and the ARCore wall plane under
 * the crosshair. Pure, so the rules can be tested without a device.
 *
 * The depth fit ([WallEstimator.fromPoints]) is only trusted when it was told where the user aimed: without
 * an aim point it fits the biggest surface in view, which behind a tall wardrobe or beside a doorway is not
 * the wall at the crosshair. And an ARCore plane may only stand in for a failed fit when it really is the
 * surface at the crosshair, because the "first wall plane along the ray" can be the wall of the next room
 * seen through a doorway or the wall behind a column.
 */
internal object WallOutcome {
    /** A plane counts as the surface at the crosshair when the crosshair's depth is within this of it (m)... */
    const val PLANE_AIM_TOLERANCE = 0.05

    /** ...or within this many expected depth sigmas at that range, whichever is larger. */
    const val PLANE_AIM_SIGMAS = 2.0

    /**
     * Confidence given to a plane that stands in for a fit the estimator REFUSED (a short wall, too little of
     * the wall in view): low enough for an amber wall chip and a "weak capture" note in the room review, so the
     * user knows which wall to check with a tape.
     */
    const val UNVERIFIED_PLANE_CONFIDENCE = 0.35

    const val HINT_NO_DEPTH_AT_AIM = "Nothing measurable at the crosshair. Aim at a part of this wall with some detail, or tap its base."
    const val HINT_AIM_OFF_WALL = "The crosshair is on the floor or ceiling. Aim at the wall itself and capture again."
    const val HINT_AIM_UNSTEADY = "Keep the crosshair on the wall until the ring fills, then try again."

    /** How [decide] reached its result (for the diagnostics line). */
    enum class Source { DEPTH_FIT, PLANE, PLANE_AFTER_REFUSAL, NO_AIM, FIT_FAILED }

    class Decision(val fit: WallFit, val source: Source)

    /**
     * @param sampleFit the depth fit of all samples (computed with [aimPoint])
     * @param aimPoint where the user aimed (median in-band crosshair sample), null when there were too few
     * @param crosshair every crosshair sample of the capture, in or out of [band]
     * @param plane the wall plane under the crosshair as an observation, or null
     */
    fun decide(
        sampleFit: WallFit,
        aimPoint: Vec3?,
        crosshair: List<Vec3>,
        band: ClosedFloatingPointRange<Double>,
        plane: WallObservation?,
        viewer: Vec3,
        totalPoints: Int,
        config: WallEstimatorConfig = WallEstimatorConfig(),
    ): Decision {
        val fitted = sampleFit.observation?.takeIf { sampleFit.failure == null }
        if (aimPoint != null && fitted != null) return Decision(sampleFit, Source.DEPTH_FIT)

        val planeAtAim = plane?.takeIf { planeIsAtCrosshair(it, crosshair, band, viewer, config) }
        if (planeAtAim != null) {
            // A plane replacing a fit the estimator refused on purpose is used, but flagged as unverified.
            val refused = aimPoint != null && sampleFit.hint != null
            val observation = if (refused) {
                planeAtAim.copy(confidence = minOf(planeAtAim.confidence, UNVERIFIED_PLANE_CONFIDENCE))
            } else {
                planeAtAim
            }
            val fit = WallFit(observation, null, usedPoints = planeAtAim.pointCount, totalPoints = totalPoints)
            return Decision(fit, if (refused) Source.PLANE_AFTER_REFUSAL else Source.PLANE)
        }
        if (aimPoint == null) {
            val finite = crosshair.filter { it.isFinite() }
            val hint = when {
                finite.isEmpty() -> HINT_NO_DEPTH_AT_AIM
                finite.none { it.y in band } -> HINT_AIM_OFF_WALL
                else -> HINT_AIM_UNSTEADY
            }
            val fit = WallFit(null, WallFitFailure.NO_DOMINANT_PLANE, sampleFit.usedPoints, totalPoints, hint = hint)
            return Decision(fit, Source.NO_AIM)
        }
        return Decision(sampleFit, Source.FIT_FAILED)
    }

    /**
     * Whether [plane] is the surface the crosshair is on. With crosshair samples inside the height band, their
     * median must lie on the plane (within [PLANE_AIM_TOLERANCE] or [PLANE_AIM_SIGMAS] expected sigmas). With
     * crosshair samples only outside the band (floor, ceiling) the plane is not what was aimed at. With no
     * crosshair samples at all nothing contradicts the plane, so it is accepted (depth off, or a blank wall
     * ARCore still found a plane on).
     */
    fun planeIsAtCrosshair(
        plane: WallObservation,
        crosshair: List<Vec3>,
        band: ClosedFloatingPointRange<Double>,
        viewer: Vec3,
        config: WallEstimatorConfig = WallEstimatorConfig(),
    ): Boolean {
        val finite = crosshair.filter { it.isFinite() }
        if (finite.isEmpty()) return true
        val inBand = finite.filter { it.y in band }
        if (inBand.isEmpty()) return false
        val at = MeasurementMath.componentMedian(inBand)
        if (!at.isFinite()) return false
        val range = at.toPlan().distanceTo(viewer.toPlan())
        val tolerance = max(PLANE_AIM_TOLERANCE, PLANE_AIM_SIGMAS * config.expectedSigma(range))
        return abs(plane.line.signedDistance(at.toPlan())) <= tolerance
    }
}

