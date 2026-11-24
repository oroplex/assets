package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Minimum-area oriented bounding box. [length] >= [width]; [angle] is the direction of the length axis. */
@Serializable
data class OrientedBox(val center: Vec2, val angle: Double, val length: Double, val width: Double)

/** Polygon utilities. Polygons are vertex lists without a repeated closing vertex. */
object Polygons {

    fun edges(pts: List<Vec2>): List<Segment2> =
        if (pts.size < 2) emptyList() else pts.indices.map { Segment2(pts[it], pts[(it + 1) % pts.size]) }

    /** Shoelace signed area; > 0 for counter-clockwise. */
    fun signedArea(pts: List<Vec2>): Double {
        if (pts.size < 3) return 0.0
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2
    }

    fun area(pts: List<Vec2>): Double = abs(signedArea(pts))

    fun perimeter(pts: List<Vec2>): Double = edges(pts).sumOf { it.length }

    fun isCounterClockwise(pts: List<Vec2>): Boolean = signedArea(pts) > 0

    fun ensureCounterClockwise(pts: List<Vec2>): List<Vec2> = if (signedArea(pts) < 0) pts.reversed() else pts

    /** Area-weighted centroid (falls back to vertex average for degenerate polygons). */
    fun centroid(pts: List<Vec2>): Vec2 {
        if (pts.isEmpty()) return Vec2.ZERO
        val a = signedArea(pts)
        if (abs(a) < 1e-12) return pts.fold(Vec2.ZERO) { acc, p -> acc + p } / pts.size.toDouble()
        var cx = 0.0
        var cy = 0.0
        for (i in pts.indices) {
            val p = pts[i]
            val q = pts[(i + 1) % pts.size]
            val f = p.x * q.y - q.x * p.y
            cx += (p.x + q.x) * f
            cy += (p.y + q.y) * f
        }
        return Vec2(cx / (6 * a), cy / (6 * a))
    }

    /** True when no two non-adjacent edges intersect and no edge is degenerate. */
    fun isSimple(pts: List<Vec2>): Boolean {
        val n = pts.size
        if (n < 3) return false
        val e = edges(pts)
        if (e.any { it.length < 1e-9 }) return false
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val adjacent = j == i + 1 || (i == 0 && j == n - 1)
                if (adjacent) continue
                if (e[i].intersects(e[j])) return false
            }
        }
        return true
    }

    /** Even-odd point-in-polygon test. */
    fun contains(pts: List<Vec2>, p: Vec2): Boolean {
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Signed distance from [p] to the polygon outline: positive inside, negative outside. */
    fun signedDistanceToOutline(pts: List<Vec2>, p: Vec2): Double {
        val d = edges(pts).minOf { it.distanceTo(p) }
        return if (contains(pts, p)) d else -d
    }

    /**
     * Offset a simple polygon. Positive [distance] grows a counter-clockwise polygon outward
     * (and shrinks a clockwise one). Sharp corners whose miter would exceed [miterLimit] * |distance|
     * are beveled (two vertices instead of one), so the output can have more vertices than the input.
     */
    fun offset(pts: List<Vec2>, distance: Double, miterLimit: Double = 4.0): List<Vec2> {
        val n = pts.size
        if (n < 3 || distance == 0.0) return pts
        val ccw = signedArea(pts) > 0
        // For a CCW polygon the outward normal of edge a->b is (dir rotated -90deg) = -perp.
        fun outward(a: Vec2, b: Vec2): Vec2 {
            val d = (b - a).normalized()
            val left = d.perp()
            return if (ccw) -left else left
        }
        val out = ArrayList<Vec2>(n + 4)
        for (i in 0 until n) {
            val prev = pts[(i - 1 + n) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val n1 = outward(prev, cur)
            val n2 = outward(cur, next)
            val l1 = Line2.of(prev + n1 * distance, cur - prev)
            val l2 = Line2.of(cur + n2 * distance, next - cur)
            val hit = l1.intersect(l2, 1e-9)
            if (hit == null) {
                out += cur + n1 * distance // collinear edges
                continue
            }
            if (hit.distanceTo(cur) > miterLimit * abs(distance)) {
                out += cur + n1 * distance
                out += cur + n2 * distance
            } else {
                out += hit
            }
        }
        return out
    }

    fun bounds(pts: List<Vec2>): Bounds = Bounds.of(pts)

    /** Minimum-area oriented bounding box, testing every edge direction (exact for convex hulls). */
    fun orientedBoundingBox(pts: List<Vec2>): OrientedBox {
        require(pts.isNotEmpty())
        if (pts.size == 1) return OrientedBox(pts[0], 0.0, 0.0, 0.0)
        var best: OrientedBox? = null
        var bestArea = Double.POSITIVE_INFINITY
        val candidates = edges(pts).filter { it.length > 1e-9 }.map { (it.b - it.a).angle() }.ifEmpty { listOf(0.0) }
        for (theta in candidates) {
            val rot = pts.map { it.rotated(-theta) }
            val b = Bounds.of(rot)
            val area = b.width * b.height
            if (area < bestArea - 1e-12) {
                bestArea = area
                val center = b.center.rotated(theta)
                best = if (b.width >= b.height) OrientedBox(center, normalizeAngle(theta), b.width, b.height)
                else OrientedBox(center, normalizeAngle(theta + PI / 2), b.height, b.width)
            }
        }
        return best!!
    }

    /**
     * "Pole of inaccessibility" (polylabel): the interior point farthest from the outline.
     * Best place for a room label in L-shaped / irregular rooms. [precision] in meters.
     */
    fun poleOfInaccessibility(pts: List<Vec2>, precision: Double = 0.01): Vec2 {
        if (pts.size < 3) return centroid(pts)
        val b = Bounds.of(pts)
        val cellSize = min(b.width, b.height)
        if (cellSize <= 1e-9) return centroid(pts)
        class Cell(val c: Vec2, val h: Double) {
            val d = signedDistanceToOutline(pts, c)
            val max = d + h * sqrt(2.0)
        }
        val queue = PriorityQueue<Cell>(compareByDescending { it.max })
        var h = cellSize / 2
        var x = b.minX
        while (x < b.maxX) {
            var y = b.minY
            while (y < b.maxY) {
                queue.add(Cell(Vec2(x + h, y + h), h))
                y += cellSize
            }
            x += cellSize
        }
        var best = Cell(centroid(pts), 0.0)
        val bboxCell = Cell(b.center, 0.0)
        if (bboxCell.d > best.d) best = bboxCell
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 100_000) {
            val cell = queue.poll()
            if (cell.d > best.d) best = cell
            if (cell.max - best.d <= precision) continue
            h = cell.h / 2
            queue.add(Cell(Vec2(cell.c.x - h, cell.c.y - h), h))
            queue.add(Cell(Vec2(cell.c.x + h, cell.c.y - h), h))
            queue.add(Cell(Vec2(cell.c.x - h, cell.c.y + h), h))
            queue.add(Cell(Vec2(cell.c.x + h, cell.c.y + h), h))
        }
        return best.c
    }

    /**
     * Interior angle at each vertex in radians, in the caller's vertex order.
     * Convex corners are < PI, reflex corners (e.g. the inside corner of an L) are > PI.
     */
    fun interiorAngles(pts: List<Vec2>): List<Double> {
        val n = pts.size
        if (n < 3) return emptyList()
        val wasCw = signedArea(pts) < 0
        val p = if (wasCw) pts.reversed() else pts
        val ccwAngles = (0 until n).map { i ->
            val prev = p[(i - 1 + n) % n]
            val cur = p[i]
            val next = p[(i + 1) % n]
            var ang = (prev - cur).angle() - (next - cur).angle()
            while (ang <= 0) ang += 2 * PI
            while (ang > 2 * PI) ang -= 2 * PI
            ang
        }
        return if (wasCw) List(n) { i -> ccwAngles[n - 1 - i] } else ccwAngles
    }

    /** Wrap to (-PI, PI]. */
    fun normalizeAngle(a: Double): Double {
        var x = a % (2 * PI)
        if (x <= -PI) x += 2 * PI
        if (x > PI) x -= 2 * PI
        return x
    }
}
