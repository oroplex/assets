package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.fit.RobustStats
import com.oroplex.floorplan.core.fit.WallEstimatorConfig
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
 * Wall sampling policy: how many frames to sample and which point the user aimed at.
 * Depth-from-motion noise grows with range (≈ 5 mm + 1.2 % of the distance), so far walls get more frames.
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
     * Height band in which wall points are used, matching [WallEstimatorConfig] defaults: from
     * floorY + minHeightAboveFloor up to the measured ceiling (or the assumed height when it is missing
     * or leaves no band) minus minDistanceBelowCeiling.
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
