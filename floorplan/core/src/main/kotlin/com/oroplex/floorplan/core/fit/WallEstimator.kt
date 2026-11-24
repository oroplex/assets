package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

enum class WallFitFailure(val message: String) {
    TOO_FEW_POINTS("Not enough depth points on the wall. Move closer or aim at a textured area."),
    NO_DOMINANT_PLANE("Couldn't find a flat wall. Aim at a clear section of wall above furniture."),
    TOO_NOISY("Wall measurement too noisy. Hold steady, add light, or move closer."),
    TOO_FAR("Too far from the wall for an accurate reading. Move within about 4 m (13 ft)."),
}

/**
 * Outcome of [WallEstimator.fromPoints]: exactly one of [observation] / [failure] is non-null.
 * [usedPoints] is the inlier count of the fitted wall line when a line was found, otherwise the number
 * of points that survived filtering; [totalPoints] is the number of raw samples supplied.
 */
data class WallFit(
    val observation: WallObservation?,
    val failure: WallFitFailure?,
    val usedPoints: Int,
    val totalPoints: Int,
)

data class WallEstimatorConfig(
    /** Ignore points lower than this above the floor (baseboards, floor bleed). */
    val minHeightAboveFloor: Double = 0.20,
    /** Ignore points closer than this to the ceiling. */
    val minDistanceBelowCeiling: Double = 0.15,
    /** Assumed ceiling height for filtering when none was measured. */
    val assumedCeilingHeight: Double = 2.4,
    /** Points farther than this from the viewer (horizontal) are discarded. */
    val maxRange: Double = 6.0,
    val inlierThreshold: Double = 0.025,
    val minInliers: Int = 25,
    /**
     * RMS residual above which the fit is rejected as TOO_NOISY. Compared against the larger of the
     * inlier RMS and a robust (MAD-based) σ of the points near the line; see [WallEstimator.fromPoints].
     */
    val maxRms: Double = 0.03,
    /**
     * When a second roughly-parallel line lies FARTHER from the viewer than the best line and has at
     * least this fraction of the best line's inliers, prefer it (the near one is usually furniture).
     */
    val preferFartherRatio: Double = 0.5,
)

/**
 * Turns raw AR measurements into [WallObservation]s. Walls are modeled as vertical planes, i.e. lines
 * in the plan after projecting points with Vec3.toPlan().
 *
 * Every observation produced here follows the shared orientation convention: the viewer (room
 * interior) is on the LEFT of `line.direction` (`line.signedDistance(viewer) > 0`, `line.normal`
 * points into the room) and `extentStart -> extentEnd` runs along `+line.direction`.
 */
object WallEstimator {
    /** Fixed RANSAC seeds: results are fully deterministic for identical input. */
    private const val PRIMARY_SEED = 0x5EED_0001L
    private const val SECONDARY_SEED = 0x5EED_0002L
    private const val RANSAC_ITERATIONS = 300

    /** "Prefer farther": the candidate must be within this angle of parallel to the best line. */
    private val PARALLEL_SIN = sin(Math.toRadians(15.0))

    /** "Prefer farther": the candidate must be at least this much farther from the viewer (m). */
    private const val FARTHER_MARGIN = 0.10

    /**
     * A wall line supported by less than this fraction of the usable points is not a dominant plane
     * (a large scattered cloud always contains SOME strip with [WallEstimatorConfig.minInliers] points).
     */
    private const val MIN_INLIER_RATIO = 0.10

    /** Observed wall extents shorter than this (m) cannot define a direction reliably. */
    private const val MIN_EXTENT = 0.10

    /** A viewer closer than this (m) to the fitted line cannot tell the interior side apart. */
    private const val MIN_VIEWER_DISTANCE = 0.05

    /**
     * Noise is estimated from all usable points within this multiple of the inlier threshold of the
     * line, so that a thick (noisy) band is not hidden by the RANSAC inlier gate.
     */
    private const val NOISE_WINDOW_FACTOR = 4.0

    /** Minimum distance (m) between two baseline taps. */
    private const val MIN_BASELINE_LENGTH = 0.15

    /** Maximum |normal.y| of a plane accepted as a wall. */
    private const val MAX_PLANE_NORMAL_Y = 0.2

    private const val PLANE_CONFIDENCE = 0.8
    private const val BASELINE_CONFIDENCE = 0.9

    /** Half-length (m) of the assumed extent of a detected plane without boundary points. */
    private const val DEFAULT_PLANE_HALF_EXTENT = 0.5

    /**
     * Fit a vertical wall to world-space sample points (depth hits) captured around the reticle.
     *
     * Pipeline:
     * 1. Keep finite points whose height lies in
     *    `[floorY + minHeightAboveFloor, ceiling - minDistanceBelowCeiling]` (ceiling = [ceilingY], or
     *    `floorY + assumedCeilingHeight` when it is null, non-finite, or leaves an empty band) and whose
     *    horizontal distance to [viewer] is ≤ `maxRange`.
     * 2. Fewer than `minInliers` survivors → [WallFitFailure.TOO_FAR] when the range filter removed most
     *    of the in-band points, else [WallFitFailure.TOO_FEW_POINTS].
     * 3. RANSAC + TLS on the plan projections ([LineFit.ransac]). No line, a line explaining < 10 % of the
     *    usable points, or one whose inliers span < 0.10 m → [WallFitFailure.NO_DOMINANT_PLANE].
     * 4. "Prefer farther": RANSAC again on the points that are NOT inliers of the best line. If that line
     *    is within 15° of parallel, has ≥ `preferFartherRatio` × the best line's inliers and lies on the
     *    same side of the viewer at least 0.10 m farther away, it is chosen instead — the near line is
     *    then almost always furniture (sofa back, cabinet front) standing in front of the wall. Note this
     *    can also pick a parallel wall seen through a wide doorway; the ratio keeps that unlikely.
     * 5. Noise = max(inlier RMS, robust σ of all usable points within 4 × `inlierThreshold` of the line).
     *    The robust σ (1.4826 · MAD of the residuals) is what actually detects a thick, noisy band: the
     *    inlier RMS alone can never exceed the inlier threshold. Noise > `maxRms` → [WallFitFailure.TOO_NOISY].
     * 6. Orient the line viewer-left; extents are the extreme inlier parameters in that orientation. A
     *    viewer within 5 cm of the line (interior side undefined) → [WallFitFailure.NO_DOMINANT_PLANE].
     * 7. Confidence, see [depthFitConfidence].
     *
     * Non-finite [floorY] / [viewer] degrade to [WallFitFailure.TOO_FEW_POINTS]; this never throws.
     */
    fun fromPoints(
        worldPoints: List<Vec3>,
        floorY: Double,
        ceilingY: Double?,
        viewer: Vec3,
        config: WallEstimatorConfig = WallEstimatorConfig(),
    ): WallFit {
        val total = worldPoints.size
        fun fail(reason: WallFitFailure, used: Int) = WallFit(null, reason, used, total)

        if (!floorY.isFinite() || !viewer.isFinite()) return fail(WallFitFailure.TOO_FEW_POINTS, 0)
        val viewerPlan = viewer.toPlan()
        val minInliers = config.minInliers.coerceAtLeast(2)

        // 1) Height band + range filter.
        val bandLow = floorY + config.minHeightAboveFloor
        val measuredHigh = ceilingY?.takeIf { it.isFinite() }?.let { it - config.minDistanceBelowCeiling }
        val bandHigh = if (measuredHigh != null && measuredHigh > bandLow) {
            measuredHigh
        } else {
            floorY + config.assumedCeilingHeight - config.minDistanceBelowCeiling
        }
        val maxRangeSq = config.maxRange * config.maxRange
        var inBand = 0
        val usable = ArrayList<Vec2>(worldPoints.size)
        for (p in worldPoints) {
            if (!p.isFinite() || p.y < bandLow || p.y > bandHigh) continue
            inBand++
            val q = p.toPlan()
            val dx = q.x - viewerPlan.x
            val dy = q.y - viewerPlan.y
            if (dx * dx + dy * dy <= maxRangeSq) usable.add(q)
        }

        // 2) Enough data?
        if (usable.size < minInliers) {
            val removedByRange = inBand - usable.size
            val reason = if (removedByRange * 2 > inBand) WallFitFailure.TOO_FAR else WallFitFailure.TOO_FEW_POINTS
            return fail(reason, usable.size)
        }

        // 3) Dominant line.
        val best = LineFit.ransac(usable, config.inlierThreshold, RANSAC_ITERATIONS, minInliers, PRIMARY_SEED)
            ?: return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)

        // 4) Prefer a parallel line behind the best one (the best one is then furniture).
        // When the farther line wins, its totalCount is the number of points NOT explained by the
        // foreground line, so its inlier ratio is not penalized for the furniture in front of it.
        val chosen = fartherParallelLine(usable, best, viewerPlan, config, minInliers) ?: best

        val inlierRatio = chosen.inlierRatio
        val extentLength = chosen.tMax - chosen.tMin
        if (inlierRatio < MIN_INLIER_RATIO || extentLength < MIN_EXTENT) {
            return fail(WallFitFailure.NO_DOMINANT_PLANE, chosen.inlierCount)
        }

        // 5) Noise.
        val noise = maxOf(chosen.rmsResidual, robustSigmaNear(usable, chosen.line, NOISE_WINDOW_FACTOR * config.inlierThreshold))
        if (!(noise <= config.maxRms)) return fail(WallFitFailure.TOO_NOISY, chosen.inlierCount)

        // 6) Orientation: viewer on the LEFT; extents along +direction in the final orientation.
        val side = chosen.line.signedDistance(viewerPlan)
        if (abs(side) < MIN_VIEWER_DISTANCE) return fail(WallFitFailure.NO_DOMINANT_PLANE, chosen.inlierCount)
        val (line, tMin, tMax) = if (side > 0) {
            Triple(chosen.line, chosen.tMin, chosen.tMax)
        } else {
            // flipped() keeps the anchor point and negates direction, so parameters negate and swap.
            Triple(chosen.line.flipped(), -chosen.tMax, -chosen.tMin)
        }
        val extentStart = line.at(tMin)
        val extentEnd = line.at(tMax)

        // 7) Confidence.
        val viewerDistance = Vec2.lerp(extentStart, extentEnd, 0.5).distanceTo(viewerPlan)
        val confidence = depthFitConfidence(
            inlierRatio = inlierRatio,
            noise = noise,
            maxRms = config.maxRms,
            inlierCount = chosen.inlierCount,
            extentLength = extentLength,
            viewerDistance = viewerDistance,
            maxRange = config.maxRange,
        )

        val observation = WallObservation(
            line = line,
            extentStart = extentStart,
            extentEnd = extentEnd,
            source = WallSource.DEPTH_FIT,
            rmsResidual = chosen.rmsResidual,
            pointCount = chosen.inlierCount,
            confidence = confidence,
            viewer = viewerPlan,
        )
        return WallFit(observation, null, chosen.inlierCount, total)
    }

    /**
     * Wall from an ARCore vertical plane: [center] = plane center, [normal] = plane normal (world),
     * [boundary] = plane polygon in world space (used for the observed extent).
     * Returns null if the plane is not close to vertical (|normal.y| > 0.2).
     *
     * Also returns null for degenerate input (non-finite values, zero normal, or a viewer standing in
     * the plane, which leaves the interior side undefined). The sign of [normal] does not matter: the
     * line is always oriented with the viewer on its left. Extent = extreme parameters of the finite
     * [boundary] points projected on the line (±0.5 m around the center when there are none).
     * Confidence is 0.8, scaled down linearly for observed extents shorter than 0.75 m (to a floor of
     * 25 % of that), because small ARCore planes are often partial or drifting.
     */
    fun fromPlane(center: Vec3, normal: Vec3, boundary: List<Vec3>, viewer: Vec3): WallObservation? {
        if (!center.isFinite() || !normal.isFinite() || !viewer.isFinite()) return null
        val n = normal.normalized()
        if (n == Vec3.ZERO || abs(n.y) > MAX_PLANE_NORMAL_Y) return null
        val planNormal = Vec2(n.x, -n.z).normalized()
        if (planNormal == Vec2.ZERO) return null

        val viewerPlan = viewer.toPlan()
        val raw = Line2(center.toPlan(), planNormal.perp())
        val side = raw.signedDistance(viewerPlan)
        if (abs(side) < MIN_VIEWER_DISTANCE) return null
        val line = if (side > 0) raw else raw.flipped()

        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY
        var counted = 0
        for (b in boundary) {
            if (!b.isFinite()) continue
            val t = line.param(b.toPlan())
            if (t < tMin) tMin = t
            if (t > tMax) tMax = t
            counted++
        }
        if (counted == 0) {
            tMin = -DEFAULT_PLANE_HALF_EXTENT
            tMax = DEFAULT_PLANE_HALF_EXTENT
        }
        val extent = tMax - tMin
        val confidence = PLANE_CONFIDENCE * (extent / 0.75).coerceIn(0.25, 1.0)

        return WallObservation(
            line = line,
            extentStart = line.at(tMin),
            extentEnd = line.at(tMax),
            source = WallSource.DETECTED_PLANE,
            rmsResidual = 0.0,
            pointCount = boundary.size,
            confidence = confidence,
            viewer = viewerPlan,
        )
    }

    /**
     * Wall from two floor points tapped along the base of the wall (fallback mode).
     *
     * Returns null when the taps are closer than 0.15 m in plan (direction undefined), when any input is
     * non-finite, or when the viewer stands (within 5 cm) on the tapped line so the interior side is
     * unknown. Extents are the two taps ordered along +direction; confidence 0.9.
     */
    fun fromBaseline(a: Vec3, b: Vec3, viewer: Vec3): WallObservation? {
        if (!a.isFinite() || !b.isFinite() || !viewer.isFinite()) return null
        val pa = a.toPlan()
        val pb = b.toPlan()
        if (pa.distanceTo(pb) < MIN_BASELINE_LENGTH) return null

        val viewerPlan = viewer.toPlan()
        val raw = Line2.through(pa, pb)
        val side = raw.signedDistance(viewerPlan)
        if (abs(side) < MIN_VIEWER_DISTANCE) return null
        val line = if (side > 0) raw else raw.flipped()
        val (start, end) = if (line.param(pa) <= line.param(pb)) pa to pb else pb to pa

        return WallObservation(
            line = line,
            extentStart = start,
            extentEnd = end,
            source = WallSource.BASELINE_TAPS,
            rmsResidual = 0.0,
            pointCount = 2,
            confidence = BASELINE_CONFIDENCE,
            viewer = viewerPlan,
        )
    }

    /**
     * Heuristic 0..1 quality score of a depth fit, the weighted geometric mean
     * `Π qᵢ^wᵢ` (weights sum to 1) of these factors, each clamped to [0.05, 1]:
     *
     * | factor | formula | weight | rationale |
     * |---|---|---|---|
     * | inlier ratio | ratio / 0.6 | 0.20 | ≥ 60 % of usable points on the wall = clean view |
     * | noise | 1 − (noise / maxRms)² | 0.25 | per-point depth noise vs. the rejection limit |
     * | inlier count | 1 − e^(−n / 150) | 0.15 | statistical support (25 → 0.15, 150 → 0.63, 500 → 0.96) |
     * | extent | length / 1.5 m | 0.20 | short spans give a poorly constrained direction |
     * | range | 1 up to 2.5 m, then linear to 0.4 at maxRange | 0.20 | depth error grows with range |
     *
     * A geometric mean lets one bad factor pull the score down clearly while keeping it in [0, 1].
     */
    internal fun depthFitConfidence(
        inlierRatio: Double,
        noise: Double,
        maxRms: Double,
        inlierCount: Int,
        extentLength: Double,
        viewerDistance: Double,
        maxRange: Double,
    ): Double {
        fun q(v: Double) = if (v.isNaN()) 0.05 else v.coerceIn(0.05, 1.0)
        val qRatio = q(inlierRatio / 0.6)
        val qNoise = q(if (maxRms > 0) 1.0 - (noise / maxRms) * (noise / maxRms) else 0.0)
        val qCount = q(1.0 - exp(-inlierCount / 150.0))
        val qExtent = q(extentLength / 1.5)
        val rangeSpan = (maxRange - 2.5).coerceAtLeast(1e-6)
        val qRange = q(if (viewerDistance <= 2.5) 1.0 else 1.0 - 0.6 * (viewerDistance - 2.5) / rangeSpan)
        val logScore = 0.20 * ln(qRatio) + 0.25 * ln(qNoise) + 0.15 * ln(qCount) +
            0.20 * ln(qExtent) + 0.20 * ln(qRange)
        return exp(logScore).coerceIn(0.0, 1.0)
    }

    /**
     * Step 4 of [fromPoints]: the best line among the points that are not inliers of [best], if it
     * qualifies as "the real wall behind furniture" (see the KDoc there); else null.
     */
    private fun fartherParallelLine(
        usable: List<Vec2>,
        best: LineFitResult,
        viewer: Vec2,
        config: WallEstimatorConfig,
        minInliers: Int,
    ): LineFitResult? {
        val bestLine = best.line
        val threshold = config.inlierThreshold
        // LineFit returns the caller's own Vec2 instances as inliers: remove exactly those.
        val bestInliers: MutableSet<Vec2> = Collections.newSetFromMap(IdentityHashMap())
        bestInliers.addAll(best.inliers)
        val remainder = usable.filter { it !in bestInliers }
        val needed = maxOf(minInliers.toDouble(), config.preferFartherRatio * best.inlierCount)
        if (remainder.size < needed) return null

        val second = LineFit.ransac(remainder, threshold, RANSAC_ITERATIONS, minInliers, SECONDARY_SEED)
            ?: return null
        if (second.inlierCount < config.preferFartherRatio * best.inlierCount) return null
        if (bestLine.sinAngleTo(second.line) > PARALLEL_SIN) return null

        val aligned = if ((second.line.direction dot bestLine.direction) < 0) second.line.flipped() else second.line
        val nearSide = bestLine.signedDistance(viewer)
        val farSide = aligned.signedDistance(viewer)
        val sameSide = nearSide * farSide > 0
        return if (sameSide && abs(farSide) - abs(nearSide) >= FARTHER_MARGIN) second else null
    }

    /**
     * Robust noise estimate: 1.4826 · MAD of the signed residuals of the points within [window] of
     * [line] (0 when there are none).
     */
    private fun robustSigmaNear(points: List<Vec2>, line: Line2, window: Double): Double {
        val residuals = ArrayList<Double>()
        for (p in points) {
            val d = line.signedDistance(p)
            if (abs(d) <= window) residuals.add(d)
        }
        if (residuals.isEmpty()) return 0.0
        return RobustStats.MAD_TO_SIGMA * RobustStats.mad(residuals)
    }
}
