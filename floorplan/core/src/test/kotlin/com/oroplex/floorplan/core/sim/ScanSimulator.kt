package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.RobustStats
import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.solve.RoomSolver
import com.oroplex.floorplan.core.solve.SolveResult
import com.oroplex.floorplan.core.solve.SolverOptions
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * Depth-from-motion-like noise, applied along each hit ray: σ = [scale] · ([sigma0] + [sigmaPerMeter] · distance).
 *
 * The defaults are exactly the estimator's own noise prior (WallEstimatorConfig), i.i.d. per ray: the nominal
 * case. Review round 2 (MATH-7) showed that such a simulation only checks the estimator against its own
 * assumption, so the stress scenarios also use [scale] > 1 (real sensors on dark / glossy walls are noisier
 * than any prior), [frameRangeBias] (depth-from-motion errors are correlated within a frame: every ray of
 * a frame is off by the same relative range error, which more rays cannot average away) and [fieldBias] (a
 * smooth error across the image that stays the same for the whole capture: a patch of wall off by
 * centimetres together, which no number of samples or frames averages away).
 */
internal data class DepthNoise(
    val sigma0: Double = 0.005,
    val sigmaPerMeter: Double = 0.012,
    /** Fraction of samples replaced by a gross outlier at depth × U([outlierMinFactor], [outlierMaxFactor]). */
    val outlierRate: Double = 0.10,
    val outlierMinFactor: Double = 0.4,
    val outlierMaxFactor: Double = 1.6,
    /** Multiplier of the per-ray noise relative to the estimator's model (1 = the model). */
    val scale: Double = 1.0,
    /** σ of a per-frame relative range error: every ray of one frame has its range scaled by (1 + b), b ~ N(0, this). */
    val frameRangeBias: Double = 0.0,
    /**
     * Amplitude of a smooth relative range error across the sampling grid, drawn once per capture: with grid
     * coordinates u, v in [-1, 1], range × (1 + this · (c1·u + c2·v + c3·(u² − ⅓) + c4·(v² − ⅓) + c5·u·v)),
     * c ~ N(0, 1): a tilt and a bow of the measured surface.
     */
    val fieldBias: Double = 0.0,
) {
    fun sigma(distance: Double) = scale * (sigma0 + sigmaPerMeter * distance)

    companion object {
        val NONE = DepthNoise(sigma0 = 0.0, sigmaPerMeter = 0.0, outlierRate = 0.0)

        /** Pessimistic: 1.5 × the model per ray plus a 0.3 % per-frame range error. */
        val PESSIMISTIC = DepthNoise(scale = 1.5, frameRangeBias = 0.003)

        /** The nominal per-ray noise plus a 0.3 % smooth field error (7.5 mm across the grid at 2.5 m). */
        val SMOOTH_FIELD = DepthNoise(fieldBias = 0.003)
    }
}

/** How the simulated AR layer picks one sample from the hits of a ray (ARCore returns them nearest first). */
internal enum class HitPolicy {
    /** Review round 3 (MATH-5): the depth hit whenever there is one; a vertical-plane hit only where depth has a hole. */
    DEPTH_FIRST,

    /** Round-2 behaviour, kept for the record: the nearest of the depth and plane hits (censors depth errors). */
    NEAREST,
}

/**
 * ARCore vertical planes competing with depth hits. On [coverage] of the wall rays a detected plane of that
 * wall is also hit (at the wall face shifted by a per-capture offset error ~ N(0, [planeOffsetSigma])); on
 * [depthDropout] of all rays the depth map has a hole.
 */
internal data class PlaneHits(
    val coverage: Double = 0.0,
    val depthDropout: Double = 0.0,
    val planeOffsetSigma: Double = 0.01,
    val policy: HitPolicy = HitPolicy.DEPTH_FIRST,
) {
    companion object {
        val NONE = PlaneHits()
    }
}

/**
 * MeasureRequest.Wall sampling the way `WallSampler` in the AR layer does it: [frames] frames of a
 * [gridSize] × [gridSize] hit-test grid around the screen center (±[halfAngleDeg] in the image plane),
 * with a little hand shake between frames. The viewer handed to the estimator is the mean camera position.
 */
internal data class CaptureRig(
    val frames: Int = 15,
    val gridSize: Int = 9,
    val halfAngleDeg: Double = 12.0,
    val handPositionSigma: Double = 0.003,
    val handAngleSigmaDeg: Double = 0.3,
)

/** Systematic AR tracking error: the whole world (hits and camera poses) scaled by [scale] about [origin]. */
internal data class ArDistortion(val scale: Double = 1.0, val origin: Vec3 = Vec3.ZERO) {
    fun apply(p: Vec3): Vec3 = if (scale == 1.0) p else origin + (p - origin) * scale
}

/**
 * One simulated capture. [surfaceHits] counts the rays by the surface they hit first (wall index, or
 * [RayHit.FLOOR] / [RayHit.CEILING] / [RayHit.FURNITURE] / [RayHit.COLUMN]).
 */
internal class SimCapture(
    val fit: WallFit,
    val points: List<Vec3>,
    val viewer: Vec3,
    val surfaceHits: Map<Int, Int>,
    val aimPoint: Vec3?,
    /** First surface hit by the ray of each point in [points] (same order). */
    val pointSurfaces: List<Int> = emptyList(),
) {
    val rays: Int get() = surfaceHits.values.sum()

    fun fraction(surface: Int): Double = if (rays == 0) 0.0 else (surfaceHits[surface] ?: 0).toDouble() / rays
}

internal object ScanSimulator {
    /** Same as the AR layer's `WallSampling.MIN_AIM_SAMPLES`. */
    private const val MIN_AIM_SAMPLES = 3

    /**
     * Simulates one "Wall" capture from [station] aimed at [aim] and runs [WallEstimator.fromPoints].
     * [ceilingY] is what the capture flow passes to the estimator (the measured ceiling, or its assumed band
     * top when the ceiling was skipped), not necessarily the room's real ceiling.
     */
    fun capture(
        room: SimRoom,
        station: Vec3,
        aim: Vec3,
        rng: Random,
        noise: DepthNoise = DepthNoise(),
        rig: CaptureRig = CaptureRig(),
        distortion: ArDistortion = ArDistortion(),
        config: WallEstimatorConfig = WallEstimatorConfig(),
        /** Pass the reticle hit (median of the center-ray hits) like the app does; false = legacy, no aim point. */
        useAim: Boolean = true,
        planes: PlaneHits = PlaneHits.NONE,
        ceilingY: Double? = SIM_CEILING_Y,
    ): SimCapture {
        val g = rig.gridSize
        val points = ArrayList<Vec3>(rig.frames * g * g)
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        val tanHalf = tan(Math.toRadians(rig.halfAngleDeg))
        val half = (g - 1) / 2.0
        val tanShake = { tan(Math.toRadians(rng.nextGaussian() * rig.handAngleSigmaDeg)) }
        val surfaces = HashMap<Int, Int>()
        val pointSurfaces = ArrayList<Int>(rig.frames * g * g)
        val centerHits = ArrayList<Vec3>(rig.frames)
        val centerIndex = if (g % 2 == 1) g / 2 else -1
        // One detected-plane offset error per wall and capture (a plane is one estimate, not per-ray noise).
        // Random draws only happen for the options in use, so the nominal scenarios keep their exact samples.
        val planeOffsets = DoubleArray(room.size) { if (planes.coverage > 0) rng.nextGaussian() * planes.planeOffsetSigma else 0.0 }
        val field = DoubleArray(5) { if (noise.fieldBias > 0) rng.nextGaussian() * noise.fieldBias else 0.0 }
        for (frame in 0 until rig.frames) {
            val cam = station + Vec3(
                rng.nextGaussian() * rig.handPositionSigma,
                rng.nextGaussian() * rig.handPositionSigma,
                rng.nextGaussian() * rig.handPositionSigma,
            )
            val frameScale = if (noise.frameRangeBias > 0) 1.0 + rng.nextGaussian() * noise.frameRangeBias else 1.0
            val aimDir = (aim - cam).normalized()
            val (r0, u0) = basis(aimDir)
            val forward = (aimDir + r0 * tanShake() + u0 * tanShake()).normalized()
            val (right, up) = basis(forward)
            for (row in 0 until g) {
                for (col in 0 until g) {
                    val u = if (half > 0) (col - half) / half else 0.0
                    val v = if (half > 0) (half - row) / half else 0.0
                    val a = if (half > 0) tanHalf * (col - half) / half else 0.0
                    val b = if (half > 0) tanHalf * (half - row) / half else 0.0
                    val fieldScale = 1.0 + field[0] * u + field[1] * v + field[2] * (u * u - 1.0 / 3) + field[3] * (v * v - 1.0 / 3) + field[4] * u * v
                    val d = (forward + right * a + up * b).normalized()
                    val hit = room.hit(cam, d) ?: continue
                    surfaces.merge(hit.surface, 1, Int::plus)
                    val t = hit.t
                    val depth: Double? = if (planes.depthDropout > 0 && rng.nextDouble() < planes.depthDropout) {
                        null
                    } else if (rng.nextDouble() < noise.outlierRate) {
                        t * (noise.outlierMinFactor + (noise.outlierMaxFactor - noise.outlierMinFactor) * rng.nextDouble())
                    } else {
                        t * frameScale * fieldScale + rng.nextGaussian() * noise.sigma(t)
                    }
                    val plane: Double? = if (hit.surface >= 0 && planes.coverage > 0 && rng.nextDouble() < planes.coverage) {
                        room.planeHit(cam, d, hit.surface, planeOffsets[hit.surface])
                    } else {
                        null
                    }
                    val measured = when (planes.policy) {
                        HitPolicy.DEPTH_FIRST -> depth ?: plane
                        HitPolicy.NEAREST -> listOfNotNull(depth, plane).minOrNull()
                    } ?: continue
                    if (measured <= 0.05) continue
                    val p = distortion.apply(cam + d * measured)
                    points += p
                    pointSurfaces += hit.surface
                    if (row == centerIndex && col == centerIndex) centerHits += p
                }
            }
            val c = distortion.apply(cam)
            vx += c.x
            vy += c.y
            vz += c.z
        }
        val viewer = Vec3(vx / rig.frames, vy / rig.frames, vz / rig.frames)
        val aimPoint = if (useAim) aimPointOf(centerHits, rig.frames, wallBand(SIM_FLOOR_Y, ceilingY, config)) else null
        val fit = WallEstimator.fromPoints(points, SIM_FLOOR_Y, ceilingY, viewer, config, aimPoint)
        return SimCapture(fit, points, viewer, surfaces, aimPoint, pointSurfaces)
    }

    /**
     * The reticle hit the AR layer hands to the estimator (`WallSampling.aimPoint`): component-wise median of
     * the center-ray hits inside the estimator's height [band] (robust to the occasional outlier), null when
     * fewer than max(3, ⌈frames / 3⌉) of them are in the band (a handful of hits is no statement of intent).
     */
    fun aimPointOf(centerHits: List<Vec3>, frames: Int = 0, band: ClosedFloatingPointRange<Double>? = null): Vec3? {
        val inBand = if (band == null) centerHits else centerHits.filter { it.y in band }
        if (inBand.isEmpty() || inBand.size < max(MIN_AIM_SAMPLES, (frames + 2) / 3)) return null
        return Vec3(
            RobustStats.median(inBand.map { it.x }),
            RobustStats.median(inBand.map { it.y }),
            RobustStats.median(inBand.map { it.z }),
        )
    }

    /** The estimator's height band (same rule as `WallSampling.wallBand` in the AR layer). */
    fun wallBand(floorY: Double, ceilingY: Double?, config: WallEstimatorConfig = WallEstimatorConfig()): ClosedFloatingPointRange<Double> {
        val low = floorY + config.minHeightAboveFloor
        val measuredHigh = ceilingY?.takeIf { it.isFinite() }?.let { it - config.minDistanceBelowCeiling }
        val high = if (measuredHigh != null && measuredHigh > low) measuredHigh else floorY + config.assumedCeilingHeight - config.minDistanceBelowCeiling
        return low..high
    }

    /** Camera right / up vectors for a forward direction (world +Y up). */
    private fun basis(forward: Vec3): Pair<Vec3, Vec3> {
        val right = (forward cross Vec3.UP).normalized()
        val up = right cross forward
        return right to up
    }
}

/** Outcome of one wall as the simulated user captured it (including retries after failures). */
internal class WallCaptureLog(
    val edge: Int,
    val observation: WallObservation?,
    val attempts: Int,
    val failures: List<WallFitFailure>,
    /** Horizontal distance from the final station to the aim point. */
    val distance: Double,
    /** Distance of the FIRST attempt (what the scenario's station placement asked for). */
    val firstDistance: Double,
    /** The scenario's home station could not see the aim point (or was too far) and the user walked. */
    val relocated: Boolean,
    /**
     * True edge the accepted observation lies on (within 15° and 0.15 m of its face, in the possibly
     * scale-distorted AR frame); -1 when it matches no wall (e.g. furniture), -2 when nothing was accepted.
     */
    val matchedEdge: Int,
    /** Ray statistics of the accepted (else the last) attempt. */
    val surfaceHits: Map<Int, Int>,
    /** The accepted observation is within 5° and 5 cm of the aimed wall's face ([SimFace.matches]). */
    val precise: Boolean = observation != null && matchedEdge == edge,
    /** Incidence (degrees off square) of the FIRST attempt. */
    val firstIncidenceDeg: Double = 0.0,
) {
    val rays: Int get() = surfaceHits.values.sum()

    /**
     * The accepted observation is the wall that was aimed at, strictly: the right wall and within 5° / 5 cm of
     * its face. A tilted chord through a corner or the next face of a closet return is a WRONG wall.
     */
    val onTarget: Boolean get() = observation != null && matchedEdge == edge && precise

    fun fraction(surface: Int): Double = if (rays == 0) 0.0 else (surfaceHits[surface] ?: 0).toDouble() / rays
}

/**
 * A simulated user capturing walls the way the capture screen instructs: aim at the wall (random point
 * on the middle 60 % of the edge, 1.0–1.8 m high), hold steady, tap Capture. On a failure they do what the
 * message says: with a hint ("stand square to this wall", "a little closer") they stand square in front of
 * the wall, and closer (60 %) each time it comes again; on TOO_NOISY / TOO_FAR they move closer (to 60 % of
 * the distance); on a first NO_DOMINANT_PLANE / TOO_FEW_POINTS they re-aim, on a second one they stand square.
 */
internal class SimUser(
    val room: SimRoom,
    val noise: DepthNoise = DepthNoise(),
    val rig: CaptureRig = CaptureRig(),
    val distortion: ArDistortion = ArDistortion(),
    val config: WallEstimatorConfig = WallEstimatorConfig(),
    val maxAttempts: Int = 4,
    /** Height range (m) of the point on the wall the user aims at. */
    val aimHeight: ClosedFloatingPointRange<Double> = 1.0..1.8,
    /** The user walks closer before capturing when the aim point is farther than this (app hint: ~4 m). */
    val comfortRange: Double = 4.0,
    /** Where a user who has to walk stands: this far in front of the wall (clipped to half the room). */
    val preferredDistance: Double = 1.8,
    val minDistance: Double = 0.5,
    val planes: PlaneHits = PlaneHits.NONE,
    /** What the capture flow passes as ceilingY (see [ScanSimulator.capture]). */
    val ceilingY: Double? = SIM_CEILING_Y,
) {
    private class Aim(val plan: Vec2, val height: Double)

    private fun aimAt(edge: Int, rng: Random) =
        Aim(
            room.pointOn(edge, 0.2 + 0.6 * rng.nextDouble()),
            aimHeight.start + (aimHeight.endInclusive - aimHeight.start) * rng.nextDouble(),
        )

    /** Closest the user stands to wall [edge]: in front of any furniture standing against it. */
    private fun standOff(edge: Int): Double {
        val box = room.furniture.filter { it.edge == edge }.maxOfOrNull { it.depth } ?: return minDistance
        return max(minDistance, box + 0.3)
    }

    private fun usable(station: Vec2, edge: Int, aim: Vec2): Boolean =
        room.clearance(station) >= 0.15 &&
            room.frontDistance(edge, station) >= min(standOff(edge), 0.5 * room.depthInFront(edge, aim)) - 1e-9 &&
            room.lineOfSight(station, aim, edge) &&
            room.furniture.none { it.footprintContains(station, 0.1) }

    private fun stationInFront(edge: Int, aim: Vec2, distance: Double): Vec2 {
        val halfDepth = 0.5 * room.depthInFront(edge, aim)
        val d = min(distance, halfDepth).coerceAtLeast(min(standOff(edge), halfDepth))
        return aim + room.inwardNormal(edge) * d
    }

    /**
     * Capture wall [edge]. With [nearDistance] the user stands that far in front of the wall (clipped to
     * the room); otherwise at [home] when it sees the aim point within [comfortRange], else in front of it.
     */
    fun captureWall(edge: Int, home: Vec2?, rng: Random, nearDistance: Double? = null): WallCaptureLog {
        var aim = aimAt(edge, rng)
        var relocated = false
        var station = when {
            nearDistance != null -> stationInFront(edge, aim.plan, nearDistance)
            home != null && usable(home, edge, aim.plan) && home.distanceTo(aim.plan) <= comfortRange -> home
            else -> {
                relocated = home != null
                stationInFront(edge, aim.plan, preferredDistance)
            }
        }
        val firstDistance = station.distanceTo(aim.plan)
        val firstIncidence = incidenceDeg(edge, station, aim.plan)
        var squareUps = 0
        val failures = ArrayList<WallFitFailure>()
        var lastHits: Map<Int, Int> = emptyMap()
        for (attempt in 1..maxAttempts) {
            val cap = ScanSimulator.capture(
                room = room,
                station = Vec3.fromPlan(station, CAMERA_HEIGHT),
                aim = Vec3.fromPlan(aim.plan, aim.height),
                rng = rng,
                noise = noise,
                rig = rig,
                distortion = distortion,
                config = config,
                planes = planes,
                ceilingY = ceilingY,
            )
            lastHits = cap.surfaceHits
            val obs = cap.fit.observation
            if (obs != null && cap.fit.failure == null) {
                return WallCaptureLog(
                    edge, obs, attempt, failures, station.distanceTo(aim.plan), firstDistance, relocated,
                    matchEdge(obs), cap.surfaceHits, face(edge).matches(obs), firstIncidence,
                )
            }
            val reason = cap.fit.failure ?: WallFitFailure.NO_DOMINANT_PLANE
            failures += reason
            val dist = station.distanceTo(aim.plan)
            val closest = min(standOff(edge), 0.5 * room.depthInFront(edge, aim.plan))
            when {
                // "Stand square to this wall (a little closer)": do that; closer still when it comes again.
                cap.fit.hint != null -> {
                    squareUps++
                    aim = aimAt(edge, rng)
                    val target = if (squareUps == 1) min(dist, preferredDistance) else max(0.6 * min(dist, preferredDistance), closest)
                    station = stationInFront(edge, aim.plan, target)
                }
                reason == WallFitFailure.TOO_NOISY || reason == WallFitFailure.TOO_FAR -> {
                    val target = max(dist * 0.6, closest)
                    if (target < dist - 0.05) {
                        val dir = (station - aim.plan).normalized()
                        val candidate = aim.plan + dir * target
                        station = if (usable(candidate, edge, aim.plan)) candidate else stationInFront(edge, aim.plan, target)
                    } else {
                        aim = aimAt(edge, rng)
                        station = stationInFront(edge, aim.plan, dist)
                    }
                }
                else -> {
                    // "Aim at a bare part of the wall": re-aim; when that fails again, stand square in front of it.
                    aim = aimAt(edge, rng)
                    val repeated = failures.count { it == WallFitFailure.NO_DOMINANT_PLANE || it == WallFitFailure.TOO_FEW_POINTS } >= 2
                    station = if (!repeated && usable(station, edge, aim.plan)) {
                        station
                    } else {
                        stationInFront(edge, aim.plan, min(dist, preferredDistance))
                    }
                }
            }
        }
        return WallCaptureLog(
            edge, null, maxAttempts, failures, station.distanceTo(aim.plan), firstDistance, relocated, -2, lastHits,
            firstIncidenceDeg = firstIncidence,
        )
    }

    /** Angle (degrees) between the line of sight [station] -> [aim] and the normal of wall [edge]: 0 = square to it. */
    private fun incidenceDeg(edge: Int, station: Vec2, aim: Vec2): Double {
        val toStation = (station - aim).normalized()
        return Math.toDegrees(Math.acos((toStation dot room.inwardNormal(edge)).coerceIn(-1.0, 1.0)))
    }

    /** The true wall [o] lies on (see [WallCaptureLog.matchedEdge]). */
    fun matchEdge(o: WallObservation): Int = matchEdge(room, distortion, o)

    /** Wall [edge] as the (possibly scale-distorted) AR frame sees it. */
    private fun face(edge: Int): SimFace =
        SimFace(
            "wall ${edge + 1}",
            distortion.apply(Vec3.fromPlan(room.start(edge), 0.0)).toPlan(),
            distortion.apply(Vec3.fromPlan(room.end(edge), 0.0)).toPlan(),
        )

    companion object {
        /**
         * The true wall [o] lies on: the edge within 15° of its direction (same facing) whose face its observed
         * middle is closest to (≤ 0.15 m); -1 when none. Strict: a chord through a corner, the next face of a
         * closet return or a column face all count as wrong.
         */
        fun matchEdge(room: SimRoom, distortion: ArDistortion, o: WallObservation): Int {
            val mid = Vec2.lerp(o.extentStart, o.extentEnd, 0.5)
            var best = -1
            var bestScore = Double.POSITIVE_INFINITY
            for (e in 0 until room.size) {
                val a = distortion.apply(Vec3.fromPlan(room.start(e), 0.0)).toPlan()
                val b = distortion.apply(Vec3.fromPlan(room.end(e), 0.0)).toPlan()
                val truth = Line2.through(a, b)
                if ((o.line.direction dot truth.direction) <= cos(Math.toRadians(15.0))) continue
                val off = truth.distance(mid)
                if (off >= 0.15) continue
                // Collinear walls (a chimney breast's alcoves) share one line: judge by the segment too.
                val t = truth.param(mid)
                val outside = max(0.0, -t) + max(0.0, t - a.distanceTo(b))
                if (off + outside < bestScore) {
                    bestScore = off + outside
                    best = e
                }
            }
            return best
        }

        /** Signed offset (m) of [o]'s line from the true face of [edge] at that face's middle; + = into the room. */
        fun faceOffset(room: SimRoom, edge: Int, o: WallObservation): Double {
            val mid = room.pointOn(edge, 0.5)
            val p = o.line.project(mid)
            return (p - mid) dot room.inwardNormal(edge)
        }

        /** Angle (deg) between [o]'s line and the true face of [edge]. */
        fun angleError(room: SimRoom, edge: Int, o: WallObservation): Double =
            Math.toDegrees(Math.asin(abs(o.line.direction cross room.direction(edge)).coerceAtMost(1.0)))
    }
}

/** Deterministic seed mixing (SplitMix64 finalizer over the key parts). */
internal fun seedOf(vararg parts: Long): Long {
    var h = 0x9E3779B97F4A7C15uL.toLong()
    for (p in parts) {
        var z = h xor (p * -0x61c8864680b583ebL)
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        h = z xor (z ushr 31)
    }
    return h
}

/** One simulated room scan: the walls in the order they were fed to the solver, and the solved outline. */
internal class RoomScan(
    val truth: SimRoom,
    /** Trial label: seed set / station, e.g. "s3/pole". */
    val stationLabel: String,
    val captures: List<WallCaptureLog>,
    val result: SolveResult?,
) {
    val observations: List<WallObservation> get() = captures.mapNotNull { it.observation }

    /** Captures whose accepted fit is a different surface than the wall that was aimed at. */
    val wrongWall: Int get() = captures.count { it.observation != null && !it.onTarget }

    /** Walls the user gave up on (every attempt failed). */
    val missingWalls: Int get() = captures.count { it.observation == null }

    /** Every capture produced an observation of the wall that was aimed at. */
    val allOnTarget: Boolean get() = captures.all { it.onTarget }
    val error: OutlineError? by lazy { result?.let { OutlineError.of(truth.corners, it.corners) } }
}

/**
 * Captures [sequence] (edge indices in capture order; repeats = the same wall captured again) and solves.
 * Each capture's RNG is keyed by ([family], room, station, edge, repeat#) — not by its position in the
 * sequence — so two scenarios of the same family see identical observations, only ordered differently.
 */
internal fun scanRoom(
    user: SimUser,
    roomIndex: Int,
    stationIndex: Int,
    stationLabel: String,
    home: Vec2?,
    sequence: List<Int>,
    family: Long,
    nearDistance: Double? = null,
    options: SolverOptions = SolverOptions(),
): RoomScan {
    val seen = HashMap<Int, Int>()
    val captures = sequence.map { edge ->
        val repeat = seen.merge(edge, 1, Int::plus)!! - 1
        val rng = Random(seedOf(family, roomIndex.toLong(), stationIndex.toLong(), edge.toLong(), repeat.toLong()))
        user.captureWall(edge, home, rng, nearDistance)
    }
    val observations = captures.mapNotNull { it.observation }
    return RoomScan(user.room, stationLabel, captures, RoomSolver.solveWalls(observations, options))
}
