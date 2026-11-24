package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class WallFitFailure(val message: String) {
    TOO_FEW_POINTS("Not enough depth points on the wall. Move closer or aim at a textured area."),
    NO_DOMINANT_PLANE("Couldn't find a flat wall at the crosshair. Aim at the middle of the wall, above furniture."),
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

/**
 * Tuning of [WallEstimator.fromPoints].
 *
 * Depth noise grows with range (depth-from-motion: σ(d) ≈ 5 mm + 1.2 % of d), so the inlier band and the
 * noise gate are range-adaptive: at the median horizontal range d of the usable points the estimator uses
 * `max(inlierThreshold, 2σ(d))` as the RANSAC / refinement band and `max(maxRms, 1.5σ(d))` as the
 * TOO_NOISY limit (see [inlierThresholdAt], [noiseLimitAt]). A fixed 2.5 cm band rejects every correct
 * capture beyond ~2 m and, worse, favours thin grazing-incidence side walls over the wall aimed at.
 */
data class WallEstimatorConfig(
    /** Ignore points lower than this above the floor (baseboards, floor bleed). */
    val minHeightAboveFloor: Double = 0.20,
    /** Ignore points closer than this to the ceiling. */
    val minDistanceBelowCeiling: Double = 0.15,
    /** Assumed ceiling height for filtering when none was measured. */
    val assumedCeilingHeight: Double = 2.4,
    /**
     * Points farther than this from the viewer (horizontal) are discarded; when that removes most of them
     * the fit fails with [WallFitFailure.TOO_FAR], whose message asks for ~4 m (the margin keeps captures
     * from 4 m reliable).
     */
    val maxRange: Double = 5.0,
    /** Minimum half-width (m) of the inlier band; widened to 2σ(d) at range. */
    val inlierThreshold: Double = 0.025,
    val minInliers: Int = 25,
    /**
     * Minimum per-point noise limit (m); widened to 1.5σ(d) at range. The noise compared against it is the
     * larger of the inlier RMS and a robust (MAD-based) σ of the points near the line; see
     * [WallEstimator.fromPoints].
     */
    val maxRms: Double = 0.03,
    /**
     * Without an aim point: when a second roughly-parallel surface lies FARTHER from the viewer than the
     * best line, overlaps it along the wall, rises above it and has at least this fraction of the best
     * line's inliers, prefer it (the near one is furniture standing in front of the wall).
     */
    val preferFartherRatio: Double = 0.5,
    /** Expected depth noise at zero range (m): σ(d) = noiseSigmaBase + noiseSigmaPerMeter · d. */
    val noiseSigmaBase: Double = 0.005,
    /** Growth of the expected depth noise per meter of range (dimensionless; 0.012 = 1.2 %). */
    val noiseSigmaPerMeter: Double = 0.012,
) {
    /** Expected per-sample depth noise σ (m) at [range] meters (along the viewing ray); 0 for invalid input. */
    fun expectedSigma(range: Double): Double {
        val r = if (range.isFinite()) range.coerceAtLeast(0.0) else 0.0
        val s = noiseSigmaBase + noiseSigmaPerMeter * r
        return if (s.isFinite() && s > 0.0) s else 0.0
    }

    /** Inlier band half-width at [range]: max([inlierThreshold], 2σ(range)). */
    fun inlierThresholdAt(range: Double): Double = max(inlierThreshold, 2.0 * expectedSigma(range))

    /** TOO_NOISY limit at [range]: max([maxRms], 1.5σ(range)). */
    fun noiseLimitAt(range: Double): Double = max(maxRms, 1.5 * expectedSigma(range))
}

/**
 * Turns raw AR measurements into [WallObservation]s. Walls are modeled as vertical planes, i.e. lines
 * in the plan after projecting points with Vec3.toPlan().
 *
 * Every observation produced here follows the shared orientation convention: the viewer (room
 * interior) is on the LEFT of `line.direction` (`line.signedDistance(viewer) > 0`, `line.normal`
 * points into the room) and `extentStart -> extentEnd` runs along `+line.direction`.
 */
object WallEstimator {
    /** Fixed RANSAC seeds (one per extracted line): results are fully deterministic for identical input. */
    private val SEEDS = longArrayOf(0x5EED_0001L, 0x5EED_0002L, 0x5EED_0003L)
    private const val RANSAC_ITERATIONS = 300

    /**
     * Lines extracted, each from the points the previous ones did not explain: the aimed-at wall, furniture,
     * a wall seen through a doorway, side walls. Without an aim point only the first two compete ("prefer
     * farther"); with one, all three can match it.
     */
    private const val CANDIDATES = 3

    /**
     * Refinement band per sample, in units of its expected perpendicular deviation σᵢ·|n·r̂ᵢ| (range noise
     * seen across the wall): about 2σ head-on, much tighter for oblique rays, where a uniform band would
     * swallow the adjacent wall's points near the corner. Clamped to [[MIN_REFINE_BAND], RANSAC band].
     */
    private const val REFINE_BAND_SIGMAS = 2.5

    /** Narrowest refinement band (m): unmodeled error (trim, slight bow, calibration) is never below this. */
    private const val MIN_REFINE_BAND = 0.01

    /** Hypotheses tried with the per-sample refinement bands (see [refine]). */
    private const val TIGHT_ITERATIONS = 200

    private const val TIGHT_SEED = 0x5EED_0010L

    /** Hypothesis pairs closer than this (m) give a poorly conditioned direction and are skipped. */
    private const val MIN_PAIR_DISTANCE = 0.1

    /** Corner-spur test (see [cornerSpur]): end zone width = max(this (m), [SPUR_ZONE_FRACTION] × span). */
    private const val MIN_SPUR_ZONE = 0.04
    private const val SPUR_ZONE_FRACTION = 0.05

    /**
     * Spans shorter than this many end zones are not tested: extrapolating a short central fit into the
     * zones is too uncertain for the sign test.
     */
    private const val MIN_SPUR_SPAN_ZONES = 6.0

    /** Threshold (z-score of the end zone's mean residual) of the corner-spur test. */
    private const val SPUR_Z = 3.5

    /** End zones with fewer samples are not tested. */
    private const val MIN_SPUR_SAMPLES = 12

    /** At most this many trims (an end can be trimmed again when a wide, oblique corner remains). */
    private const val MAX_SPUR_TRIMS = 4

    /** Two surfaces count as parallel within this angle (furniture test). */
    private val PARALLEL_SIN = sin(Math.toRadians(15.0))

    /** A surface counts as behind another when it is at least this much farther from the viewer (m). */
    private const val FARTHER_MARGIN = 0.10

    /**
     * Furniture test: the two surfaces' inlier intervals along the wall must overlap by at least this
     * fraction of the shorter one. Furniture covers the wall in front of it; a doorway or a step sits
     * BESIDE the near wall, so their intervals are (nearly) disjoint.
     */
    private const val MIN_OVERLAP_FRACTION = 0.5

    /** Furniture test: a surface's extent along the wall is this percentile range of its inlier positions. */
    private const val INTERVAL_LOW = 0.05
    private const val INTERVAL_HIGH = 0.95

    /**
     * Furniture test: the farther surface must reach at least this much higher, as an elevation angle seen
     * from the camera, than the near one (tops = [TOP_PERCENTILE] of the inliers' elevation angles): the
     * wall shows above a sofa back; nothing behind a full-height wall can be seen above it. (4°)
     */
    private val MIN_TOP_GAP_RAD = Math.toRadians(4.0)

    /** Percentile of the inliers' elevation angles used as a surface's "top" (robust to stray samples). */
    private const val TOP_PERCENTILE = 0.9

    /** Confidence multiplier when the wall behind furniture was chosen (it is partly occluded). */
    private const val BEHIND_FURNITURE_CONFIDENCE = 0.85

    /** Aim matching: a line passes through the aim point when within max(2 × band, this) of it (m). */
    private const val MIN_AIM_WINDOW = 0.05

    /** Floor (m) of the aim point's expected error (small protrusions: switches, frames, trim). */
    private const val MIN_AIM_SIGMA = 0.02

    /**
     * Aim matching fallback: with no candidate through the aim point, the choice made without aim point is
     * still used when the aim point lies within this multiple of the aim window of it (a noisy aim, a
     * slightly curved patch); farther away the crosshair is clearly on another surface and the fit fails.
     */
    private const val AIM_FALLBACK_FACTOR = 2.0

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
     * Noise is estimated from all usable points within this multiple of the inlier band of the line, so
     * that a thick (noisy) band is not hidden by the inlier gate.
     */
    private const val NOISE_WINDOW_FACTOR = 4.0

    /** The robust noise estimate uses the samples whose rays meet the wall in this percentile range of its inliers'. */
    private const val NOISE_CORE_LOW = 0.1
    private const val NOISE_CORE_HIGH = 0.9

    /**
     * Candidate lines closer than this (m) to the viewer are not surfaces but fans of range outliers along
     * one ray (they radiate from the camera); nobody captures a wall from closer than this.
     */
    private const val MIN_SURFACE_VIEWER_DISTANCE = 0.25

    /** Weighted / along-ray refinement passes (each re-selects the inliers against the refined line). */
    private const val REFINE_PASSES = 6

    /** Rays closer than this (|cos| to the line normal) to parallel keep their measured range for σ. */
    private const val MIN_RAY_COS = 0.05

    /** Floor (m) of a sample's expected plan noise when turned into a 1/σ² weight. */
    private const val MIN_WEIGHT_SIGMA = 1e-3

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
     * 3. Noise model: d = median horizontal range of the usable points; band = `inlierThresholdAt(d)`,
     *    noise limit = `noiseLimitAt(d)` (see [WallEstimatorConfig]).
     * 4. Candidate lines: RANSAC ([LineFit.ransac]) with that band, then again on the points the previous
     *    lines did not explain, up to 3 lines (wall, furniture, adjacent walls); a line passing within 25 cm
     *    of the viewer is a fan of range outliers along one ray, not a surface, and is skipped. No line →
     *    NO_DOMINANT_PLANE.
     * 5. Choice:
     *    * With a finite [aimPoint]: the wall is the candidate the aim point lies on. The aim point is a
     *      range sample, so it is compared ALONG the center ray (viewer → aim): the ray must meet the line
     *      within max(2 × band, 5 cm) of the aim point, inside the line's inlier span ± that window. When
     *      several match (near a corner) the dominant, best-matching one wins: score = inliers ·
     *      exp(−½(miss / σₐ)²) with σₐ = max(band / 2, 2 cm), the aim point's expected error, so a sparse
     *      streak of samples cannot beat the wall it crosses. A side wall seen at grazing incidence can pass
     *      a few centimetres from an aim point near the corner, but the center ray, almost parallel to it,
     *      meets it far beyond the aim: it never wins (the hallway end wall), and neither does a parallel
     *      wall seen through a doorway beside the aimed wall. If the aimed surface passes the furniture test
     *      below (a low object in front of a taller parallel surface), the user aimed at furniture →
     *      NO_DOMINANT_PLANE, whose message says to aim above it. If no candidate matches, the choice
     *      without aim point is used when it matches within 2 × that window; otherwise the crosshair is
     *      clearly on another surface → NO_DOMINANT_PLANE ("aim at the middle of the wall").
     *    * Without aim point: the best line, unless the second candidate is the wall behind furniture
     *      ("prefer farther"): it has ≥ `preferFartherRatio` × the best line's inliers and passes the
     *      furniture test. Confidence is then multiplied by 0.85.
     *    * Furniture test (near surface A in front of B): within 15° of parallel; B on the same side of the
     *      viewer ≥ 0.10 m farther away; their extents along the wall (5–95 % of the inlier positions)
     *      overlap by ≥ 50 % of the shorter one; B's top reaches ≥ 4° higher than A's (90th-percentile
     *      elevation angle of the inliers seen from the camera; in angles because the same view covers more
     *      height on a farther surface). Overlap and height separate furniture (in front of the wall,
     *      lower) from a doorway or step (beside it, full height).
     * 6. Refinement ([refine]) on all usable samples within a per-sample band of the chosen line (the noise
     *    a range error can cause across this line: tight for oblique rays, so the adjacent wall's samples
     *    near a corner stay out), weighted by 1/σᵢ² (σᵢ = the expected noise at the sample's own range,
     *    projected to the plan): an MSAC hypothesis search, weighted TLS, then the along-ray
     *    maximum-likelihood fit [LineFit.fitAlongRays] (depth error lies along the viewing ray, which tilts
     *    orthogonal regression for oblique views), re-selecting the inliers after each pass; corner
     *    clusters that still got in are trimmed ([cornerSpur]). A line that explains < 10 % of the usable
     *    points (not counting furniture in front of it), or whose inliers span < 0.10 m → NO_DOMINANT_PLANE.
     * 7. Noise = max(inlier RMS, robust σ of the usable points within 4 × band of the line whose rays meet it
     *    in the core of the wall (10–90 % of the inliers), away from the ends where adjacent walls' smeared
     *    samples and stray range outliers would count). The robust σ
     *    (1.4826 · MAD of the residuals) is what actually detects a thick, noisy band: the inlier RMS alone
     *    can never exceed the band. Noise > the noise limit → [WallFitFailure.TOO_NOISY].
     * 8. Orient the line viewer-left; extents are the extreme inlier parameters in that orientation. A
     *    viewer within 5 cm of the line (interior side undefined) → [WallFitFailure.NO_DOMINANT_PLANE].
     *    The line's anchor (`line.point`) is the projection of the inliers' 1/σ²-weighted centroid: where
     *    the wall's position is known best (RoomSolver rotates squared walls about it).
     * 9. Confidence, see [depthFitConfidence] (noise relative to the range-adaptive limit).
     *
     * Non-finite [floorY] / [viewer] degrade to [WallFitFailure.TOO_FEW_POINTS]; a non-finite [aimPoint]
     * is ignored; this never throws.
     */
    fun fromPoints(
        worldPoints: List<Vec3>,
        floorY: Double,
        ceilingY: Double?,
        viewer: Vec3,
        config: WallEstimatorConfig = WallEstimatorConfig(),
        /**
         * Where the user aimed: the reticle (screen-center) hit, e.g. the median of the center-ray hits over
         * the sampling frames. When given, the fitted wall must be the surface at the aim point (this resolves
         * furniture, doorways and side walls seen at grazing angles). Null = legacy heuristics only.
         */
        aimPoint: Vec3? = null,
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
        val samples = ArrayList<Sample>(worldPoints.size)
        for (p in worldPoints) {
            if (!p.isFinite() || p.y < bandLow || p.y > bandHigh) continue
            inBand++
            val q = p.toPlan()
            val dx = q.x - viewerPlan.x
            val dy = q.y - viewerPlan.y
            val horizontalSq = dx * dx + dy * dy
            if (horizontalSq > maxRangeSq) continue
            val horizontal = sqrt(horizontalSq)
            val range3d = p.distanceTo(viewer)
            // Depth error lies along the 3D ray; its horizontal part is scaled by cos(elevation).
            val elevationCos = if (range3d > 1e-9) horizontal / range3d else 1.0
            samples += Sample(q, p.y - floorY, horizontal, elevationCos)
        }

        // 2) Enough data?
        if (samples.size < minInliers) {
            val removedByRange = inBand - samples.size
            val reason = if (removedByRange * 2 > inBand) WallFitFailure.TOO_FAR else WallFitFailure.TOO_FEW_POINTS
            return fail(reason, samples.size)
        }
        val usable = samples.map { it.plan }

        // 3) Range-adaptive noise model.
        val medianRange = RobustStats.median(samples.map { it.horizontalRange })
        val threshold = config.inlierThresholdAt(medianRange)
        val noiseLimit = config.noiseLimitAt(medianRange)

        // 4) Candidate lines.
        val aimPlan = aimPoint?.takeIf { it.isFinite() }?.toPlan()
        val candidates = extractCandidates(usable, samples, viewerPlan, viewer.y - floorY, threshold, minInliers, CANDIDATES)
        if (candidates.isEmpty()) return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)

        // 5) Choice.
        val legacy = legacyChoice(candidates, viewerPlan, config, minInliers)
        val choice = if (aimPlan == null) {
            legacy
        } else {
            val window = max(2.0 * threshold, MIN_AIM_WINDOW)
            when (val aimed = aimChoice(candidates, aimPlan, window, threshold, viewerPlan, minInliers)) {
                is AimOutcome.OnWall -> Choice(aimed.wall, null)
                AimOutcome.OnFurniture -> return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)
                AimOutcome.Elsewhere ->
                    if (aimMiss(legacy.wall.fit, aimPlan, viewerPlan, AIM_FALLBACK_FACTOR * window) != null) {
                        legacy
                    } else {
                        return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)
                    }
            }
        }

        // 6) Refinement on all usable points.
        val sigmas = expectedPlanSigmas(samples, choice.wall.fit.line, viewerPlan, config)
        val weights = sigmas.map { s -> max(s, MIN_WEIGHT_SIGMA).let { 1.0 / (it * it) } }
        val refined = refine(choice.wall.fit.line, usable, weights, sigmas, viewerPlan, threshold, minInliers)
            ?: return fail(WallFitFailure.NO_DOMINANT_PLANE, choice.wall.fit.inlierCount)
        val explained = usable.size - (choice.foreground?.fit?.inlierCount ?: 0)
        val inlierRatio = if (explained > 0) min(1.0, refined.count.toDouble() / explained) else 0.0
        val extentLength = refined.tMax - refined.tMin
        if (inlierRatio < MIN_INLIER_RATIO || extentLength < MIN_EXTENT) {
            return fail(WallFitFailure.NO_DOMINANT_PLANE, refined.count)
        }

        // 7) Noise.
        val noise = maxOf(
            refined.rms,
            robustSigmaNear(usable, refined.line, NOISE_WINDOW_FACTOR * threshold, viewerPlan, refined.centralLo, refined.centralHi),
        )
        if (!(noise <= noiseLimit)) return fail(WallFitFailure.TOO_NOISY, refined.count)

        // 8) Orientation: viewer on the LEFT; extents along +direction in the final orientation.
        val side = refined.line.signedDistance(viewerPlan)
        if (abs(side) < MIN_VIEWER_DISTANCE) return fail(WallFitFailure.NO_DOMINANT_PLANE, refined.count)
        val (line, tMin, tMax) = if (side > 0) {
            Triple(refined.line, refined.tMin, refined.tMax)
        } else {
            // flipped() keeps the anchor point and negates direction, so parameters negate and swap.
            Triple(refined.line.flipped(), -refined.tMax, -refined.tMin)
        }
        val extentStart = line.at(tMin)
        val extentEnd = line.at(tMax)

        // 9) Confidence.
        val viewerDistance = Vec2.lerp(extentStart, extentEnd, 0.5).distanceTo(viewerPlan)
        val confidence = depthFitConfidence(
            inlierRatio = inlierRatio,
            noise = noise,
            maxRms = noiseLimit,
            inlierCount = refined.count,
            extentLength = extentLength,
            viewerDistance = viewerDistance,
            maxRange = config.maxRange,
        ) * (if (choice.foreground != null) BEHIND_FURNITURE_CONFIDENCE else 1.0)

        val observation = WallObservation(
            line = line,
            extentStart = extentStart,
            extentEnd = extentEnd,
            source = WallSource.DEPTH_FIT,
            rmsResidual = refined.rms,
            pointCount = refined.count,
            confidence = confidence,
            viewer = viewerPlan,
        )
        return WallFit(observation, null, refined.count, total)
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
     * [fromPoints] passes its range-adaptive noise limit as [maxRms].
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

    // ---------------------------------------------------------------------------------------------
    // fromPoints internals

    /** A usable depth sample: plan position, height above the floor, horizontal range, cos(elevation) of its ray. */
    private class Sample(val plan: Vec2, val height: Double, val horizontalRange: Double, val elevationCos: Double)

    /**
     * Each sample's expected depth noise, projected to the plan: σ(range) · cos(elevation) along its ray.
     *
     * The range is where the sample's ray meets [line] (the chosen surface), not the sample's own measured
     * range: weighting by the measured range would favour samples measured too short (closer → smaller
     * expected σ → larger weight) and pull every wall ~2·k·σ (≈ 0.7 mm at 2–3 m, k = noiseSigmaPerMeter)
     * towards the viewer, which shortens rooms systematically. Rays nearly parallel to the line keep the
     * measured range.
     */
    private fun expectedPlanSigmas(samples: List<Sample>, line: Line2, viewer: Vec2, config: WallEstimatorConfig): List<Double> {
        val n = line.normal
        val offset = n dot (line.point - viewer)
        return samples.map { s ->
            val d = s.plan - viewer
            val len = d.length
            val g = if (len > 1e-9) (n dot d) / len else 0.0
            val horizontal = if (abs(g) > MIN_RAY_COS) (offset / g).takeIf { it > 0 } ?: s.horizontalRange else s.horizontalRange
            val cosElevation = s.elevationCos.coerceIn(1e-3, 1.0)
            config.expectedSigma(horizontal / cosElevation) * cosElevation
        }
    }

    /** A candidate surface: its RANSAC line and its top: the 90th-percentile elevation angle of its inliers. */
    private class Candidate(val fit: LineFitResult, val topAngle: Double)

    /** The wall to refine, and the furniture surface in front of it when "prefer farther" chose it. */
    private class Choice(val wall: Candidate, val foreground: Candidate?)

    private sealed interface AimOutcome {
        class OnWall(val wall: Candidate) : AimOutcome

        /** The aimed surface is a low object in front of a taller parallel surface. */
        data object OnFurniture : AimOutcome

        /** No candidate passes through the aim point. */
        data object Elsewhere : AimOutcome
    }

    /**
     * Up to [count] RANSAC lines: each from the points the previous lines did not explain, so a second /
     * third surface in the patch (furniture, a side wall, a wall seen through a doorway) gets its own line.
     */
    private fun extractCandidates(
        usable: List<Vec2>,
        samples: List<Sample>,
        viewer: Vec2,
        cameraHeight: Double,
        threshold: Double,
        minInliers: Int,
        count: Int,
    ): List<Candidate> {
        val index = IdentityHashMap<Vec2, Int>(usable.size * 2)
        usable.forEachIndexed { i, p -> index[p] = i }
        val out = ArrayList<Candidate>(count)
        var remaining = usable
        for (k in 0 until count) {
            if (remaining.size < minInliers) break
            val fit = LineFit.ransac(remaining, threshold, RANSAC_ITERATIONS, minInliers, SEEDS[k % SEEDS.size]) ?: break
            // A "line" through the viewer is a fan of range outliers along one ray, not a surface: no wall can
            // be seen edge-on from inside it. Its points are still set aside.
            if (fit.line.distance(viewer) >= MIN_SURFACE_VIEWER_DISTANCE) {
                // LineFit returns the caller's own Vec2 instances as inliers: map them back to samples.
                val elevations = fit.inliers.mapNotNull { p ->
                    index[p]?.let { samples[it] }?.let { s -> atan2(s.height - cameraHeight, s.horizontalRange) }
                }.sorted()
                out += Candidate(fit, percentile(elevations, TOP_PERCENTILE))
            }
            val taken = IdentityHashMap<Vec2, Boolean>(fit.inliers.size * 2)
            fit.inliers.forEach { taken[it] = true }
            remaining = remaining.filter { it !in taken }
        }
        return out
    }

    /** Without an aim point: the best line, or the wall behind it when the best line is furniture. */
    private fun legacyChoice(candidates: List<Candidate>, viewer: Vec2, config: WallEstimatorConfig, minInliers: Int): Choice {
        val best = candidates[0]
        val second = candidates.getOrNull(1)
        val needed = maxOf(minInliers.toDouble(), config.preferFartherRatio * best.fit.inlierCount)
        return if (second != null && second.fit.inlierCount >= needed && isInFrontOf(best, second, viewer)) {
            Choice(second, best)
        } else {
            Choice(best, null)
        }
    }

    /**
     * The dominant candidate the aim point lies on (see [fromPoints], step 5), unless that surface is
     * furniture. [threshold] is the inlier band; ties keep the earlier (larger) candidate.
     */
    private fun aimChoice(
        candidates: List<Candidate>,
        aim: Vec2,
        window: Double,
        threshold: Double,
        viewer: Vec2,
        minInliers: Int,
    ): AimOutcome {
        val aimSigma = max(threshold / 2, MIN_AIM_SIGMA)
        var hit: Candidate? = null
        var hitScore = 0.0
        for (c in candidates) {
            val miss = aimMiss(c.fit, aim, viewer, window) ?: continue
            val z = miss / aimSigma
            val score = c.fit.inlierCount * exp(-0.5 * z * z)
            if (hit == null || score > hitScore) {
                hit = c
                hitScore = score
            }
        }
        val wall = hit ?: return AimOutcome.Elsewhere
        val furniture = candidates.any { other ->
            other !== wall && other.fit.inlierCount >= minInliers && isInFrontOf(wall, other, viewer)
        }
        return if (furniture) AimOutcome.OnFurniture else AimOutcome.OnWall(wall)
    }

    /**
     * How far (m) the aim point lies from where the center ray (viewer → [aim]) meets the candidate line,
     * measured ALONG that ray, when that is within [window] and the meeting point within the line's inlier
     * span ± [window]; else null.
     *
     * Along the ray, because that is how the aim point errs (it is a range sample); and it decides the
     * corner cases right: a side wall seen at grazing incidence can pass a few centimetres from an aim point
     * near the corner, but the center ray, nearly parallel to it, meets it far beyond the aim.
     */
    private fun aimMiss(fit: LineFitResult, aim: Vec2, viewer: Vec2, window: Double): Double? {
        val toAim = aim - viewer
        val range = toAim.length
        if (!(range > 1e-6)) return null
        val ray = toAim / range
        val n = fit.line.normal
        val g = n dot ray
        if (abs(g) < 1e-6) return null
        val hitRange = (n dot (fit.line.point - viewer)) / g
        if (!(hitRange > 0)) return null
        val miss = abs(hitRange - range)
        if (miss > window) return null
        val t = fit.line.param(viewer + ray * hitRange)
        return if (t >= fit.tMin - window && t <= fit.tMax + window) miss else null
    }

    /**
     * Furniture test: [near] is a lower object standing in front of the taller surface [far] (see
     * [fromPoints], step 5). Intervals along the wall are the 5–95 % range of the inlier positions (a few
     * stray samples in a band must not stretch them); "taller" compares the surfaces' top elevation
     * ANGLES as seen from the camera, because the same field of view covers more height on a farther
     * surface (a wall seen through a doorway would otherwise look taller than the wall beside it).
     */
    private fun isInFrontOf(near: Candidate, far: Candidate, viewer: Vec2): Boolean {
        val nearLine = near.fit.line
        if (nearLine.sinAngleTo(far.fit.line) > PARALLEL_SIN) return false
        val aligned = if ((far.fit.line.direction dot nearLine.direction) < 0) far.fit.line.flipped() else far.fit.line
        val nearSide = nearLine.signedDistance(viewer)
        val farSide = aligned.signedDistance(viewer)
        if (nearSide * farSide <= 0 || abs(farSide) - abs(nearSide) < FARTHER_MARGIN) return false
        // Overlap along the wall, both intervals measured on the near line.
        val a = near.fit.inliers.map { nearLine.param(it) }.sorted()
        val b = far.fit.inliers.map { nearLine.param(it) }.sorted()
        val a0 = percentile(a, INTERVAL_LOW)
        val a1 = percentile(a, INTERVAL_HIGH)
        val b0 = percentile(b, INTERVAL_LOW)
        val b1 = percentile(b, INTERVAL_HIGH)
        val shorter = min(a1 - a0, b1 - b0)
        if (!(shorter > 0.0)) return false
        val overlap = min(a1, b1) - max(a0, b0)
        if (overlap < MIN_OVERLAP_FRACTION * shorter) return false
        return far.topAngle - near.topAngle >= MIN_TOP_GAP_RAD
    }

    /**
     * The refined wall: line, inlier count and RMS, inlier span [tMin]..[tMax], and [centralLo]..[centralHi],
     * the 10–90 % range of where the inliers' rays meet the line (the wall's core, robust to stray samples
     * at the ends).
     */
    private class Refined(
        val line: Line2,
        val count: Int,
        val rms: Double,
        val tMin: Double,
        val tMax: Double,
        val centralLo: Double,
        val centralHi: Double,
    )

    /**
     * Step 6 of [fromPoints]: the chosen surface's line, re-estimated from all usable points. Every reported
     * statistic is computed against the returned line; null when fewer than [minInliers] support it.
     *
     * A sample supports a line when it lies within its own band, `2.5 · σᵢ·|n·r̂ᵢ|` clamped to
     * [1 cm, [threshold]]: depth error runs along the viewing ray r̂ᵢ, so σᵢ·|n·r̂ᵢ| is the deviation it can
     * have across a line with normal n. Head-on that is the usual ~2σ band; for oblique rays it is much
     * tighter, which matters at corners: the adjacent wall's points near the corner all lie on one side of
     * the line, and a uniform band lets them pull the line's end towards them (a side wall seen at grazing
     * incidence collected ~120 such points in simulation and tilted 1.4°; with per-sample bands, 0.01°).
     *
     * 1. Hypotheses: the [initial] line plus [TIGHT_ITERATIONS] lines through random pairs (seeded) of the
     *    samples within [threshold] of it, scored MSAC-style, Σ min((dᵢ / sᵢ)², 2.5²) with sᵢ = bandᵢ / 2.5.
     *    Unlike a plain count this prefers the line that fits the bulk of the wall tightly over one tilted
     *    to grab a cluster at the edge of the band; and the uniform-band RANSAC line alone can sit in such a
     *    tilted optimum.
     * 2. Up to [REFINE_PASSES] passes of weighted TLS then the along-ray ML fit [LineFit.fitAlongRays] on
     *    the supporting samples (weights 1/σᵢ²), re-selecting them after each pass until stable.
     * 3. Corner clusters the bands still let in are trimmed ([cornerSpur]) and the passes repeated.
     */
    private fun refine(
        initial: Line2,
        usable: List<Vec2>,
        weights: List<Double>,
        sigmas: List<Double>,
        viewer: Vec2,
        threshold: Double,
        minInliers: Int,
    ): Refined? {
        val n = usable.size
        val rays = usable.map { (it - viewer).normalized() }
        val minBand = min(MIN_REFINE_BAND, threshold)
        fun band(line: Line2, i: Int) = (REFINE_BAND_SIGMAS * sigmas[i] * abs(line.normal dot rays[i])).coerceIn(minBand, threshold)
        fun select(line: Line2, excluded: Set<Int>): List<Int> =
            (0 until n).filter { it !in excluded && line.distance(usable[it]) <= band(line, it) }

        // 1) Hypothesis search with the per-sample bands (MSAC score).
        val pool = (0 until n).filter { initial.distance(usable[it]) <= threshold }
        if (pool.size < minInliers) return null
        val capCost = REFINE_BAND_SIGMAS * REFINE_BAND_SIGMAS
        var line = initial
        var bestCost = Double.POSITIVE_INFINITY
        fun consider(hypothesis: Line2) {
            var cost = 0.0
            for (i in 0 until n) {
                val d = hypothesis.distance(usable[i])
                val z = if (d > threshold) REFINE_BAND_SIGMAS else d / band(hypothesis, i) * REFINE_BAND_SIGMAS
                cost += min(z * z, capCost)
                if (cost >= bestCost) return
            }
            bestCost = cost
            line = hypothesis
        }
        consider(initial)
        val random = java.util.Random(TIGHT_SEED)
        val minPairDistSq = MIN_PAIR_DISTANCE * MIN_PAIR_DISTANCE
        for (iter in 0 until TIGHT_ITERATIONS) {
            val a = usable[pool[random.nextInt(pool.size)]]
            val b = usable[pool[random.nextInt(pool.size)]]
            val dx = b.x - a.x
            val dy = b.y - a.y
            if (dx * dx + dy * dy < minPairDistSq) continue
            consider(Line2.through(a, b))
        }

        // 2) Weighted TLS + along-ray ML on the supporting samples; 3) trim corner clusters and refit.
        val excluded = HashSet<Int>()
        var inliers = select(line, excluded)
        if (inliers.size < minInliers) return null
        fun fitPasses() {
            for (pass in 0 until REFINE_PASSES) {
                val pts = inliers.map { usable[it] }
                val w = inliers.map { weights[it] }
                val tls = LineFit.weightedTotalLeastSquares(pts, w) ?: break
                val ml = LineFit.fitAlongRays(pts, w, viewer, tls) ?: tls
                val reselected = select(ml, excluded)
                if (reselected.size < minInliers) break
                val stable = reselected == inliers
                line = ml
                inliers = reselected
                if (stable) break
            }
        }
        fitPasses()
        for (trim in 0 until MAX_SPUR_TRIMS) {
            val spur = cornerSpur(line, usable, weights, viewer, inliers)
            if (spur.isEmpty() || inliers.size - spur.size < minInliers) break
            excluded += spur
            inliers = inliers.filter { it !in excluded }
            fitPasses()
        }
        var sumSq = 0.0
        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY
        for (i in inliers) {
            val p = usable[i]
            val d = line.signedDistance(p)
            sumSq += d * d
            val t = line.param(p)
            if (t < tMin) tMin = t
            if (t > tMax) tMax = t
        }
        val rayHits = inliers.map { rayHitParam(line, viewer, usable[it]) }.sorted()
        return Refined(
            line, inliers.size, sqrt(sumSq / inliers.size), tMin, tMax,
            percentile(rayHits, NOISE_CORE_LOW), percentile(rayHits, NOISE_CORE_HIGH),
        )
    }

    /**
     * Inliers of [line] forming a corner "spur" at either end of its span, else empty.
     *
     * An adjacent wall meeting this one at a corner leaves a compact cluster of samples inside the band
     * right at the end of the span, all on ONE side of the line (the room side at a convex corner, the
     * outside at a reflex one), which tilts the line towards it. The adjacent wall is not always a
     * candidate of its own (seen at grazing incidence its few points can lose RANSAC to a line through
     * both side walls), so it is detected from that asymmetry instead. The line is re-fitted WITHOUT both
     * end zones (the last max(4 cm, 5 % of the span) at each end; with a zone in, the line tilts towards
     * its cluster and hides much of the asymmetry), and each zone's mean residual against that central
     * line is tested: |mean| / (σ / √n) > 3.5 with σ the robust (MAD) noise of the central samples.
     * A clean wall practically never gets there (p ≈ 5·10⁻⁴), a corner cluster (whose residuals are
     * offset by up to the band) reliably does; a sign test was not enough, because the cluster's own range
     * noise spreads it over both sides. Spans under 6 zones are not tested. The zone's samples (both sides,
     * so the remaining wall samples stay symmetric) are returned for removal.
     */
    private fun cornerSpur(line: Line2, usable: List<Vec2>, weights: List<Double>, viewer: Vec2, inliers: List<Int>): List<Int> {
        if (inliers.isEmpty()) return emptyList()
        // Zone membership by where each sample's RAY meets the line, not by the sample's own position: range
        // noise moves a sample along its ray, so samples measured too far land farther out along an oblique
        // ray, and selecting the outermost positions would favour them (a clean wall's test statistic came
        // out at z ≈ -1.3 on average that way).
        val ts = DoubleArray(inliers.size) { k -> rayHitParam(line, viewer, usable[inliers[k]]) }
        val tMin = ts.min()
        val tMax = ts.max()
        val zone = max(MIN_SPUR_ZONE, SPUR_ZONE_FRACTION * (tMax - tMin))
        if (tMax - tMin < MIN_SPUR_SPAN_ZONES * zone) return emptyList()
        // Reference: the wall without BOTH end zones (either end may carry a corner cluster).
        val central = inliers.indices.filter { ts[it] > tMin + zone && ts[it] < tMax - zone }.map { inliers[it] }
        if (central.size < MIN_SPUR_SAMPLES) return emptyList()
        val centralPoints = central.map { usable[it] }
        val centralWeights = central.map { weights[it] }
        val tls = LineFit.weightedTotalLeastSquares(centralPoints, centralWeights) ?: return emptyList()
        val reference = LineFit.fitAlongRays(centralPoints, centralWeights, viewer, tls) ?: tls
        val ref = if ((reference.direction dot line.direction) < 0) reference.flipped() else reference
        val sigma = RobustStats.MAD_TO_SIGMA * RobustStats.mad(central.map { ref.signedDistance(usable[it]) })
        if (!(sigma > 0.0)) return emptyList()
        val out = ArrayList<Int>()
        for (atStart in listOf(true, false)) {
            val members = ArrayList<Int>()
            for (k in inliers.indices) {
                val inZone = if (atStart) ts[k] <= tMin + zone else ts[k] >= tMax - zone
                if (inZone) members += inliers[k]
            }
            if (members.size < MIN_SPUR_SAMPLES) continue
            val mean = members.sumOf { ref.signedDistance(usable[it]) } / members.size
            val z = mean / (sigma / sqrt(members.size.toDouble()))
            if (abs(z) > SPUR_Z) out += members
        }
        return out
    }

    /** [q]-quantile of an ascending list (NaN when empty). */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        return sorted[ceil((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
    }

    /**
     * Robust noise estimate: 1.4826 · MAD of the signed residuals of the points within [window] of
     * [line] whose rays meet it within [lo]..[hi] (the wall's core, see [Refined]); 0 when there are
     * none. Near the span's ends the adjacent walls' samples, smeared along their grazing rays, and range
     * outliers of the rays that just miss the wall also fall inside the wide window and would read as
     * noise.
     */
    private fun robustSigmaNear(points: List<Vec2>, line: Line2, window: Double, viewer: Vec2, lo: Double, hi: Double): Double {
        val residuals = ArrayList<Double>()
        for (p in points) {
            val d = line.signedDistance(p)
            if (abs(d) > window) continue
            val t = rayHitParam(line, viewer, p)
            if (t >= lo && t <= hi) residuals.add(d)
        }
        if (residuals.isEmpty()) return 0.0
        return RobustStats.MAD_TO_SIGMA * RobustStats.mad(residuals)
    }

    /**
     * Parameter along [line] where the ray from [viewer] through [p] meets it: independent of the range
     * error of [p], which only moves it along that ray. Rays (nearly) parallel to the line fall back to
     * [p]'s own projection.
     */
    private fun rayHitParam(line: Line2, viewer: Vec2, p: Vec2): Double {
        val n = line.normal
        val r = (p - viewer).normalized()
        val g = n dot r
        return if (abs(g) > 1e-6) line.param(viewer + r * ((n dot (line.point - viewer)) / g)) else line.param(p)
    }
}
