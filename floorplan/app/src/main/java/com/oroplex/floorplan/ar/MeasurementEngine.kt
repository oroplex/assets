package com.oroplex.floorplan.ar

import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.oroplex.floorplan.core.fit.RobustStats
import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.geometry.Vec3
import kotlinx.coroutines.CompletableDeferred
import java.util.Queue
import kotlin.math.min

/**
 * One [ArCaptureController.measure] call in flight. Created on the caller's thread, sampled on the GL
 * thread. The only cross-thread interaction is completing [result] (thread-safe): whoever completes it
 * first (GL thread with a result, the lifecycle on pause, or the caller on cancellation) wins, and the GL
 * thread drops jobs whose result is already completed.
 */
internal class PendingMeasurement(val request: MeasureRequest, val createdAtNs: Long) {
    val result = CompletableDeferred<MeasureResult>()
    val isDone: Boolean get() = result.isCompleted
    fun complete(value: MeasureResult) {
        result.complete(value)
    }
}

/** Why a measurement was stopped before it collected enough samples. */
internal enum class MeasurementExpiry { TIMED_OUT, TRACKING_LOST }

/**
 * Timeout policy of one measurement (pure; times are monotonic nanoseconds).
 * Fails when more than [timeoutNs] passed since [startNs], or when tracking has been lost for more than
 * [maxLostNs] without interruption (a brief tracking hiccup is tolerated; the sampler just skips it).
 */
internal class MeasurementDeadline(
    private val startNs: Long,
    private val timeoutNs: Long = DEFAULT_TIMEOUT_NS,
    private val maxLostNs: Long = DEFAULT_MAX_LOST_NS,
) {
    private var lostSinceNs = NOT_LOST

    fun check(nowNs: Long, tracking: Boolean): MeasurementExpiry? {
        if (nowNs - startNs > timeoutNs) return MeasurementExpiry.TIMED_OUT
        if (tracking) {
            lostSinceNs = NOT_LOST
            return null
        }
        if (lostSinceNs == NOT_LOST) lostSinceNs = nowNs
        return if (nowNs - lostSinceNs > maxLostNs) MeasurementExpiry.TRACKING_LOST else null
    }

    companion object {
        const val DEFAULT_TIMEOUT_NS = 4_000_000_000L
        const val DEFAULT_MAX_LOST_NS = 1_500_000_000L
        private const val NOT_LOST = Long.MIN_VALUE
    }
}

/** The 9 × 9 hit-test grid used to sample a wall: a centered square, ~25 % of the shorter view side. */
internal object WallSampleGrid {
    const val SIZE = 9
    const val COUNT = SIZE * SIZE
    const val SIDE_FRACTION = 0.25f

    /** Index of the middle sample, which is exactly the view center (the crosshair). */
    const val CENTER_INDEX = COUNT / 2

    /**
     * Writes [COUNT] (x, y) view-pixel pairs into [out] (length >= 2 * COUNT), row by row from the top-left.
     * The middle sample ([CENTER_INDEX]) is exactly the view center.
     */
    fun positions(viewportWidth: Int, viewportHeight: Int, out: FloatArray) {
        require(out.size >= 2 * COUNT) { "out needs ${2 * COUNT} floats" }
        val side = min(viewportWidth, viewportHeight) * SIDE_FRACTION
        val step = side / (SIZE - 1)
        val x0 = viewportWidth / 2f - side / 2f
        val y0 = viewportHeight / 2f - side / 2f
        var k = 0
        for (row in 0 until SIZE) {
            for (col in 0 until SIZE) {
                out[k++] = x0 + col * step
                out[k++] = y0 + row * step
            }
        }
    }
}

/** Sample statistics for point measurements. */
internal object MeasurementMath {
    /** Arithmetic mean; [Vec3.ZERO] for an empty list. */
    fun mean(points: List<Vec3>): Vec3 {
        if (points.isEmpty()) return Vec3.ZERO
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (p in points) {
            x += p.x
            y += p.y
            z += p.z
        }
        val n = points.size.toDouble()
        return Vec3(x / n, y / n, z / n)
    }

    /** Component-wise median: robust to the occasional depth outlier in every axis independently. */
    fun componentMedian(points: List<Vec3>): Vec3 = Vec3(
        RobustStats.median(points.map { it.x }),
        RobustStats.median(points.map { it.y }),
        RobustStats.median(points.map { it.z }),
    )

    /** Median distance of [points] from [center] (the `spread` quality indicator). */
    fun medianDistance(points: List<Vec3>, center: Vec3): Double =
        RobustStats.median(points.map { it.distanceTo(center) })
}

/**
 * Everything a sampler may use for one NEW camera frame (GL thread only; valid for this frame only).
 * [centerHits] is the screen-center hit test, shared with the reticle so it runs once per frame.
 */
internal class FrameInputs(
    val frame: Frame,
    val nowNs: Long,
    val tracking: Boolean,
    val cameraPosition: Vec3?,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val invViewProjection: FloatArray?,
    val centerHits: List<HitResult>,
    val reticle: ReticleResolution?,
    val floor: FloorEstimate?,
)

/**
 * Runs all in-flight measurements on the GL thread. Measurements are independent and sampled in
 * parallel from the same frames, so a slow request never delays another one.
 */
internal class MeasurementEngine(private val incoming: Queue<PendingMeasurement>) {
    private class Active(val job: PendingMeasurement, val sampler: Sampler, val deadline: MeasurementDeadline)

    private val active = ArrayList<Active>(4)

    /** A new camera frame: admit queued requests, enforce deadlines, sample tracking frames. */
    fun onFrame(input: FrameInputs) {
        while (true) {
            val job = incoming.poll() ?: break
            if (job.isDone) continue
            active += Active(job, Sampler.forRequest(job.request), MeasurementDeadline(job.createdAtNs))
        }
        if (active.isEmpty()) return
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val a = iterator.next()
            if (a.job.isDone) {
                iterator.remove()
                continue
            }
            val expiry = a.deadline.check(input.nowNs, input.tracking)
            val result = when {
                expiry != null -> expiryResult(a.sampler, expiry)
                !input.tracking -> null
                else -> sample(a.sampler, input)
            }
            if (result != null) {
                a.job.complete(result)
                iterator.remove()
            }
        }
    }

    /**
     * No new camera frame (duplicate frame, or Session.update failed): only enforce deadlines, with
     * [tracking] the best knowledge of the tracking state.
     */
    fun checkDeadlines(nowNs: Long, tracking: Boolean) {
        if (active.isEmpty()) return
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val a = iterator.next()
            if (a.job.isDone) {
                iterator.remove()
                continue
            }
            val expiry = a.deadline.check(nowNs, tracking) ?: continue
            a.job.complete(expiryResult(a.sampler, expiry))
            iterator.remove()
        }
    }

    private fun sample(sampler: Sampler, input: FrameInputs): MeasureResult? = try {
        sampler.onTrackingFrame(input)
    } catch (e: Exception) {
        // ARCore can throw on a frame it just invalidated; never take the GL thread down for it.
        Log.w(TAG, "Measurement sampling failed", e)
        MeasureResult.Failed("Measurement failed. Hold the phone steady and try again.")
    }

    private fun expiryResult(sampler: Sampler, expiry: MeasurementExpiry): MeasureResult = when (expiry) {
        MeasurementExpiry.TIMED_OUT -> MeasureResult.Failed(sampler.timeoutMessage)
        MeasurementExpiry.TRACKING_LOST -> MeasureResult.Failed(TRACKING_LOST_MESSAGE)
    }

    companion object {
        private const val TAG = "MeasurementEngine"
        const val TRACKING_LOST_MESSAGE =
            "Tracking was lost during the measurement. Move the phone slowly over a textured area and try again."
    }
}

/**
 * Collects samples for one request. [onTrackingFrame] is only called for new frames while the camera
 * is tracking; it returns the final result once enough samples were gathered, else null.
 */
private sealed class Sampler {
    abstract val timeoutMessage: String
    abstract fun onTrackingFrame(input: FrameInputs): MeasureResult?

    companion object {
        /** Upper bound on requested frame counts (the 4 s timeout would end longer requests anyway). */
        const val MAX_FRAMES = 90

        fun forRequest(request: MeasureRequest): Sampler = when (request) {
            MeasureRequest.LockFloor -> LockFloorSampler()
            is MeasureRequest.Wall -> WallSampler(request)
            is MeasureRequest.FloorPoint -> FloorPointSampler(request)
            is MeasureRequest.SurfacePoint -> SurfacePointSampler(request)
            is MeasureRequest.CeilingHeight -> CeilingSampler(request)
        }

        fun frameTarget(requested: Int) = requested.coerceIn(1, MAX_FRAMES)
    }
}

/**
 * Locks the floor height, in order of preference, on every tracking frame:
 * 1. an upward plane under the crosshair (its height; CaptureViewModel rejects raised surfaces);
 * 2. the tracked floor ([FloorTracker], the lowest large upward plane), unless it is only a little below
 *    the phone and the crosshair sees floor clearly below it ([FloorLockFallback.contradictsTrackedFloor]);
 * 3. fallback when ARCore has detected no floor plane at all (glossy tile, polished concrete): the
 *    median height of DepthPoint / feature hits at the crosshair over [FloorLockFallback.TARGET_SAMPLES]
 *    frames, taken only while the phone points down ≥ 30°, and only if the readings agree
 *    ([FloorLockFallback.MAX_MAD]). Gives up with a specific hint when no usable hit shows up.
 */
private class LockFloorSampler : Sampler() {
    override val timeoutMessage =
        "Couldn't lock the floor because tracking isn't ready. Move the phone slowly and try again."

    private val samples = ArrayList<Double>(FloorLockFallback.TARGET_SAMPLES)
    private var frames = 0
    private var framesNotPointingDown = 0

    /** Set once the crosshair showed floor clearly below the tracked (ambiguous) floor during this request. */
    private var trackedFloorRejected = false

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val plane = input.reticle?.plane
        if (plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
            val y = plane.centerPose.ty().toDouble()
            if (y.isFinite()) return MeasureResult.FloorLocked(y)
        }
        val camera = input.cameraPosition ?: return null

        val inv = input.invViewProjection
        val rayY = inv?.let { HitTesting.centerRayDirection(it, input.viewportWidth, input.viewportHeight)?.y }
        val pointingDown = rayY == null || rayY <= -FloorLockFallback.MIN_RAY_DOWN
        // Aiming at a wall or the horizon: whatever the ray hits is not the floor.
        val sample = if (pointingDown) HitTesting.floorFallbackSample(input.centerHits, camera) else null
        val hitY = sample?.world?.y

        val tracked = input.floor
        if (tracked != null && tracked.y.isFinite() && !trackedFloorRejected) {
            if (hitY == null || !FloorLockFallback.contradictsTrackedFloor(tracked.y, camera.y, hitY)) {
                return MeasureResult.FloorLocked(tracked.y)
            }
            // The user aims at floor well below a plane that may be a bed or table: measure it instead.
            trackedFloorRejected = true
        }

        frames++
        if (hitY != null) {
            samples += hitY
        } else if (!pointingDown || sample?.rejection == FloorLockFallback.Rejection.NOT_POINTING_DOWN) {
            framesNotPointingDown++
        }

        if (samples.size >= FloorLockFallback.TARGET_SAMPLES) {
            val evaluation = FloorLockFallback.evaluate(samples)
            return evaluation.floorY?.let { MeasureResult.FloorLocked(it) } ?: MeasureResult.Failed(MSG_UNSTEADY)
        }
        val gaveUp = (samples.isEmpty() && frames >= FloorLockFallback.FRAMES_WITHOUT_SAMPLE_LIMIT) ||
            frames >= FloorLockFallback.MAX_FRAMES
        if (!gaveUp) return null
        return MeasureResult.Failed(
            when {
                samples.isNotEmpty() -> MSG_UNSTEADY
                framesNotPointingDown * 2 > frames -> MSG_TILT_DOWN
                else -> MSG_NO_FLOOR
            },
        )
    }

    private companion object {
        const val MSG_NO_FLOOR =
            "No floor found yet. Aim at a textured patch of floor near your feet, move the phone slowly, " +
                "then tap Lock floor again."
        const val MSG_TILT_DOWN = "Tilt the phone down toward the floor near your feet, then tap Lock floor."
        const val MSG_UNSTEADY =
            "The floor reading wasn't steady. Hold the phone still, aimed at the floor, and try again."
    }
}

/**
 * Accumulates wall points from the 9 × 9 grid over N frames, then fits them with
 * [WallEstimator.fromPoints]. The crosshair hit of every frame is also kept: their median is passed as
 * the `aimPoint`, so the fit locks onto the surface the user aimed at (not furniture in front of it, a
 * wall behind a doorway, or a side wall seen at a grazing angle). Far walls (crosshair beyond
 * [WallSampling.LONG_RANGE_DISTANCE]) are sampled over more frames to average depth noise.
 *
 * The depth fit wins whenever it succeeds; otherwise a VERTICAL plane seen under the crosshair during
 * sampling is used ([WallEstimator.fromPlane]); otherwise the failed depth fit is returned so the UI can
 * show its [WallFit.failure] message.
 */
private class WallSampler(private val request: MeasureRequest.Wall) : Sampler() {
    override val timeoutMessage = "Wall measurement timed out. Hold the phone steady while it samples."

    private val baseFrames = frameTarget(request.frames)
    private val maxFrames = frameTarget(WallSampling.targetFrames(baseFrames, Double.POSITIVE_INFINITY))
    private val points = ArrayList<Vec3>(maxFrames * WallSampleGrid.COUNT)
    private val centerPoints = ArrayList<Vec3>(maxFrames)
    private val aimDistances = ArrayList<Double>(maxFrames)
    private val grid = FloatArray(WallSampleGrid.COUNT * 2)
    private var frames = 0
    private var firstFrameNs = 0L
    private var viewerX = 0.0
    private var viewerY = 0.0
    private var viewerZ = 0.0
    private var centerPlane: Plane? = null

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        if (input.viewportWidth <= 0 || input.viewportHeight <= 0) return null
        if (frames == 0) firstFrameNs = input.nowNs
        WallSampleGrid.positions(input.viewportWidth, input.viewportHeight, grid)
        for (i in 0 until WallSampleGrid.COUNT) {
            // The grid's middle sample is the crosshair, which the frame already hit-tested.
            if (i == WallSampleGrid.CENTER_INDEX) continue
            val hits = input.frame.hitTest(grid[2 * i], grid[2 * i + 1])
            HitTesting.firstWallSample(hits, camera)?.let { points += it }
        }
        val center = HitTesting.firstWallSample(input.centerHits, camera)
        if (center != null) {
            points += center
            centerPoints += center
            aimDistances += center.distanceTo(camera)
        } else {
            input.reticle?.hit?.distance?.takeIf { it.isFinite() }?.let { aimDistances += it }
        }
        HitTesting.firstVerticalPlane(input.centerHits, camera)?.let { centerPlane = it }
        viewerX += camera.x
        viewerY += camera.y
        viewerZ += camera.z
        frames++
        val aimDistance = if (aimDistances.isEmpty()) null else RobustStats.median(aimDistances)
        val done = frames >= maxFrames ||
            WallSampling.isComplete(frames, baseFrames, aimDistance, input.nowNs - firstFrameNs)
        return if (done) finish() else null
    }

    private fun finish(): MeasureResult {
        // The phone moves a little while sampling; the mean pose is the most representative viewer.
        val viewer = Vec3(viewerX / frames, viewerY / frames, viewerZ / frames)
        val band = WallSampling.wallBand(request.floorY, request.ceilingY)
        val aimPoint = WallSampling.aimPoint(centerPoints, frames, band)
        val depthFit = WallEstimator.fromPoints(points, request.floorY, request.ceilingY, viewer, aimPoint = aimPoint)
        if (depthFit.failure == null && depthFit.observation != null) return MeasureResult.WallMeasured(depthFit)
        val planeObservation = centerPlane?.let { HitTesting.wallFromPlane(it, viewer) }
            ?: return MeasureResult.WallMeasured(depthFit)
        return MeasureResult.WallMeasured(
            WallFit(
                observation = planeObservation,
                failure = null,
                usedPoints = planeObservation.pointCount,
                totalPoints = points.size,
            ),
        )
    }
}

/** Screen-center ray ∩ (Y = floorY) per frame; mean over N frames, spread = median distance to it. */
private class FloorPointSampler(private val request: MeasureRequest.FloorPoint) : Sampler() {
    override val timeoutMessage = "Couldn't find the floor at the crosshair. Aim lower, at the floor, and hold steady."

    private val target = frameTarget(request.frames)
    private val samples = ArrayList<Vec3>(target)

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        val inv = input.invViewProjection ?: return null
        val p = HitTesting.floorRayHit(inv, input.viewportWidth, input.viewportHeight, request.floorY, camera)
            ?: return null
        samples += p
        if (samples.size < target) return null
        val mean = MeasurementMath.mean(samples)
        return MeasureResult.Point(mean, samples.size, MeasurementMath.medianDistance(samples, mean))
    }
}

/**
 * Real hit-test hits under the crosshair (depth, plane or feature point; the floor-ray fallback is
 * excluded so aiming at glass or a blank wall fails instead of silently landing on the floor far away).
 * Component-wise median over N frames.
 */
private class SurfacePointSampler(request: MeasureRequest.SurfacePoint) : Sampler() {
    override val timeoutMessage =
        "Couldn't find a surface at the crosshair. Aim at a textured spot nearby and hold steady."

    private val target = frameTarget(request.frames)
    private val samples = ArrayList<Vec3>(target)

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val reticle = input.reticle?.takeIf { it.fromHitTest } ?: return null
        samples += reticle.hit.world
        if (samples.size < target) return null
        val median = MeasurementMath.componentMedian(samples)
        return MeasureResult.Point(median, samples.size, MeasurementMath.medianDistance(samples, median))
    }
}

/**
 * Ceiling height from the center ray over N frames (AR layer AR-2). Per frame the highest ceiling-like
 * hit is taken ([HitTesting.ceilingSampleY]: downward planes, or depth / feature hits on roughly
 * horizontal surfaces reached by a steep upward ray, more than 1.8 m above the floor). The result is the
 * median of the UPPER cluster of those heights ([CeilingSampling.upperCluster]) minus floorY, so frames
 * that grazed a cabinet top or a lamp do not pull the height down; spread = MAD of that cluster.
 */
private class CeilingSampler(private val request: MeasureRequest.CeilingHeight) : Sampler() {
    override val timeoutMessage =
        "Couldn't measure the ceiling. Aim straight up at a plain part of the ceiling and hold steady."

    private val target = frameTarget(request.frames)
    private val heights = ArrayList<Double>(target)

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        val y = HitTesting.ceilingSampleY(input.centerHits, camera, request.floorY) ?: return null
        heights += y
        if (heights.size < target) return null
        val cluster = CeilingSampling.upperCluster(heights)
        if (cluster.isEmpty()) return MeasureResult.Failed(MSG_INCONSISTENT)
        val median = RobustStats.median(cluster)
        return MeasureResult.Height(median - request.floorY, cluster.size, RobustStats.mad(cluster))
    }

    private companion object {
        const val MSG_INCONSISTENT =
            "The ceiling readings didn't agree. Aim straight up at a plain spot, away from lights and " +
                "cabinets, and hold steady."
    }
}
