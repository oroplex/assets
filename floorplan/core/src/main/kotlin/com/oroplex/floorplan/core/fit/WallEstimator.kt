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
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class WallFitFailure(val message: String) {
    TOO_FEW_POINTS("Not enough depth points on the wall. Move closer or aim at a textured area."),
    NO_DOMINANT_PLANE("Couldn't find a flat wall at the crosshair. Aim at a bare part of the wall, above furniture."),
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
 *
 * [noiseRatio] is the measured per-point depth noise of the wall relative to the estimator's noise model
 * ([WallEstimatorConfig.expectedSigma]); null when no wall got far enough to measure it. Log it on device: a
 * typical value far from 1 means the model needs tuning.
 */
data class WallFit(
    val observation: WallObservation?,
    val failure: WallFitFailure?,
    val usedPoints: Int,
    val totalPoints: Int,
    val hint: String? = null,
    val noiseRatio: Double? = null,
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
    /**
     * Largest acceptable standard error (degrees) of the fitted line's direction: the statistical error of the
     * fit combined with what an end of the observed stretch could add unseen when a corner is in view (see
     * [WallEstimator.fromPoints], step 8). At 1.5° three standard errors stay inside the solver's default 5°
     * squaring tolerance, beyond which a wrong direction is kept in the plan.
     */
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

    /**
     * Threshold (z-score of the end zone's mean residual) of the corner-spur test; the mean must also be at
     * least [MIN_SPUR_DEVIATION] (m) off: with (nearly) noiseless samples every rounding error is "significant".
     */
    private const val SPUR_Z = 3.5
    private const val MIN_SPUR_DEVIATION = 0.0005

    /** End zones with fewer samples are not tested. */
    private const val MIN_SPUR_SAMPLES = 12

    /** At most this many trims (an end can be trimmed again when a wide, oblique corner remains). */
    private const val MAX_SPUR_TRIMS = 4

    /**
     * Stations ([Station]): ordered by the azimuth of their rays, the usable samples fall into clusters wherever
     * consecutive rays are more than [STATION_GAP_DEG] apart (the AR layer samples a 9 × 9 grid: nine columns
     * about 3° apart, each smeared by hand shake); a cluster wider than [STATION_MAX_WIDTH_DEG] (dense
     * sampling, or a shaky hand merging columns) is cut into equal pieces no wider than that. A station counts
     * for a surface when at least [MIN_STATION_SAMPLES] of its samples support it.
     *
     * These angles describe the sampling, not the room: with nine columns 3° apart a wall has to fill about a
     * third of the view to be seen at three stations. If the AR layer samples differently (the whole depth
     * image, say), stations become the 2.5° pieces of one continuous cluster and the same rules apply.
     */
    private const val STATION_GAP_DEG = 0.6
    private const val STATION_MAX_WIDTH_DEG = 2.5
    private const val MIN_STATION_SAMPLES = 8

    /** A station counts towards [MIN_STATIONS] with at least this share of the samples of the stretch's median station. */
    private const val MIN_STATION_SHARE = 0.25

    /** Stations are also not split at gaps below this multiple of the median angle between neighbouring rays. */
    private const val SPARSE_GAP_FACTOR = 8.0

    /**
     * A surface is a wall only when it is seen flat at this many stations or more: two stations always fit a
     * line, so with two a chord across a corner cannot be told from a wall (review round 2, MATH-1).
     */
    private const val MIN_STATIONS = 3

    /** The seed (aim) direction must lie within this angle (degrees) of a station, else nothing was sampled there. */
    private const val SEED_STATION_TOLERANCE_DEG = 1.0

    /**
     * Flat-stretch walk (see [flatStretch]): each station is cut, along the azimuth, into chunks of about
     * [CHUNK_SAMPLES] samples near the line (at most [MAX_CHUNKS]). A chunk fits the line when its median
     * residual is within [SEGMENT_Z] standard errors, but never tighter than [SEGMENT_MIN_DEVIATION] (m). The
     * standard error is that of a median (√(π/2) σ/√n) of the expected noise across the line, scaled by the noise
     * ratio measured at the seed station (1 … [MAX_SEGMENT_NOISE_RATIO]), plus [SEGMENT_SYSTEMATIC] of that
     * per-sample noise in quadrature: real depth errors are correlated within a frame and across a patch of
     * wall, so a chunk's median is never as precise as independent samples would make it.
     */
    private const val CHUNK_SAMPLES = 32
    private const val MAX_CHUNKS = 6
    private const val SEGMENT_Z = 3.5
    private const val SEGMENT_MIN_DEVIATION = 0.005
    private const val SEGMENT_SYSTEMATIC = 0.2
    private const val MAX_SEGMENT_NOISE_RATIO = 2.5
    private const val MEDIAN_SE_FACTOR = 1.2533

    /**
     * Walk / refit rounds at most, the first [FREE_SEGMENT_ROUNDS] of them free to grow; a stretch that still
     * changes by more than [SETTLED_FRACTION] of its samples after them is ambiguous.
     */
    private const val SEGMENT_ROUNDS = 5
    private const val FREE_SEGMENT_ROUNDS = 2
    private const val SETTLED_FRACTION = 0.05

    /**
     * Neighbour test (see [ownedByNeighbour]): the end of a stretch lies on the surface beyond it when the robust
     * position of its samples is within [NEIGHBOUR_SIGMAS] standard errors (at least [MIN_NEIGHBOUR_TOLERANCE] m)
     * of the line through the next two stations; that line is extrapolated over at most
     * [MAX_NEIGHBOUR_EXTRAPOLATION] times their distance, which must be at least [MIN_NEIGHBOUR_BASE] (m).
     */
    private const val NEIGHBOUR_SIGMAS = 3.0
    private const val MIN_NEIGHBOUR_TOLERANCE = 0.015
    private const val MAX_NEIGHBOUR_EXTRAPOLATION = 3.0
    private const val MIN_NEIGHBOUR_BASE = 0.05

    /**
     * Joint flatness test (see [Stretch.flat]): the stations' median residuals, each divided by its standard
     * error (the same as a chunk's, see [SEGMENT_Z]), are summed in squares; for a flat wall that sum follows a χ²
     * distribution with (stations − 2) degrees of freedom, and it is rejected above the quantile [FLATNESS_Z]
     * normal deviations out (p ≈ 0.001). A chord across a closet return passes each station within its own
     * tolerance (a few centimetres on alternating sides) but not all of them together.
     */
    private const val FLATNESS_Z = 3.09

    /**
     * Shallow steps ([shallowStep]): a step model must beat one line by [STEP_Z]² in χ², over at least
     * [MIN_STEP_STATIONS] stations, with a step of [STEP_MIN_SIGMAS] times the per-sample noise or more
     * (never under [STEP_MIN_DEPTH] m). A station's median is taken to be known to its statistical error plus
     * [STEP_SYSTEMATIC] of the per-sample noise. At most [MAX_STEPS] steps are cut off per walk.
     */
    private const val STEP_Z = 3.5
    private const val MIN_STEP_STATIONS = 4
    private const val STEP_MIN_DEPTH = 0.012
    private const val STEP_MIN_SIGMAS = 0.8
    private const val STEP_SYSTEMATIC = 0.05

    /** A step that leaves one station alone on a side must be this many times the minimum step deep. */
    private const val LONE_STATION_FACTOR = 1.3
    private const val MAX_STEPS = 3

    /**
     * Aim cluster (see [FitContext.aimCluster]): the samples of the crosshair's station within this multiple of
     * the RANSAC band of the aim point, along their rays. The wall fitted without that station must pass their
     * robust position within [AIM_CLUSTER_SIGMAS] standard errors, at least [MIN_AIM_CLUSTER_TOLERANCE] m (see
     * [confirmedAtAim]).
     */
    private const val AIM_CLUSTER_WINDOW_FACTOR = 2.0
    private const val AIM_CLUSTER_SIGMAS = 3.0
    private const val MIN_AIM_CLUSTER_TOLERANCE = 0.015

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
    const val HINT_SHORT_WALL = "Short wall or corner here. Stand square to this wall and capture again, or tap its base."

    /**
     * [WallFit.hint] when something flat stands proud of the wall or is set into it under the crosshair (a
     * door, a picture, a TV) and too little is left on the crosshair's side of that step to measure.
     */
    const val HINT_STEP = "A door, picture or TV is in the way here. Aim at bare wall beside it, or tap the wall's base."

    /** [WallFit.hint] when the wall was found but too little of it was seen for a precise line. */
    const val HINT_IMPRECISE = "Too little of this wall is in view. Stand square to it, a little closer, or tap its base."

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
     *    trimmed ([cornerSpur]).
     * 7. One flat face? Review round 2 (MATH-1): a short wall seen at an angle sits between two corners, and a
     *    line through one of its sampling columns and one of a neighbour's, the diagonal of a closet return or
     *    the neighbouring wall bent a little all fit their samples as well as a wall does. In the plan such
     *    lines cannot be told from a wall; along the azimuth they can, because from one viewpoint every
     *    direction shows exactly one surface. The samples are grouped into stations by the azimuth of their rays
     *    ([Station]: the sampling columns), and the line must own a stretch of that profile ([flatStretch]):
     *    * from the crosshair's station outwards, the stations whose samples are centred on the line, up to the
     *      first that is not (another face) or that shows another surface altogether; end stations that lie on
     *      the surface beyond them are the neighbour's ([ownedByNeighbour]); the line is refitted to the
     *      stretch and the stretch re-walked until both agree;
     *    * at least 3 stations, also among the refined line's own inliers: two always fit a line;
     *    * the stations' residuals jointly consistent with a flat face (χ², [Stretch.flat]);
     *    * the line still through the aim point ([throughAim]) and, fitted again without the crosshair's own
     *      station, still through the surface there ([confirmedAtAim]).
     *    A surface that fails is not a wall: the next candidate through the aim point is tried; when none is
     *    left → NO_DOMINANT_PLANE with [HINT_SHORT_WALL] (the capture screen then offers tapping the wall's
     *    base). Also → NO_DOMINANT_PLANE: a line that explains < 10 % of the usable points (not counting
     *    furniture in front of it) or whose robust extent is < 0.10 m.
     *    (The review's own fix notes, a turn limit of 3–5° between candidate and refined line and a lack-of-fit
     *    test over bins of inliers, were tried first: a chord through two columns has no lack of fit, and a
     *    tilted candidate legitimately turns onto the wall it grows into.)
     * 8. Noise (MATH-4). The noise model is a prior: only noise grossly off it fails, measured where the line
     *    cannot excuse itself (at the crosshair's station about the samples' own neighbourhood, [localNoise])
     *    and along the wall (robust σ = 1.4826 · MAD of the usable points within 4 × band of the line whose rays
     *    meet it in the core of the wall, 10–90 % of the inliers): ratio to the noise expected across the line
     *    > `grossNoiseFactor` and σ > `maxRms` → [WallFitFailure.TOO_NOISY]. Then the line's own precision:
     *    offset error ([WallEstimatorConfig.lineErrors]) > `maxOffsetError`, or direction error >
     *    `maxDirectionErrorDeg` → TOO_NOISY with [HINT_IMPRECISE]. The direction error is the statistical one
     *    combined with [Stretch.endTilt]: what an end station that is really the next face's could add unseen,
     *    which is what limits short stretches between two corners. Otherwise the ratio only lowers the
     *    confidence and is reported as [WallFit.noiseRatio].
     * 9. Orient the line viewer-left; the extent is the robust span of where the inliers' rays meet the line
     *    (2–98 % quantiles, widened by the share they cut off; MATH-6). A viewer within 5 cm of the line
     *    (interior side undefined) → [WallFitFailure.NO_DOMINANT_PLANE]. The line's anchor (`line.point`) is the
     *    projection of the inliers' 1/σ²-weighted centroid: where the wall's position is known best (RoomSolver
     *    rotates squared walls about it).
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
        fun fail(reason: WallFitFailure, used: Int, hint: String? = null, noiseRatio: Double? = null) =
            WallFit(null, reason, used, total, hint, noiseRatio)

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

        // 6-10) Refine and check each in turn; the first surface that is one flat, precise face wins.
        val context = FitContext(samples, usable, viewerPlan, aimPlan, threshold, minInliers, config, total)
        var rejection: Rejection? = null
        for (choice in choices) {
            when (val outcome = evaluate(choice, context)) {
                is Accepted -> return outcome.fit
                is Rejection -> {
                    // Report the rejection that got furthest: a wall that was found but is imprecise or too
                    // noisy says more than "short wall", which says more than "no wall here".
                    if (rejection == null || outcome.rank > rejection.rank) rejection = outcome
                    if (!outcome.tryNext) break
                }
            }
        }
        val reported = rejection ?: return fail(WallFitFailure.NO_DOMINANT_PLANE, usable.size)
        return fail(reported.reason, reported.used, reported.hint, reported.noiseRatio)
    }

    /**
     * A station of the depth profile: the samples of one sampling column (or of one piece of a densely sampled
     * stretch), in ascending azimuth. [center] is their robust position (component-wise median: gross range
     * outliers do not move it) and [centerSigma] its standard error (m) along the viewing ray under the noise
     * model, √(π/2) · median(σᵢ) / √n.
     */
    private class Station(val members: IntArray, val azimuthLow: Double, val azimuthHigh: Double, val center: Vec2, val centerSigma: Double)

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

        /** Reference direction of the azimuths: the center ray (viewer → aim point), or the mean ray without aim point. */
        private val reference: Vec2 = (aim?.let { it - viewer } ?: rays.fold(Vec2.ZERO) { a, r -> a + r }).normalized()

        /** Azimuth (radians, counter-clockwise) of each sample's ray relative to the reference direction. */
        val azimuth: DoubleArray = DoubleArray(usable.size) { azimuthOf(rays[it]) }

        /** The depth profile's stations in ascending azimuth (see [Station]). */
        val stations: List<Station> = buildStations()

        /**
         * The surface at the crosshair, located without any line: the samples of the crosshair's station whose
         * range is within [AIM_CLUSTER_WINDOW_FACTOR] × band of the aim point's (re-centred once on their median
         * range). The aim point itself is a median of a handful of center-ray hits; these are several times as
         * many samples of the same surface, so their robust position ([Station.center]) pins the surface down
         * much more tightly. Null without aim point or with fewer than [MIN_STATION_SAMPLES] such samples.
         */
        val aimCluster: Station? = buildAimCluster()

        /** Index (in [stations]) of the station of each sample. */
        private val stationOf: IntArray = IntArray(usable.size).also { map -> stations.forEachIndexed { s, station -> for (i in station.members) map[i] = s } }

        /** Number of stations holding at least [MIN_STATION_SAMPLES] of the samples [members]. */
        fun stationsOf(members: List<Int>): Int {
            val counts = IntArray(stations.size)
            for (i in members) counts[stationOf[i]]++
            return counts.count { it >= MIN_STATION_SAMPLES }
        }

        /** Azimuth of a direction (need not be unit) relative to the reference direction. */
        fun azimuthOf(direction: Vec2): Double = atan2(reference cross direction, reference dot direction)

        /**
         * Index of the station (of at least [MIN_STATION_SAMPLES] samples) nearest to direction [azimuth], when
         * within [toleranceDeg] of it; else null.
         */
        fun stationAt(azimuth: Double, toleranceDeg: Double = SEED_STATION_TOLERANCE_DEG): Int? {
            fun gap(s: Station) = max(0.0, max(s.azimuthLow - azimuth, azimuth - s.azimuthHigh))
            val nearest = stations.indices.filter { stations[it].members.size >= MIN_STATION_SAMPLES }.minByOrNull { gap(stations[it]) } ?: return null
            return nearest.takeIf { gap(stations[it]) <= Math.toRadians(toleranceDeg) }
        }

        /** Expected noise (m) of sample [i] along its ray, in the plan, at its own measured range. */
        fun modelSigma(i: Int): Double {
            val cosElevation = samples[i].elevationCos.coerceIn(1e-3, 1.0)
            return config.expectedSigma(samples[i].horizontalRange / cosElevation) * cosElevation
        }

        /** A station of [members] (ascending azimuth, not empty): their robust position and its standard error. */
        fun station(members: IntArray): Station {
            val center = Vec2(RobustStats.median(members.map { usable[it].x }), RobustStats.median(members.map { usable[it].y }))
            val sigma = RobustStats.median(members.map { modelSigma(it) })
            return Station(members, azimuth[members.first()], azimuth[members.last()], center, MEDIAN_SE_FACTOR * sigma / sqrt(members.size.toDouble()))
        }

        private fun buildAimCluster(): Station? {
            val target = aim ?: return null
            val seed = stations[stationAt(0.0) ?: return null]
            val window = AIM_CLUSTER_WINDOW_FACTOR * threshold
            var range = target.distanceTo(viewer)
            var members = IntArray(0)
            repeat(2) {
                members = seed.members.filter { abs(samples[it].horizontalRange - range) <= window }.toIntArray()
                if (members.size < MIN_STATION_SAMPLES) return null
                range = RobustStats.median(members.map { samples[it].horizontalRange })
            }
            return station(members)
        }

        private fun buildStations(): List<Station> {
            val order = usable.indices.sortedBy { azimuth[it] }
            // Sparse samples (legacy callers, tests) are further apart than a column gap of the dense grid.
            val spacing = RobustStats.median((1 until order.size).map { azimuth[order[it]] - azimuth[order[it - 1]] })
            val gap = max(Math.toRadians(STATION_GAP_DEG), if (spacing.isFinite()) SPARSE_GAP_FACTOR * spacing else 0.0)
            val maxWidth = Math.toRadians(STATION_MAX_WIDTH_DEG)
            val out = ArrayList<Station>()
            fun add(from: Int, to: Int) {
                if (to > from) out += station(IntArray(to - from) { order[from + it] })
            }
            var start = 0
            for (k in 1..order.size) {
                if (k < order.size && azimuth[order[k]] - azimuth[order[k - 1]] <= gap) continue
                // One cluster: order[start until k]; cut into pieces of at most maxWidth.
                val low = azimuth[order[start]]
                val width = azimuth[order[k - 1]] - low
                val pieces = ceil(width / maxWidth).toInt().coerceAtLeast(1)
                var from = start
                for (piece in 1..pieces) {
                    var to = from
                    if (piece == pieces) {
                        to = k
                    } else {
                        val limit = low + width * piece / pieces
                        while (to < k && azimuth[order[to]] <= limit) to++
                    }
                    add(from, to)
                    from = to
                }
                start = k
            }
            return out
        }
    }

    private sealed interface Outcome

    private class Accepted(val fit: WallFit) : Outcome

    /**
     * A rejected surface; [tryNext] when it is not one flat face at all, so another candidate may be the wall.
     * [rank] orders rejections by how far the surface got (the furthest is reported).
     */
    private class Rejection(
        val reason: WallFitFailure,
        val used: Int,
        val hint: String?,
        val tryNext: Boolean,
        val noiseRatio: Double? = null,
    ) : Outcome {
        val rank: Int get() = if (reason != WallFitFailure.NO_DOMINANT_PLANE) 2 else if (hint != null) 1 else 0
    }

    /** Steps 6–10 of [fromPoints] for one chosen surface. */
    private fun evaluate(choice: Choice, c: FitContext): Outcome {
        // A stretch that a shallow step cut short: the refusal should name the door / picture, not a corner.
        var stepSeen = false
        fun notAWall(used: Int) = Rejection(WallFitFailure.NO_DOMINANT_PLANE, used, if (stepSeen) HINT_STEP else HINT_SHORT_WALL, tryNext = true)

        // 6) Refinement on the samples near the chosen line, then 7) on its own stretch of the depth profile.
        val sigmas = expectedPlanSigmas(c.samples, choice.wall.fit.line, c.viewer, c.config)
        val weights = sigmas.map { s -> max(s, MIN_WEIGHT_SIGMA).let { 1.0 / (it * it) } }
        var refined = refine(choice.wall.fit.line, c.usable, weights, sigmas, c.viewer, c.threshold, c.minInliers, null)
            ?: return Rejection(WallFitFailure.NO_DOMINANT_PLANE, choice.wall.fit.inlierCount, null, tryNext = true)
        // Where the wall is looked for: at the crosshair, or without aim point where the surface's samples are.
        val seedAzimuth = if (c.aim != null) 0.0 else c.azimuthOf(refined.line.point - c.viewer)
        // Something must have been sampled at the crosshair; without one the nearest station will do.
        val seedStation = c.stationAt(seedAzimuth, if (c.aim != null) SEED_STATION_TOLERANCE_DEG else 360.0)
            ?: return notAWall(refined.count)
        // Grossly noisy right at the crosshair: say so, rather than "not a flat wall". Only when most of that
        // station's samples are this (thick) surface: a stray line through a few range outliers is not one.
        val seedNoise = localNoise(refined.line, sigmas, c.stations[seedStation], c) ?: return notAWall(refined.count)
        if (seedNoise.ratio > c.config.grossNoiseFactor && seedNoise.sigma > c.config.maxRms &&
            2 * seedNoise.count > c.stations[seedStation].members.size
        ) {
            return Rejection(WallFitFailure.TOO_NOISY, refined.count, null, tryNext = false, noiseRatio = seedNoise.ratio)
        }
        // Walk, refit on the stretch found, walk again … until the line's own stretch is the one it was fitted to.
        // The first rounds may move freely (a tilted candidate grows into the whole wall); later ones may only
        // give samples up, so a chunk that fits one line and not the next cannot make the two alternate for ever.
        var fitted: BooleanArray? = null
        var stretch: Stretch? = null
        for (round in 0..SEGMENT_ROUNDS) {
            val within = if (round >= FREE_SEGMENT_ROUNDS) fitted else null
            val next = flatStretch(refined.line, sigmas, seedAzimuth, seedStation, within, c) ?: return notAWall(refined.count)
            if (next.stepCut) stepSeen = true
            if (next.stations < MIN_STATIONS) return notAWall(refined.count)
            if (fitted != null && next.sameAs(fitted)) {
                stretch = next
                break
            }
            if (round == SEGMENT_ROUNDS) break
            fitted = next.allowed
            refined = refine(refined.line, c.usable, weights, sigmas, c.viewer, c.threshold, c.minInliers, next.allowed) ?: return notAWall(refined.count)
        }
        if (stretch == null) return notAWall(refined.count)
        // The line as finally fitted must itself rest on three stations (its tighter bands can let one go).
        if (c.stationsOf(refined.inliers) < MIN_STATIONS) return notAWall(refined.count)
        if (!stretch.flat) return notAWall(refined.count)

        val explained = c.usable.size - (choice.foreground?.fit?.inlierCount ?: 0)
        val inlierRatio = if (explained > 0) min(1.0, refined.count.toDouble() / explained) else 0.0
        val extentLength = refined.tMax - refined.tMin
        if (inlierRatio < MIN_INLIER_RATIO || extentLength < MIN_EXTENT) {
            return Rejection(WallFitFailure.NO_DOMINANT_PLANE, refined.count, null, tryNext = true)
        }
        val noise = noiseNear(c.usable, sigmas, refined.line, NOISE_WINDOW_FACTOR * c.threshold, c.viewer, refined.centralLo, refined.centralHi)

        // … through the aim point, also without the crosshair's own samples?
        if (!throughAim(refined, noise, c)) return notAWall(refined.count)
        if (!confirmedAtAim(refined, stretch, seedStation, sigmas, weights, c)) return notAWall(refined.count)

        // 8) Noise: gross cap, then the line's own precision.
        val sigma = max(refined.rms, noise.sigma)
        if (noise.ratio > c.config.grossNoiseFactor && sigma > c.config.maxRms) {
            return Rejection(WallFitFailure.TOO_NOISY, refined.count, null, tryNext = false, noiseRatio = noise.ratio)
        }
        // √12 · spread is the evenly covered length with the same leverage as where the inliers' rays really meet
        // the line (a patch seen through a few sampling columns has a long extent at grazing incidence but
        // little spread).
        val (offsetError, statisticalDirectionError) = c.config.lineErrors(noise.sigma, refined.count, sqrt(12.0) * refined.spread)
        val directionError = hypot(statisticalDirectionError, stretch.endTilt)
        if (!(offsetError <= c.config.maxOffsetError) || !(directionError <= Math.toRadians(c.config.maxDirectionErrorDeg))) {
            return Rejection(WallFitFailure.TOO_NOISY, refined.count, if (stepSeen) HINT_STEP else HINT_IMPRECISE, tryNext = false, noiseRatio = noise.ratio)
        }

        // 9) Orientation: viewer on the LEFT; extents along +direction in the final orientation.
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

        // 10) Confidence.
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
        return Accepted(WallFit(observation, null, refined.count, c.total, noiseRatio = noise.ratio))
    }

    /** The stretch of the depth profile a surface may use: its [allowed] samples, seen at [stations] stations. */
    private class Stretch(
        val allowed: BooleanArray,
        /** Stations holding a fair share of samples (see [MIN_STATION_SHARE]). */
        val stations: Int,
        /** Stations in the flatness sum: every one with [MIN_STATION_SAMPLES] samples near the line. */
        val judged: Int,
        /** Measured-to-expected noise at the seed station (1 … [MAX_SEGMENT_NOISE_RATIO]). */
        val noiseRatio: Double,
        /** Σ (station median residual / its standard error)² over the judged stations. */
        val chiSquare: Double,
        /**
         * Direction error (radians, 1 σ) the wall may carry unseen because an end station could be the next
         * face's: the end stations' position errors over the lever between them (see [flatStretch]).
         */
        val endTilt: Double,
        /** A shallow step ([shallowStep]) ended this stretch on one side. */
        val stepCut: Boolean,
        /** Exactly three stations, and the middle one is off the line through the other two ([bentTriple]). */
        val bent: Boolean,
    ) {
        /** The stations' residuals are jointly consistent with one flat face (see [FLATNESS_Z] and [bentTriple]). */
        val flat: Boolean get() = !bent && (judged <= 2 || chiSquare <= chiSquareLimit(judged - 2))

        /** The same samples as [other] up to [SETTLED_FRACTION] of them (a chunk flipping at one end is not a change). */
        fun sameAs(other: BooleanArray): Boolean {
            var union = 0
            var different = 0
            for (i in allowed.indices) {
                if (allowed[i] || other[i]) union++
                if (allowed[i] != other[i]) different++
            }
            return different <= SETTLED_FRACTION * union
        }
    }

    /**
     * A run of one station's samples, consecutive in azimuth, with [support] samples near the line; [fits] when
     * those are centred on the line (and so are the station's as a whole).
     */
    private class Chunk(val station: Int, val members: IntArray, val azimuthLow: Double, val azimuthHigh: Double, val support: Int) {
        var fits = false
    }

    /**
     * Step 7 of [fromPoints]: the wall's own stretch of the depth profile.
     *
     * Seen from one place, every direction shows exactly one surface (at one height), so along the azimuth the
     * faces of a room do not overlap, whereas in the plan their samples do: near a corner the neighbouring
     * face's samples are smeared along their rays into this face's band (± 4 cm at 2 m). A short wall seen at an
     * angle sits between two such corners; a line through one of its sampling columns and one of a neighbour's
     * fits both perfectly, and so does the diagonal of a closet return through one column of each of its three
     * faces (review round 2, MATH-1: "fake diagonal chord"). Four rules, all in azimuth order:
     *
     * 1. Walk. Each station's samples within the RANSAC band of [line] are cut into chunks along the azimuth;
     *    starting at the chunk in the [seedAzimuth] direction (the crosshair), chunks are taken in both
     *    directions while each one's median residual fits [line] (see [SEGMENT_Z]) and so does its whole
     *    station's. The first chunk that does not fit belongs to another face and ends the stretch on that side,
     *    and the chunk taken just before it in the same station is given up too (it holds the corner). A station
     *    with fewer than [MIN_STATION_SAMPLES] samples near the line but that many off it, at the heights where
     *    the surface shows at the crosshair, shows another surface (the next face, a doorway, a column in front)
     *    and ends the stretch as well: stepping over it would let a chord skip the faces it cuts across. Stations
     *    without samples at those heights (one stray frame, furniture below the crosshair) are stepped over.
     * 2. Neighbours. While the outermost station of the stretch lies on the line through the next two stations
     *    beyond it ([ownedByNeighbour]), it is the neighbouring face's, not this wall's, and is given up.
     * 3. Steps. Where the stations left show a shallow step (two parallel faces a few centimetres apart, see
     *    [shallowStep]), the stretch ends at the step and only the side the crosshair is on is kept.
     * 4. The caller requires [MIN_STATIONS] stations: a wall is only a wall where it is seen flat at three places.
     *
     * Tolerances scale with the noise actually measured at the seed station ([localNoise]), relative to the
     * noise model, clamped to 1 … [MAX_SEGMENT_NOISE_RATIO].
     *
     * With [within], only those samples may be used (a chunk mostly outside them ends the stretch like one that
     * does not fit): the stretch can then only shrink.
     *
     * Returns null when nothing near [line] was sampled at [seedStation], when the seed chunk does not fit (the
     * line is not the surface at the crosshair) or when the seed station is a neighbour's.
     */
    private fun flatStretch(line: Line2, sigmas: List<Double>, seedAzimuth: Double, seedStation: Int, within: BooleanArray?, c: FitContext): Stretch? {
        val stations = c.stations
        val normal = line.normal
        fun perp(i: Int) = max(sigmas[i] * abs(normal dot c.rays[i]), MIN_PERP_SIGMA)
        fun gapTo(low: Double, high: Double) = max(0.0, max(low - seedAzimuth, seedAzimuth - high))
        val ratio = (localNoise(line, sigmas, stations[seedStation], c) ?: return null).ratio.coerceIn(1.0, MAX_SEGMENT_NOISE_RATIO)

        // Heights at which the surface shows at the crosshair's station: only samples at those heights can tell
        // that another surface stands where the wall should be (a wardrobe below the crosshair does not).
        val seedHeights = stations[seedStation].members.filter { abs(line.signedDistance(c.usable[it])) <= c.threshold }.map { c.samples[it].height }.sorted()
        if (seedHeights.size < MIN_STATION_SAMPLES) return null
        val heights = percentile(seedHeights, INTERVAL_LOW)..percentile(seedHeights, INTERVAL_HIGH)

        // 1) Chunks of every sampled station, in ascending azimuth. A station that shows another surface at those
        // heights becomes one chunk that never fits.
        val chunks = ArrayList<Chunk>()
        for ((s, station) in stations.withIndex()) {
            val members = station.members
            if (members.size < MIN_STATION_SAMPLES) continue
            val near = BooleanArray(members.size) { abs(line.signedDistance(c.usable[members[it]])) <= c.threshold }
            val support = near.count { it }
            if (support < MIN_STATION_SAMPLES) {
                // Another surface there (enough samples off the line at the wall's heights), or too few samples to tell.
                val elsewhere = members.indices.count { !near[it] && c.samples[members[it]].height in heights }
                if (elsewhere >= MIN_STATION_SAMPLES) chunks += Chunk(s, members, station.azimuthLow, station.azimuthHigh, 0)
                continue
            }
            val pieces = (support / CHUNK_SAMPLES).coerceIn(1, MAX_CHUNKS)
            var from = 0
            var seen = 0
            var piece = 1
            for (m in members.indices) {
                if (near[m]) seen++
                val last = m == members.lastIndex
                if (!last && (piece == pieces || seen < support * piece / pieces)) continue
                val run = IntArray(m + 1 - from) { members[from + it] }
                chunks += Chunk(s, run, c.azimuth[run.first()], c.azimuth[run.last()], (from..m).count { near[it] })
                from = m + 1
                piece++
            }
        }
        val seed = chunks.indices.filter { chunks[it].station == seedStation && chunks[it].support > 0 }
            .minByOrNull { gapTo(chunks[it].azimuthLow, chunks[it].azimuthHigh) } ?: return null

        /** Standard error of the median residual of [count] samples with expected noise [perps] across the line. */
        fun medianError(perps: List<Double>, count: Int): Double {
            val perSample = ratio * RobustStats.median(perps)
            return hypot(MEDIAN_SE_FACTOR * perSample / sqrt(count.toDouble()), SEGMENT_SYSTEMATIC * perSample)
        }

        /** The samples near the line among [members] are centred on it, within the tolerance for their number. */
        fun centred(members: IntArray): Boolean {
            val residuals = ArrayList<Double>()
            val perps = ArrayList<Double>()
            for (i in members) {
                val d = line.signedDistance(c.usable[i])
                if (abs(d) > c.threshold) continue
                residuals += d
                perps += perp(i)
            }
            if (residuals.size < MIN_STATION_SAMPLES) return false
            return abs(RobustStats.median(residuals)) <= max(SEGMENT_MIN_DEVIATION, SEGMENT_Z * medianError(perps, residuals.size))
        }
        val stationFits = BooleanArray(stations.size) { stations[it].members.size >= MIN_STATION_SAMPLES && centred(stations[it].members) }
        for (chunk in chunks) {
            chunk.fits = chunk.support > 0 && centred(chunk.members) && (within == null || 2 * chunk.members.count { within[it] } > chunk.members.size)
        }
        if (!chunks[seed].fits) return null
        val taken = BooleanArray(chunks.size)
        taken[seed] = true
        for (step in intArrayOf(-1, 1)) {
            var k = seed + step
            while (k in chunks.indices) {
                val station = chunks[k].station
                if (!chunks[k].fits || (station != seedStation && !stationFits[station])) {
                    val before = k - step
                    if (before != seed && chunks[before].station == station) taken[before] = false
                    break
                }
                taken[k] = true
                k += step
            }
        }

        // 2) End stations that lie on the neighbouring surface are not this wall's.
        for (step in intArrayOf(-1, 1)) {
            while (true) {
                var end = -1
                for (k in chunks.indices) if (taken[k] && (end < 0 || (chunks[k].station - end) * step > 0)) end = chunks[k].station
                if (end < 0) return null
                val beyond = ArrayList<Station>(3)
                var s = end + step
                while (s in stations.indices && beyond.size < 3) {
                    if (stations[s].members.size >= MIN_STATION_SAMPLES) beyond += stations[s]
                    s += step
                }
                // Where this wall is at its end station: the samples there that support it (the station's other
                // samples may be furniture below the crosshair or the ceiling above it).
                val supporting = ArrayList<Int>()
                for (k in chunks.indices) {
                    if (!taken[k] || chunks[k].station != end) continue
                    for (i in chunks[k].members) if (abs(line.signedDistance(c.usable[i])) <= c.threshold) supporting += i
                }
                if (supporting.size < MIN_STATION_SAMPLES || !ownedByNeighbour(line, c.station(supporting.toIntArray()), beyond, ratio)) break
                if (end == seedStation) return null
                for (k in chunks.indices) if (chunks[k].station == end) taken[k] = false
            }
        }

        // 3) What is left: the samples the wall may use, its stations and their joint flatness.
        fun judge(): StationProfile {
            val profile = StationProfile(c.usable.size)
            var k = 0
            while (k < chunks.size) {
                val station = chunks[k].station
                val residuals = ArrayList<Double>()
                val perps = ArrayList<Double>()
                val along = ArrayList<Double>()
                while (k < chunks.size && chunks[k].station == station) {
                    if (taken[k]) {
                        for (i in chunks[k].members) {
                            if (within != null && !within[i]) continue
                            profile.allowed[i] = true
                            val d = line.signedDistance(c.usable[i])
                            if (abs(d) > c.threshold) continue
                            residuals += d
                            perps += perp(i)
                            along += line.param(c.usable[i])
                        }
                    }
                    k++
                }
                if (residuals.size < MIN_STATION_SAMPLES) continue
                val error = medianError(perps, residuals.size)
                val median = RobustStats.median(residuals)
                val perSample = ratio * RobustStats.median(perps)
                profile.supports += residuals.size
                profile.errors += error
                profile.stepErrors += hypot(MEDIAN_SE_FACTOR * perSample / sqrt(residuals.size.toDouble()), STEP_SYSTEMATIC * perSample)
                profile.noises += perSample
                profile.medians += median
                profile.positions += RobustStats.median(along)
                profile.indices += station
                val z = median / error
                profile.chiSquare += z * z
            }
            return profile
        }
        var profile = judge()
        var stepCut = false
        // A shallow step inside the stretch (a door, a picture, a TV: see [shallowStep]) ends it like a corner
        // does: only the side the crosshair is on stays. A recessed door has a step at each jamb, hence the loop.
        for (pass in 0 until MAX_STEPS) {
            val minDepth = max(STEP_MIN_DEPTH, STEP_MIN_SIGMAS * RobustStats.median(profile.noises))
            val step = shallowStep(profile.positions, profile.medians, profile.stepErrors, minDepth) ?: break
            stepCut = true
            val lowEnd = profile.indices[step.lastLow]
            val highStart = profile.indices[step.firstHigh]
            for (k in chunks.indices) {
                if (!taken[k]) continue
                val station = chunks[k].station
                val keep = when {
                    seedStation <= lowEnd -> station <= lowEnd
                    seedStation >= highStart -> station >= highStart
                    else -> station == seedStation // the crosshair is on the edge itself
                }
                if (!keep) taken[k] = false
            }
            profile = judge()
        }
        // Three stations cannot show which side of a step is the wall, only that they are not in one line.
        val bent = profile.indices.size == MIN_STEP_STATIONS - 1 && run {
            val minDepth = max(STEP_MIN_DEPTH, STEP_MIN_SIGMAS * RobustStats.median(profile.noises))
            bentTriple(profile.positions, profile.medians, profile.stepErrors, minDepth)
        }
        val allowed = profile.allowed
        val supports = profile.supports
        val errors = profile.errors
        val positions = profile.positions
        val indices = profile.indices
        val chiSquare = profile.chiSquare
        // A station counts when it holds a fair share of samples: one stray frame between two sampling columns
        // (a jolt of the hand) is not a third place where the wall was seen.
        val fairShare = max(MIN_STATION_SAMPLES.toDouble(), MIN_STATION_SHARE * RobustStats.median(supports.map { it.toDouble() }))
        // How far an end station that is really the next face's could tilt the line without showing: its
        // position error over the lever between the two end stations. Only an end with something sampled
        // beyond it can be a neighbour's; an end at the edge of the sampled field cannot.
        fun sampledBeyond(station: Int, step: Int): Boolean {
            var s = station + step
            while (s in stations.indices) {
                if (stations[s].members.size >= MIN_STATION_SAMPLES) return true
                s += step
            }
            return false
        }
        var endTilt = Double.POSITIVE_INFINITY
        if (indices.size >= 2) {
            val lever = abs(positions.last() - positions.first())
            val low = if (sampledBeyond(indices.first(), -1)) errors.first() else 0.0
            val high = if (sampledBeyond(indices.last(), 1)) errors.last() else 0.0
            if (lever > 1e-6) endTilt = hypot(low, high) / lever
        }
        return Stretch(allowed, supports.count { it >= fairShare }, supports.size, ratio, chiSquare, endTilt, stepCut, bent)
    }

    /** The stations of a stretch as [flatStretch] judges them, in azimuth order. */
    private class StationProfile(samples: Int) {
        val allowed = BooleanArray(samples)
        val supports = ArrayList<Int>()
        val errors = ArrayList<Double>()

        /** Standard errors for [shallowStep]: without most of the allowance for smooth depth errors. */
        val stepErrors = ArrayList<Double>()

        /** Expected per-sample noise across the line at each station (m). */
        val noises = ArrayList<Double>()
        val medians = ArrayList<Double>()
        val positions = ArrayList<Double>()
        val indices = ArrayList<Int>()
        var chiSquare = 0.0
    }

    /**
     * A shallow step in a stretch: something flat that stands a few centimetres proud of the wall or is set
     * into it (a closed door seen from its recessed side, a wall-mounted TV, a deep picture frame, a radiator
     * cover). When the step is smaller than the inlier band (2 σ of the depth noise: 6 cm at 2 m, 11 cm at
     * 4 m) both surfaces are inliers of one line, each station passes its own tolerance, and the line that
     * fits best is a diagonal through the wall on one side and the object on the other: 5–9° off, which the
     * room solver keeps as measured. The chord has nothing to do with noise, so the precision gates pass it.
     *
     * The stations' median residuals ([medians], with standard errors [errors]) at the places [positions]
     * along the line are fitted twice by weighted least squares: as one line, and as two PARALLEL lines with
     * a step between two neighbouring stations (every split of [MIN_STEP_STATIONS] stations or more; a single
     * end station that is off the line through all the others is the smallest case, and it alone turns a
     * line through three stations by 6°). A step is reported when the best split lowers χ² by [STEP_Z]² or
     * more (one extra parameter, so that gain is χ²(1) for a flat wall: p ≈ 0.0005 per split) and is at
     * least [minDepth] deep. The station on the edge itself sees both faces and lies somewhere between them,
     * which blurs a clean step; so every split is also tried with that one station left out.
     *
     * The errors here are tighter than the ones a station is judged by ([SEGMENT_SYSTEMATIC]): that allowance
     * is for depth errors that vary smoothly over a patch of wall, and a smooth error (a bowed patch, a
     * gradient) is not a step: neighbouring stations share it, and the step model does not fit it better
     * than a line by a step of [minDepth]. What decides is therefore the size of the step, [minDepth]: about
     * half the per-sample noise, three times the smooth error the estimator allows for, and the size from
     * which a chord turns the wall by more than squaring absorbs. A tilted wall is still one line.
     * Returns where the step is, or null.
     */
    private fun shallowStep(positions: List<Double>, medians: List<Double>, errors: List<Double>, minDepth: Double): Step? {
        val n = positions.size
        if (n < MIN_STEP_STATIONS) return null
        if (errors.any { !(it > 0.0) || !it.isFinite() }) return null
        var best: Step? = null
        var bestGain = STEP_Z * STEP_Z
        // [skip] = the station on the edge that is left out of both sides, or -1 for a clean step.
        for (skip in -1 until n) {
            if (skip >= 0 && (n - 1 < MIN_STEP_STATIONS || skip == 0 || skip == n - 1)) continue
            val used = (0 until n).filter { it != skip }
            val w = DoubleArray(n) { 1.0 / (errors[it] * errors[it]) }
            val sw = used.sumOf { w[it] }
            val centre = used.sumOf { w[it] * positions[it] } / sw
            var stt = 0.0
            var sy = 0.0
            var sty = 0.0
            for (i in used) {
                val t = positions[i] - centre
                stt += w[i] * t * t
                sy += w[i] * medians[i]
                sty += w[i] * t * medians[i]
            }
            if (stt < 1e-12) continue
            // One line (Σ w t = 0 after centring).
            val a0 = sy / sw
            val b0 = sty / stt
            var chiLine = 0.0
            for (i in used) chiLine += w[i] * (medians[i] - a0 - b0 * (positions[i] - centre)).let { it * it }
            if (chiLine < bestGain) continue

            val splits = if (skip >= 0) skip - 1..skip - 1 else 0..n - 2
            for (split in splits) {
                // y = a + b t + c h with h = 1 beyond the split; normal equations of the weighted fit.
                var sh = 0.0
                var sth = 0.0
                var shy = 0.0
                for (i in used) {
                    if (i <= split) continue
                    sh += w[i]
                    sth += w[i] * (positions[i] - centre)
                    shy += w[i] * medians[i]
                }
                // | sw  0   sh  | a     | sy  |
                // | 0   stt sth | b  =  | sty |
                // | sh  sth sh  | c     | shy |
                val det = sw * (stt * sh - sth * sth) - sh * (stt * sh)
                if (abs(det) < 1e-9 * sw * stt * sh || det == 0.0) continue
                val a = (sy * (stt * sh - sth * sth) + sh * (sty * sth - stt * shy)) / det
                val b = (sw * (sty * sh - sth * shy) - sh * (sty * sh - sth * sy)) / det
                val depth = (sw * (stt * shy - sth * sty) - sh * (stt * sy)) / det
                var chiStep = 0.0
                for (i in used) {
                    chiStep += w[i] * (medians[i] - a - b * (positions[i] - centre) - (if (i > split) depth else 0.0)).let { it * it }
                }
                val gain = chiLine - chiStep
                // A single station on one side is only as good as the line through the others carried out to
                // it, and a bowed patch bends away from that line at its ends: it needs a deeper step, and
                // its neighbour may not be left out as well.
                val low = used.count { it <= split }
                val lone = low == 1 || used.size - low == 1
                if (lone && skip >= 0) continue
                if (gain >= bestGain && abs(depth) >= (if (lone) LONE_STATION_FACTOR * minDepth else minDepth)) {
                    bestGain = gain
                    best = Step(lastLow = split, firstHigh = if (skip >= 0) skip + 1 else split + 1)
                }
            }
        }
        return best
    }

    /**
     * Where [shallowStep] found a step: between the stations [lastLow] and [firstHigh] of the profile. They
     * are neighbours, or have one station between them that sits on the edge and belongs to neither face.
     */
    private class Step(val lastLow: Int, val firstHigh: Int)

    /**
     * [shallowStep] for a stretch of exactly three stations: a step between two of them shows as the middle
     * station lying off the line through the outer two, by half the step when they are evenly spaced. Three
     * stations cannot tell which side is the wall (a step after the first and one after the second fit them
     * equally well), so such a stretch is not a flat face at all. The same errors and minimum step as in
     * [shallowStep] apply; a smooth depth error over three neighbouring stations bends them far less.
     */
    private fun bentTriple(positions: List<Double>, medians: List<Double>, errors: List<Double>, minDepth: Double): Boolean {
        if (positions.size != 3) return false
        val span = positions[2] - positions[0]
        if (abs(span) < 1e-9) return false
        val f = (positions[1] - positions[0]) / span
        if (f <= 0.0 || f >= 1.0) return false
        val off = medians[1] - (medians[0] + f * (medians[2] - medians[0]))
        val error = sqrt(errors[1] * errors[1] + (1 - f) * (1 - f) * errors[0] * errors[0] + f * f * errors[2] * errors[2])
        // A step of minDepth right after the first or before the last station puts the middle one off by
        // (1 − f) or f times that.
        return abs(off) >= STEP_Z * error && abs(off) >= min(f, 1 - f) * minDepth
    }

    /** Upper quantile, [FLATNESS_Z] normal deviations out, of χ² with [dof] degrees of freedom (Wilson–Hilferty). */
    private fun chiSquareLimit(dof: Int): Double {
        val k = 2.0 / (9.0 * dof)
        val v = 1.0 - k + FLATNESS_Z * sqrt(k)
        return dof * v * v * v
    }

    /**
     * Noise of one station's samples, free of how well [line] fits them: the scatter of the samples within the
     * wide noise window of [line] about the median of their own neighbourhood (runs of [CHUNK_SAMPLES] samples,
     * consecutive in azimuth), as a robust σ (m, across the line) and relative to the noise model. A chord must
     * not excuse itself by the misfit it causes, so the scatter is NOT taken about the line. Null with fewer
     * than [MIN_STATION_SAMPLES] samples in the window.
     */
    private fun localNoise(line: Line2, sigmas: List<Double>, station: Station, c: FitContext): Noise? {
        val window = NOISE_WINDOW_FACTOR * c.threshold
        val normal = line.normal
        val inWindow = station.members.filter { abs(line.signedDistance(c.usable[it])) <= window }
        if (inWindow.size < MIN_STATION_SAMPLES) return null
        val runs = (inWindow.size / CHUNK_SAMPLES).coerceAtLeast(1)
        val scatter = ArrayList<Double>(inWindow.size)
        val normalized = ArrayList<Double>(inWindow.size)
        for (run in 0 until runs) {
            val members = inWindow.subList(run * inWindow.size / runs, (run + 1) * inWindow.size / runs)
            val middle = RobustStats.median(members.map { line.signedDistance(c.usable[it]) })
            for (i in members) {
                val d = line.signedDistance(c.usable[i]) - middle
                scatter += d
                normalized += d / max(sigmas[i] * abs(normal dot c.rays[i]), MIN_PERP_SIGMA)
            }
        }
        val sigma = RobustStats.MAD_TO_SIGMA * RobustStats.mad(scatter)
        val ratio = RobustStats.MAD_TO_SIGMA * RobustStats.mad(normalized)
        return if (sigma.isFinite() && ratio.isFinite()) Noise(sigma, ratio, inWindow.size) else null
    }

    /**
     * Cross-check at the crosshair: the wall, fitted again WITHOUT the crosshair's own station, must still pass
     * through the surface there ([FitContext.aimCluster]) within [AIM_CLUSTER_SIGMAS] standard errors (the
     * cluster's own, with the same systematic allowance as a chunk, and the line's at that place), at least
     * [MIN_AIM_CLUSTER_TOLERANCE] m. Always true without an aim cluster.
     *
     * Next to a corner the neighbouring wall, bent a little towards the crosshair's samples, fits them within
     * every per-station tolerance when the sensor is noisier than the model; fitted without them it passes
     * centimetres away. A wall that really is at the crosshair does not need its samples to get there.
     */
    private fun confirmedAtAim(refined: Refined, stretch: Stretch, seedStation: Int, sigmas: List<Double>, weights: List<Double>, c: FitContext): Boolean {
        val cluster = c.aimCluster ?: return true
        val allowed = stretch.allowed.copyOf()
        for (i in c.stations[seedStation].members) allowed[i] = false
        val others = refine(refined.line, c.usable, weights, sigmas, c.viewer, c.threshold, c.minInliers, allowed) ?: return false
        val ratio = stretch.noiseRatio
        val across = abs(others.line.normal dot (cluster.center - c.viewer).normalized()).coerceAtLeast(MIN_RAY_COS)
        val perSample = ratio * cluster.centerSigma * sqrt(cluster.members.size.toDouble()) / MEDIAN_SE_FACTOR
        val clusterError = across * hypot(ratio * cluster.centerSigma, SEGMENT_SYSTEMATIC * perSample)
        // The line's standard error where the cluster is: its offset error at the samples' centroid (the line's
        // anchor) and its direction error times the distance from there.
        val (offsetError, directionError) = c.config.lineErrors(across * perSample, others.count, sqrt(12.0) * others.spread)
        val lineError = hypot(offsetError, directionError * abs(others.line.param(cluster.center)))
        return others.line.distance(cluster.center) <= max(MIN_AIM_CLUSTER_TOLERANCE, AIM_CLUSTER_SIGMAS * hypot(clusterError, lineError))
    }

    /**
     * True when the end of a stretch along [line] lies on the surface seen just [beyond] it. [end] holds the
     * samples of the end station that support the line, [beyond] the next stations outwards, nearest first.
     * The first two of those are not both on [line] (else the same wall simply goes on there), the line through
     * their robust positions passes [end]'s within the tolerance (see [NEIGHBOUR_SIGMAS]; standard errors from
     * the noise model × [ratio], extrapolated), and a third station, where there is one, lies on that line too
     * (two positions always make a line; three make a surface).
     *
     * Such a station is where the neighbouring face is, or the corner itself: a chord across a closet return
     * uses one station of each neighbouring face, and each of those is on its own face's line.
     */
    private fun ownedByNeighbour(line: Line2, end: Station, beyond: List<Station>, ratio: Double): Boolean {
        if (beyond.size < 2) return false
        val first = beyond[0]
        val second = beyond[1]
        fun tolerance(sigma: Double) = max(MIN_NEIGHBOUR_TOLERANCE, NEIGHBOUR_SIGMAS * ratio * sigma)
        if (line.distance(first.center) <= tolerance(first.centerSigma) && line.distance(second.center) <= tolerance(second.centerSigma)) return false
        val base = first.center.distanceTo(second.center)
        if (base < MIN_NEIGHBOUR_BASE) return false
        val neighbour = Line2.through(first.center, second.center)
        // Standard error of the neighbour line at a point r base lengths beyond `first`, plus the point's own.
        fun onNeighbour(station: Station): Boolean {
            val r = abs(neighbour.param(station.center)) / base
            if (r > MAX_NEIGHBOUR_EXTRAPOLATION) return false
            val sigma = sqrt(
                station.centerSigma * station.centerSigma +
                    (1 + r) * (1 + r) * first.centerSigma * first.centerSigma + r * r * second.centerSigma * second.centerSigma,
            )
            return neighbour.distance(station.center) <= tolerance(sigma)
        }
        if (beyond.size >= 3 && !onNeighbour(beyond[2])) return false
        return onNeighbour(end)
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
     */
    private class Refined(
        val line: Line2,
        val count: Int,
        val rms: Double,
        val tMin: Double,
        val tMax: Double,
        val centralLo: Double,
        val centralHi: Double,
        /** Standard deviation (m) of where the inliers' rays meet the line: the leverage behind its direction. */
        val spread: Double,
        /** Indices of the supporting samples. */
        val inliers: List<Int>,
    )

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
     * 4. Extent, core and spread of the supporting samples (see [Refined]).
     */
    private fun refine(
        initial: Line2,
        usable: List<Vec2>,
        weights: List<Double>,
        sigmas: List<Double>,
        viewer: Vec2,
        threshold: Double,
        minInliers: Int,
        /** Samples this surface may use (null = all): its own stretch of the depth profile, see [flatStretch]. */
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
        for ((k, i) in inliers.withIndex()) {
            val d = line.signedDistance(usable[i])
            sumSq += d * d
            ts[k] = rayHitParam(line, viewer, usable[i])
        }
        val sortedTs = ts.sorted()
        val lo = percentile(sortedTs, EXTENT_LOW)
        val hi = percentile(sortedTs, EXTENT_HIGH)
        val widen = (hi - lo) * EXTENT_LOW / (EXTENT_HIGH - EXTENT_LOW)
        val meanT = ts.average()
        val spread = sqrt(ts.sumOf { t -> (t - meanT) * (t - meanT) } / count)
        return Refined(
            line, count, sqrt(sumSq / count), lo - widen, hi + widen,
            percentile(sortedTs, NOISE_CORE_LOW), percentile(sortedTs, NOISE_CORE_HIGH),
            spread, inliers,
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
            if (abs(z) > SPUR_Z && abs(mean) > MIN_SPUR_DEVIATION) out += members
        }
        return out
    }

    /** [q]-quantile of an ascending list (NaN when empty). */
    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        return sorted[ceil((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)]
    }

    /**
     * Robust noise near a line: [sigma] (m, across the line) and [ratio] to the noise the model expects there,
     * from [count] samples.
     */
    private class Noise(val sigma: Double, val ratio: Double, val count: Int)

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
        if (residuals.isEmpty()) return Noise(0.0, 0.0, 0)
        return Noise(RobustStats.MAD_TO_SIGMA * RobustStats.mad(residuals), RobustStats.MAD_TO_SIGMA * RobustStats.mad(normalized), residuals.size)
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
