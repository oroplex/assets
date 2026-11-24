package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Ground-truth world used by the scan simulation: floor at Y = 0, ceiling at 2.44 m (the sample house). */
internal const val SIM_FLOOR_Y = 0.0
internal const val SIM_CEILING_Y = 2.44

/** Height (world Y) at which the simulated user holds the phone. */
internal const val CAMERA_HEIGHT = 1.4

private const val RAY_EPS = 1e-6

/**
 * A furniture box standing against wall [edge] of a room. In the wall frame (u along the wall from its
 * start corner [origin], v from the wall face into the room along [v]) it occupies u in [[u0], [u1]],
 * v in [0, [depth]] and heights [0, [height]] above the floor, i.e. its front face is [depth] in front of
 * the lower part of the wall.
 */
internal class FurnitureBox(
    val edge: Int,
    val origin: Vec2,
    val u: Vec2,
    val v: Vec2,
    val u0: Double,
    val u1: Double,
    val depth: Double,
    val height: Double,
) {
    /** Ray parameter of the first hit on the box (slab method in the wall frame), or null. */
    fun raycast(o: Vec3, d: Vec3, floorY: Double): Double? {
        val op = o.toPlan() - origin
        val dp = Vec2(d.x, -d.z)
        val lo = doubleArrayOf(op dot u, op dot v, o.y - floorY)
        val ld = doubleArrayOf(dp dot u, dp dot v, d.y)
        val mins = doubleArrayOf(u0, 0.0, 0.0)
        val maxs = doubleArrayOf(u1, depth, height)
        var tNear = Double.NEGATIVE_INFINITY
        var tFar = Double.POSITIVE_INFINITY
        for (k in 0 until 3) {
            if (abs(ld[k]) < 1e-12) {
                if (lo[k] < mins[k] || lo[k] > maxs[k]) return null
                continue
            }
            var t1 = (mins[k] - lo[k]) / ld[k]
            var t2 = (maxs[k] - lo[k]) / ld[k]
            if (t1 > t2) t1 = t2.also { t2 = t1 }
            tNear = max(tNear, t1)
            tFar = min(tFar, t2)
            if (tNear > tFar) return null
        }
        // A camera inside the box sees nothing of it (cannot happen for valid stations).
        return if (tNear > RAY_EPS) tNear else null
    }

    /** True when the plan point lies on the box footprint (grown by [margin]). */
    fun footprintContains(p: Vec2, margin: Double = 0.0): Boolean {
        val q = p - origin
        val a = q dot u
        val b = q dot v
        return a >= u0 - margin && a <= u1 + margin && b >= -margin && b <= depth + margin
    }
}

/** A ray hit: distance [t] and [surface] = wall (edge) index, or one of the negative constants. */
internal class RayHit(val t: Double, val surface: Int) {
    companion object {
        const val NONE = -100
        const val FLOOR = -1
        const val CEILING = -2
        const val FURNITURE = -3
    }
}

/**
 * A room as a vertical prism: walls = the CCW interior outline [corners] extruded from the floor to the
 * ceiling, plus optional [furniture]. Edge i runs corners[i] -> corners[i + 1]; the interior is on its left.
 */
internal class SimRoom(
    val name: String,
    val corners: List<Vec2>,
    val furniture: List<FurnitureBox> = emptyList(),
    val floorY: Double = SIM_FLOOR_Y,
    val ceilingY: Double = SIM_CEILING_Y,
) {
    init {
        require(corners.size >= 3 && Polygons.signedArea(corners) > 0) { "$name: outline must be CCW" }
    }

    val size: Int get() = corners.size

    fun start(i: Int): Vec2 = corners[Math.floorMod(i, size)]

    fun end(i: Int): Vec2 = corners[Math.floorMod(i + 1, size)]

    fun length(i: Int): Double = start(i).distanceTo(end(i))

    fun direction(i: Int): Vec2 = (end(i) - start(i)).normalized()

    /** Unit normal of edge [i] pointing into the room (CCW outline: the left side). */
    fun inwardNormal(i: Int): Vec2 = direction(i).perp()

    fun pointOn(i: Int, s: Double): Vec2 = Vec2.lerp(start(i), end(i), s)

    /** Perpendicular distance of [p] in front of the (infinite) face of wall [i]; negative behind it. */
    fun frontDistance(i: Int, p: Vec2): Double = (p - start(i)) dot inwardNormal(i)

    val pole: Vec2 by lazy { Polygons.poleOfInaccessibility(corners, 0.002) }

    /** Distance to the nearest wall when [p] is inside the room, negative outside. */
    fun clearance(p: Vec2): Double = Polygons.signedDistanceToOutline(corners, p)

    fun withFurniture(boxes: List<FurnitureBox>) = SimRoom(name, corners, boxes, floorY, ceilingY)

    /**
     * First surface hit along the ray [o] + t·[d] ([d] unit): walls, floor, ceiling and furniture.
     * Returns t (= distance, since [d] is unit) and the surface that was hit, or null when nothing is hit.
     */
    fun hit(o: Vec3, d: Vec3): RayHit? {
        var best = Double.POSITIVE_INFINITY
        var surface = RayHit.NONE
        if (d.y < -1e-12) {
            val t = (floorY - o.y) / d.y
            if (t > RAY_EPS && t < best) {
                best = t
                surface = RayHit.FLOOR
            }
        }
        if (d.y > 1e-12) {
            val t = (ceilingY - o.y) / d.y
            if (t > RAY_EPS && t < best) {
                best = t
                surface = RayHit.CEILING
            }
        }
        val op = o.toPlan()
        val dp = Vec2(d.x, -d.z)
        for (i in 0 until size) {
            val t = rayHitsSegment(op, dp, start(i), end(i)) ?: continue
            if (t > RAY_EPS && t < best) {
                val y = o.y + d.y * t
                if (y >= floorY && y <= ceilingY) {
                    best = t
                    surface = i
                }
            }
        }
        for (b in furniture) {
            val t = b.raycast(o, d, floorY) ?: continue
            if (t < best) {
                best = t
                surface = RayHit.FURNITURE
            }
        }
        return if (best.isFinite()) RayHit(best, surface) else null
    }

    fun raycast(o: Vec3, d: Vec3): Double? = hit(o, d)?.t

    /** True when the straight plan segment [from] -> [to] crosses no wall other than [targetEdge]. */
    fun lineOfSight(from: Vec2, to: Vec2, targetEdge: Int): Boolean {
        val d = to - from
        for (i in 0 until size) {
            if (i == Math.floorMod(targetEdge, size)) continue
            val t = rayHitsSegment(from, d, start(i), end(i)) ?: continue
            if (t > 1e-6 && t < 1 - 1e-6) return false
        }
        return true
    }

    /** Free distance from the face of wall [i] at [p] along its inward normal to the opposite boundary. */
    fun depthInFront(i: Int, p: Vec2): Double {
        val n = inwardNormal(i)
        val o = p + n * 1e-4
        var best = Double.POSITIVE_INFINITY
        for (k in 0 until size) {
            if (k == Math.floorMod(i, size)) continue
            val t = rayHitsSegment(o, n, start(k), end(k)) ?: continue
            if (t > 1e-6 && t < best) best = t
        }
        return best
    }

    companion object {
        /** Ray o + t·d (t > 0) against segment a-b in the plan; d need not be unit. */
        fun rayHitsSegment(o: Vec2, d: Vec2, a: Vec2, b: Vec2): Double? {
            val e = b - a
            val denom = d cross e
            if (abs(denom) < 1e-12) return null
            val w = a - o
            val t = (w cross e) / denom
            val s = (w cross d) / denom
            if (s < -1e-9 || s > 1 + 1e-9 || t <= 0.0) return null
            return t
        }
    }
}
