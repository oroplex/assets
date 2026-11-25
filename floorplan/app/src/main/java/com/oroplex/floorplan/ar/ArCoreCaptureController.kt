package com.oroplex.floorplan.ar

import android.opengl.Matrix
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
import com.oroplex.floorplan.recording.WalkthroughRecording
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

    /**
     * The floor height the capture flow locked ([setReferenceFloor]); written on the main thread, read on
     * the GL thread. Only used for the reticle; the floor tracker stays an independent estimate.
     */
    @Volatile
    private var referenceFloorY: Double? = null

    private val recorder = WalkthroughRecorder(depthEnabled = depthEnabled, hardwareDepth = hardwareDepth)
    override val walkthrough: WalkthroughRecording get() = recorder

    // ---- GL-thread state ----
    private val engine = MeasurementEngine(incomingMeasurements)
    private val floorTracker = FloorTracker()
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private var lastFrameTimestamp = Long.MIN_VALUE
    private var lastTracking = false
    private val anchorErrors = ThrottledLog()
    private val trackingChanges = ChangeLog(TRACKING_LOG_INTERVAL_NS)
    private var loggedResumeCount = -1

    override fun setReferenceFloor(y: Double?) {
        val value = y?.takeIf { it.isFinite() }
        if (value == referenceFloorY) return
        referenceFloorY = value
        // Called every frame by the capture flow; only a change is logged (a lock, a re-lock, a new session).
        val tracked = mutableFrameState.value
        ArLog.i(
            "locked floor: " + (value?.let { "y=${it.fmt(3)}" } ?: "none") +
                " (tracker: local ${tracked.localFloorY?.let { "y=${it.fmt(3)}" } ?: "none"}, " +
                "global ${tracked.floorY?.let { "y=${it.fmt(3)}" } ?: "none"}, " +
                "auto-lock ${if (tracked.floorAutoLockable) "allowed" else "not allowed"})",
        )
    }

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
            ArLog.w("ignoring a room anchor with a non-finite position")
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
        ArLog.i("session resumed (resume #$resumeCount, session ${sessionId.take(8)})")
    }

    /** The GL thread is paused and Session.pause() returned: fail everything in flight. */
    fun onSessionPaused() {
        isRunning = false
        val failed = failOutstanding(MSG_PAUSED)
        publishWithoutPose(TrackingStatus.PAUSED, mutableFrameState.value.problem)
        ArLog.i("session paused" + if (failed > 0) " ($failed measurement(s) interrupted)" else "")
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
        recorder.abort()
        incomingMeasurements.clear()
        pendingAnchors.clear()
        val anchorCount = anchors.size
        for (anchor in anchors.values) detachQuietly(anchor)
        anchors.clear()
        publishWithoutPose(TrackingStatus.STOPPED, mutableFrameState.value.problem)
        ArLog.i("session closed ($anchorCount room anchor(s), ${anchorPoses.values.count { it.tracking }} still tracking)")
    }

    /** Fails every measurement in flight with [reason]; returns how many there were. */
    private fun failOutstanding(reason: String): Int {
        var count = 0
        for (job in outstandingMeasurements) {
            if (!job.isDone) count++
            job.complete(MeasureResult.Failed(reason))
        }
        return count
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
        val failureReason = camera.trackingFailureReason
        val tracking = trackingState == TrackingState.TRACKING
        logSessionProgress(nowNs, camera, trackingState, failureReason, viewportWidth, viewportHeight)

        var cameraPosition: Vec3? = null
        var cameraForward: Vec3? = null
        var viewProjection: FloatArray? = null
        var invViewProjection: FloatArray? = null
        if (tracking) {
            val pose = camera.pose
            cameraPosition = pose.position().takeIf { it.isFinite() }
            cameraForward = pose.forward()
            viewProjection = computeViewProjection(camera)
            invViewProjection = viewProjection?.let { vp ->
                FloatArray(16).takeIf { Matrix.invertM(it, 0, vp, 0) }
            }
        }
        // Tracking with a usable pose; anything else counts as lost tracking for measurements.
        val poseValid = cameraPosition != null && viewProjection != null
        lastTracking = poseValid
        recorder.onFrame(session, frame, camera, poseValid, resumeCount, nowNs)

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
        val lockedFloorY = referenceFloorY
        val floor = floorTracker.update(
            session.getAllTrackables(Plane::class.java),
            cameraPosition?.takeIf { poseValid },
            floorLikeHitY,
            nowNs,
            floorLocked = lockedFloorY != null,
        )
        // The reticle follows the floor the capture flow LOCKED, so it agrees with FloorPoint measurements
        // and still works on a featureless floor after a depth-fallback lock (ARX-4). Before a lock it uses
        // the tracker: the floor under the user, else the session-wide estimate.
        val reticleFloorY = lockedFloorY ?: floor.local?.y ?: floor.global?.y
        val reticle = cameraPosition?.takeIf { poseValid }?.let {
            HitTesting.resolveReticle(centerHits, it, reticleFloorY, invViewProjection, viewportWidth, viewportHeight)
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
                // computeViewProjection() just filled projectionMatrix for this frame (column-major: m00, m11).
                focalXPx = if (poseValid) WallSampleGrid.focalPx(projectionMatrix[0], viewportWidth) else 0f,
                focalYPx = if (poseValid) WallSampleGrid.focalPx(projectionMatrix[5], viewportHeight) else 0f,
                centerHits = centerHits,
                reticle = reticle,
                localFloor = floor.local,
                depthEnabled = depthEnabled,
            ),
        )

        snapshotAnchors()

        mutableFrameState.value = ArFrameState(
            tracking = trackingState.toStatus(),
            problem = problemFor(trackingState, failureReason),
            floorY = floor.global?.y,
            floorArea = floor.global?.area ?: 0.0,
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
            localFloorY = floor.local?.y,
            floorAutoLockable = poseValid && floor.autoLockable,
            cameraForward = cameraForward?.takeIf { poseValid },
        )
    }

    /**
     * Diagnostics for things that are checked every frame but logged only when they change: the first
     * camera image after each resume (with the viewport and the camera intrinsics ARCore reports, which
     * must stay the same from session to session now that focus is fixed) and the tracking state with its
     * failure reason.
     */
    private fun logSessionProgress(
        nowNs: Long,
        camera: Camera,
        state: TrackingState?,
        reason: TrackingFailureReason?,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        try {
            if (loggedResumeCount != resumeCount) {
                loggedResumeCount = resumeCount
                val intrinsics = try {
                    val i = camera.imageIntrinsics
                    val focal = i.focalLength
                    val center = i.principalPoint
                    val size = i.imageDimensions
                    "image ${size[0]}x${size[1]}, focal length ${focal[0].toDouble().fmt(1)} x " +
                        "${focal[1].toDouble().fmt(1)} px, principal point ${center[0].toDouble().fmt(1)}, " +
                        center[1].toDouble().fmt(1)
                } catch (e: Exception) {
                    "intrinsics unavailable"
                }
                ArLog.i("first camera image after resume #$resumeCount, viewport ${viewportWidth}x$viewportHeight, $intrinsics")
            }
            trackingChanges.offer(nowNs, state to reason) {
                "tracking: ${state ?: "unknown"}" + if (state == TrackingState.TRACKING || reason == null) "" else " ($reason)"
            }?.let { ArLog.i(it) }
        } catch (e: Exception) {
            // Diagnostics only.
        }
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
                ArLog.w("could not create a room anchor", e)
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
                if (request.attempts < MAX_ANCHOR_ATTEMPTS) {
                    pendingAnchors += request.retried()
                } else {
                    ArLog.w("gave up on a room anchor: its pose stayed unusable for $MAX_ANCHOR_ATTEMPTS frames")
                }
                break
            }
            anchors.put(request.roomId, anchor)?.let { replaced -> detachQuietly(replaced) }
            anchorPoses[request.roomId] =
                AnchorPoses(initial, initial, tracking = anchor.trackingState == TrackingState.TRACKING)
            ArLog.i("room anchor created (${anchors.size} in this session)")
        }
    }

    private fun detachQuietly(anchor: Anchor) {
        try {
            anchor.detach()
        } catch (e: Exception) {
            ArLog.w("Anchor.detach failed", e)
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
                anchorErrors.warn("reading a room anchor's pose failed", e)
                null
            }
            anchorPoses[roomId] = if (current != null && current.isFinite()) {
                previous.copy(current = current, tracking = true)
            } else {
                previous.copy(tracking = false)
            }
        }
    }

    /**
     * Publishes a state without anything that needs a camera pose: overlays hide, and the floor under the
     * user is unknown (so nothing auto-locks) until a tracking frame says otherwise. The session-wide floor
     * estimate is kept.
     */
    private fun publishWithoutPose(tracking: TrackingStatus, problem: TrackingProblem) {
        mutableFrameState.value = mutableFrameState.value.copy(
            tracking = tracking,
            problem = problem,
            reticle = null,
            cameraPosition = null,
            viewProjection = null,
            localFloorY = null,
            floorAutoLockable = false,
            cameraForward = null,
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
        const val NEAR_PLANE = 0.05f
        const val FAR_PLANE = 100f

        /** Tracking-state changes are logged at most this often (a flapping state is summarized). */
        private const val TRACKING_LOG_INTERVAL_NS = 250_000_000L

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
