package com.oroplex.floorplan.core.sim

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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Depth-from-motion-like noise, applied along each hit ray: σ = [sigma0] + [sigmaPerMeter] · distance. */
internal data class DepthNoise(
    val sigma0: Double = 0.005,
    val sigmaPerMeter: Double = 0.012,
    /** Fraction of samples replaced by a gross outlier at depth × U([outlierMinFactor], [outlierMaxFactor]). */
    val outlierRate: Double = 0.10,
    val outlierMinFactor: Double = 0.4,
    val outlierMaxFactor: Double = 1.6,
) {
    fun sigma(distance: Double) = sigma0 + sigmaPerMeter * distance

    companion object {
        val NONE = DepthNoise(sigma0 = 0.0, sigmaPerMeter = 0.0, outlierRate = 0.0)
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
 * [RayHit.FLOOR] / [RayHit.CEILING] / [RayHit.FURNITURE]).
 */
internal class SimCapture(val fit: WallFit, val points: List<Vec3>, val viewer: Vec3, val surfaceHits: Map<Int, Int>) {
    val rays: Int get() = surfaceHits.values.sum()

    fun fraction(surface: Int): Double = if (rays == 0) 0.0 else (surfaceHits[surface] ?: 0).toDouble() / rays
}

internal object ScanSimulator {
    /** Simulates one "Wall" capture from [station] aimed at [aim] and runs [WallEstimator.fromPoints]. */
    fun capture(
        room: SimRoom,
        station: Vec3,
        aim: Vec3,
        rng: Random,
        noise: DepthNoise = DepthNoise(),
        rig: CaptureRig = CaptureRig(),
        distortion: ArDistortion = ArDistortion(),
        config: WallEstimatorConfig = WallEstimatorConfig(),
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
        for (frame in 0 until rig.frames) {
            val cam = station + Vec3(
                rng.nextGaussian() * rig.handPositionSigma,
                rng.nextGaussian() * rig.handPositionSigma,
                rng.nextGaussian() * rig.handPositionSigma,
            )
            val aimDir = (aim - cam).normalized()
            val (r0, u0) = basis(aimDir)
            val forward = (aimDir + r0 * tanShake() + u0 * tanShake()).normalized()
            val (right, up) = basis(forward)
            for (row in 0 until g) {
                for (col in 0 until g) {
                    val a = if (half > 0) tanHalf * (col - half) / half else 0.0
                    val b = if (half > 0) tanHalf * (half - row) / half else 0.0
                    val d = (forward + right * a + up * b).normalized()
                    val hit = room.hit(cam, d) ?: continue
                    surfaces.merge(hit.surface, 1, Int::plus)
                    val t = hit.t
                    val measured = if (rng.nextDouble() < noise.outlierRate) {
                        t * (noise.outlierMinFactor + (noise.outlierMaxFactor - noise.outlierMinFactor) * rng.nextDouble())
                    } else {
                        t + rng.nextGaussian() * noise.sigma(t)
                    }
                    if (measured <= 0.05) continue
                    points += distortion.apply(cam + d * measured)
                }
            }
            val c = distortion.apply(cam)
            vx += c.x
            vy += c.y
            vz += c.z
        }
        val viewer = Vec3(vx / rig.frames, vy / rig.frames, vz / rig.frames)
        val fit = WallEstimator.fromPoints(points, SIM_FLOOR_Y, SIM_CEILING_Y, viewer, config)
        return SimCapture(fit, points, viewer, surfaces)
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
) {
    val rays: Int get() = surfaceHits.values.sum()

    /** The accepted observation is the wall that was aimed at. */
    val onTarget: Boolean get() = observation != null && matchedEdge == edge

    fun fraction(surface: Int): Double = if (rays == 0) 0.0 else (surfaceHits[surface] ?: 0).toDouble() / rays
}

/**
 * A simulated user capturing walls the way the capture screen instructs: aim at the wall (random point
 * on the middle 60 % of the edge, 1.0–1.8 m high), hold steady, tap Capture. On TOO_NOISY / TOO_FAR the
 * user moves closer (to 60 % of the distance); on NO_DOMINANT_PLANE / TOO_FEW_POINTS they re-aim.
 */
internal class SimUser(
    val room: SimRoom,
    val noise: DepthNoise = DepthNoise(),
    val rig: CaptureRig = CaptureRig(),
    val distortion: ArDistortion = ArDistortion(),
    val config: WallEstimatorConfig = WallEstimatorConfig(),
    /** When set, overrides [config] per attempt from the camera-to-aim distance (what-if experiments). */
    val configForDistance: ((Double) -> WallEstimatorConfig)? = null,
    val maxAttempts: Int = 4,
    /** Height range (m) of the point on the wall the user aims at. */
    val aimHeight: ClosedFloatingPointRange<Double> = 1.0..1.8,
    /** The user walks closer before capturing when the aim point is farther than this (app hint: ~4 m). */
    val comfortRange: Double = 4.0,
    /** Where a user who has to walk stands: this far in front of the wall (clipped to half the room). */
    val preferredDistance: Double = 1.8,
    val minDistance: Double = 0.5,
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
                config = configForDistance?.invoke(station.distanceTo(aim.plan)) ?: config,
            )
            lastHits = cap.surfaceHits
            val obs = cap.fit.observation
            if (obs != null && cap.fit.failure == null) {
                return WallCaptureLog(
                    edge, obs, attempt, failures, station.distanceTo(aim.plan), firstDistance, relocated,
                    matchEdge(obs), cap.surfaceHits,
                )
            }
            val reason = cap.fit.failure ?: WallFitFailure.NO_DOMINANT_PLANE
            failures += reason
            val dist = station.distanceTo(aim.plan)
            when (reason) {
                WallFitFailure.TOO_NOISY, WallFitFailure.TOO_FAR -> {
                    val target = max(dist * 0.6, min(standOff(edge), 0.5 * room.depthInFront(edge, aim.plan)))
                    if (target < dist - 0.05) {
                        val dir = (station - aim.plan).normalized()
                        val candidate = aim.plan + dir * target
                        station = if (usable(candidate, edge, aim.plan)) candidate else stationInFront(edge, aim.plan, target)
                    } else {
                        aim = aimAt(edge, rng)
                        station = stationInFront(edge, aim.plan, dist)
                    }
                }
                WallFitFailure.NO_DOMINANT_PLANE, WallFitFailure.TOO_FEW_POINTS -> {
                    aim = aimAt(edge, rng)
                    station = if (usable(station, edge, aim.plan)) station else stationInFront(edge, aim.plan, min(dist, preferredDistance))
                }
            }
        }
        return WallCaptureLog(
            edge, null, maxAttempts, failures, station.distanceTo(aim.plan), firstDistance, relocated, -2, lastHits,
        )
    }

    /** The true wall [o] lies on (see [WallCaptureLog.matchedEdge]). */
    fun matchEdge(o: WallObservation): Int {
        val mid = Vec2.lerp(o.extentStart, o.extentEnd, 0.5)
        var best = -1
        var bestOffset = 0.15
        for (e in 0 until room.size) {
            val a = distortion.apply(Vec3.fromPlan(room.start(e), 0.0)).toPlan()
            val b = distortion.apply(Vec3.fromPlan(room.end(e), 0.0)).toPlan()
            val truth = Line2.through(a, b)
            if ((o.line.direction dot truth.direction) <= cos(Math.toRadians(15.0))) continue
            val off = truth.distance(mid)
            if (off < bestOffset) {
                bestOffset = off
                best = e
            }
        }
        return best
    }
}

/**
 * What-if estimator settings scaled with the expected depth noise σ(d) = 5 mm + 1.2 % · d at the capture
 * distance: inlier band max(2.5 cm, 2σ), noise gate max(3 cm, 1.5σ). Not a production default.
 */
internal fun rangeAdaptiveConfig(distance: Double, noise: DepthNoise = DepthNoise()): WallEstimatorConfig {
    val sigma = noise.sigma(distance)
    return WallEstimatorConfig(inlierThreshold = max(0.025, 2.0 * sigma), maxRms = max(0.03, 1.5 * sigma))
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
