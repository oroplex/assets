package com.oroplex.floorplan.ar

import android.app.Activity
import android.os.Build
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnsupportedConfigurationException
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A configured ARCore session plus the controller that drives it. Owned by the AR host composable.
 * [close] must only be called once the GL thread can no longer touch the session (GLSurfaceView paused).
 */
internal class ArSessionHolder(
    val session: Session,
    val depthEnabled: Boolean,
    val hardwareDepth: Boolean,
) {
    val controller = ArCoreCaptureController(depthEnabled = depthEnabled, hardwareDepth = hardwareDepth)

    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    /**
     * Set once a GLSurfaceView renders this session. From then on only that view's owner may call
     * [close], because it has to park the GL thread first.
     */
    @Volatile
    var isOwnedByView: Boolean = false
        private set

    fun markOwnedByView() {
        isOwnedByView = true
    }

    /** Fails pending measurements, detaches anchors and releases the native session. Idempotent. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        controller.close()
        try {
            session.close()
        } catch (e: Exception) {
            ArLog.w("Session.close failed", e)
        }
    }
}

internal sealed interface SessionCreateResult {
    class Created(val holder: ArSessionHolder) : SessionCreateResult
    class Failed(val error: ArSetupError) : SessionCreateResult
}

/** Creates and configures the ARCore session (main thread, after availability was confirmed). */
internal object ArSessionFactory {
    private const val PREFERRED_FPS = 30

    fun create(activity: Activity): SessionCreateResult {
        val session = try {
            Session(activity)
        } catch (e: UnavailableArcoreNotInstalledException) {
            return failed(ArSetupError.ARCORE_NOT_INSTALLED, e)
        } catch (e: UnavailableApkTooOldException) {
            return failed(ArSetupError.ARCORE_TOO_OLD, e)
        } catch (e: UnavailableSdkTooOldException) {
            return failed(ArSetupError.APP_TOO_OLD, e)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            return failed(ArSetupError.DEVICE_NOT_SUPPORTED, e)
        } catch (e: UnavailableException) {
            return failed(ArSetupError.SESSION_FAILED, e)
        } catch (e: SecurityException) {
            // Camera permission missing at session creation.
            return failed(ArSetupError.CAMERA_UNAVAILABLE, e)
        } catch (e: Exception) {
            return failed(ArSetupError.SESSION_FAILED, e)
        }

        return try {
            val hardwareDepth = selectCameraConfig(session)
            val depthEnabled = configure(session)
            ArLog.i(describe(session, hardwareDepth))
            SessionCreateResult.Created(ArSessionHolder(session, depthEnabled, hardwareDepth))
        } catch (e: Exception) {
            try {
                session.close()
            } catch (closeError: Exception) {
                ArLog.w("Session.close after a failed configuration", closeError)
            }
            failed(ArSetupError.SESSION_FAILED, e)
        }
    }

    private fun failed(error: ArSetupError, cause: Exception): SessionCreateResult.Failed {
        ArLog.e("session creation failed: $error", cause)
        return SessionCreateResult.Failed(error)
    }

    /**
     * Picks the camera config before the first resume (ARCore requires the session to be paused).
     *
     * 1. A back-camera config that uses a hardware depth (ToF) sensor, 30 fps preferred, any fps otherwise:
     *    hardware depth is far less noisy than depth-from-motion, which matters more than frame rate.
     * 2. Otherwise ARCore's default config, unless it targets more than 30 fps, in which case the 30 fps
     *    config with the same GPU texture size is chosen (30 fps leaves GPU / thermal headroom for the
     *    per-frame hit testing and halves the number of near-duplicate frames).
     *
     * Returns true when the active config uses a hardware depth sensor. Never throws: on any error the
     * default config is kept.
     */
    private fun selectCameraConfig(session: Session): Boolean {
        val tofUsage = EnumSet.of(CameraConfig.DepthSensorUsage.REQUIRE_AND_USE)
        val fpsPreferences = listOf(
            EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30),
            EnumSet.allOf(CameraConfig.TargetFps::class.java),
        )
        var tofSelected = false
        for (fps in fpsPreferences) {
            val tofConfig = supportedConfigs(session, fps, tofUsage).firstOrNull() ?: continue
            if (applyConfig(session, tofConfig)) {
                tofSelected = true
                break
            }
        }

        if (!tofSelected) {
            try {
                val current = session.cameraConfig
                val maxFps = current.fpsRange?.upper ?: PREFERRED_FPS
                if (maxFps > PREFERRED_FPS) {
                    val thirty = supportedConfigs(session, EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30), null)
                    val match = thirty.firstOrNull { it.textureSize == current.textureSize } ?: thirty.firstOrNull()
                    if (match != null) applyConfig(session, match)
                }
            } catch (e: Exception) {
                ArLog.w("keeping the default camera config", e)
            }
        }

        return try {
            session.cameraConfig.depthSensorUsage == CameraConfig.DepthSensorUsage.REQUIRE_AND_USE
        } catch (e: Exception) {
            false
        }
    }

    private fun supportedConfigs(
        session: Session,
        fps: EnumSet<CameraConfig.TargetFps>,
        depthUsage: EnumSet<CameraConfig.DepthSensorUsage>?,
    ): List<CameraConfig> = try {
        val filter = CameraConfigFilter(session)
            .setFacingDirection(CameraConfig.FacingDirection.BACK)
            .setTargetFps(fps)
        if (depthUsage != null) filter.setDepthSensorUsage(depthUsage)
        session.getSupportedCameraConfigs(filter).orEmpty()
    } catch (e: Exception) {
        ArLog.w("camera config query failed", e)
        emptyList()
    }

    private fun applyConfig(session: Session, config: CameraConfig): Boolean = try {
        session.setCameraConfig(config)
        true
    } catch (e: Exception) {
        ArLog.w("setCameraConfig rejected", e)
        false
    }

    /**
     * Applies the session config. Depth is enabled (AUTOMATIC: hardware depth when the camera config uses
     * it, depth-from-motion otherwise) whenever the device supports it; if the combination is rejected the
     * session is configured again without depth. Returns whether depth ended up enabled.
     *
     * Focus is FIXED, explicitly. This app measures with the camera, so its intrinsics must not move:
     * auto-focus shifts the lens, and with it the effective focal length ("focus breathing"), while it
     * hunts, which it does on exactly the plain walls and floors measured here. ARCore's documentation
     * says fixed focus gives the best tracking and that AUTO is meant for capturing photos or video of
     * close subjects, which this app never does. FIXED is ARCore's default today, but the documentation
     * warns the default may change, so it is set here rather than relied on.
     */
    private fun configure(session: Session): Boolean {
        val depthSupported = try {
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (e: Exception) {
            false
        }
        val config = Config(session)
            .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            .setFocusMode(Config.FocusMode.FIXED)
            .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
            .setDepthMode(if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED)
            .setInstantPlacementMode(Config.InstantPlacementMode.DISABLED)
            .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        try {
            session.configure(config)
        } catch (e: UnsupportedConfigurationException) {
            if (!depthSupported) throw e
            ArLog.w("depth configuration rejected; continuing without depth", e)
            config.setDepthMode(Config.DepthMode.DISABLED)
            session.configure(config)
        }
        return config.depthMode == Config.DepthMode.AUTOMATIC
    }

    /**
     * One line describing the session as ARCore actually configured it (read back from the session, not
     * from what was asked for): depth mode, focus mode, plane finding, and the camera config in use.
     * Never throws.
     */
    private fun describe(session: Session, hardwareDepth: Boolean): String {
        val sb = StringBuilder(200)
        sb.append("session created on ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        sb.append(" (Android ").append(Build.VERSION.RELEASE).append(", API ").append(Build.VERSION.SDK_INT).append(')')
        try {
            val config = session.config
            sb.append(": depth ").append(config.depthMode)
            sb.append(if (hardwareDepth) " (hardware depth sensor)" else " (from motion, no hardware depth sensor)")
            sb.append(", focus ").append(config.focusMode)
            sb.append(", planes ").append(config.planeFindingMode)
            sb.append(", update ").append(config.updateMode)
        } catch (e: Exception) {
            sb.append(": config unreadable (").append(e.javaClass.simpleName).append(')')
        }
        try {
            val camera = session.cameraConfig
            sb.append("; camera image ").append(camera.imageSize)
            sb.append(", texture ").append(camera.textureSize)
            sb.append(", fps ").append(camera.fpsRange)
            sb.append(", depth sensor ").append(camera.depthSensorUsage)
        } catch (e: Exception) {
            sb.append("; camera config unreadable (").append(e.javaClass.simpleName).append(')')
        }
        return sb.toString()
    }
}
