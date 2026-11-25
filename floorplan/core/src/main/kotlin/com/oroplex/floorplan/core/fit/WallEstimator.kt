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
import kotlin.math.floor
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
 *
 * [hint] is a more specific, actionable message for some failures (e.g. a short wall seen at an angle,
 * which is reported as [WallFitFailure.NO_DOMINANT_PLANE]); show [message], which falls back to the
 * failure's generic message.
 */
data class WallFit(
    val observation: WallObservation?,
    val failure: WallFitFailure?,
    val usedPoints: Int,
    val totalPoints: Int,
    val hint: String? = null,
) {
    /** What to tell the user about a failed fit: [hint] when there is one, else the failure's message; null on success. */
    val message: String? get() = if (failure == null) null else hint ?: failure.message
}

/**
 * Tuning of [WallEstimator.fromPoints].
 *
 * Depth noise grows with range (depth-from-motion: σ(d) ≈ 5 mm + 1.2 % of d), so the inlier band is
 * range-adaptive: at the median horizontal range d of the usable points the estimator uses
 * `max(inlierThreshold, 2σ(d))` as the RANSAC band ([inlierThresholdAt]). A fixed 2.5 cm band rejects every
 * correct capture beyond ~2 m and, worse, favours thin grazing-incidence side walls over the wall aimed at.
 *
 * The noise model is a prior, not a fact (review round 2, MATH-4): a sensor that is 1.5–2× noisier than it
 * must still measure walls, because with hundreds of samples the fitted LINE stays precise. So a capture is
 * rejected for noise only when the per-point noise is grossly off the model ([grossNoiseFactor], see
 * [noiseLimitAt]) or when the line itself is imprecise ([maxOffsetError], [maxDirectionErrorDeg]); in between,
 * the measured-to-expected noise ratio only lowers the confidence.
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
     * Per-point noise (m) that is always acceptable: the gross noise cap ([noiseLimitAt]) never goes below it,
     * so close-range captures, where the model expects only millimetres, are not rejected for unmodeled
     * centimetre-level texture. The noise is the robust (MAD-based) σ of the points near the line.
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
    /**
     * Gross per-point noise cap, as a multiple of the expected noise: beyond it the samples are not a wall
     * reading at all (motion blur, a glossy or dark surface, a moving person) → [WallFitFailure.TOO_NOISY].
     */
    val grossNoiseFactor: Double = 3.5,
    /** Largest acceptable standard error (m) of the fitted line's offset. */
    val maxOffsetError: Double = 0.01,
    /** Largest acceptable standard error (degrees) of the fitted line's direction. */
    val maxDirectionErrorDeg: Double = 1.5,
    /**
     * Cap on the sample count used for the line's standard errors: neighbouring depth samples (and all
     * samples of one frame) share errors, so thousands of samples do not make a line thousands of times
     * more precise.
     */
    val maxEffectivePoints: Int = 300,
) {
    /** Expected per-sample depth noise σ (m) at [range] meters (along the viewing ray); 0 for invalid input. */
    fun expectedSigma(range: Double): Double {
        val r = if (range.isFinite()) range.coerceAtLeast(0.0) else 0.0
        val s = noiseSigmaBase + noiseSigmaPerMeter * r
        return if (s.isFinite() && s > 0.0) s else 0.0
    }

    /** Inlier band half-width at [range]: max([inlierThreshold], 2σ(range)). */
    fun inlierThresholdAt(range: Double): Double = max(inlierThreshold, 2.0 * expectedSigma(range))

    /**
     * Gross per-point noise cap for a wall seen head-on at [range]: max([maxRms], [grossNoiseFactor] · σ(range)).
     * [WallEstimator.fromPoints] applies it to the noise expected across the wall, which is smaller for
     * oblique views (range error runs along the ray).
     */
    fun noiseLimitAt(range: Double): Double = max(maxRms, grossNoiseFactor * expectedSigma(range))

    /**
     * Standard errors of a line fitted to [count] samples with per-point noise [sigma] (m) spread over
     * [extent] (m): offset σ/√n and direction √12·σ/(√n·L) (radians), n capped at [maxEffectivePoints].
     * Non-finite or non-positive input gives infinite errors.
     */
    fun lineErrors(sigma: Double, count: Int, extent: Double): Pair<Double, Double> {
        val n = count.coerceIn(0, maxEffectivePoints.coerceAtLeast(1)).toDouble()
        if (!(n > 0) || !sigma.isFinite() || sigma < 0 || !(extent > 0) || !extent.isFinite()) {
            return Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY
        }
        return sigma / sqrt(n) to sqrt(12.0) * sigma / (sqrt(n) * extent)
    }
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
    private val SEEDS = longArrayOf(0x5EED_0001L, 0x5EED_0002L, 0x5EED_0003L, 0x5EED_0004L)
    private const val RANSAC_ITERATIONS = 300

    /**
     * Lines extracted, each from the points the previous ones did not explain: the aimed-at wall, furniture,
     * a wall seen through a doorway, side walls, and near a corner a chord through the corner's samples. Without
     * an aim point only the first two compete ("prefer farther"); with one, every candidate through the aim
     * point is tried in turn.
     */
    private const val CANDIDATES = 4

    /**
     * Refinement band per sample, in units of its expected perpendicular deviation σᵢ·|n·r̂ᵢ| (range noise
     * seen across the wall): about 2σ head-on, much tighter for oblique rays, where a uniform band would
     * swallow the adjacent wall's points near the corner. Clamped to [[MIN_REFINE_BAND], RANSAC band].
     */
    private const val REFINE_BAND_SIGMAS = 2.5

    /** Narrowest refinement band (m): unmodeled error (trim, slight bow, calibration) is never below this. */
    private const val MIN_REFINE_BAND = 0.01

    /** Smallest expected perpendicular noise (m) of one sample (the refinement band floor in σ units). */
    private const val MIN_PERP_SIGMA = MIN_REFINE_BAND / REFINE_BAND_SIGMAS

    /** Hypotheses tried with the per-sample refinement bands (see [refine]). */
    private const val TIGHT_ITERATIONS = 200

    private const val TIGHT_SEED = 0x5EED_0010L

    /**
     * Refinement re-selects its samples only within this multiple of the RANSAC band around the chosen
     * candidate (the hypothesis search scores only the samples within one band): it may straighten a slightly
     * tilted candidate, but never walk off onto the next face of a corner (review round 2, MATH-1).
     */
    private const val REFINE_UNIVERSE_FACTOR = 1.5

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

    /**
     * Lack-of-fit test (see [Refined.lackOfFit]): the inliers are split into up to [LOF_MAX_BINS] equal-count
     * bins along the wall (by where their rays meet it), each of at least [LOF_MIN_BIN] samples; a bin whose
     * mean residual is more than [LOF_Z] standard errors AND more than [LOF_MIN_DEVIATION_SIGMAS] expected
     * per-point σ (and [LOF_MIN_DEVIATION] m) off the line shows that the "wall" bends: it is a chord across
     * several faces of a corner, not one flat face.
     */
    private const val LOF_MAX_BINS = 6
    private const val LOF_MIN_BINS = 3
    private const val LOF_MIN_BIN = 25
    private const val LOF_Z = 4.0
    private const val LOF_MIN_DEVIATION = 0.004
    private const val LOF_MIN_DEVIATION_SIGMAS = 0.2

    /**
     * Depth-profile segmentation (see [segment]): rays are binned by azimuth in bins this wide (degrees); a bin
     * needs [SEGMENT_MIN_BIN] in-band samples to be judged; its median residual may be off the line by
     * [SEGMENT_Z] standard errors of a median (√(π/2) σ/√n) or [SEGMENT_MIN_DEVIATION] (m), whichever is larger;
     * the noise ratio used for the standard error is capped at [MAX_SEGMENT_NOISE_RATIO]. At most
     * [SEGMENT_ROUNDS] segment / refit rounds.
     */
    private const val SEGMENT_BIN_DEG = 0.5
    private const val SEGMENT_MIN_BIN = 8
    private const val SEGMENT_Z = 3.5
    private const val SEGMENT_MIN_DEVIATION = 0.005
    private const val MAX_SEGMENT_NOISE_RATIO = 2.5
    private const val SEGMENT_ROUNDS = 3
    private const val MEDIAN_SE_FACTOR = 1.2533

    /** The measured noise ratio widens the post-refinement aim window up to this factor (see [POST_AIM_SIGMAS]). */
    private const val MAX_AIM_NOISE_RATIO = 1.5

    /**
     * After refinement the aim point must lie on the surface within [POST_AIM_SIGMAS] of its own expected
     * error: [POST_AIM_SIGMA_FACTOR] × σ(range) (a median of a handful of center-ray hits) × the measured noise
     * ratio when the sensor is noisier than the model; never tighter than [MIN_POST_AIM_WINDOW] (m).
     */
    private const val POST_AIM_SIGMA_FACTOR = 0.6
    private const val POST_AIM_SIGMAS = 3.0
    private const val MIN_POST_AIM_WINDOW = 0.03

    /** The center ray must meet a refined surface within its robust extent ± this (m). */
    private const val POST_AIM_EXTENT_MARGIN = 0.03

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
     * still tried when the aim point lies within this multiple of the aim window of it (a noisy aim, a
     * slightly curved patch); its refined line must then pass through the aim point like any other. Farther
     * away the crosshair is clearly on another surface and the fit fails.
     */
    private const val AIM_FALLBACK_FACTOR = 2.0

    /**
     * A wall line supported by less than this fraction of the usable points is not a dominant plane
     * (a large scattered cloud always contains SOME strip with [WallEstimatorConfig.minInliers] points).
     */
    private const val MIN_INLIER_RATIO = 0.10

    /** Observed wall extents shorter than this (m) cannot define a direction reliably. */
    private const val MIN_EXTENT = 0.10

    /**
     * Observed extent: the [EXTENT_LOW]–[EXTENT_HIGH] quantiles of where the inliers' rays meet the wall,
     * widened by the share they cut off (unbiased for evenly covered walls). The extreme inliers are not
     * used: a few range outliers on rays that pass the wall's end land inside the band beyond the corner and
     * stretched extents by up to 1.7 m in simulation (review round 2, MATH-6).
     */
    private const val EXTENT_LOW = 0.02
    private const val EXTENT_HIGH = 0.98

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

    /** [WallFit.hint] when the surface at the crosshair is not one flat face (a chord across a corner). */
    const val HINT_SHORT_WALL = "Short wall or corner in view. Stand square to this wall and capture again, or tap its base."

    /** [WallFit.hint] when the wall was found but too little of it was seen for a precise line. */
    const val HINT_IMPRECISE = "Too little of this wall was in view for a precise reading. Stand square to it, a little closer, and hold steady."

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
     * 3. Noise model: d = median horizontal range of the usable points; band = `inlierThresholdAt(d)`.
     * 4. Candidate lines: RANSAC ([LineFit.ransac]) with that band, then again on the points the previous
     *    lines did not explain, up to 4 lines (wall, furniture, adjacent walls, chords); a line passing within
     *    25 cm of the viewer is a fan of range outliers along one ray, not a surface, and is skipped. No line →
     *    NO_DOMINANT_PLANE.
     * 5. Choice: the surfaces to try, best first.
     *    * With a finite [aimPoint]: the candidates the aim point lies on. The aim point is a range sample,
     *      so it is compared ALONG the center ray (viewer → aim): the ray must meet the line within
     *      max(2 × band, 5 cm) of the aim point, inside the line's inlier span ± that window. They are tried in
     *      order of score = inliers · exp(−½(miss / σₐ)²) with σₐ = max(band / 2, 2 cm), the aim point's
     *      expected error, so a sparse streak of samples cannot beat the wall it crosses. A side wall seen at
     *      grazing incidence can pass a few centimetres from an aim point near the corner, but the center ray,
     *      almost parallel to it, meets it far beyond the aim: it never matches (the hallway end wall), and
     *      neither does a parallel wall seen through a doorway beside the aimed wall. If the best-scoring
     *      surface passes the furniture test below (a low object in front of a taller parallel surface), the
     *      user aimed at furniture → NO_DOMINANT_PLANE, whose message says to aim above it (lower-ranked
     *      furniture surfaces are skipped). If no candidate matches, the choice without aim point is tried when
     *      it matches within 2 × that window; otherwise the crosshair is clearly on another surface →
     *      NO_DOMINANT_PLANE ("aim at the middle of the wall").
     *    * Without aim point: the best line, unless the second candidate is the wall behind furniture
     *      ("prefer farther"): it has ≥ `preferFartherRatio` × the best line's inliers and passes the
     *      furniture test. Confidence is then multiplied by 0.85.
     *    * Furniture test (near surface A in front of B): within 15° of parallel; B on the same side of the
     *      viewer ≥ 0.10 m farther away; their extents along the wall (5–95 % of the inlier positions)
     *      overlap by ≥ 50 % of the shorter one; B's top reaches ≥ 4° higher than A's (90th-percentile
     *      elevation angle of the inliers seen from the camera; in angles because the same view covers more
     *      height on a farther surface). Overlap and height separate furniture (in front of the wall,
     *      lower) from a doorway or step (beside it, full height).
     * 6. Refinement ([refine]) on the usable samples near the chosen line, within a per-sample band (the noise
     *    a range error can cause across this line: tight for oblique rays, so the adjacent wall's samples near
     *    a corner stay out), weighted by 1/σᵢ² (σᵢ = the expected noise at the sample's own range, projected
     *    to the plan): an MSAC hypothesis search, weighted TLS, then the along-ray maximum-likelihood fit
     *    [LineFit.fitAlongRays] (depth error lies along the viewing ray, which tilts orthogonal regression for
     *    oblique views), re-selecting the inliers after each pass; corner clusters that still got in are
     *    trimmed ([cornerSpur]). A line that explains < 10 % of the usable points (not counting furniture in
     *    front of it), or whose robust extent is < 0.10 m → NO_DOMINANT_PLANE.
     * 7. One flat face? (review round 2, MATH-1: a short wall seen at an angle sits in a cluster of three
     *    faces whose smeared samples all fit a diagonal chord.) The refined line must still pass through the
     *    aim point (normal window), must not have turned more than 4° from its candidate, and must pass the
     *    lack-of-fit test ([Refined.lackOfFit]: no stretch of the wall bends away from the line). A surface
     *    that fails is not a wall: the next candidate through the aim point is tried; when none is left →
     *    NO_DOMINANT_PLANE with [HINT_SHORT_WALL] (the capture screen then offers tapping the wall's base).
     * 8. Noise (MATH-4): the robust σ (1.4826 · MAD) of the usable points within 4 × band of the line whose
     *    rays meet it in the core of the wall (10–90 % of the inliers), in metres and relative to the noise
     *    expected across the line. Grossly off the model (ratio > `grossNoiseFactor` and above `maxRms`) →
     *    [WallFitFailure.TOO_NOISY]. Then the line's own precision ([WallEstimatorConfig.lineErrors]): offset
     *    error > `maxOffsetError` or direction error > `maxDirectionErrorDeg` → TOO_NOISY with
     *    [HINT_IMPRECISE]. Otherwise the ratio only lowers the confidence.
     * 9. Orient the line viewer-left; the extent is the robust span of where the inliers' rays meet the line
     *    (2–98 % quantiles, widened by the share they cut off). A viewer within 5 cm of the line (interior side
     *    undefined) → [WallFitFailure.NO_DOMINANT_PLANE]. The line's anchor (`line.point`) is the projection of
     *    the inliers' 1/σ²-weighted centroid: where the wall's position is known best (RoomSolver rotates
     *    squared walls about it).
     * 10. Confidence, see [depthFitConfidence] (noise ratio relative to the gross cap).
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
        fun fail(reason: WallFitFailure, used: Int, hint: String? = null) = WallFit(null, reason, used, total, hint)

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

        // 3) Range-adaptive band.
        val medianRange = RobustStats.median(samples.map { it.horizontalRange })
        val threshold = config.inlierThresholdAt(medianRange)

        // 4) Candidate lines.
        val aimPlan = aimPoint?.takeIf { it.isFinite() }?.toPlan()
        val candidates = extractCandidates(usable, samples, viewerPlan, viewer.y - floorY, threshold, minInliers, CANDIDATES)
        if (candidates.isEmpty()) return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)


        // 5) Choice: the surfaces to try, best first.
        val legacy = legacyChoice(candidates, viewerPlan, config, minInliers)
        val window = max(2.0 * threshold, MIN_AIM_WINDOW)
        val choices: List<Choice> = if (aimPlan == null) {
            listOf(legacy)
        } else {
            when (val aimed = aimChoice(candidates, aimPlan, window, threshold, viewerPlan, minInliers)) {
                is AimOutcome.OnWall -> aimed.walls.map { Choice(it, null) }
                AimOutcome.OnFurniture -> return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)
                AimOutcome.Elsewhere -> {
                    val fit = legacy.wall.fit
                    if (aimMiss(fit.line, fit.tMin, fit.tMax, aimPlan, viewerPlan, AIM_FALLBACK_FACTOR * window) == null) {
                        return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)
                    }
                    listOf(legacy)
                }
            }
        }

        // 6-11) Refine and check each in turn; the first surface that is one flat, precise face wins.
        val context = FitContext(samples, usable, viewerPlan, aimPlan, threshold, minInliers, config, total)
        var firstRejection: Rejection? = null
        for (choice in choices) {
            when (val outcome = evaluate(choice, context)) {
                is Accepted -> return outcome.fit
                is Rejection -> {
                    if (firstRejection == null) firstRejection = outcome
                    if (!outcome.tryNext) break
                }
            }
        }
        val rejection = firstRejection ?: return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)
        return fail(rejection.reason, rejection.used, rejection.hint)
    }

    /** Inputs shared by the steps after candidate extraction. */
    private class FitContext(
        val samples: List<Sample>,
        val usable: List<Vec2>,
        val viewer: Vec2,
        val aim: Vec2?,
        val threshold: Double,
        val minInliers: Int,
        val config: WallEstimatorConfig,
        val total: Int,
    ) {
        /** Unit ray from the viewer to each usable sample. */
        val rays: List<Vec2> = usable.map { (it - viewer).normalized() }
    }

    private sealed interface Outcome

    private class Accepted(val fit: WallFit) : Outcome

    /** A rejected surface; [tryNext] when it is not one flat face at all, so another candidate may be the wall. */
    private class Rejection(val reason: WallFitFailure, val used: Int, val hint: String?, val tryNext: Boolean) : Outcome

    /** Steps 6–11 of [fromPoints] for one chosen surface. */
    private fun evaluate(choice: Choice, c: FitContext): Outcome {
        // 6) Refinement on the samples near the chosen line, then 7) on its stretch of the depth profile.
        val sigmas = expectedPlanSigmas(c.samples, choice.wall.fit.line, c.viewer, c.config)
        val weights = sigmas.map { s -> max(s, MIN_WEIGHT_SIGMA).let { 1.0 / (it * it) } }
        var refined = refine(choice.wall.fit.line, c.usable, weights, sigmas, c.viewer, c.threshold, c.minInliers, null)
            ?: return Rejection(WallFitFailure.NO_DOMINANT_PLANE, choice.wall.fit.inlierCount, null, tryNext = true)
        val seed = c.aim ?: refined.line.point
        var segment: BooleanArray? = null
        for (round in 0 until SEGMENT_ROUNDS) {
            val next = segment(refined.line, sigmas, seed, c) ?: return Rejection(WallFitFailure.NO_DOMINANT_PLANE, refined.count, HINT_SHORT_WALL, tryNext = true)
            if (segment != null && next.contentEquals(segment)) break
            segment = next
            refined = refine(refined.line, c.usable, weights, sigmas, c.viewer, c.threshold, c.minInliers, next)
                ?: return Rejection(WallFitFailure.NO_DOMINANT_PLANE, refined.count, HINT_SHORT_WALL, tryNext = true)
        }
        val explained = c.usable.size - (choice.foreground?.fit?.inlierCount ?: 0)
        val inlierRatio = if (explained > 0) min(1.0, refined.count.toDouble() / explained) else 0.0
        val extentLength = refined.tMax - refined.tMin
        if (inlierRatio < MIN_INLIER_RATIO || extentLength < MIN_EXTENT) {
            return Rejection(WallFitFailure.NO_DOMINANT_PLANE, refined.count, null, tryNext = true)
        }
        val noise = noiseNear(c.usable, sigmas, refined.line, NOISE_WINDOW_FACTOR * c.threshold, c.viewer, refined.centralLo, refined.centralHi)
        diagnostics?.invoke(
            mapOf(
                "aimMiss" to (c.aim?.let { aimMiss(refined.line, refined.tMin, refined.tMax, it, c.viewer, Double.POSITIVE_INFINITY) } ?: -1.0),
                "sigmaAim" to c.config.expectedSigma(c.aim?.distanceTo(c.viewer) ?: 0.0),
                "window" to 1.0,
                "aimOutside" to 0.0,
                "turnDeg" to Math.toDegrees(Math.asin(refined.line.sinAngleTo(choice.wall.fit.line).coerceAtMost(1.0))),
                "lofZ" to refined.maxBinZ,
                "lofDev" to refined.maxBinDeviation,
                "lofDevSigmas" to refined.maxBinDeviationSigmas,
                "lx" to refined.line.point.x, "ly" to refined.line.point.y,
                "ux" to refined.line.direction.x, "uy" to refined.line.direction.y,
                "mx" to refined.line.at((refined.tMin + refined.tMax) / 2).x, "my" to refined.line.at((refined.tMin + refined.tMax) / 2).y,
                "count" to refined.count.toDouble(), "extent" to extentLength,
                "lofZin" to refined.extra[3], "lofDevIn" to refined.extra[4], "lofIdx" to refined.extra[5], "lofRatio" to noise.ratio,
                "spanDeg" to Math.toDegrees(refined.span), "noiseSigma" to noise.sigma, "shared" to 0.0, "spread" to refined.spread,
                "postAim" to (if (c.aim == null) 1.0 else if (throughAim(refined, noise, c)) 1.0 else 0.0),
            ),
        )

        // 8) One flat face through the aim point?
        if (!throughAim(refined, noise, c) || refined.lackOfFit) {
            return Rejection(WallFitFailure.NO_DOMINANT_PLANE, refined.count, HINT_SHORT_WALL, tryNext = true)
        }

        // 9) Noise: gross cap, then the line's own precision.
        val sigma = max(refined.rms, noise.sigma)
        if (noise.ratio > c.config.grossNoiseFactor && sigma > c.config.maxRms) {
            return Rejection(WallFitFailure.TOO_NOISY, refined.count, null, tryNext = false)
        }
        // The uniform-coverage equivalent of where the inliers' rays actually meet the line (a patch seen through
        // one or two sampling columns has a long extent at grazing incidence but little spread).
        val (offsetError, directionError) = c.config.lineErrors(noise.sigma, refined.count, sqrt(12.0) * refined.spread)
        if (!(offsetError <= c.config.maxOffsetError) || !(directionError <= Math.toRadians(c.config.maxDirectionErrorDeg))) {
            return Rejection(WallFitFailure.TOO_NOISY, refined.count, HINT_IMPRECISE, tryNext = false)
        }

        // 10) Orientation: viewer on the LEFT; extents along +direction in the final orientation.
        val side = refined.line.signedDistance(c.viewer)
        if (abs(side) < MIN_VIEWER_DISTANCE) return Rejection(WallFitFailure.NO_DOMINANT_PLANE, refined.count, null, tryNext = false)
        val (line, tMin, tMax) = if (side > 0) {
            Triple(refined.line, refined.tMin, refined.tMax)
        } else {
            // flipped() keeps the anchor point and negates direction, so parameters negate and swap.
            Triple(refined.line.flipped(), -refined.tMax, -refined.tMin)
        }
        val extentStart = line.at(tMin)
        val extentEnd = line.at(tMax)

        // 11) Confidence.
        val viewerDistance = Vec2.lerp(extentStart, extentEnd, 0.5).distanceTo(c.viewer)
        val confidence = depthFitConfidence(
            inlierRatio = inlierRatio,
            noise = noise.ratio,
            maxRms = c.config.grossNoiseFactor,
            inlierCount = refined.count,
            extentLength = extentLength,
            viewerDistance = viewerDistance,
            maxRange = c.config.maxRange,
        ) * (if (choice.foreground != null) BEHIND_FURNITURE_CONFIDENCE else 1.0)

        val observation = WallObservation(
            line = line,
            extentStart = extentStart,
            extentEnd = extentEnd,
            source = WallSource.DEPTH_FIT,
            rmsResidual = refined.rms,
            pointCount = refined.count,
            confidence = confidence,
            viewer = c.viewer,
        )
        return Accepted(WallFit(observation, null, refined.count, c.total))
    }

    /**
     * Step 7 of [fromPoints]: the wall's own stretch of the depth profile. Every ray sees exactly one surface, so
     * the samples are binned by the AZIMUTH of their ray ([SEGMENT_BIN_DEG] bins, in-band samples only); starting at
     * the [seed] direction (the aim point), bins are taken in both directions while each bin's median residual
     * fits [line] (within max([SEGMENT_MIN_DEVIATION], [SEGMENT_Z] standard errors of a median, from the expected
     * noise scaled by the measured noise ratio). Bins without in-band samples (a doorway, the gap between two
     * sampling columns) are stepped over; the first bin that does not fit belongs to another face and ends the
     * stretch on that side.
     *
     * Near a corner, the neighbouring face's samples are smeared along their rays into this face's band (by up to
     * the band: ±4 cm at 2 m); a closet return has a convex and a reflex corner, so its neighbours' samples sit on
     * opposite sides of its line and tilt it by 5–15° without any visible lack of fit, and a chord through such a
     * corner cluster is made only of other faces' samples (review round 2, MATH-1). In azimuth the faces do not
     * overlap: those samples are cut off at the corner, and a chord keeps only its crossing with the aimed face.
     *
     * Returns the samples this surface may use (in-band samples of the bins taken), or null when fewer than
     * `minInliers` remain.
     */
    private fun segment(line: Line2, sigmas: List<Double>, seed: Vec2, c: FitContext): BooleanArray? {
        val n = c.usable.size
        val seedRay = (seed - c.viewer).normalized()
        if (seedRay == Vec2.ZERO) return null
        val minBand = min(MIN_REFINE_BAND, c.threshold)
        val binWidth = Math.toRadians(SEGMENT_BIN_DEG)
        val bins = HashMap<Int, MutableList<Int>>()
        val normalized = ArrayList<Double>()
        for (i in 0 until n) {
            val r = c.rays[i]
            val g = abs(line.normal dot r)
            val perp = max(sigmas[i] * g, MIN_PERP_SIGMA)
            val d = line.signedDistance(c.usable[i])
            if (abs(d) > (REFINE_BAND_SIGMAS * perp).coerceIn(minBand, c.threshold)) continue
            val azimuth = atan2(seedRay cross r, seedRay dot r)
            bins.getOrPut(floor(azimuth / binWidth).toInt()) { ArrayList() } += i
            normalized += d / perp
        }
        if (bins.isEmpty()) return null
        val ratio = (RobustStats.MAD_TO_SIGMA * RobustStats.mad(normalized)).takeIf { it.isFinite() }?.coerceIn(1.0, MAX_SEGMENT_NOISE_RATIO) ?: 1.0
        fun fits(members: List<Int>): Boolean {
            val residuals = members.map { line.signedDistance(c.usable[it]) }
            val perps = members.map { max(sigmas[it] * abs(line.normal dot c.rays[it]), MIN_PERP_SIGMA) }
            val se = MEDIAN_SE_FACTOR * ratio * RobustStats.median(perps) / sqrt(members.size.toDouble())
            return abs(RobustStats.median(residuals)) <= max(SEGMENT_MIN_DEVIATION, SEGMENT_Z * se)
        }
        val allowed = BooleanArray(n)
        var count = 0
        val keys = bins.keys
        val lo = keys.min()
        val hi = keys.max()
        for (step in listOf(-1, 1)) {
            var k = if (step < 0) 0 else 1
            while (k in lo..hi) {
                val members = bins[k]
                // Bins with too few samples to judge are stepped over like empty ones.
                if (members != null && members.size >= SEGMENT_MIN_BIN) {
                    if (!fits(members)) break
                    for (i in members) allowed[i] = true
                    count += members.size
                }
                k += step
            }
        }
        return if (count >= c.minInliers) allowed else null
    }

    /**
     * The aim point lies on the refined surface: a refined line is precise, so it must meet the center ray within
     * about 3 σ of the aim point (a median of a handful of center-ray hits: σₐ ≈ [POST_AIM_SIGMA_FACTOR] × σ(range),
     * widened by the measured noise ratio up to [MAX_AIM_NOISE_RATIO], never below [MIN_POST_AIM_WINDOW]) and inside
     * its own robust extent ± [POST_AIM_EXTENT_MARGIN]: near a corner the neighbouring face passes close to the aim
     * point too, but only beyond its own end. Always true without an aim point.
     */
    private fun throughAim(refined: Refined, noise: Noise, c: FitContext): Boolean {
        val aim = c.aim ?: return true
        val sigmaAim = POST_AIM_SIGMA_FACTOR * c.config.expectedSigma(aim.distanceTo(c.viewer)) * noise.ratio.coerceIn(1.0, MAX_AIM_NOISE_RATIO)
        val window = max(MIN_POST_AIM_WINDOW, POST_AIM_SIGMAS * sigmaAim)
        // aimMiss allows its span ± window; shift the span so that only ± POST_AIM_EXTENT_MARGIN remains.
        return aimMiss(
            refined.line, refined.tMin + window - POST_AIM_EXTENT_MARGIN, refined.tMax - window + POST_AIM_EXTENT_MARGIN,
            aim, c.viewer, window,
        ) != null
    }



    /** Test-only probe of per-candidate statistics (threshold calibration); null in production. */
    @Volatile
    internal var diagnostics: ((Map<String, Double>) -> Unit)? = null

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
        /** The candidates the aim point lies on, best first (furniture surfaces left out). */
        class OnWall(val walls: List<Candidate>) : AimOutcome

        /** The best surface at the aim point is a low object in front of a taller parallel surface. */
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
     * The candidates the aim point lies on (see [fromPoints], step 5), best-matching first, unless the best of
     * them is furniture. [threshold] is the inlier band; ties keep the earlier (larger) candidate.
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
        val scored = candidates.mapNotNull { c ->
            val miss = aimMiss(c.fit.line, c.fit.tMin, c.fit.tMax, aim, viewer, window) ?: return@mapNotNull null
            val z = miss / aimSigma
            c to c.fit.inlierCount * exp(-0.5 * z * z)
        }
        if (scored.isEmpty()) return AimOutcome.Elsewhere
        // Stable sort: equal scores keep the extraction order (larger candidates first).
        val ranked = scored.sortedByDescending { it.second }.map { it.first }
        fun isFurniture(wall: Candidate) = candidates.any { other ->
            other !== wall && other.fit.inlierCount >= minInliers && isInFrontOf(wall, other, viewer)
        }
        if (isFurniture(ranked[0])) return AimOutcome.OnFurniture
        return AimOutcome.OnWall(ranked.filterNot(::isFurniture))
    }

    /**
     * How far (m) the aim point lies from where the center ray (viewer → [aim]) meets [line], measured ALONG
     * that ray, when that is within [window] and the meeting point within the line's span [tMin]..[tMax] ±
     * [window]; else null.
     *
     * Along the ray, because that is how the aim point errs (it is a range sample); and it decides the
     * corner cases right: a side wall seen at grazing incidence can pass a few centimetres from an aim point
     * near the corner, but the center ray, nearly parallel to it, meets it far beyond the aim.
     */
    private fun aimMiss(line: Line2, tMin: Double, tMax: Double, aim: Vec2, viewer: Vec2, window: Double): Double? {
        val toAim = aim - viewer
        val range = toAim.length
        if (!(range > 1e-6)) return null
        val ray = toAim / range
        val n = line.normal
        val g = n dot ray
        if (abs(g) < 1e-6) return null
        val hitRange = (n dot (line.point - viewer)) / g
        if (!(hitRange > 0)) return null
        val miss = abs(hitRange - range)
        if (miss > window) return null
        val t = line.param(viewer + ray * hitRange)
        return if (t >= tMin - window && t <= tMax + window) miss else null
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
     * The refined wall: line, inlier count and RMS, robust extent [tMin]..[tMax] (see [EXTENT_LOW]), and
     * [centralLo]..[centralHi], the 10–90 % range of where the inliers' rays meet the line (the wall's core).
     *
     * Lack of fit: the inliers, ordered by where their rays meet the line, in equal-count bins; per bin the
     * mean residual, as a z-score against its standard error (the noise of the bin's samples, from the model
     * scaled by the measured noise ratio, so a sensor noisier than the model does not trip it), in metres
     * ([maxBinDeviation]) and in expected per-point σ ([maxBinDeviationSigmas]) of the bin with the largest |z|.
     * A flat face scatters evenly about its line along its whole length; a chord across a corner cluster (a
     * short return and its two neighbour faces seen at an angle) runs in front of the faces in some stretches
     * and behind them in others, centimetres either way.
     */
    private class Refined(
        val line: Line2,
        val count: Int,
        val rms: Double,
        val tMin: Double,
        val tMax: Double,
        val centralLo: Double,
        val centralHi: Double,
        val maxBinZ: Double,
        val maxBinDeviation: Double,
        val maxBinDeviationSigmas: Double,
    ) {
        var extra: DoubleArray = DoubleArray(7)
        var span: Double = 0.0
        var spread: Double = 0.0
        val lackOfFit: Boolean
            get() = maxBinZ > LOF_Z && maxBinDeviation > LOF_MIN_DEVIATION && maxBinDeviationSigmas > LOF_MIN_DEVIATION_SIGMAS
    }

    /**
     * Step 6 of [fromPoints]: the chosen surface's line, re-estimated from the usable samples near it. Every
     * reported statistic is computed against the returned line; null when fewer than [minInliers] support it.
     *
     * A sample supports a line when it lies within its own band, `2.5 · σᵢ·|n·r̂ᵢ|` clamped to
     * [1 cm, [threshold]]: depth error runs along the viewing ray r̂ᵢ, so σᵢ·|n·r̂ᵢ| is the deviation it can
     * have across a line with normal n. Head-on that is the usual ~2σ band; for oblique rays it is much
     * tighter, which matters at corners: the adjacent wall's points near the corner all lie on one side of
     * the line, and a uniform band lets them pull the line's end towards them (a side wall seen at grazing
     * incidence collected ~120 such points in simulation and tilted 1.4°; with per-sample bands, 0.01°).
     *
     * Only samples within [threshold] of [initial] (the pool) score the hypotheses, and only samples within
     * 1.5 × [threshold] of it can be selected: scoring every usable sample let a hypothesis through two
     * points of a neighbouring face win by collecting that face (review round 2, MATH-1).
     *
     * 1. Hypotheses: the [initial] line plus [TIGHT_ITERATIONS] lines through random pairs (seeded) of the
     *    pool, scored MSAC-style over the pool, Σ min((dᵢ / sᵢ)², 2.5²) with sᵢ = bandᵢ / 2.5.
     *    Unlike a plain count this prefers the line that fits the bulk of the wall tightly over one tilted
     *    to grab a cluster at the edge of the band; and the uniform-band RANSAC line alone can sit in such a
     *    tilted optimum.
     * 2. Up to [REFINE_PASSES] passes of weighted TLS then the along-ray ML fit [LineFit.fitAlongRays] on
     *    the supporting samples (weights 1/σᵢ²), re-selecting them after each pass until stable.
     * 3. Corner clusters the bands still let in are trimmed ([cornerSpur]) and the passes repeated.
     * 4. Extent, core and lack-of-fit statistics (see [Refined]).
     */
    private fun refine(
        initial: Line2,
        usable: List<Vec2>,
        weights: List<Double>,
        sigmas: List<Double>,
        viewer: Vec2,
        threshold: Double,
        minInliers: Int,
        /** Samples this surface may use (null = all): the joint refinement excludes those other surfaces own. */
        allowed: BooleanArray?,
    ): Refined? {
        val rays = usable.map { (it - viewer).normalized() }
        val minBand = min(MIN_REFINE_BAND, threshold)
        fun band(line: Line2, i: Int) = (REFINE_BAND_SIGMAS * sigmas[i] * abs(line.normal dot rays[i])).coerceIn(minBand, threshold)
        val universe = usable.indices.filter {
            (allowed == null || allowed[it]) && initial.distance(usable[it]) <= REFINE_UNIVERSE_FACTOR * threshold
        }
        fun select(line: Line2, excluded: Set<Int>): List<Int> =
            universe.filter { it !in excluded && line.distance(usable[it]) <= band(line, it) }

        // 1) Hypothesis search with the per-sample bands (MSAC score over the pool).
        val pool = universe.filter { initial.distance(usable[it]) <= threshold }
        if (pool.size < minInliers) return null
        val capCost = REFINE_BAND_SIGMAS * REFINE_BAND_SIGMAS
        var line = initial
        var bestCost = Double.POSITIVE_INFINITY
        fun consider(hypothesis: Line2) {
            var cost = 0.0
            for (i in pool) {
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

        // 4) Statistics against the final line.
        var sumSq = 0.0
        val count = inliers.size
        val ts = DoubleArray(count)
        val residuals = DoubleArray(count)
        val perp = DoubleArray(count)
        for ((k, i) in inliers.withIndex()) {
            val d = line.signedDistance(usable[i])
            sumSq += d * d
            ts[k] = rayHitParam(line, viewer, usable[i])
            residuals[k] = d
            perp[k] = max(sigmas[i] * abs(line.normal dot rays[i]), MIN_PERP_SIGMA)
        }
        val sortedTs = ts.sorted()
        val lo = percentile(sortedTs, EXTENT_LOW)
        val hi = percentile(sortedTs, EXTENT_HIGH)
        val widen = (hi - lo) * EXTENT_LOW / (EXTENT_HIGH - EXTENT_LOW)
        val lof = lackOfFit(ts, residuals, perp)
        val angles = inliers.map { i -> rays[i] }.let { rs ->
            val ref = rs.fold(Vec2.ZERO) { a, r -> a + r }.normalized()
            rs.map { r -> atan2(ref cross r, ref dot r) }.sorted()
        }
        val span = percentile(angles, EXTENT_HIGH) - percentile(angles, EXTENT_LOW)
        return Refined(
            line, count, sqrt(sumSq / count), lo - widen, hi + widen,
            percentile(sortedTs, NOISE_CORE_LOW), percentile(sortedTs, NOISE_CORE_HIGH),
            lof[0], lof[1], lof[2],
        ).also {
            it.extra = lof
            it.span = span
            val meanT = ts.average()
            it.spread = sqrt(ts.sumOf { t -> (t - meanT) * (t - meanT) } / count)
        }
    }

    /**
     * Lack-of-fit statistics of a fitted line (see [Refined]): (max |z|, |mean residual| (m) and |mean residual|
     * in expected σ of the bin with that z). [ts] = where each inlier's ray meets the line, [residuals] = its
     * signed distance from the line, [perp] = its expected perpendicular noise. Zeros when there are too few
     * samples for [LOF_MIN_BINS] bins.
     */
    private fun lackOfFit(ts: DoubleArray, residuals: DoubleArray, perp: DoubleArray): DoubleArray {
        val n = ts.size
        val bins = min(LOF_MAX_BINS, n / LOF_MIN_BIN)
        if (bins < LOF_MIN_BINS) return doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        // Measured-to-expected noise ratio of these samples (robust): the bins' standard errors use it.
        val ratio = (RobustStats.MAD_TO_SIGMA * RobustStats.mad(List(n) { residuals[it] / perp[it] })).takeIf { it > 0 && it.isFinite() } ?: 1.0
        val order = (0 until n).sortedBy { ts[it] }
        var bestZ = 0.0
        var bestDev = 0.0
        var bestDevSigmas = 0.0
        var bestZin = 0.0
        var bestDevIn = 0.0
        var bestIdx = 0.0
        for (b in 0 until bins) {
            val from = b * n / bins
            val to = (b + 1) * n / bins
            var sum = 0.0
            var varSum = 0.0
            var sigmaSum = 0.0
            for (k in from until to) {
                val i = order[k]
                sum += residuals[i]
                varSum += perp[i] * perp[i]
                sigmaSum += perp[i]
            }
            val m = to - from
            if (m <= 0 || !(varSum > 0)) continue
            val z = abs(sum) / (ratio * sqrt(varSum))
            if (z > bestZ) {
                bestZ = z
                bestDev = abs(sum) / m
                bestDevSigmas = bestDev / (ratio * sigmaSum / m)
                bestIdx = b.toDouble() / (bins - 1)
            }
            if (b > 0 && b < bins - 1 && z > bestZin) {
                bestZin = z
                bestDevIn = abs(sum) / m
            }
        }
        return doubleArrayOf(bestZ, bestDev, bestDevSigmas, bestZin, bestDevIn, bestIdx, ratio)
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

    /** Robust noise near a line: [sigma] (m, across the line) and [ratio] to the noise the model expects there. */
    private class Noise(val sigma: Double, val ratio: Double)

    /**
     * Robust noise estimate of the points within [window] of [line] whose rays meet it within [lo]..[hi]
     * (the wall's core, see [Refined]): 1.4826 · MAD of their signed residuals, and of the residuals divided
     * by each point's expected noise across the line ([sigmas] along its ray, projected on the normal); zeros
     * when there are none. Near the span's ends the adjacent walls' samples, smeared along their grazing rays,
     * and range outliers of the rays that just miss the wall also fall inside the wide window and would read as
     * noise.
     */
    private fun noiseNear(points: List<Vec2>, sigmas: List<Double>, line: Line2, window: Double, viewer: Vec2, lo: Double, hi: Double): Noise {
        val residuals = ArrayList<Double>()
        val normalized = ArrayList<Double>()
        for ((i, p) in points.withIndex()) {
            val d = line.signedDistance(p)
            if (abs(d) > window) continue
            val t = rayHitParam(line, viewer, p)
            if (t < lo || t > hi) continue
            residuals.add(d)
            val r = (p - viewer).normalized()
            normalized.add(d / max(sigmas[i] * abs(line.normal dot r), MIN_PERP_SIGMA))
        }
        if (residuals.isEmpty()) return Noise(0.0, 0.0)
        return Noise(RobustStats.MAD_TO_SIGMA * RobustStats.mad(residuals), RobustStats.MAD_TO_SIGMA * RobustStats.mad(normalized))
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
