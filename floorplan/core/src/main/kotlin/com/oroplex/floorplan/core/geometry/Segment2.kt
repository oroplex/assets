package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable

@Serializable
data class Segment2(val a: Vec2, val b: Vec2) {
    val length: Double get() = a.distanceTo(b)
    val midpoint: Vec2 get() = Vec2.lerp(a, b, 0.5)

    /** Unit direction a -> b (ZERO if degenerate). */
    val direction: Vec2 get() = (b - a).normalized()

    fun toLine(): Line2 = Line2.through(a, b)

    /** Closest point on the segment to [p]. */
    fun closestPoint(p: Vec2): Vec2 {
        val ab = b - a
        val l2 = ab.lengthSquared
        if (l2 < 1e-18) return a
        val t = ((p - a) dot ab / l2).coerceIn(0.0, 1.0)
        return a + ab * t
    }

    fun distanceTo(p: Vec2): Double = closestPoint(p).distanceTo(p)

    /**
     * True when the two segments properly intersect or touch.
     * Shared endpoints count as an intersection.
     */
    fun intersects(o: Segment2, eps: Double = 1e-12): Boolean {
        val d1 = orient(o.a, o.b, a)
        val d2 = orient(o.a, o.b, b)
        val d3 = orient(a, b, o.a)
        val d4 = orient(a, b, o.b)
        if (((d1 > eps && d2 < -eps) || (d1 < -eps && d2 > eps)) &&
            ((d3 > eps && d4 < -eps) || (d3 < -eps && d4 > eps))
        ) return true
        if (kotlin.math.abs(d1) <= eps && onSegment(o.a, o.b, a)) return true
        if (kotlin.math.abs(d2) <= eps && onSegment(o.a, o.b, b)) return true
        if (kotlin.math.abs(d3) <= eps && onSegment(a, b, o.a)) return true
        if (kotlin.math.abs(d4) <= eps && onSegment(a, b, o.b)) return true
        return false
    }

    private fun orient(p: Vec2, q: Vec2, r: Vec2): Double = (q - p) cross (r - p)

    private fun onSegment(p: Vec2, q: Vec2, r: Vec2): Boolean =
        r.x <= maxOf(p.x, q.x) + 1e-12 && r.x >= minOf(p.x, q.x) - 1e-12 &&
            r.y <= maxOf(p.y, q.y) + 1e-12 && r.y >= minOf(p.y, q.y) - 1e-12
}
