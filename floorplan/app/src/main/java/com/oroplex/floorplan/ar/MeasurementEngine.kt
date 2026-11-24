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

    /**
     * Writes [COUNT] (x, y) view-pixel pairs into [out] (length >= 2 * COUNT), row by row from the top-left.
     * The middle sample (index COUNT / 2) is exactly the view center.
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
            MeasureRequest.LockFloor -> LockFloorSampler
            is MeasureRequest.Wall -> WallSampler(request)
            is MeasureRequest.FloorPoint -> FloorPointSampler(request)
            is MeasureRequest.SurfacePoint -> SurfacePointSampler(request)
            is MeasureRequest.CeilingHeight -> CeilingSampler(request)
        }

        fun frameTarget(requested: Int) = requested.coerceIn(1, MAX_FRAMES)
    }
}

/** Upward plane under the crosshair, else the tracked best floor, else a failure. One frame. */
private object LockFloorSampler : Sampler() {
    override val timeoutMessage =
        "Couldn't lock the floor because tracking isn't ready. Move the phone slowly and try again."

    override fun onTrackingFrame(input: FrameInputs): MeasureResult {
        val plane = input.reticle?.plane
        if (plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
            val y = plane.centerPose.ty().toDouble()
            if (y.isFinite()) return MeasureResult.FloorLocked(y)
        }
        input.floor?.let { return MeasureResult.FloorLocked(it.y) }
        return MeasureResult.Failed(
            "No floor found yet. Point the phone at the floor and move it slowly until the floor is detected.",
        )
    }
}

/**
 * Accumulates wall points from the 9 × 9 grid over N frames, then fits them with
 * [WallEstimator.fromPoints]. The depth fit wins whenever it succeeds; otherwise a VERTICAL plane seen
 * under the crosshair during sampling is used ([WallEstimator.fromPlane]); otherwise the failed depth fit
 * is returned so the UI can show its [WallFit.failure] message.
 */
private class WallSampler(private val request: MeasureRequest.Wall) : Sampler() {
    override val timeoutMessage = "Wall measurement timed out. Hold the phone steady while it samples."

    private val targetFrames = frameTarget(request.frames)
    private val points = ArrayList<Vec3>(targetFrames * WallSampleGrid.COUNT)
    private val grid = FloatArray(WallSampleGrid.COUNT * 2)
    private var frames = 0
    private var viewerX = 0.0
    private var viewerY = 0.0
    private var viewerZ = 0.0
    private var centerPlane: Plane? = null

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        if (input.viewportWidth <= 0 || input.viewportHeight <= 0) return null
        WallSampleGrid.positions(input.viewportWidth, input.viewportHeight, grid)
        for (i in 0 until WallSampleGrid.COUNT) {
            val hits = input.frame.hitTest(grid[2 * i], grid[2 * i + 1])
            HitTesting.firstWallSample(hits, camera)?.let { points += it }
        }
        HitTesting.firstVerticalPlane(input.centerHits, camera)?.let { centerPlane = it }
        viewerX += camera.x
        viewerY += camera.y
        viewerZ += camera.z
        frames++
        return if (frames >= targetFrames) finish() else null
    }

    private fun finish(): MeasureResult {
        // The phone moves a little while sampling; the mean pose is the most representative viewer.
        val viewer = Vec3(viewerX / frames, viewerY / frames, viewerZ / frames)
        val depthFit = WallEstimator.fromPoints(points, request.floorY, request.ceilingY, viewer)
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
 * Center-ray hits on downward planes or depth / feature points more than [MIN_HEIGHT] above the floor.
 * Height = median(y) − floorY, spread = MAD of the heights.
 */
private class CeilingSampler(private val request: MeasureRequest.CeilingHeight) : Sampler() {
    override val timeoutMessage =
        "Couldn't measure the ceiling. Aim straight up at a plain part of the ceiling and hold steady."

    private val target = frameTarget(request.frames)
    private val heights = ArrayList<Double>(target)

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        val y = HitTesting.firstCeilingSampleY(
            input.centerHits,
            camera,
            minY = request.floorY + MIN_HEIGHT,
            maxY = request.floorY + MAX_HEIGHT,
        ) ?: return null
        heights += y
        if (heights.size < target) return null
        val median = RobustStats.median(heights)
        return MeasureResult.Height(median - request.floorY, heights.size, RobustStats.mad(heights))
    }

    private companion object {
        /** Ceilings are at least this high above the floor (rejects tabletops and shelves), meters. */
        const val MIN_HEIGHT = 1.5

        /** Above this (m) a hit is a glitch rather than a residential ceiling. */
        const val MAX_HEIGHT = 7.0
    }
}
