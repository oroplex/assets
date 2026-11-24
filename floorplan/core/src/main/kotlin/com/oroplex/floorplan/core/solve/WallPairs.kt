package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min

/**
 * Two walls of different rooms that face each other across a partition: room A's wall [edge] and room B's
 * wall [otherEdge] run in opposite directions (both outlines counter-clockwise, interiors on opposite sides).
 *
 * @property angleError angle (radians) between A's wall direction and the reverse of B's.
 * @property gap distance between the two interior faces along B's outward normal, measured at the middle of
 *   the overlap; it is about one wall thickness for neighbours and negative when A's face is inside B.
 * @property overlap length (m) of the stretch where the two walls face each other.
 * @property pivot point on A's wall at the middle of that stretch.
 * @property outward B's outward unit normal at the wall (from B's interior toward A).
 * @property targetDirection the reverse of B's wall direction: A's wall direction once exactly parallel.
 * @property direction A's current wall direction.
 */
internal data class FacingWalls(
    val edge: Int,
    val otherEdge: Int,
    val angleError: Double,
    val gap: Double,
    val overlap: Double,
    val pivot: Vec2,
    val outward: Vec2,
    val targetDirection: Vec2,
    val direction: Vec2,
)

internal object WallPairs {
    /** Walls shorter than this (m) are ignored when pairing (small jogs and returns). */
    const val MIN_WALL = 0.2

    /** Walls must face each other along at least this length (m) to count as a pair. */
    const val MIN_OVERLAP = 0.15

    /**
     * [corners] as a counter-clockwise outline when it is usable (at least 3 finite corners, simple, non-zero
     * area), else null. Clockwise outlines are reversed, so edge indices then refer to the reversed list.
     */
    fun ccwOutline(corners: List<Vec2>): List<Vec2>? {
        if (corners.size < 3 || corners.any { !it.isFinite() }) return null
        val area = Polygons.signedArea(corners)
        if (!area.isFinite() || abs(area) < 1e-6 || !Polygons.isSimple(corners)) return null
        return if (area > 0) corners else corners.reversed()
    }

    /**
     * Every pair (wall of [a], wall of [b]) whose directions are opposite within [maxAngle] radians and that
     * face each other along at least [MIN_OVERLAP]. Both outlines must be counter-clockwise. The gap is not
     * filtered here, so callers decide what "close enough" means.
     */
    fun facing(a: List<Vec2>, b: List<Vec2>, maxAngle: Double): List<FacingWalls> {
        val out = ArrayList<FacingWalls>()
        for (i in a.indices) {
            val a1 = a[i]
            val a2 = a[(i + 1) % a.size]
            val la = a1.distanceTo(a2)
            if (la < MIN_WALL) continue
            val da = (a2 - a1) / la
            for (j in b.indices) {
                val b1 = b[j]
                val b2 = b[(j + 1) % b.size]
                val lb = b1.distanceTo(b2)
                if (lb < MIN_WALL) continue
                val db = (b2 - b1) / lb
                val cos = -(da dot db)
                if (cos <= 0.0) continue
                val angle = acos(cos.coerceAtMost(1.0))
                if (angle > maxAngle) continue
                // Stretch of B's wall covered by A's wall, in B's wall parameter.
                val s1 = (a1 - b1) dot db
                val s2 = (a2 - b1) dot db
                val lo = max(min(s1, s2), 0.0)
                val hi = min(max(s1, s2), lb)
                val overlap = hi - lo
                if (overlap < MIN_OVERLAP) continue
                val onB = b1 + db * ((lo + hi) / 2)
                val pivot = a1 + da * ((onB - a1) dot da)
                val outward = -db.perp()
                out += FacingWalls(
                    edge = i,
                    otherEdge = j,
                    angleError = angle,
                    gap = (pivot - onB) dot outward,
                    overlap = overlap,
                    pivot = pivot,
                    outward = outward,
                    targetDirection = -db,
                    direction = da,
                )
            }
        }
        return out
    }
}
