package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec3
import kotlinx.coroutines.flow.StateFlow

enum class TrackingStatus { NOT_STARTED, TRACKING, PAUSED, STOPPED }

enum class TrackingProblem(val message: String) {
    NONE(""),
    BAD_STATE("AR tracking error. Try restarting the scan."),
    INSUFFICIENT_LIGHT("Too dark. Turn on lights."),
    EXCESSIVE_MOTION("Moving too fast. Slow down."),
    INSUFFICIENT_FEATURES("Can't see enough detail. Aim at a textured surface."),
    CAMERA_UNAVAILABLE("Camera in use by another app."),
    INITIALIZING("Move the phone slowly to start tracking."),
}

enum class SurfaceKind { FLOOR, WALL_PLANE, CEILING, DEPTH, FEATURE_POINT, OTHER }

/** What the screen-center crosshair currently points at. */
data class ReticleHit(
    val world: Vec3,
    val surface: SurfaceKind,
    /** Distance from the camera, meters. */
    val distance: Double,
    /** World normal when the hit is on a detected plane, else null. */
    val normal: Vec3?,
)

/**
 * Snapshot published once per rendered AR frame (conflated). Immutable; safe to read on the main thread.
 * [viewProjection] is the 4x4 column-major view*projection matrix for the CURRENT viewport, usable with
 * core.projection.Projection to draw overlays in screen pixels (origin top-left) of size viewportWidth x viewportHeight.
 */
data class ArFrameState(
    val tracking: TrackingStatus = TrackingStatus.NOT_STARTED,
    val problem: TrackingProblem = TrackingProblem.INITIALIZING,
    /**
     * Current best floor height (world Y): the LOWEST large (≥ 0.5 m²) upward plane 0.8–2.2 m below the
     * camera, so beds, tables and islands above it never count; kept while no plane qualifies; null until found.
     */
    val floorY: Double? = null,
    /** Area (m²) of the plane backing [floorY]; > ~1 m² means a confident floor. */
    val floorArea: Double = 0.0,
    val reticle: ReticleHit? = null,
    val cameraPosition: Vec3? = null,
    val viewProjection: FloatArray? = null,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    /** ARCore Depth API active (depth-from-motion or hardware). */
    val depthEnabled: Boolean = false,
    /** A hardware depth (ToF) sensor is being used. */
    val hardwareDepth: Boolean = false,
    val horizontalPlanes: Int = 0,
    val verticalPlanes: Int = 0,
    val timestampNs: Long = 0L,
) {
    // FloatArray breaks data-class equality; compare by timestamp so StateFlow emits each frame.
    override fun equals(other: Any?): Boolean = other is ArFrameState && other.timestampNs == timestampNs && other.tracking == tracking && other.problem == problem
    override fun hashCode(): Int = timestampNs.hashCode()
}

sealed interface MeasureRequest {
    /**
     * Lock the floor height: plane under the reticle if it is an upward plane, else the current best floor,
     * else (no floor plane detected) the median of depth / feature hits at the reticle over several frames
     * while the phone points down at least 30°, when those readings agree.
     */
    data object LockFloor : MeasureRequest

    /**
     * Sample the wall under the reticle over [frames] frames (hit-test grid around screen center; more frames,
     * up to 25, when the reticle is beyond 2.5 m), then fit with WallEstimator using the median reticle hit
     * as the aim point. Falls back to a detected vertical plane under the reticle when the depth fit fails.
     */
    data class Wall(val floorY: Double, val ceilingY: Double?, val frames: Int = 15) : MeasureRequest

    /** Reticle ray intersected with the horizontal plane Y = floorY, averaged over [frames] frames. */
    data class FloorPoint(val floorY: Double, val frames: Int = 6) : MeasureRequest

    /** Reticle hit on any surface (depth/plane/feature), median over [frames] frames. */
    data class SurfacePoint(val frames: Int = 8) : MeasureRequest

    /**
     * Ceiling height above [floorY]: reticle hits on downward planes, or depth / feature hits on horizontal
     * surfaces while aiming steeply up, more than 1.8 m above the floor; median of the upper cluster over [frames].
     */
    data class CeilingHeight(val floorY: Double, val frames: Int = 15) : MeasureRequest
}

sealed interface MeasureResult {
    data class FloorLocked(val floorY: Double) : MeasureResult
    data class WallMeasured(val fit: WallFit) : MeasureResult

    /** [spread] = median distance of the samples from the result, meters (quality indicator). */
    data class Point(val world: Vec3, val samples: Int, val spread: Double) : MeasureResult
    data class Height(val height: Double, val samples: Int, val spread: Double) : MeasureResult
    data class Failed(val reason: String) : MeasureResult
}

/** Main-thread facing API of the AR layer. Implemented by ArCoreCaptureController. */
interface ArCaptureController {
    /** Unique id of this AR session; rooms captured in the same session share a coordinate frame. */
    val sessionId: String

    val frameState: StateFlow<ArFrameState>

    /**
     * Run a measurement on the GL thread over the next frames. Never throws; returns Failed on timeout
     * (~4 s), tracking loss, or session pause.
     */
    suspend fun measure(request: MeasureRequest): MeasureResult

    /** Create an ARCore anchor for [roomId] at the given world position (on the floor). */
    fun createRoomAnchor(roomId: String, position: Vec3)

    /**
     * For every room anchor that is still tracking: the plan-space rigid transform that maps geometry
     * captured at anchor-creation time to the anchor's CURRENT pose (ARCore map refinements).
     * Apply with RoomOps.transform before the final save.
     */
    fun anchorCorrections(): Map<String, RigidTransform2>
}
