package com.oroplex.floorplan.core.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Overlap area of two simple polygons that may be non-convex (L-, U-, T-shaped rooms).
 *
 * Method: each polygon is split into triangles by ear clipping, then every pair of triangles is intersected
 * with Sutherland–Hodgman clipping (exact for convex pieces) and the pieces' areas are summed. Because the
 * triangles of one polygon tile it without overlapping, the sum is the exact intersection area.
 *
 * Inputs follow the usual polygon convention (no repeated closing vertex) and may be in either orientation.
 * When a polygon cannot be triangulated (self-intersecting or numerically degenerate input), the area is
 * estimated by sampling a fine grid with the even-odd rule instead, so callers always get a usable number.
 */
object PolygonClipping {
    /** Grid resolution (per axis) of the sampling fallback. */
    private const val SAMPLES = 200

    /**
     * Triangles (each counter-clockwise, 3 vertices) covering [pts] exactly, or null when the polygon is
     * not simple enough to triangulate. Fewer than 3 distinct vertices or zero area give an empty list.
     * Collinear and duplicate vertices are tolerated.
     */
    fun triangulate(pts: List<Vec2>): List<List<Vec2>>? {
        if (pts.any { !it.isFinite() }) return null
        val clean = withoutDuplicates(pts)
        if (clean.size < 3) return emptyList()
        // Self-intersecting outlines (even a symmetric bowtie, whose signed area is zero) cannot be tiled.
        if (!Polygons.isSimple(clean)) return null
        val area = Polygons.signedArea(clean)
        val b = Bounds.of(clean)
        val scale = max(b.width, b.height)
        if (!area.isFinite() || scale <= 0.0) return null
        val eps = 1e-12 * scale * scale
        if (abs(area) <= eps) return emptyList()
        val p = if (area < 0) clean.reversed() else clean

        val remaining = p.indices.toMutableList()
        val out = ArrayList<List<Vec2>>(p.size)
        var guard = 0
        while (remaining.size > 3) {
            if (guard++ > 4 * p.size) return null
            val m = remaining.size
            var clipped = false
            for (k in 0 until m) {
                val a = p[remaining[(k - 1 + m) % m]]
                val c = p[remaining[k]]
                val d = p[remaining[(k + 1) % m]]
                val turn = (c - a) cross (d - c)
                if (abs(turn) <= eps) {
                    // Collinear (straight-through vertex or zero-width spike): dropping it keeps the area.
                    remaining.removeAt(k)
                    clipped = true
                    break
                }
                if (turn < 0) continue // reflex vertex
                val blocked = remaining.any { idx ->
                    val q = p[idx]
                    !q.approxEquals(a, 1e-12) && !q.approxEquals(c, 1e-12) && !q.approxEquals(d, 1e-12) &&
                        inTriangle(q, a, c, d, eps)
                }
                if (blocked) continue
                out += listOf(a, c, d)
                remaining.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) return null
        }
        val last = remaining.map { p[it] }
        if (Polygons.signedArea(last) > eps) out += last
        // A triangulation that does not add up to the polygon's area means the input was not simple.
        val sum = out.sumOf { Polygons.signedArea(it) }
        return if (abs(sum - abs(area)) <= 1e-9 * max(1.0, abs(area))) out else null
    }

    /**
     * Intersection of the convex polygon [subject] with the convex polygon [clip] (both counter-clockwise),
     * by Sutherland–Hodgman. Empty when they do not overlap.
     */
    fun clipConvex(subject: List<Vec2>, clip: List<Vec2>): List<Vec2> {
        var output = subject
        for (i in clip.indices) {
            if (output.isEmpty()) break
            val c1 = clip[i]
            val c2 = clip[(i + 1) % clip.size]
            val edge = c2 - c1
            val input = output
            val next = ArrayList<Vec2>(input.size + 2)
            for (j in input.indices) {
                val s = input[(j - 1 + input.size) % input.size]
                val e = input[j]
                val sIn = (edge cross (s - c1)) >= 0.0
                val eIn = (edge cross (e - c1)) >= 0.0
                if (eIn) {
                    if (!sIn) crossing(s, e, c1, edge)?.let { next += it }
                    next += e
                } else if (sIn) {
                    crossing(s, e, c1, edge)?.let { next += it }
                }
            }
            output = next
        }
        return if (output.size >= 3) output else emptyList()
    }

    /**
     * Area (m²) of the region inside both [a] and [b]. Zero for disjoint, merely touching or degenerate
     * polygons; never negative and never NaN.
     */
    fun intersectionArea(a: List<Vec2>, b: List<Vec2>): Double {
        if (a.size < 3 || b.size < 3) return 0.0
        val fa = a.filter { it.isFinite() }
        val fb = b.filter { it.isFinite() }
        if (fa.size < 3 || fb.size < 3) return 0.0
        val ba = Bounds.of(fa)
        val bb = Bounds.of(fb)
        if (ba.maxX <= bb.minX || bb.maxX <= ba.minX || ba.maxY <= bb.minY || bb.maxY <= ba.minY) return 0.0
        val ta = if (fa.size == a.size) triangulate(fa) else null
        val tb = if (fb.size == b.size) triangulate(fb) else null
        if (ta == null || tb == null) return sampledIntersectionArea(fa, fb)
        var sum = 0.0
        for (t1 in ta) {
            val b1 = Bounds.of(t1)
            for (t2 in tb) {
                val b2 = Bounds.of(t2)
                if (b1.maxX <= b2.minX || b2.maxX <= b1.minX || b1.maxY <= b2.minY || b2.maxY <= b1.minY) continue
                sum += Polygons.area(clipConvex(t1, t2))
            }
        }
        return if (sum.isFinite()) max(0.0, sum) else 0.0
    }

    /** Even-odd grid estimate of the intersection area; the fallback for polygons that cannot be triangulated. */
    internal fun sampledIntersectionArea(a: List<Vec2>, b: List<Vec2>): Double {
        val ba = Bounds.of(a)
        val bb = Bounds.of(b)
        val minX = max(ba.minX, bb.minX)
        val maxX = min(ba.maxX, bb.maxX)
        val minY = max(ba.minY, bb.minY)
        val maxY = min(ba.maxY, bb.maxY)
        if (!(maxX > minX && maxY > minY)) return 0.0
        val dx = (maxX - minX) / SAMPLES
        val dy = (maxY - minY) / SAMPLES
        var count = 0
        for (i in 0 until SAMPLES) {
            val x = minX + (i + 0.5) * dx
            for (j in 0 until SAMPLES) {
                val q = Vec2(x, minY + (j + 0.5) * dy)
                if (Polygons.contains(a, q) && Polygons.contains(b, q)) count++
            }
        }
        return count * dx * dy
    }

    private fun withoutDuplicates(pts: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>(pts.size)
        for (p in pts) if (out.isEmpty() || !out.last().approxEquals(p, 1e-12)) out += p
        while (out.size > 1 && out.first().approxEquals(out.last(), 1e-12)) out.removeAt(out.size - 1)
        return out
    }

    /** Inside or on the boundary of the counter-clockwise triangle (a, b, c). */
    private fun inTriangle(q: Vec2, a: Vec2, b: Vec2, c: Vec2, eps: Double): Boolean =
        ((b - a) cross (q - a)) >= -eps && ((c - b) cross (q - b)) >= -eps && ((a - c) cross (q - c)) >= -eps

    /** Point where segment s -> e crosses the line through [c1] with direction [edge], or null if parallel. */
    private fun crossing(s: Vec2, e: Vec2, c1: Vec2, edge: Vec2): Vec2? {
        val d = e - s
        val denom = edge cross d
        if (abs(denom) < 1e-18) return null
        val t = ((s - c1) cross edge) / denom
        return s + d * t.coerceIn(0.0, 1.0)
    }
}
