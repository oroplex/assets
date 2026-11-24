package com.oroplex.floorplan.ar

import android.app.Activity
import android.util.Log
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
            Log.w(TAG, "Session.close failed", e)
        }
    }

    private companion object {
        const val TAG = "ArSessionHolder"
    }
}

internal sealed interface SessionCreateResult {
    class Created(val holder: ArSessionHolder) : SessionCreateResult
    class Failed(val error: ArSetupError) : SessionCreateResult
}

/** Creates and configures the ARCore session (main thread, after availability was confirmed). */
internal object ArSessionFactory {
    private const val TAG = "ArSessionFactory"
    private const val PREFERRED_FPS = 30

    fun create(activity: Activity): SessionCreateResult {
        val session = try {
            Session(activity)
        } catch (e: UnavailableArcoreNotInstalledException) {
            return SessionCreateResult.Failed(ArSetupError.ARCORE_NOT_INSTALLED)
        } catch (e: UnavailableApkTooOldException) {
            return SessionCreateResult.Failed(ArSetupError.ARCORE_TOO_OLD)
        } catch (e: UnavailableSdkTooOldException) {
            return SessionCreateResult.Failed(ArSetupError.APP_TOO_OLD)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            return SessionCreateResult.Failed(ArSetupError.DEVICE_NOT_SUPPORTED)
        } catch (e: UnavailableException) {
            Log.w(TAG, "ARCore unavailable", e)
            return SessionCreateResult.Failed(ArSetupError.SESSION_FAILED)
        } catch (e: SecurityException) {
            Log.w(TAG, "Camera permission missing at session creation", e)
            return SessionCreateResult.Failed(ArSetupError.CAMERA_UNAVAILABLE)
        } catch (e: Exception) {
            Log.e(TAG, "Session creation failed", e)
            return SessionCreateResult.Failed(ArSetupError.SESSION_FAILED)
        }

        return try {
            val hardwareDepth = selectCameraConfig(session)
            val depthEnabled = configure(session)
            Log.i(TAG, "AR session ready: depth=$depthEnabled, hardwareDepth=$hardwareDepth")
            SessionCreateResult.Created(ArSessionHolder(session, depthEnabled, hardwareDepth))
        } catch (e: Exception) {
            Log.e(TAG, "Session configuration failed", e)
            try {
                session.close()
            } catch (closeError: Exception) {
                Log.w(TAG, "Session.close after failed configuration", closeError)
            }
            SessionCreateResult.Failed(ArSetupError.SESSION_FAILED)
        }
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
                Log.w(TAG, "Keeping the default camera config", e)
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
        Log.w(TAG, "Camera config query failed", e)
        emptyList()
    }

    private fun applyConfig(session: Session, config: CameraConfig): Boolean = try {
        session.setCameraConfig(config)
        true
    } catch (e: Exception) {
        Log.w(TAG, "setCameraConfig rejected", e)
        false
    }

    /**
     * Applies the session config. Depth is enabled (AUTOMATIC: hardware depth when the camera config uses
     * it, depth-from-motion otherwise) whenever the device supports it; if the combination is rejected the
     * session is configured again without depth. Returns whether depth ended up enabled.
     */
    private fun configure(session: Session): Boolean {
        val depthSupported = try {
            session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        } catch (e: Exception) {
            false
        }
        val config = Config(session)
            .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            .setFocusMode(Config.FocusMode.AUTO)
            .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
            .setDepthMode(if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED)
            .setInstantPlacementMode(Config.InstantPlacementMode.DISABLED)
            .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
        try {
            session.configure(config)
        } catch (e: UnsupportedConfigurationException) {
            if (!depthSupported) throw e
            Log.w(TAG, "Depth configuration rejected; continuing without depth", e)
            config.setDepthMode(Config.DepthMode.DISABLED)
            session.configure(config)
        }
        return config.depthMode == Config.DepthMode.AUTOMATIC
    }
}
