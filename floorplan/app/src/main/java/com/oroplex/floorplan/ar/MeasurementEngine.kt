package com.oroplex.floorplan.ar

import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.oroplex.floorplan.core.fit.RobustStats
import com.oroplex.floorplan.core.fit.WallCaptureRecord
import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.diagnostics.WallCaptureLog
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

/**
 * The hit-test grid used to sample a wall: a centered square that spans ± [HALF_ANGLE_DEG] of the camera's
 * view left-right and up-down, normally 9 × 9 points. On a phone where that many hit tests per frame do not
 * fit the frame budget, the same square is sampled with fewer points ([SIZES], chosen by [WallGridGovernor]).
 *
 * The square is sized by ANGLE, not as a share of the screen: the wall estimator's direction precision and
 * its "stations" (one per grid column) depend on how much of the wall is seen, and they were designed and
 * simulated for ± 12°. A fixed 25 % of the short side of a tall phone screen is only about ± 4.5° (a 16 cm
 * wide patch from 1 m), where walls close by are refused and the wall's direction is three times less
 * certain.
 */
internal object WallSampleGrid {
    /** Points per side of the full grid. */
    const val SIZE = 9
    const val COUNT = SIZE * SIZE

    /** Half-angle of the sampled square in the image plane (the same as the simulation's capture rig). */
    const val HALF_ANGLE_DEG = 12.0

    /**
     * Side of the square as a share of the shorter view side when the camera's projection is not known:
     * about ± 11° on a tall phone screen showing a camera with a 65–72° long-side field of view.
     */
    const val FALLBACK_SIDE_FRACTION = 0.6f

    /** The square never takes more than this share of either view side, so every ray stays inside the view. */
    const val MAX_SIDE_FRACTION = 0.9f

    /** Index of the middle sample of the full grid, which is exactly the view center (the crosshair). */
    const val CENTER_INDEX = COUNT / 2

    /** Allowed points per side, densest first. All odd, so the middle sample is always the crosshair. */
    val SIZES = intArrayOf(SIZE, 7, 5)

    private val TAN_HALF_ANGLE = kotlin.math.tan(Math.toRadians(HALF_ANGLE_DEG)).toFloat()

    /** Index of the middle sample (the crosshair) of a [size] × [size] grid. */
    fun centerIndex(size: Int): Int = size * size / 2

    /**
     * The camera's focal length in view pixels along one axis, from the matching diagonal element of its
     * OpenGL projection matrix ([projectionScale]: m00 for x, m11 for y) and the view's size along that
     * axis. 0 when not usable.
     */
    fun focalPx(projectionScale: Float, viewportSide: Int): Float {
        val focal = projectionScale * viewportSide / 2f
        return if (focal.isFinite() && focal > 0f) focal else 0f
    }

    /**
     * Side (view pixels) of the sampled square along an axis that is [viewportSide] pixels long and has the
     * focal length [focalPx] (0 = unknown; then [FALLBACK_SIDE_FRACTION] of [shortSide] is used).
     */
    fun sidePx(viewportSide: Int, shortSide: Int, focalPx: Float): Float {
        val wanted = if (focalPx.isFinite() && focalPx > 0f) 2f * focalPx * TAN_HALF_ANGLE else shortSide * FALLBACK_SIDE_FRACTION
        return min(wanted, viewportSide * MAX_SIDE_FRACTION)
    }

    /**
     * Writes [size] × [size] (x, y) view-pixel pairs into [out] (length >= 2 · size²), row by row from the
     * top-left. For an odd [size] the middle sample ([centerIndex]) is exactly the view center.
     * [focalXPx] / [focalYPx] are the camera's focal lengths in view pixels ([focalPx]; 0 = unknown).
     */
    fun positions(
        viewportWidth: Int,
        viewportHeight: Int,
        out: FloatArray,
        size: Int = SIZE,
        focalXPx: Float = 0f,
        focalYPx: Float = 0f,
    ) {
        require(size >= 2) { "grid size must be at least 2" }
        require(out.size >= 2 * size * size) { "out needs ${2 * size * size} floats" }
        val shortSide = min(viewportWidth, viewportHeight)
        val sideX = sidePx(viewportWidth, shortSide, focalXPx)
        val sideY = sidePx(viewportHeight, shortSide, focalYPx)
        val stepX = sideX / (size - 1)
        val stepY = sideY / (size - 1)
        val x0 = viewportWidth / 2f - sideX / 2f
        val y0 = viewportHeight / 2f - sideY / 2f
        var k = 0
        for (row in 0 until size) {
            for (col in 0 until size) {
                out[k++] = x0 + col * stepX
                out[k++] = y0 + row * stepY
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
    /** The camera's focal lengths in view pixels ([WallSampleGrid.focalPx]); 0 when not known. */
    val focalXPx: Float,
    val focalYPx: Float,
    val centerHits: List<HitResult>,
    val reticle: ReticleResolution?,
    /** The floor under the user ([FloorState.local]): the only tracked floor a lock may take unseen. */
    val localFloor: FloorEstimate?,
    /** The session runs the Depth API (decides which hits become wall samples, see [WallSampleRule]). */
    val depthEnabled: Boolean,
)

/**
 * Runs all in-flight measurements on the GL thread. Measurements are independent and sampled in
 * parallel from the same frames, so a slow request never delays another one. Every measurement that ends
 * here writes one diagnostics line ([MeasurementReport]).
 */
internal class MeasurementEngine(private val incoming: Queue<PendingMeasurement>) {
    private class Active(val job: PendingMeasurement, val sampler: Sampler, val deadline: MeasurementDeadline) {
        /** Tracking frames handed to the sampler. */
        var frames = 0
    }

    private val active = ArrayList<Active>(4)

    /** Shared by all wall captures of the session, so the per-hit cost learned in one carries to the next. */
    private val gridGovernor = WallGridGovernor()

    /** A new camera frame: admit queued requests, enforce deadlines, sample tracking frames. */
    fun onFrame(input: FrameInputs) {
        while (true) {
            val job = incoming.poll() ?: break
            if (job.isDone) continue
            active += Active(job, Sampler.forRequest(job.request, gridGovernor), MeasurementDeadline(job.createdAtNs))
        }
        if (active.isEmpty()) return
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val a = iterator.next()
            if (a.job.isDone) {
                report(a, null, input.nowNs)
                iterator.remove()
                continue
            }
            val expiry = a.deadline.check(input.nowNs, input.tracking)
            val result = when {
                expiry != null -> expiryResult(a.sampler, expiry)
                !input.tracking -> null
                else -> {
                    a.frames++
                    sample(a.sampler, input)
                }
            }
            if (result != null) {
                a.job.complete(result)
                report(a, result, input.nowNs)
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
                report(a, null, nowNs)
                iterator.remove()
                continue
            }
            val expiry = a.deadline.check(nowNs, tracking) ?: continue
            val result = expiryResult(a.sampler, expiry)
            a.job.complete(result)
            report(a, result, nowNs)
            iterator.remove()
        }
    }

    private fun sample(sampler: Sampler, input: FrameInputs): MeasureResult? = try {
        sampler.onTrackingFrame(input)
    } catch (e: Exception) {
        // ARCore can throw on a frame it just invalidated; never take the GL thread down for it.
        ArLog.w("measurement sampling failed", e)
        MeasureResult.Failed("Measurement failed. Hold the phone steady and try again.")
    }

    private fun expiryResult(sampler: Sampler, expiry: MeasurementExpiry): MeasureResult = when (expiry) {
        MeasurementExpiry.TIMED_OUT -> MeasureResult.Failed(sampler.timeoutMessage)
        MeasurementExpiry.TRACKING_LOST -> MeasureResult.Failed(TRACKING_LOST_MESSAGE)
    }

    /** One diagnostics line per finished measurement; [result] null = it was completed elsewhere. */
    private fun report(a: Active, result: MeasureResult?, nowNs: Long) {
        try {
            val detail = a.sampler.describe()
            val line = MeasurementReport.line(a.job.request, result, nowNs - a.job.createdAtNs, a.frames, detail)
            ArLog.i(line)
            a.sampler.keepRawData(line)
        } catch (e: Exception) {
            // Diagnostics must never cost a measurement or a frame.
        }
    }

    companion object {
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

    /**
     * What this sampler collected, for the measurement's diagnostics line ([MeasurementReport.line]).
     * Called once the measurement ended, successfully or not; plain numbers only.
     */
    abstract fun describe(): String

    /**
     * Called once when the measurement ended, whatever the outcome, with its diagnostics line: a sampler
     * whose raw samples are worth replaying later hands them to the on-device log. Must only queue work.
     */
    open fun keepRawData(outcome: String) {}

    companion object {
        /** Upper bound on requested frame counts (the 4 s timeout would end longer requests anyway). */
        const val MAX_FRAMES = 90

        fun forRequest(request: MeasureRequest, gridGovernor: WallGridGovernor): Sampler = when (request) {
            MeasureRequest.LockFloor -> LockFloorSampler()
            is MeasureRequest.Wall -> WallSampler(request, gridGovernor)
            is MeasureRequest.FloorPoint -> FloorPointSampler(request)
            is MeasureRequest.SurfacePoint -> SurfacePointSampler(request)
            is MeasureRequest.CeilingHeight -> CeilingSampler(request)
        }

        fun frameTarget(requested: Int) = requested.coerceIn(1, MAX_FRAMES)
    }
}

/**
 * Locks the floor height, in order of preference, on every tracking frame:
 * 1. an upward plane under the crosshair (its height; CaptureViewModel questions raised surfaces);
 * 2. the floor under the user ([FloorState.local]: the lowest large, never-vetoed upward plane near the
 *    phone's ground point), unless it is only a little below the phone and the crosshair sees floor clearly
 *    below it ([FloorLockFallback.contradictsTrackedFloor]). A floor that is tracked somewhere else but
 *    not under the user is NOT taken: it may be another level seen through a doorway (ARX-1);
 * 3. otherwise (no floor plane here: glossy tile, polished concrete, or only another level in view): the
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
    private var path = "none"
    private var lastMad = Double.NaN

    /** Set once the crosshair showed floor clearly below the local (ambiguous) floor during this request. */
    private var localFloorRejected = false

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val plane = input.reticle?.plane
        if (plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING) {
            val y = plane.centerPose.ty().toDouble()
            if (y.isFinite()) {
                path = "plane under crosshair"
                return MeasureResult.FloorLocked(y)
            }
        }
        val camera = input.cameraPosition ?: return null

        val inv = input.invViewProjection
        val rayY = inv?.let { HitTesting.centerRayDirection(it, input.viewportWidth, input.viewportHeight)?.y }
        val pointingDown = rayY == null || rayY <= -FloorLockFallback.MIN_RAY_DOWN
        // Aiming at a wall or the horizon: whatever the ray hits is not the floor.
        val sample = if (pointingDown) HitTesting.floorFallbackSample(input.centerHits, camera) else null
        val hitY = sample?.world?.y

        val local = input.localFloor
        if (local != null && local.y.isFinite() && !localFloorRejected) {
            if (hitY == null || !FloorLockFallback.contradictsTrackedFloor(local.y, camera.y, hitY)) {
                path = "floor under the user"
                return MeasureResult.FloorLocked(local.y)
            }
            // The user aims at floor well below a plane that may be a bed or table: measure it instead.
            localFloorRejected = true
        }

        frames++
        if (hitY != null) {
            samples += hitY
        } else if (!pointingDown || sample?.rejection == FloorLockFallback.Rejection.NOT_POINTING_DOWN) {
            framesNotPointingDown++
        }

        if (samples.size >= FloorLockFallback.TARGET_SAMPLES) {
            val evaluation = FloorLockFallback.evaluate(samples)
            path = "depth / feature hits at the crosshair"
            lastMad = evaluation.mad
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

    override fun describe(): String {
        val sb = StringBuilder("path: ").append(path)
        if (frames > 0) {
            sb.append(", fallback samples ").append(samples.size).append(" of ").append(frames).append(" frames")
            sb.append(" (").append(framesNotPointingDown).append(" not pointing down)")
        }
        if (lastMad.isFinite()) sb.append(", MAD ").append(lastMad.fmtMm())
        if (localFloorRejected) sb.append(", crosshair saw floor below the local plane")
        return sb.toString()
    }

    private companion object {
        const val MSG_NO_FLOOR =
            "No floor found here yet. Aim at a textured patch of floor near your feet, move the phone slowly, " +
                "then tap Lock floor again."
        const val MSG_TILT_DOWN = "Tilt the phone down toward the floor near your feet, then tap Lock floor."
        const val MSG_UNSTEADY =
            "The floor reading wasn't steady. Hold the phone still, aimed at the floor, and try again."
    }
}

/**
 * Accumulates wall points from the hit-test grid ([WallSampleGrid], 9 × 9 unless the phone is too slow for
 * it, see [WallGridGovernor]) over N frames, then fits them with [WallEstimator.fromPoints]. Every ray
 * contributes the sample [WallSampleRule] picks (the DepthPoint when depth is on, never simply the nearest
 * hit). The crosshair sample of every frame is also kept: their median is passed as the `aimPoint`, so the
 * fit locks onto the surface the user aimed at (not furniture in front of it, a wall behind a doorway, or a
 * side wall seen at a grazing angle). Far walls (crosshair beyond [WallSampling.LONG_RANGE_DISTANCE]) are
 * sampled over more frames to average depth noise ([WallSampling.isDone]).
 *
 * The fit of the samples wins whenever it succeeds; otherwise a VERTICAL plane seen under the crosshair
 * during sampling is used ([WallEstimator.fromPlane]); otherwise the failed fit is returned so the UI can
 * show its [WallFit.failure] message. The diagnostics line reports both the fit and the plane.
 */
private class WallSampler(
    private val request: MeasureRequest.Wall,
    private val governor: WallGridGovernor,
) : Sampler() {
    override val timeoutMessage = "Wall measurement timed out. Hold the phone steady while it samples."

    private val baseFrames = frameTarget(request.frames)
    private val maxFrames = WallSampling.maxFrames(baseFrames)
    private val points = ArrayList<Vec3>(maxFrames * WallSampleGrid.COUNT)
    private val centerPoints = ArrayList<Vec3>(maxFrames)
    private val aimDistances = ArrayList<Double>(maxFrames)

    /** Raw data for [keepRawData]: the camera position of every frame and every sample with its frame and kind. */
    private val cameras = ArrayList<Vec3>(maxFrames)
    private val recorded = ArrayList<WallCaptureRecord.Sample>(maxFrames * WallSampleGrid.COUNT)
    private var measured: Measured? = null
    private val grid = FloatArray(WallSampleGrid.COUNT * 2)
    private val kindCounts = IntArray(WallSampleKind.entries.size)
    private var frames = 0
    private var firstFrameNs = 0L
    private var samplingNs = 0L

    /** Rays cast so far (grid + crosshair); rays without a usable hit are the gap to the sample count. */
    private var rays = 0
    private var smallestGrid = WallSampleGrid.SIZE

    /** Width of the sampled square (view pixels) and the focal length it was sized with, for the log. */
    private var gridSidePx = 0f
    private var gridFocalPx = 0f
    private var viewerX = 0.0
    private var viewerY = 0.0
    private var viewerZ = 0.0
    private var centerPlane: Plane? = null
    private var summary = ""

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        if (input.viewportWidth <= 0 || input.viewportHeight <= 0) return null
        if (frames == 0) firstFrameNs = input.nowNs

        val size = governor.gridSize()
        if (size < smallestGrid) smallestGrid = size
        WallSampleGrid.positions(input.viewportWidth, input.viewportHeight, grid, size, input.focalXPx, input.focalYPx)
        gridSidePx = grid[2 * (size * size - 1)] - grid[0]
        gridFocalPx = input.focalXPx
        val centerIndex = WallSampleGrid.centerIndex(size)
        val startNs = System.nanoTime()
        for (i in 0 until size * size) {
            // The grid's middle sample is the crosshair, which the frame already hit-tested.
            if (i == centerIndex) continue
            val hits = input.frame.hitTest(grid[2 * i], grid[2 * i + 1])
            HitTesting.wallSample(hits, camera, input.depthEnabled)?.let { add(it) }
        }
        val elapsedNs = System.nanoTime() - startNs
        samplingNs += elapsedNs
        rays += size * size
        governor.onFrameSampled(size * size - 1, elapsedNs)

        // The crosshair sample follows the same rule as the grid, so the aim point is not min(depth, plane).
        val center = HitTesting.wallSample(input.centerHits, camera, input.depthEnabled)
        if (center != null) {
            add(center, crosshair = true)
            centerPoints += center.world
            aimDistances += center.world.distanceTo(camera)
        } else {
            input.reticle?.hit?.distance?.takeIf { it.isFinite() }?.let { aimDistances += it }
        }
        // Plane evidence is kept apart from the samples: it backs the fallback and the diagnostics.
        HitTesting.firstVerticalPlane(input.centerHits, camera)?.let { centerPlane = it }
        cameras += camera
        viewerX += camera.x
        viewerY += camera.y
        viewerZ += camera.z
        frames++
        val aimDistance = if (aimDistances.isEmpty()) null else RobustStats.median(aimDistances)
        val done = WallSampling.isDone(frames, baseFrames, aimDistance, input.nowNs - firstFrameNs)
        return if (done) finish(aimDistance) else null
    }

    private fun add(sample: WallSample, crosshair: Boolean = false) {
        points += sample.world
        kindCounts[sample.kind.ordinal]++
        // `frames` is still the index of the frame being sampled (it is counted at the end of the frame).
        recorded += WallCaptureRecord.Sample(frames, KIND_CODES[sample.kind.ordinal], crosshair, sample.world)
    }

    private fun finish(aimDistance: Double?): MeasureResult {
        // The phone moves a little while sampling; the mean pose is the most representative viewer.
        val viewer = Vec3(viewerX / frames, viewerY / frames, viewerZ / frames)
        // One band for the aim point and the fit: both come from the request's floorY / ceilingY (CAPX-8).
        val band = WallSampling.wallBand(request.floorY, request.ceilingY)
        val aimPoint = WallSampling.aimPoint(centerPoints, frames, band)
        val sampleFit = WallEstimator.fromPoints(points, request.floorY, request.ceilingY, viewer, aimPoint = aimPoint)
        val planeObservation = centerPlane?.let { plane ->
            try {
                HitTesting.wallFromPlane(plane, viewer)
            } catch (e: Exception) {
                null // a plane ARCore dropped meanwhile must not cost a successful fit
            }
        }
        // Which evidence may be returned (never an un-aimed fit, never a plane that is not at the crosshair).
        val decision = WallOutcome.decide(sampleFit, aimPoint, centerPoints, band, planeObservation, viewer, points.size)
        measured = Measured(viewer, aimPoint, planeObservation?.let { Segment2(it.extentStart, it.extentEnd) })
        summary = try {
            summarize(aimDistance, aimPoint, band, sampleFit, planeObservation, viewer) +
                " | result " + decision.source.name + (decision.fit.hint?.let { " (\"$it\")" } ?: "")
        } catch (e: Exception) {
            "" // diagnostics only
        }
        return MeasureResult.WallMeasured(decision.fit)
    }

    private fun summarize(
        aimDistance: Double?,
        aimPoint: Vec3?,
        band: ClosedFloatingPointRange<Double>,
        sampleFit: WallFit,
        plane: WallObservation?,
        viewer: Vec3,
    ): String {
        val config = WallEstimatorConfig()
        val viewerPlan = viewer.toPlan()
        val sb = StringBuilder(320)
        sb.append(sampleCounts())
        sb.append(" | aim ").append(aimDistance.fmtOrNa(2)).append(" m")
        if (aimPoint == null) sb.append(" (no aim point: ").append(centerPoints.size).append(" crosshair hits)")
        sb.append(" | band y=").append(band.start.fmt(2)).append("..").append(band.endInclusive.fmt(2))
        val fit = sampleFit.observation
        if (fit != null && sampleFit.failure == null) {
            sb.append(" | fit ").append(fit.source.name).append(' ').append(MeasurementReport.wallLine(fit.line, viewerPlan))
            sb.append(" length ").append(fit.extentStart.distanceTo(fit.extentEnd).fmt(2)).append(" m")
            sb.append(" used ").append(sampleFit.usedPoints).append(" conf ").append(fit.confidence.fmt(2))
            sb.append(" rms ").append(fit.rmsResidual.fmtMm())
            val range = fit.line.signedDistance(viewerPlan)
            val sigma = MeasurementReport.robustSigma(points, band, fit.line, SIGMA_WINDOW_FACTOR * config.inlierThresholdAt(range))
            sb.append(" | sigma ").append(sigma.fmtMm())
            sb.append(" (expected ").append(config.expectedSigma(range).fmtMm())
            sb.append(", limit ").append(config.noiseLimitAt(range).fmtMm()).append(')')
        } else {
            sb.append(" | fit FAILED ").append(sampleFit.failure?.name ?: "no wall")
            sb.append(" usable ").append(sampleFit.usedPoints)
        }
        if (plane != null) {
            sb.append(" | plane ").append(MeasurementReport.wallLine(plane.line, viewerPlan))
            sb.append(" length ").append(plane.extentStart.distanceTo(plane.extentEnd).fmt(2)).append(" m")
            if (fit != null && sampleFit.failure == null) {
                val at = aimPoint?.toPlan() ?: Vec2.lerp(fit.extentStart, fit.extentEnd, 0.5)
                sb.append(' ').append(MeasurementReport.planeVersusFit(fit.line, plane.line, at))
            } else {
            }
        } else {
            sb.append(" | no wall plane under the crosshair")
        }
        return sb.toString()
    }

    private fun sampleCounts(): String {
        val sb = StringBuilder(96)
        sb.append("samples ").append(points.size).append(" of ").append(rays).append(" rays")
        sb.append(" (depth ").append(kindCounts[WallSampleKind.DEPTH.ordinal])
        sb.append(", plane ").append(kindCounts[WallSampleKind.PLANE.ordinal])
        sb.append(", feature ").append(kindCounts[WallSampleKind.FEATURE.ordinal]).append(')')
        sb.append(" grid ").append(smallestGrid).append(" over ").append(gridSidePx.toDouble().fmt(0)).append(" px")
        if (gridFocalPx > 0f) {
            sb.append(" (±").append(Math.toDegrees(kotlin.math.atan2(gridSidePx / 2.0, gridFocalPx.toDouble())).fmt(1)).append("°)")
        } else {
            sb.append(" (camera angle unknown)")
        }
        if (frames > 0) sb.append(" sampling ").append((samplingNs / frames).nsToMs()).append(" ms/frame")
        return sb.toString()
    }

    /** After a result: the full summary. After a timeout or lost tracking: what was collected so far. */
    override fun describe(): String = summary.ifEmpty { sampleCounts() }

    /**
     * Keeps the samples of this capture for a later replay ([WallCaptureRecord.replay]), also when it timed
     * out or tracking was lost: those are the captures worth looking at. This sampler gets no more frames
     * once its measurement ended, so the lists can be handed over as they are.
     */
    override fun keepRawData(outcome: String) {
        if (frames == 0 || recorded.isEmpty()) return
        // Without a result, what the estimator WOULD have been given (the same rules as in finish()).
        val used = measured ?: Measured(
            Vec3(viewerX / frames, viewerY / frames, viewerZ / frames),
            WallSampling.aimPoint(centerPoints, frames, WallSampling.wallBand(request.floorY, request.ceilingY)),
            null,
        )
        WallCaptureLog.record(
            WallCaptureRecord(
                timeMillis = System.currentTimeMillis(),
                floorY = request.floorY,
                ceilingY = request.ceilingY,
                viewer = used.viewer,
                aimPoint = used.aimPoint,
                cameras = cameras,
                samples = recorded,
                plane = used.plane,
                note = outcome,
            ),
        )
    }

    /** What finish() handed to the estimator, and the wall plane it saw under the crosshair. */
    private class Measured(val viewer: Vec3, val aimPoint: Vec3?, val plane: Segment2?)

    private companion object {
        /** Samples within this many inlier bands of the fitted line count toward the logged robust sigma. */
        const val SIGMA_WINDOW_FACTOR = 4.0

        /** [WallCaptureRecord.Sample.kind] for each [WallSampleKind], by ordinal. */
        val KIND_CODES = CharArray(WallSampleKind.entries.size) { WallSampleKind.entries[it].name[0] }
    }
}

/** Screen-center ray ∩ (Y = floorY) per frame; mean over N frames, spread = median distance to it. */
private class FloorPointSampler(private val request: MeasureRequest.FloorPoint) : Sampler() {
    override val timeoutMessage = "Couldn't find the floor at the crosshair. Aim lower, at the floor, and hold steady."

    private val target = frameTarget(request.frames)
    private val samples = ArrayList<Vec3>(target)
    private var range = Double.NaN

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        val inv = input.invViewProjection ?: return null
        val p = HitTesting.floorRayHit(inv, input.viewportWidth, input.viewportHeight, request.floorY, camera)
            ?: return null
        samples += p
        range = p.distanceTo(camera)
        if (samples.size < target) return null
        val mean = MeasurementMath.mean(samples)
        return MeasureResult.Point(mean, samples.size, MeasurementMath.medianDistance(samples, mean))
    }

    override fun describe(): String =
        "ray on floor y=${request.floorY.fmt(3)}, samples ${samples.size} of $target, range ${range.fmt(2)} m"
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
    private val kindCounts = IntArray(SurfaceKind.entries.size)
    private var range = Double.NaN

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val reticle = input.reticle?.takeIf { it.fromHitTest } ?: return null
        samples += reticle.hit.world
        kindCounts[reticle.hit.surface.ordinal]++
        range = reticle.hit.distance
        if (samples.size < target) return null
        val median = MeasurementMath.componentMedian(samples)
        return MeasureResult.Point(median, samples.size, MeasurementMath.medianDistance(samples, median))
    }

    override fun describe(): String {
        val kinds = SurfaceKind.entries.filter { kindCounts[it.ordinal] > 0 }
            .joinToString(", ") { "${it.name} ${kindCounts[it.ordinal]}" }
        return "samples ${samples.size} of $target (${kinds.ifEmpty { "no hits" }}), range ${range.fmt(2)} m"
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
    private var clusterSize = -1

    override fun onTrackingFrame(input: FrameInputs): MeasureResult? {
        val camera = input.cameraPosition ?: return null
        val y = HitTesting.ceilingSampleY(input.centerHits, camera, request.floorY) ?: return null
        heights += y
        if (heights.size < target) return null
        val cluster = CeilingSampling.upperCluster(heights)
        clusterSize = cluster.size
        if (cluster.isEmpty()) return MeasureResult.Failed(MSG_INCONSISTENT)
        val median = RobustStats.median(cluster)
        return MeasureResult.Height(median - request.floorY, cluster.size, RobustStats.mad(cluster))
    }

    override fun describe(): String {
        val sb = StringBuilder("above floor y=").append(request.floorY.fmt(3))
        sb.append(", ceiling-like hits ").append(heights.size).append(" of ").append(target)
        if (heights.isNotEmpty()) {
            sb.append(", heights ").append((heights.min() - request.floorY).fmt(2))
            sb.append("..").append((heights.max() - request.floorY).fmt(2)).append(" m")
        }
        if (clusterSize >= 0) sb.append(", upper cluster ").append(clusterSize)
        return sb.toString()
    }

    private companion object {
        const val MSG_INCONSISTENT =
            "The ceiling readings didn't agree. Aim straight up at a plain spot, away from lights and " +
                "cabinets, and hold steady."
    }
}
