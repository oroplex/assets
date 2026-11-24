package com.oroplex.floorplan.ar

import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotTrackingException
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.io.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ARCore implementation of [ArCaptureController].
 *
 * Threading model:
 * - The public API is called from the main thread (or any coroutine); it only enqueues work into
 *   lock-free queues and reads immutable snapshots.
 * - [onFrame] / [onFrameUnavailable] run on the GL thread, the only thread that touches ARCore objects
 *   (frames, planes, hit results, anchors) while the session is running.
 * - [onSessionResumed] / [onSessionPaused] / [close] are lifecycle calls from the main thread, made while
 *   the GL thread is paused (GLSurfaceView.onPause blocks until it is).
 */
internal class ArCoreCaptureController(
    private val depthEnabled: Boolean,
    private val hardwareDepth: Boolean,
    override val sessionId: String = Ids.newId(),
) : ArCaptureController {

    private val mutableFrameState =
        MutableStateFlow(ArFrameState(depthEnabled = depthEnabled, hardwareDepth = hardwareDepth))
    override val frameState: StateFlow<ArFrameState> = mutableFrameState.asStateFlow()

    private val incomingMeasurements = ConcurrentLinkedQueue<PendingMeasurement>()
    private val outstandingMeasurements: MutableSet<PendingMeasurement> = ConcurrentHashMap.newKeySet()
    private val pendingAnchors = ConcurrentLinkedQueue<AnchorRequest>()

    /** Live ARCore anchors per room id. Mutated on the GL thread; detached in [close]. */
    private val anchors = ConcurrentHashMap<String, Anchor>()

    /** Plan-space poses per room id (initial at creation, latest while tracking); read by [anchorCorrections]. */
    private val anchorPoses = ConcurrentHashMap<String, AnchorPoses>()

    /** True between a successful Session.resume() and the next pause; gates all GL-thread ARCore calls. */
    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Incremented on every successful resume, so GL-thread state can tell resume cycles apart. */
    @Volatile
    var resumeCount: Int = 0
        private set

    // ---- GL-thread state ----
    private val engine = MeasurementEngine(incomingMeasurements)
    private val floorTracker = FloorTracker()
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private var lastFrameTimestamp = Long.MIN_VALUE
    private var lastTracking = false
    private val anchorErrors = ThrottledLog(TAG)

    override suspend fun measure(request: MeasureRequest): MeasureResult {
        invalidRequestReason(request)?.let { return MeasureResult.Failed(it) }
        if (isClosed) return MeasureResult.Failed(MSG_CLOSED)
        val job = PendingMeasurement(request, System.nanoTime())
        outstandingMeasurements += job
        try {
            incomingMeasurements += job
            // Checked AFTER the job is visible: a concurrent pause either fails it via
            // outstandingMeasurements or is observed here.
            when {
                isClosed -> job.complete(MeasureResult.Failed(MSG_CLOSED))
                !isRunning -> job.complete(MeasureResult.Failed(MSG_PAUSED))
            }
            // The GL thread enforces the real 4 s / tracking-loss limits; this watchdog only covers a GL
            // thread that stopped delivering frames altogether.
            return withTimeoutOrNull(WATCHDOG_MS) { job.result.await() } ?: MeasureResult.Failed(MSG_TIMEOUT)
        } finally {
            outstandingMeasurements -= job
            // No-op if completed; otherwise (caller cancelled / watchdog) tells the GL thread to drop it.
            job.result.cancel()
        }
    }

    override fun createRoomAnchor(roomId: String, position: Vec3) {
        if (isClosed) return
        if (!position.isFinite()) {
            Log.w(TAG, "Ignoring room anchor with non-finite position for $roomId")
            return
        }
        pendingAnchors += AnchorRequest(roomId, position)
    }

    override fun anchorCorrections(): Map<String, RigidTransform2> {
        val corrections = HashMap<String, RigidTransform2>()
        for ((roomId, poses) in anchorPoses) {
            if (poses.tracking) corrections[roomId] = poses.current.compose(poses.initial.inverse())
        }
        return corrections
    }

    // ------------------------------------------------------------------------------------------------
    // Lifecycle (main thread)
    // ------------------------------------------------------------------------------------------------

    /** Session.resume() succeeded; the GL view is about to resume. */
    fun onSessionResumed() {
        if (isClosed) return
        resumeCount++
        isRunning = true
    }

    /** The GL thread is paused and Session.pause() returned: fail everything in flight. */
    fun onSessionPaused() {
        isRunning = false
        failOutstanding(MSG_PAUSED)
        publishWithoutPose(TrackingStatus.PAUSED, mutableFrameState.value.problem)
    }

    /**
     * Final shutdown before Session.close(): fails pending work and detaches anchors. The last anchor
     * snapshot is kept so [anchorCorrections] still answers (with the last known poses) afterwards.
     */
    fun close() {
        if (isClosed) return
        isClosed = true
        isRunning = false
        failOutstanding(MSG_CLOSED)
        incomingMeasurements.clear()
        pendingAnchors.clear()
        for (anchor in anchors.values) detachQuietly(anchor)
        anchors.clear()
        publishWithoutPose(TrackingStatus.STOPPED, mutableFrameState.value.problem)
    }

    private fun failOutstanding(reason: String) {
        for (job in outstandingMeasurements) job.complete(MeasureResult.Failed(reason))
    }

    // ------------------------------------------------------------------------------------------------
    // GL thread
    // ------------------------------------------------------------------------------------------------

    /**
     * Processes one frame returned by Session.update() that has a camera image (timestamp != 0).
     * Repeated frames (LATEST_CAMERA_IMAGE returns the previous frame when no new image arrived, e.g.
     * on a 120 Hz display) only enforce measurement deadlines: re-sampling them would count the same
     * camera image twice.
     */
    fun onFrame(session: Session, frame: Frame, viewportWidth: Int, viewportHeight: Int) {
        if (isClosed) return
        val nowNs = System.nanoTime()
        val timestamp = frame.timestamp
        if (timestamp == lastFrameTimestamp) {
            engine.checkDeadlines(nowNs, lastTracking)
            return
        }
        lastFrameTimestamp = timestamp

        val camera = frame.camera
        val trackingState = camera.trackingState
        val tracking = trackingState == TrackingState.TRACKING

        var cameraPosition: Vec3? = null
        var viewProjection: FloatArray? = null
        var invViewProjection: FloatArray? = null
        if (tracking) {
            cameraPosition = camera.pose.position().takeIf { it.isFinite() }
            viewProjection = computeViewProjection(camera)
            invViewProjection = viewProjection?.let { vp ->
                FloatArray(16).takeIf { Matrix.invertM(it, 0, vp, 0) }
            }
        }
        // Tracking with a usable pose; anything else counts as lost tracking for measurements.
        val poseValid = cameraPosition != null && viewProjection != null
        lastTracking = poseValid

        processAnchorRequests(session, poseValid)

        val hasViewport = viewportWidth > 0 && viewportHeight > 0
        val centerHits = if (poseValid && hasViewport) {
            frame.hitTest(viewportWidth / 2f, viewportHeight / 2f)
        } else {
            emptyList()
        }
        // A floor-like surface at the crosshair: evidence that lets the tracker veto raised planes.
        val floorLikeHitY = cameraPosition?.takeIf { poseValid && centerHits.isNotEmpty() }?.let {
            HitTesting.floorFallbackSample(centerHits, it).world?.y
        }
        val floor = floorTracker.update(
            session.getAllTrackables(Plane::class.java),
            cameraPosition?.takeIf { poseValid }?.y,
            floorLikeHitY,
            nowNs,
        )
        val reticle = cameraPosition?.takeIf { poseValid }?.let {
            HitTesting.resolveReticle(centerHits, it, floor?.y, invViewProjection, viewportWidth, viewportHeight)
        }

        engine.onFrame(
            FrameInputs(
                frame = frame,
                nowNs = nowNs,
                tracking = poseValid,
                cameraPosition = cameraPosition,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                invViewProjection = invViewProjection,
                centerHits = centerHits,
                reticle = reticle,
                floor = floor,
            ),
        )

        snapshotAnchors()

        mutableFrameState.value = ArFrameState(
            tracking = trackingState.toStatus(),
            problem = problemFor(trackingState, camera.trackingFailureReason),
            floorY = floor?.y,
            floorArea = floor?.area ?: 0.0,
            reticle = reticle?.hit,
            cameraPosition = cameraPosition,
            viewProjection = viewProjection,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            depthEnabled = depthEnabled,
            hardwareDepth = hardwareDepth,
            horizontalPlanes = floorTracker.horizontalPlanes,
            verticalPlanes = floorTracker.verticalPlanes,
            timestampNs = timestamp,
        )
    }

    /**
     * Session.update() (or frame processing) failed: publish [problem] without a pose so overlays hide,
     * and count the time as lost tracking for in-flight measurements.
     */
    fun onFrameUnavailable(problem: TrackingProblem) {
        if (isClosed) return
        lastTracking = false
        engine.checkDeadlines(System.nanoTime(), tracking = false)
        val current = mutableFrameState.value.tracking
        publishWithoutPose(if (current == TrackingStatus.NOT_STARTED) current else TrackingStatus.PAUSED, problem)
    }

    /** View-projection (proj * view, column-major) for the current viewport; null if not finite. */
    private fun computeViewProjection(camera: Camera): FloatArray? {
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, NEAR_PLANE, FAR_PLANE)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projectionMatrix, 0, viewMatrix, 0)
        return vp.takeIf { m -> m.all { it.isFinite() } }
    }

    /** Creates queued room anchors; requests wait in the queue until the camera tracks. */
    private fun processAnchorRequests(session: Session, tracking: Boolean) {
        if (!tracking || pendingAnchors.isEmpty()) return
        while (true) {
            val request = pendingAnchors.poll() ?: break
            val p = request.position
            val anchor = try {
                session.createAnchor(Pose.makeTranslation(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
            } catch (e: NotTrackingException) {
                pendingAnchors += request // tracking dropped this very frame; retry on a later one
                break
            } catch (e: Exception) {
                Log.w(TAG, "Could not create the anchor for room ${request.roomId}", e)
                continue
            }
            val initial = try {
                anchor.pose.toPlanTransform().takeIf { it.isFinite() }
            } catch (e: Exception) {
                null
            }
            if (initial == null) {
                // A glitched pose would poison anchorCorrections(); try again on a later frame (bounded).
                detachQuietly(anchor)
                if (request.attempts < MAX_ANCHOR_ATTEMPTS) pendingAnchors += request.retried()
                break
            }
            anchors.put(request.roomId, anchor)?.let { replaced -> detachQuietly(replaced) }
            anchorPoses[request.roomId] =
                AnchorPoses(initial, initial, tracking = anchor.trackingState == TrackingState.TRACKING)
        }
    }

    private fun detachQuietly(anchor: Anchor) {
        try {
            anchor.detach()
        } catch (e: Exception) {
            Log.w(TAG, "Anchor.detach failed", e)
        }
    }

    /**
     * Publishes the latest plan-space pose of every anchor that is still tracking. A pose that is not
     * finite, or an anchor that throws, keeps the last good snapshot marked as not tracking, so a single
     * bad anchor neither fails the frame nor feeds NaN into anchorCorrections().
     */
    private fun snapshotAnchors() {
        if (anchors.isEmpty()) return
        for ((roomId, anchor) in anchors) {
            val previous = anchorPoses[roomId] ?: continue
            val current = try {
                if (anchor.trackingState == TrackingState.TRACKING) anchor.pose.toPlanTransform() else null
            } catch (e: Exception) {
                anchorErrors.warn("Reading the anchor pose for room $roomId failed", e)
                null
            }
            anchorPoses[roomId] = if (current != null && current.isFinite()) {
                previous.copy(current = current, tracking = true)
            } else {
                previous.copy(tracking = false)
            }
        }
    }

    private fun publishWithoutPose(tracking: TrackingStatus, problem: TrackingProblem) {
        mutableFrameState.value = mutableFrameState.value.copy(
            tracking = tracking,
            problem = problem,
            reticle = null,
            cameraPosition = null,
            viewProjection = null,
        )
    }

    private class AnchorRequest(val roomId: String, val position: Vec3, val attempts: Int = 0) {
        fun retried() = AnchorRequest(roomId, position, attempts + 1)
    }

    private fun RigidTransform2.isFinite(): Boolean =
        angle.isFinite() && translation.x.isFinite() && translation.y.isFinite()

    private data class AnchorPoses(
        val initial: RigidTransform2,
        val current: RigidTransform2,
        val tracking: Boolean,
    )

    companion object {
        private const val TAG = "ArCaptureController"
        const val NEAR_PLANE = 0.05f
        const val FAR_PLANE = 100f

        /** A room anchor whose pose keeps coming back unusable is given up after this many frames. */
        private const val MAX_ANCHOR_ATTEMPTS = 30

        /** Caller-side safety net, above the GL-thread timeout of 4 s. */
        private const val WATCHDOG_MS = 6_000L

        const val MSG_PAUSED = "The measurement was interrupted because the camera paused. Try again."
        const val MSG_CLOSED = "The AR session has ended."
        const val MSG_TIMEOUT = "The measurement timed out. Hold the phone steady and try again."
        const val MSG_NO_FLOOR = "The floor height isn't known yet. Lock the floor first."

        private fun invalidRequestReason(request: MeasureRequest): String? = when (request) {
            MeasureRequest.LockFloor, is MeasureRequest.SurfacePoint -> null
            is MeasureRequest.Wall -> MSG_NO_FLOOR.takeUnless { request.floorY.isFinite() }
            is MeasureRequest.FloorPoint -> MSG_NO_FLOOR.takeUnless { request.floorY.isFinite() }
            is MeasureRequest.CeilingHeight -> MSG_NO_FLOOR.takeUnless { request.floorY.isFinite() }
        }

        private fun TrackingState?.toStatus(): TrackingStatus = when (this) {
            TrackingState.TRACKING -> TrackingStatus.TRACKING
            TrackingState.PAUSED -> TrackingStatus.PAUSED
            TrackingState.STOPPED -> TrackingStatus.STOPPED
            else -> TrackingStatus.NOT_STARTED
        }

        /** Maps ARCore's tracking state + failure reason to the user-facing problem. */
        private fun problemFor(state: TrackingState?, reason: TrackingFailureReason?): TrackingProblem = when (state) {
            TrackingState.TRACKING -> TrackingProblem.NONE
            TrackingState.STOPPED -> TrackingProblem.BAD_STATE
            else -> when (reason) {
                TrackingFailureReason.BAD_STATE -> TrackingProblem.BAD_STATE
                TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingProblem.INSUFFICIENT_LIGHT
                TrackingFailureReason.EXCESSIVE_MOTION -> TrackingProblem.EXCESSIVE_MOTION
                TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingProblem.INSUFFICIENT_FEATURES
                TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingProblem.CAMERA_UNAVAILABLE
                // PAUSED without a reason: ARCore is (re)initializing after start or resume.
                else -> TrackingProblem.INITIALIZING
            }
        }
    }
}

/** Rate-limited warning log for errors that can repeat every frame. Not thread-safe (GL thread). */
internal class ThrottledLog(private val tag: String, private val every: Int = 300) {
    private var count = 0

    fun warn(message: String, error: Throwable) {
        if (count % every == 0) Log.w(tag, "$message (occurrence ${count + 1})", error)
        count++
    }
}
