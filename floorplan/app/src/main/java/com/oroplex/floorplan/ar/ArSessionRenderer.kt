package com.oroplex.floorplan.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.View
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GL-thread driver of the AR session: binds the camera texture, calls Session.update() once per drawn
 * frame, draws the camera background and hands the frame to [controller].
 *
 * Every ARCore call is gated on [ArCoreCaptureController.isRunning], which is only true between a
 * successful Session.resume() and the GLSurfaceView pause that precedes Session.pause(), so the GL thread
 * never races the lifecycle. No exception escapes onDrawFrame (that would kill the GL thread).
 *
 * [onCameraLost] is invoked (on the GL thread, once per outage) when Session.update() has kept failing
 * with CameraNotAvailableException for [CAMERA_LOST_AFTER_NS]: ARCore only recovers from that through a
 * pause / resume cycle, so the host offers a Retry.
 */
internal class ArSessionRenderer(
    private val session: Session,
    private val controller: ArCoreCaptureController,
    private val displayRotation: DisplayRotationHelper,
    private val onCameraLost: () -> Unit,
) : GLSurfaceView.Renderer {

    private val background = BackgroundRenderer()
    private var viewportWidth = 0
    private var viewportHeight = 0

    /** Whether Session.setCameraTextureName was called for the current EGL context's texture. */
    private var textureBound = false
    private val errors = ThrottledLog(TAG)
    private var cameraFailingSinceNs = NOT_FAILING
    private var cameraLostReported = false
    private var seenResumeCount = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.createOnGlThread()
        } catch (e: Exception) {
            Log.e(TAG, "Creating the camera background failed", e)
        }
        // A new EGL context invalidates the old texture name: re-bind before the next update().
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        // The GL thread can run before Session.resume() returned on the main thread; ARCore calls are only
        // made while running. Otherwise the geometry is applied before the first update() instead.
        if (!controller.isRunning || controller.isClosed) {
            displayRotation.markChanged()
            return
        }
        try {
            displayRotation.apply(session, width, height)
        } catch (e: Exception) {
            displayRotation.markChanged()
            errors.warn("setDisplayGeometry failed", e)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!controller.isRunning || controller.isClosed) return
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val textureId = background.textureId
        if (textureId == 0) return
        if (seenResumeCount != controller.resumeCount) {
            // New resume cycle (e.g. after Retry): a camera outage must be detected afresh.
            seenResumeCount = controller.resumeCount
            cameraFailingSinceNs = NOT_FAILING
            cameraLostReported = false
        }

        try {
            if (!textureBound) {
                session.setCameraTextureName(textureId)
                textureBound = true
            }
            displayRotation.applyIfChanged(session, viewportWidth, viewportHeight)

            val frame = try {
                session.update()
            } catch (e: CameraNotAvailableException) {
                errors.warn("Camera not available", e)
                controller.onFrameUnavailable(TrackingProblem.CAMERA_UNAVAILABLE)
                onCameraUnavailable()
                return
            }
            cameraFailingSinceNs = NOT_FAILING
            cameraLostReported = false

            if (frame.hasDisplayGeometryChanged()) background.updateDisplayGeometry(frame)
            // Timestamp 0: the camera has not produced its first image yet; nothing valid to draw.
            if (frame.timestamp == 0L) return

            background.draw()
            controller.onFrame(session, frame, viewportWidth, viewportHeight)
        } catch (e: SessionPausedException) {
            // Pause raced this frame; the GL thread is about to be paused too.
        } catch (e: Exception) {
            errors.warn("AR frame failed", e)
            controller.onFrameUnavailable(TrackingProblem.BAD_STATE)
        }
    }

    private fun onCameraUnavailable() {
        val now = System.nanoTime()
        if (cameraFailingSinceNs == NOT_FAILING) cameraFailingSinceNs = now
        if (!cameraLostReported && now - cameraFailingSinceNs > CAMERA_LOST_AFTER_NS) {
            cameraLostReported = true
            onCameraLost()
        }
    }

    private companion object {
        const val TAG = "ArSessionRenderer"
        const val CAMERA_LOST_AFTER_NS = 1_500_000_000L
        const val NOT_FAILING = Long.MIN_VALUE
    }
}

/**
 * Tracks the display rotation for Session.setDisplayGeometry. Rotation changes are reported on the main
 * thread (DisplayManager listener) and applied on the GL thread before the next update().
 */
internal class DisplayRotationHelper(context: Context) : DisplayManager.DisplayListener {
    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)

    @Volatile
    private var display: Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
    private val changed = AtomicBoolean(true)
    private var view: View? = null

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.display?.let { display = it }
            changed.set(true)
        }

        override fun onViewDetachedFromWindow(v: View) = Unit
    }

    /** Main thread, on activity resume. [view] is the GL view whose display is used once attached. */
    fun onResume(view: View) {
        if (this.view !== view) {
            this.view?.removeOnAttachStateChangeListener(attachListener)
            view.addOnAttachStateChangeListener(attachListener)
            this.view = view
        }
        view.display?.let { display = it }
        displayManager?.registerDisplayListener(this, null)
        changed.set(true)
    }

    /** Main thread, on activity pause. */
    fun onPause() {
        displayManager?.unregisterDisplayListener(this)
    }

    /** Main thread, when the GL view is discarded. */
    fun release() {
        onPause()
        view?.removeOnAttachStateChangeListener(attachListener)
        view = null
    }

    /** GL thread: pushes the current rotation + viewport to ARCore. */
    fun apply(session: Session, width: Int, height: Int) {
        changed.set(false)
        session.setDisplayGeometry(display?.rotation ?: Surface.ROTATION_0, width, height)
    }

    /** Forces the next [applyIfChanged] to push the geometry (any thread). */
    fun markChanged() {
        changed.set(true)
    }

    /** GL thread: [apply] only if the rotation may have changed since the last call. */
    fun applyIfChanged(session: Session, width: Int, height: Int) {
        if (changed.get()) apply(session, width, height)
    }

    override fun onDisplayAdded(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        view?.display?.let { display = it }
        changed.set(true)
    }
}
