package com.oroplex.floorplan.ar

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import kotlin.math.abs

/** Current floor estimate: world height of the floor plane and the area (m²) of that plane. */
internal data class FloorEstimate(val y: Double, val area: Double)

/**
 * Tracks the best floor plane across frames (GL thread only).
 *
 * The best floor is the largest TRACKING, non-subsumed HORIZONTAL_UPWARD_FACING plane whose center is at
 * least [FloorSelection.MIN_DROP_BELOW_CAMERA] below the camera (that excludes tables and counters the user
 * stands next to). Once found, the estimate is kept while no plane currently qualifies (tracking paused,
 * the user crouching to aim at a corner, ...), so `floorY` never flickers back to null.
 */
internal class FloorTracker {
    var current: FloorEstimate? = null
        private set

    /** Non-subsumed TRACKING planes of each orientation in the last update (horizontal = up + down). */
    var horizontalPlanes: Int = 0
        private set
    var verticalPlanes: Int = 0
        private set

    private val candidates = ArrayList<FloorCandidate>(8)

    /** [cameraY] is null while the camera is not tracking: plane counts update, the floor is kept. */
    fun update(planes: Collection<Plane>, cameraY: Double?): FloorEstimate? {
        var horizontal = 0
        var vertical = 0
        candidates.clear()
        for (plane in planes) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            when (plane.type) {
                Plane.Type.VERTICAL -> vertical++
                Plane.Type.HORIZONTAL_DOWNWARD_FACING -> horizontal++
                Plane.Type.HORIZONTAL_UPWARD_FACING -> {
                    horizontal++
                    if (cameraY != null) {
                        val y = plane.centerPose.ty().toDouble()
                        // Area is only computed for planes that can win (reads the polygon buffer).
                        if (FloorSelection.isBelowCamera(y, cameraY)) {
                            candidates += FloorCandidate(y, FloorSelection.polygonArea(plane.polygon))
                        }
                    }
                }
            }
        }
        horizontalPlanes = horizontal
        verticalPlanes = vertical
        if (cameraY != null) {
            FloorSelection.select(candidates, cameraY)?.let { current = FloorEstimate(it.y, it.area) }
        }
        return current
    }
}

/** An upward-facing plane considered as the floor: center height (world Y) and polygon area (m²). */
internal data class FloorCandidate(val y: Double, val area: Double)

/** Pure floor-selection math (no ARCore types), unit-tested on the JVM. */
internal object FloorSelection {
    /** A floor plane's center must be at least this far below the camera, meters. */
    const val MIN_DROP_BELOW_CAMERA = 0.5

    fun isBelowCamera(planeY: Double, cameraY: Double): Boolean =
        planeY.isFinite() && cameraY.isFinite() && planeY <= cameraY - MIN_DROP_BELOW_CAMERA

    /** Largest candidate that is far enough below the camera; null if none qualifies. */
    fun select(candidates: List<FloorCandidate>, cameraY: Double): FloorCandidate? {
        var best: FloorCandidate? = null
        for (c in candidates) {
            if (!c.area.isFinite() || !isBelowCamera(c.y, cameraY)) continue
            if (best == null || c.area > best.area) best = c
        }
        return best
    }

    /**
     * Shoelace area of an ARCore plane polygon: (x, z) pairs in the plane's local frame, read from the
     * buffer's position to its limit without consuming it. Returns 0 for fewer than 3 vertices.
     */
    fun polygonArea(xz: FloatBuffer): Double {
        val base = xz.position()
        val n = (xz.limit() - base) / 2
        if (n < 3) return 0.0
        var twiceArea = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val xi = xz.get(base + 2 * i).toDouble()
            val zi = xz.get(base + 2 * i + 1).toDouble()
            val xj = xz.get(base + 2 * j).toDouble()
            val zj = xz.get(base + 2 * j + 1).toDouble()
            twiceArea += xi * zj - xj * zi
        }
        val area = abs(twiceArea) / 2
        return if (area.isFinite()) area else 0.0
    }
}
