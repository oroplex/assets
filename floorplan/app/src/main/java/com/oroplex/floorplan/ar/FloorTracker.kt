package com.oroplex.floorplan.ar

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import java.nio.FloatBuffer
import kotlin.math.abs

/** Current floor estimate: world height of the floor plane and the area (m²) of that plane. */
internal data class FloorEstimate(val y: Double, val area: Double)

/**
 * Tracks the floor plane across frames (GL thread only).
 *
 * The floor is chosen by HEIGHT, not by size (see [FloorSelection]): among TRACKING, non-subsumed
 * HORIZONTAL_UPWARD_FACING planes of at least [FloorSelection.MIN_AREA] whose center lies
 * [FloorSelection.MIN_DROP_BELOW_CAMERA]–[FloorSelection.MAX_DROP_BELOW_CAMERA] below the camera, the
 * LOWEST wins. Beds, tables and kitchen islands are always above the floor, so once any part of the real
 * floor has been detected they can no longer become the floor, however large they are.
 *
 * Before any floor plane exists, a raised plane that is only a little below the phone (a bed for a tall
 * user) could still qualify. Such an "ambiguous" plane is vetoed when the crosshair has recently seen
 * floor-like surfaces well below it ([FloorEvidence], [FloorSelection.isContradicted]): the floor is the
 * lowest surface in the room, so anything solid seen clearly lower proves the plane is furniture.
 *
 * Once found, the estimate is kept while no plane currently qualifies (tracking paused, the user
 * crouching to aim at a corner, the phone raised to the ceiling, ...), so `floorY` does not flicker back
 * to null, and it is never raised onto furniture by more than [FloorSelection.MAX_RAISE] while the old
 * floor is still plausibly below the phone. It is only withdrawn when it is itself contradicted.
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
    private val evidence = FloorEvidence()

    /**
     * [cameraY] is null while the camera is not tracking: plane counts update, the floor is kept.
     * [floorLikeHitY] is the height of a floor-like crosshair hit this frame
     * ([HitTesting.floorFallbackSample]), or null.
     */
    fun update(planes: Collection<Plane>, cameraY: Double?, floorLikeHitY: Double? = null, nowNs: Long = 0L): FloorEstimate? {
        var horizontal = 0
        var vertical = 0
        candidates.clear()
        for (plane in planes) {
            try {
                if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
                when (plane.type) {
                    Plane.Type.VERTICAL -> vertical++
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> horizontal++
                    Plane.Type.HORIZONTAL_UPWARD_FACING -> {
                        horizontal++
                        if (cameraY != null) {
                            val y = plane.centerPose.ty().toDouble()
                            // Area is only computed for planes at a plausible floor height (reads the polygon).
                            if (FloorSelection.isInFloorRange(y, cameraY)) {
                                candidates += FloorCandidate(y, FloorSelection.polygonArea(plane.polygon))
                            }
                        }
                    }
                    null -> Unit
                }
            } catch (e: Exception) {
                // A plane ARCore invalidated mid-frame: skip it rather than failing the whole frame.
            }
        }
        horizontalPlanes = horizontal
        verticalPlanes = vertical
        if (cameraY != null) {
            if (floorLikeHitY != null) evidence.add(floorLikeHitY, nowNs)
            current = FloorSelection.next(current, candidates, cameraY) { y ->
                FloorSelection.isContradicted(y, cameraY, evidence.countBelow(y - FloorSelection.MAX_RAISE, nowNs))
            }
        }
        return current
    }
}

/** An upward-facing plane considered as the floor: center height (world Y) and polygon area (m²). */
internal data class FloorCandidate(val y: Double, val area: Double)

/** Pure floor-selection math (no ARCore types), unit-tested on the JVM. */
internal object FloorSelection {
    /**
     * A floor plane's center must be at least this far below the camera, meters. A phone held to scan is
     * 1.1–1.6 m above the floor, while counters, islands and tables are only 0.5–0.85 m below it.
     */
    const val MIN_DROP_BELOW_CAMERA = 0.8

    /** ... and at most this far (excludes reflections in glossy floors and a lower storey), meters. */
    const val MAX_DROP_BELOW_CAMERA = 2.2

    /**
     * Minimum plane area (m²). Keeps small spurious planes (glossy-floor reflections, a shoe box) from
     * winning just because they are low.
     */
    const val MIN_AREA = 0.5

    /**
     * Upward planes within this height of the lowest candidate are pieces of the same floor (ARCore often
     * splits one floor into patches a centimeter or two apart, or reports a rug separately); the largest
     * of them backs the estimate. Anything higher (bed, table, island, step) is ignored, meters.
     */
    const val SAME_FLOOR_TOLERANCE = 0.08

    /**
     * Over time the estimate is never raised by more than this onto a higher surface while the previous
     * floor is still plausibly below the camera ([MAX_KEEP_DROP]), e.g. when the floor plane stopped
     * tracking but a bed is still tracked, or while the phone is raised to the ceiling, meters.
     */
    const val MAX_RAISE = 0.25

    /**
     * The previous floor is considered "still plausible" while it is less than this far below the camera.
     * Beyond it the user went up a storey, and a higher floor may replace it, meters.
     */
    const val MAX_KEEP_DROP = 3.2

    /**
     * A plane less than this far below the camera (m) might be a bed or table top seen by a tall user;
     * only such planes can be vetoed by [isContradicted]. Real floors are this close only while the phone
     * is held unusually low, and glossy-floor reflections (which also look "lower") cannot veto a floor
     * seen from a normal height.
     */
    const val AMBIGUOUS_DROP = 1.2

    /** Floor-like crosshair hits more than [MAX_RAISE] below a plane needed to veto it. */
    const val MIN_CONTRADICTING_HITS = 5

    /**
     * Whether a plane at [planeY] is proven NOT to be the floor: it is ambiguous (less than
     * [AMBIGUOUS_DROP] below the camera) and [hitsWellBelow] recent floor-like crosshair hits lay more
     * than [MAX_RAISE] below it.
     */
    fun isContradicted(planeY: Double, cameraY: Double, hitsWellBelow: Int): Boolean =
        planeY.isFinite() && cameraY.isFinite() && cameraY - planeY < AMBIGUOUS_DROP &&
            hitsWellBelow >= MIN_CONTRADICTING_HITS

    /** Whether a plane at [planeY] lies at a plausible floor height below a camera at [cameraY]. */
    fun isInFloorRange(planeY: Double, cameraY: Double): Boolean {
        if (!planeY.isFinite() || !cameraY.isFinite()) return false
        val drop = cameraY - planeY
        return drop >= MIN_DROP_BELOW_CAMERA && drop <= MAX_DROP_BELOW_CAMERA
    }

    /**
     * The floor plane among this frame's [candidates]: the largest candidate within
     * [SAME_FLOOR_TOLERANCE] of the LOWEST candidate that has a finite area ≥ [MIN_AREA], lies in
     * [isInFloorRange] and is not [contradicted] (a height-based veto, see [isContradicted]).
     * Null if none qualifies.
     */
    fun select(
        candidates: List<FloorCandidate>,
        cameraY: Double,
        contradicted: (y: Double) -> Boolean = { false },
    ): FloorCandidate? {
        var lowest = Double.POSITIVE_INFINITY
        for (c in candidates) {
            if (qualifies(c, cameraY) && c.y < lowest && !contradicted(c.y)) lowest = c.y
        }
        if (lowest == Double.POSITIVE_INFINITY) return null
        var best: FloorCandidate? = null
        for (c in candidates) {
            if (!qualifies(c, cameraY) || c.y > lowest + SAME_FLOOR_TOLERANCE || contradicted(c.y)) continue
            if (best == null || c.area > best.area) best = c
        }
        return best
    }

    /**
     * One tracking step: the estimate after a frame with [candidates], given the [previous] estimate.
     * - No qualifying candidate: keep [previous] (so it does not flicker back to null), unless [previous]
     *   itself is now [contradicted] (it was furniture after all): then null.
     * - A pick more than [MAX_RAISE] above [previous] while [previous] is still less than [MAX_KEEP_DROP]
     *   below the camera: keep [previous] (furniture must not replace a floor that was already seen).
     * - Otherwise the pick (lower floors always win; small refinements are followed).
     */
    fun next(
        previous: FloorEstimate?,
        candidates: List<FloorCandidate>,
        cameraY: Double,
        contradicted: (y: Double) -> Boolean = { false },
    ): FloorEstimate? {
        val kept = previous?.takeIf { it.y.isFinite() && !contradicted(it.y) }
        val pick = select(candidates, cameraY, contradicted) ?: return kept
        if (kept != null && pick.y - kept.y > MAX_RAISE && cameraY - kept.y <= MAX_KEEP_DROP) return kept
        return FloorEstimate(pick.y, pick.area)
    }

    private fun qualifies(c: FloorCandidate, cameraY: Double): Boolean =
        c.area.isFinite() && c.area >= MIN_AREA && isInFloorRange(c.y, cameraY)

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

/**
 * Recent heights of floor-like surfaces seen at the crosshair (pure; GL thread only): a small ring buffer
 * whose entries expire after [maxAgeNs], so evidence from a room left long ago does not linger.
 */
internal class FloorEvidence(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAgeNs: Long = DEFAULT_MAX_AGE_NS,
) {
    private val heights = DoubleArray(capacity)
    private val times = LongArray(capacity)
    private var size = 0
    private var next = 0

    fun add(y: Double, nowNs: Long) {
        if (!y.isFinite() || capacity <= 0) return
        heights[next] = y
        times[next] = nowNs
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** Number of entries not older than [maxAgeNs] at [nowNs] whose height is below [y]. */
    fun countBelow(y: Double, nowNs: Long): Int {
        if (!y.isFinite()) return 0
        var count = 0
        for (i in 0 until size) {
            if (nowNs - times[i] <= maxAgeNs && heights[i] < y) count++
        }
        return count
    }

    private companion object {
        const val DEFAULT_CAPACITY = 30
        const val DEFAULT_MAX_AGE_NS = 10_000_000_000L
    }
}
