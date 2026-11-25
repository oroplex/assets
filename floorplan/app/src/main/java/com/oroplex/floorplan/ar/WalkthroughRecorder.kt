package com.oroplex.floorplan.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.oroplex.floorplan.recording.FrameRecord
import com.oroplex.floorplan.recording.ImagePlanes
import com.oroplex.floorplan.recording.Intrinsics
import com.oroplex.floorplan.recording.KeyframePolicy
import com.oroplex.floorplan.recording.PlaneRecord
import com.oroplex.floorplan.recording.RecordingInfo
import com.oroplex.floorplan.recording.RecordingProgress
import com.oroplex.floorplan.recording.RecordingResult
import com.oroplex.floorplan.recording.WalkthroughFormat
import com.oroplex.floorplan.recording.WalkthroughMeta
import com.oroplex.floorplan.recording.WalkthroughRecording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Records a walk-through (see [WalkthroughFormat]): on the GL thread it writes the pose of every camera frame
 * to the trajectory and, for the frames [KeyframePolicy] picks, copies the camera image (and ARCore's raw
 * depth when it has a new one) out of ARCore's buffers; a single background thread turns those copies into
 * JPEG files and appends the frame's record. The GL thread never waits for the disk: when more than
 * [MAX_PENDING] frames are queued, the next one is dropped and counted.
 *
 * Nothing here may throw into the render loop, and a problem with one frame must not end the recording.
 */
internal class WalkthroughRecorder(
    private val depthEnabled: Boolean,
    private val hardwareDepth: Boolean,
) : WalkthroughRecording {
    private val mutableProgress = MutableStateFlow(RecordingProgress())
    override val progress: StateFlow<RecordingProgress> = mutableProgress.asStateFlow()

    private val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "walkthrough-io").apply { isDaemon = true }
    }

    @Volatile
    private var current: Run? = null

    /** Image copies waiting to be reused: all frames of a session have the same size. */
    private val pool = ArrayList<ByteArray>(MAX_PENDING + 1)

    /** GL thread only. */
    private var rowScratch = ByteArray(0)

    override fun start(directory: File, info: RecordingInfo): Boolean {
        if (current != null) return false
        val run = try {
            Run(directory, info)
        } catch (e: IOException) {
            ArLog.w("walk-through recording could not start", e)
            mutableProgress.value = RecordingProgress(problem = "Couldn't start recording: the storage is not writable.")
            return false
        }
        mutableProgress.value = RecordingProgress(active = true)
        current = run
        ArLog.i("walk-through recording started: ${directory.name} (depth ${if (depthEnabled) "on" else "off"})")
        return true
    }

    override suspend fun stop(interrupted: Boolean): RecordingResult? {
        val run = current ?: return null
        if (interrupted) run.interrupted = true
        run.stopRequested = true
        // The GL thread ends the recording on its next frame, where it can still ask ARCore for its planes.
        // If no frame comes (the camera is paused), it is ended here without them.
        val ended = withTimeoutOrNull(STOP_WAIT_MS) { run.done.await() }
        if (ended != null) return ended
        finish(run, emptyList())
        return run.done.await()
    }

    /** The session is closing: write out what there is. Any thread. */
    fun abort() {
        val run = current ?: return
        run.interrupted = true
        finish(run, emptyList())
    }

    /** GL thread: every NEW camera frame. [tracking] = the camera is tracking and its pose is usable. */
    fun onFrame(session: Session, frame: Frame, camera: Camera, tracking: Boolean, resumeCount: Int, nowNs: Long) {
        val run = current ?: return
        try {
            record(run, session, frame, camera, tracking, resumeCount, nowNs)
        } catch (e: Exception) {
            run.errors.warn("walk-through frame failed", e)
        }
    }

    private fun record(run: Run, session: Session, frame: Frame, camera: Camera, tracking: Boolean, resumeCount: Int, nowNs: Long) {
        val timestamp = frame.timestamp
        fill(run.pose, camera.pose)
        if (run.firstResume < 0) run.firstResume = resumeCount
        run.segment = resumeCount - run.firstResume
        synchronized(run.trajectory) {
            WalkthroughFormat.trajectoryLine(run.trajectory, timestamp, run.segment, tracking, run.pose)
        }
        if (++run.trajectoryLines % TRAJECTORY_BATCH == 0) flushTrajectory(run)

        if (run.stopRequested) {
            finish(run, planesOf(session))
            return
        }
        if (nowNs - run.startedNs > MAX_DURATION_NS || run.nextIndex >= MAX_FRAMES) {
            run.interrupted = true
            mutableProgress.update { it.copy(problem = "The recording reached its length limit and was stopped.") }
            finish(run, planesOf(session))
            return
        }
        if (!tracking) {
            run.lostFrames++
            publishLive(run, nowNs, tracking = false)
            return
        }
        val decision = run.policy.offer(timestamp, run.pose)
        publishLive(run, nowNs, tracking = true)
        if (decision != KeyframePolicy.Decision.SAVE) return
        if (run.pending.get() >= MAX_PENDING) {
            run.dropped.incrementAndGet()
            return
        }
        capture(run, frame, camera, timestamp)
    }

    /** Copies this frame's image (and new raw depth) out of ARCore and queues it for writing. GL thread. */
    private fun capture(run: Run, frame: Frame, camera: Camera, timestamp: Long) {
        val image: Image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            // ResourceExhaustedException (too many images held), DeadlineExceededException, ...
            run.dropped.incrementAndGet()
            run.errors.warn("camera image not available", e)
            return
        }
        val width: Int
        val height: Int
        val nv21: ByteArray
        try {
            width = image.width
            height = image.height
            val planes = image.planes
            nv21 = takeBuffer(ImagePlanes.nv21Size(width, height))
            val scratch = maxOf(planes[0].rowStride, planes[1].rowStride)
            if (rowScratch.size < scratch) rowScratch = ByteArray(scratch)
            ImagePlanes.toNv21(
                planes[0].buffer, planes[1].buffer, planes[2].buffer, width, height,
                planes[0].rowStride, planes[1].rowStride, planes[1].pixelStride, nv21, rowScratch,
            )
        } finally {
            image.close()
        }
        run.imageWidth = width
        run.imageHeight = height

        var depth: ByteArray? = null
        var confidence: ByteArray? = null
        var depthWidth = 0
        var depthHeight = 0
        var depthTimestamp = 0L
        if (depthEnabled) {
            try {
                val raw = frame.acquireRawDepthImage16Bits()
                try {
                    // ARCore hands out the same raw depth image until it has computed a new one.
                    if (raw.timestamp != run.lastDepthTimestamp) {
                        depthWidth = raw.width
                        depthHeight = raw.height
                        depthTimestamp = raw.timestamp
                        depth = ImagePlanes.copyPlane(raw.planes[0].buffer, depthWidth, depthHeight, raw.planes[0].rowStride, 2)
                    }
                } finally {
                    raw.close()
                }
                if (depth != null) {
                    val conf = frame.acquireRawDepthConfidenceImage()
                    try {
                        if (conf.width == depthWidth && conf.height == depthHeight) {
                            confidence = ImagePlanes.copyPlane(conf.planes[0].buffer, depthWidth, depthHeight, conf.planes[0].rowStride, 1)
                        }
                    } finally {
                        conf.close()
                    }
                    run.lastDepthTimestamp = depthTimestamp
                }
            } catch (e: NotYetAvailableException) {
                // No depth yet (the first seconds of a session): the frame is saved without it.
            } catch (e: Exception) {
                depth = null
                confidence = null
                run.errors.warn("raw depth not available", e)
            }
        }

        fill(run.displayPose, camera.displayOrientedPose)
        val intrinsics = intrinsicsOf(camera.imageIntrinsics)
        val textureIntrinsics = try {
            intrinsicsOf(camera.textureIntrinsics)
        } catch (e: Exception) {
            null
        }
        val index = run.nextIndex++
        val record = FrameRecord(
            index = index,
            timestampNs = timestamp,
            segment = run.segment,
            image = WalkthroughFormat.rgbPath(index),
            pose = run.pose.toList(),
            displayPose = run.displayPose.toList(),
            intrinsics = intrinsics,
            textureIntrinsics = textureIntrinsics,
            turnRate = run.policy.turnRateDeg.toFloat(),
            speed = run.policy.speed.toFloat(),
            depth = depth?.let { WalkthroughFormat.depthPath(index) },
            confidence = confidence?.let { WalkthroughFormat.confidencePath(index) },
            depthWidth = depth?.let { depthWidth },
            depthHeight = depth?.let { depthHeight },
            depthTimestampNs = depth?.let { depthTimestamp },
        )
        if (run.finished.get()) {
            giveBuffer(nv21)
            return
        }
        run.pending.incrementAndGet()
        try {
            io.execute { write(run, record, nv21, width, height, depth, confidence) }
        } catch (e: Exception) {
            run.pending.decrementAndGet()
            giveBuffer(nv21)
        }
    }

    /** Background thread: one frame onto the disk. */
    private fun write(run: Run, record: FrameRecord, nv21: ByteArray, width: Int, height: Int, depth: ByteArray?, confidence: ByteArray?) {
        try {
            val jpeg = File(run.directory, record.image)
            FileOutputStream(jpeg).use { file ->
                val out = BufferedOutputStream(file, IO_BUFFER)
                if (!YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)) {
                    throw IOException("JPEG encoding failed")
                }
                out.flush()
            }
            var size = jpeg.length()
            if (depth != null && record.depth != null) {
                File(run.directory, record.depth).writeBytes(depth)
                size += depth.size
                run.depthSaved.incrementAndGet()
            }
            if (confidence != null && record.confidence != null) {
                File(run.directory, record.confidence).writeBytes(confidence)
                size += confidence.size
            }
            run.framesOut.write(WalkthroughFormat.encodeFrame(record))
            run.framesOut.write("\n")
            val saved = run.saved.incrementAndGet()
            if (saved % FLUSH_EVERY == 0) run.framesOut.flush()
            val bytes = run.bytes.addAndGet(size)
            mutableProgress.update { it.copy(frames = saved, bytes = bytes, dropped = run.dropped.get(), imageWidth = width, imageHeight = height) }
        } catch (e: Exception) {
            run.errors.warn("walk-through frame could not be saved", e)
            mutableProgress.update { it.copy(problem = "A frame could not be saved. Is the storage full?") }
        } finally {
            giveBuffer(nv21)
            run.pending.decrementAndGet()
        }
    }

    private fun publishLive(run: Run, nowNs: Long, tracking: Boolean) {
        if (nowNs - run.lastPublishNs < PUBLISH_INTERVAL_NS && tracking == run.lastPublishedTracking) return
        run.lastPublishNs = nowNs
        run.lastPublishedTracking = tracking
        val turnRate = if (tracking) run.policy.turnRateDeg.toFloat() else 0f
        mutableProgress.update { it.copy(tracking = tracking, turnRateDeg = turnRate, dropped = run.dropped.get()) }
    }

    private fun flushTrajectory(run: Run) {
        val text = synchronized(run.trajectory) {
            val t = run.trajectory.toString()
            run.trajectory.setLength(0)
            t
        }
        if (text.isEmpty() || run.finished.get()) return
        try {
            io.execute {
                try {
                    run.trajectoryOut.write(text)
                } catch (e: IOException) {
                    run.errors.warn("trajectory could not be saved", e)
                }
            }
        } catch (e: Exception) {
            // Executor shut down: nothing more to write.
        }
    }

    /** Ends [run] once: the files are closed after every queued frame has been written. Any thread. */
    private fun finish(run: Run, planes: List<PlaneRecord>) {
        if (!run.finished.compareAndSet(false, true)) return
        if (current === run) current = null
        val tail = synchronized(run.trajectory) {
            val t = run.trajectory.toString()
            run.trajectory.setLength(0)
            t
        }
        val durationSeconds = (System.nanoTime() - run.startedNs) / 1e9
        val task = Runnable {
            try {
                run.trajectoryOut.write(tail)
                run.trajectoryOut.close()
                run.framesOut.close()
                File(run.directory, WalkthroughFormat.PLANES_FILE).writeText(WalkthroughFormat.encodePlanes(planes))
                val meta = WalkthroughMeta(
                    name = run.info.name,
                    startedAtMillis = run.startedAtMillis,
                    durationSeconds = durationSeconds,
                    device = run.info.device,
                    android = run.info.android,
                    app = run.info.app,
                    imageWidth = run.imageWidth,
                    imageHeight = run.imageHeight,
                    jpegQuality = JPEG_QUALITY,
                    depthEnabled = depthEnabled,
                    hardwareDepth = hardwareDepth,
                    frames = run.saved.get(),
                    depthFrames = run.depthSaved.get(),
                    droppedFrames = run.dropped.get(),
                    trackingLostFrames = run.lostFrames,
                    segments = run.segment + 1,
                    interrupted = run.interrupted,
                )
                File(run.directory, WalkthroughFormat.META_FILE).writeText(WalkthroughFormat.encodeMeta(meta))
            } catch (e: Exception) {
                ArLog.w("walk-through recording could not be closed cleanly", e)
            }
            val result = RecordingResult(run.directory, run.saved.get(), run.bytes.get(), durationSeconds, run.dropped.get())
            ArLog.i(
                "walk-through recording ended: ${result.frames} frames (${run.depthSaved.get()} with depth), " +
                    "${result.bytes / 1024} kB, ${durationSeconds.fmt(1)} s, dropped ${result.dropped}, " +
                    "tracking lost for ${run.lostFrames} frames, image ${run.imageWidth}x${run.imageHeight}" +
                    if (run.interrupted) ", interrupted" else "",
            )
            mutableProgress.update { it.copy(active = false, frames = result.frames, bytes = result.bytes, dropped = result.dropped) }
            run.done.complete(result)
        }
        try {
            io.execute(task)
        } catch (e: Exception) {
            task.run()
        }
    }

    /** ARCore's own planes, for the reader to compare its walls with. GL thread; never throws. */
    private fun planesOf(session: Session): List<PlaneRecord> = try {
        val pose = FloatArray(7)
        session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .map { plane ->
                fill(pose, plane.centerPose)
                val polygon = plane.polygon
                val points = ArrayList<Float>(polygon.remaining())
                val copy = polygon.duplicate()
                while (copy.hasRemaining()) points += copy.get()
                PlaneRecord(plane.type.name, pose.toList(), plane.extentX, plane.extentZ, points)
            }
    } catch (e: Exception) {
        emptyList()
    }

    private fun intrinsicsOf(source: CameraIntrinsics): Intrinsics {
        val focal = source.focalLength
        val centre = source.principalPoint
        val size = source.imageDimensions
        return Intrinsics(focal[0], focal[1], centre[0], centre[1], size[0], size[1])
    }

    private fun fill(out: FloatArray, pose: Pose) {
        pose.getTranslation(out, 0)
        pose.getRotationQuaternion(out, 3)
    }

    private fun takeBuffer(size: Int): ByteArray = synchronized(pool) {
        val i = pool.indexOfFirst { it.size == size }
        if (i >= 0) pool.removeAt(i) else ByteArray(size)
    }

    private fun giveBuffer(buffer: ByteArray) = synchronized(pool) {
        if (pool.size <= MAX_PENDING) pool += buffer
    }

    /** One recording. Fields are GL-thread state unless marked; the writers belong to the background thread. */
    private class Run(val directory: File, val info: RecordingInfo) {
        val startedAtMillis = System.currentTimeMillis()
        val startedNs = System.nanoTime()
        val policy = KeyframePolicy()
        val pose = FloatArray(7)
        val displayPose = FloatArray(7)

        /** Guarded by itself: the GL thread appends, whoever ends the recording takes the rest. */
        val trajectory = StringBuilder(TRAJECTORY_BATCH * 96)
        var trajectoryLines = 0
        var nextIndex = 0
        var firstResume = -1

        @Volatile
        var segment = 0

        @Volatile
        var lostFrames = 0
        var lastDepthTimestamp = Long.MIN_VALUE
        var lastPublishNs = 0L
        var lastPublishedTracking = true

        @Volatile
        var imageWidth = 0

        @Volatile
        var imageHeight = 0

        @Volatile
        var stopRequested = false

        @Volatile
        var interrupted = false

        val pending = AtomicInteger(0)
        val dropped = AtomicInteger(0)
        val saved = AtomicInteger(0)
        val depthSaved = AtomicInteger(0)
        val bytes = AtomicLong(0L)
        val finished = AtomicBoolean(false)
        val done = CompletableDeferred<RecordingResult>()
        val errors = ThrottledLog(120)

        val framesOut: BufferedWriter
        val trajectoryOut: BufferedWriter

        init {
            for (name in listOf(WalkthroughFormat.RGB_DIR, WalkthroughFormat.DEPTH_DIR, WalkthroughFormat.CONFIDENCE_DIR)) {
                val dir = File(directory, name)
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
            }
            framesOut = File(directory, WalkthroughFormat.FRAMES_FILE).bufferedWriter()
            trajectoryOut = File(directory, WalkthroughFormat.TRAJECTORY_FILE).bufferedWriter()
            trajectoryOut.write(WalkthroughFormat.TRAJECTORY_HEADER)
            trajectoryOut.write("\n")
        }
    }

    private companion object {
        const val JPEG_QUALITY = 90

        /** Frames copied out of ARCore but not yet on disk; beyond this the next frame is dropped. */
        const val MAX_PENDING = 3
        const val TRAJECTORY_BATCH = 60
        const val FLUSH_EVERY = 10
        const val IO_BUFFER = 64 * 1024
        const val PUBLISH_INTERVAL_NS = 250_000_000L
        const val STOP_WAIT_MS = 1_500L

        /** Hard limits, so a forgotten recording cannot fill the phone: 30 minutes or 8 000 frames. */
        const val MAX_DURATION_NS = 30L * 60 * 1_000_000_000L
        const val MAX_FRAMES = 8_000
    }
}
