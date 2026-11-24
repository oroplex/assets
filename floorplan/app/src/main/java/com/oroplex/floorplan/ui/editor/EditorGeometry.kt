package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.TextAlign
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.solve.RoomSolver
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Canvas hit-testing in the editor. Screen tolerances are in pixels and converted to plan meters with
 * the current [ViewTransform], so targets stay finger-sized at every zoom level.
 */
object EditorHitTest {

    /** Index of the corner of [room] nearest to [screen] within [radiusPx], or null. */
    fun cornerAt(room: Room, screen: Vec2, view: ViewTransform, radiusPx: Double): Int? =
        room.corners.indices
            .filter { room.corners[it].isFinite() }
            .map { it to view.toScreen(room.corners[it]).distanceTo(screen) }
            .filter { it.second <= radiusPx }
            .minByOrNull { it.second }
            ?.first

    /**
     * Wall-length label (DrawOp.Text tagged "wall:<roomId>:<edgeIndex>") under [screen], or null. The label's
     * box is reconstructed in its rotated frame exactly as PlanPainter lays it out; [measureTextPx] returns
     * the rendered width of a label in pixels, and [slopPx] enlarges the box for fingers.
     */
    fun wallLabelAt(
        drawList: DrawList,
        screen: Vec2,
        view: ViewTransform,
        slopPx: Double,
        measureTextPx: (DrawOp.Text, Double) -> Double,
    ): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (op in drawList.ops) {
            if (op !is DrawOp.Text) continue
            val target = parseWallTag(op.tag) ?: continue
            if (!op.anchor.isFinite()) continue
            val sizePx = op.sizePt * view.pxPerPt
            val widthPx = measureTextPx(op, sizePx)
            val a = view.toScreen(op.anchor)
            // Into the label frame: undo the canvas rotation (clockwise degrees in screen space)...
            val theta = Math.toRadians(view.screenDegrees(op.angle))
            val d = screen - a
            val u = d.x * cos(theta) + d.y * sin(theta)
            val v = -d.x * sin(theta) + d.y * cos(theta)
            // ...then the offset (+y in points = toward the text's up = negative frame y).
            val x = u - op.offsetPt.x * view.pxPerPt
            val y = v + op.offsetPt.y * view.pxPerPt
            val (left, right) = when (op.align) {
                TextAlign.START -> 0.0 to widthPx
                TextAlign.CENTER -> -widthPx / 2 to widthPx / 2
                TextAlign.END -> -widthPx to 0.0
            }
            val half = sizePx / 2
            if (x in (left - slopPx)..(right + slopPx) && y in (-half - slopPx)..(half + slopPx)) {
                val cx = (left + right) / 2
                val dist = Vec2(x - cx, y).length
                if (dist < bestDistance) {
                    bestDistance = dist
                    best = target
                }
            }
        }
        return best
    }

    /**
     * Wall edge near plan point [p]: within [tolerance] meters of the interior face, or anywhere on the wall
     * band (up to [wallThickness] outside the face) plus [tolerance]. Inside a room the tolerance is capped
     * at 30 % of the room's narrow dimension so tapping the middle of a small closet still selects the room.
     * Walls of [preferredRoomId] win over other rooms' walls; otherwise the closest wall wins.
     */
    fun wallAt(
        rooms: List<Room>,
        p: Vec2,
        tolerance: Double,
        wallThickness: Double,
        preferredRoomId: String?,
    ): Pair<String, Int>? {
        data class Hit(val roomId: String, val index: Int, val distance: Double)
        val hits = ArrayList<Hit>()
        for (room in rooms) {
            val c = room.corners
            if (c.size < 3 || c.any { !it.isFinite() }) continue
            val insideTolerance = min(tolerance, 0.3 * narrowDimension(c))
            val inside = Polygons.contains(c, p)
            for (i in c.indices) {
                val seg = Segment2(c[i], c[(i + 1) % c.size])
                if (seg.length < 1e-9) continue
                val d = seg.distanceTo(p)
                val effective = if (inside) {
                    if (d <= insideTolerance) d else continue
                } else {
                    // Outside the interior outline: the dark wall band itself counts as the wall.
                    val e = (d - wallThickness).coerceAtLeast(0.0)
                    if (e <= tolerance) e else continue
                }
                hits += Hit(room.id, i, effective)
            }
        }
        if (hits.isEmpty()) return null
        val preferred = hits.filter { it.roomId == preferredRoomId }
        val best = (preferred.ifEmpty { hits }).minBy { it.distance }
        return best.roomId to best.index
    }

    /** Room whose interior contains [p]; the smallest one wins when outlines overlap. */
    fun roomAt(rooms: List<Room>, p: Vec2): String? =
        rooms.filter { it.corners.size >= 3 && it.corners.all { c -> c.isFinite() } && Polygons.contains(it.corners, p) }
            .minByOrNull { Polygons.area(it.corners) }
            ?.id

    /** Parses "wall:<roomId>:<edgeIndex>" (room ids never contain the last ':'). */
    fun parseWallTag(tag: String?): Pair<String, Int>? {
        if (tag == null || !tag.startsWith("wall:")) return null
        val rest = tag.removePrefix("wall:")
        val sep = rest.lastIndexOf(':')
        if (sep <= 0) return null
        val index = rest.substring(sep + 1).toIntOrNull() ?: return null
        return rest.substring(0, sep) to index
    }

    /** Narrow side of the room's oriented bounding box; 0 for geometry the box cannot be computed for. */
    private fun narrowDimension(c: List<Vec2>): Double = try {
        Polygons.orientedBoundingBox(c).width
    } catch (e: RuntimeException) {
        0.0
    }
}

/** Placement and naming helpers for rooms created in the editor. */
object RoomPlacement {
    /** Gap between the existing floor and a newly drawn room, meters (outer wall face to outer wall face). */
    const val NEW_ROOM_GAP = 0.5

    /**
     * Corners (CCW) of a [width] (x) by [length] (y) rectangle placed to the right of everything on the
     * floor: its left interior face sits [NEW_ROOM_GAP] plus both wall bands to the right of the floor's
     * rightmost interior face, top-aligned with the floor. An empty floor gets the rectangle at the origin.
     */
    fun rectangleRightOf(existing: List<Room>, width: Double, length: Double, wallThickness: Double): List<Vec2> {
        val bounds = Bounds.of(existing.flatMap { r -> r.corners.filter { it.isFinite() } })
        val x0: Double
        val y0: Double
        if (bounds.isEmpty) {
            x0 = 0.0
            y0 = 0.0
        } else {
            x0 = bounds.maxX + 2 * wallThickness + NEW_ROOM_GAP
            y0 = bounds.maxY - length
        }
        return listOf(Vec2(x0, y0), Vec2(x0 + width, y0), Vec2(x0 + width, y0 + length), Vec2(x0, y0 + length))
    }

    /** "Bedroom", or "Bedroom 2", "Bedroom 3"... when that name is taken on the floor. */
    fun uniqueName(type: RoomType, existing: List<Room>): String {
        val base = type.displayName
        val taken = existing.map { it.name.trim().lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }

    /** True when [name] is a default name for [type] ("Bedroom" or "Bedroom 3"). */
    fun isDefaultName(name: String, type: RoomType): Boolean {
        val base = type.displayName
        val trimmed = name.trim()
        if (trimmed.equals(base, ignoreCase = true)) return true
        if (!trimmed.startsWith("$base ", ignoreCase = true)) return false
        return trimmed.substring(base.length + 1).toIntOrNull() != null
    }
}

/**
 * Snapping of a dragged corner so walls stay square: in the room's own axis frame (its dominant direction
 * [axisAngle]), each coordinate of [target] within [tolerance] meters of the same coordinate of an adjacent
 * corner is snapped onto it, which makes that wall exactly parallel to an axis.
 */
object CornerSnap {
    fun snap(corners: List<Vec2>, index: Int, target: Vec2, axisAngle: Double, tolerance: Double): Vec2 {
        val n = corners.size
        if (n < 3 || index !in corners.indices || !target.isFinite() || !axisAngle.isFinite()) return target
        val neighbors = listOf(corners[(index - 1 + n) % n], corners[(index + 1) % n]).map { it.rotated(-axisAngle) }
        val t = target.rotated(-axisAngle)
        var x = t.x
        var y = t.y
        neighbors.minByOrNull { abs(it.x - t.x) }?.let { if (abs(it.x - t.x) <= tolerance) x = it.x }
        neighbors.minByOrNull { abs(it.y - t.y) }?.let { if (abs(it.y - t.y) <= tolerance) y = it.y }
        return Vec2(x, y).rotated(axisAngle)
    }

    /** Dominant wall direction of [corners] (the oriented bounding box's long axis), radians. */
    fun axisAngle(corners: List<Vec2>): Double {
        val pts = corners.filter { it.isFinite() }
        if (pts.size < 2) return 0.0
        return try {
            Polygons.orientedBoundingBox(pts).angle
        } catch (e: RuntimeException) {
            0.0
        }
    }
}

/**
 * Snapping of a dragged corner onto alignment guides, like a CAD tool's object snaps.
 *
 * Guides for corner C = corners[index] with neighbours P (previous) and N (next), whose other neighbours are
 * PP and NN:
 *  * [Kind.RIGHT_ANGLE]: the line through P perpendicular to PP→P (square corner at P), the same at N, and the
 *    circle with diameter P–N (Thales: square corner at C itself);
 *  * [Kind.AXIS]: lines through P and N along the room's main axis and its perpendicular (walls parallel to the
 *    room's other walls);
 *  * [Kind.STRAIGHT]: the line PP→P extended (C continues that wall in a straight line), the same at N;
 *  * [Kind.ALIGN]: lines along both room axes through every other corner (C lines up with a far corner).
 *
 * Primary guides (right angle, axis) snap within the full tolerance, secondary ones (straight, align) within
 * [SECONDARY_FACTOR] of it, so the common "make it square" case wins. When two non-parallel guides are both in
 * range the corner snaps to their intersection (e.g. square at P AND in line with N's wall).
 */
object CornerGuides {
    /** In priority order: when two guides coincide, the earlier kind is kept. */
    enum class Kind { RIGHT_ANGLE, AXIS, STRAIGHT, ALIGN }

    /** Shape of a guide in plan meters. */
    sealed interface Shape {
        data class Line(val line: Line2) : Shape
        data class Circle(val center: Vec2, val radius: Double) : Shape
    }

    /** One guide; [anchorIndex] is the corner it passes through (the dragged corner for the Thales circle). */
    data class Guide(val kind: Kind, val anchorIndex: Int, val shape: Shape)

    data class Result(val position: Vec2, val guides: List<Guide>) {
        /** Stable identity of what the corner is snapped to, or null when it moves freely. */
        val key: String? get() = guides.takeIf { it.isNotEmpty() }?.joinToString("|") { "${it.kind}@${it.anchorIndex}" }
    }

    /** Tolerance multiplier of the secondary guides (straight / align). */
    const val SECONDARY_FACTOR = 0.75

    /** Lines closer than this to parallel (sine of the angle between them) are not intersected. */
    private const val MIN_INTERSECTION_SIN = 0.17 // ~10 degrees

    /**
     * Snaps [target] (the finger position for corner [index]) onto the guides within [tolerance] meters.
     * Returns the target unchanged, with no guides, when nothing is in range or the input is unusable.
     */
    fun snap(
        corners: List<Vec2>,
        index: Int,
        target: Vec2,
        tolerance: Double,
        axisAngle: Double = CornerSnap.axisAngle(corners),
    ): Result {
        val free = Result(target, emptyList())
        if (!target.isFinite() || !tolerance.isFinite() || tolerance <= 0.0) return free
        val near = candidates(corners, index, axisAngle).filter { distance(it.shape, target) <= toleranceOf(it, tolerance) }
        if (near.isEmpty()) return free

        // Prefer the intersection of two guides that are both in range (snaps both constraints at once).
        var best: Vec2? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (i in near.indices) {
            for (j in i + 1 until near.size) {
                val limit = maxOf(toleranceOf(near[i], tolerance), toleranceOf(near[j], tolerance)) * 1.5
                for (p in intersections(near[i].shape, near[j].shape)) {
                    val d = p.distanceTo(target)
                    if (d <= limit && d < bestDistance) {
                        best = p
                        bestDistance = d
                    }
                }
            }
        }
        val position = best ?: run {
            val closest = near.minWith(
                compareBy<Guide> { distance(it.shape, target) / toleranceOf(it, tolerance) }.thenBy { it.kind.ordinal },
            )
            nearest(closest.shape, target) ?: return free
        }
        val eps = 1e-7 * (1.0 + position.length)
        val active = near.filter { distance(it.shape, position) <= eps }
        if (active.isEmpty()) return free
        return Result(position, active.sortedWith(compareBy({ it.kind.ordinal }, { it.anchorIndex })))
    }

    /** All guides for dragging corner [index], with coincident lines merged (the higher-priority kind wins). */
    fun candidates(corners: List<Vec2>, index: Int, axisAngle: Double): List<Guide> {
        val n = corners.size
        if (n < 3 || index !in corners.indices || corners.any { !it.isFinite() }) return emptyList()
        val axis = if (axisAngle.isFinite()) Vec2.fromAngle(axisAngle) else Vec2(1.0, 0.0)
        val prev = (index - 1 + n) % n
        val next = (index + 1) % n
        val p = corners[prev]
        val q = corners[next]
        val pp = corners[(index - 2 + n) % n]
        val nn = corners[(index + 2) % n]
        val out = ArrayList<Guide>()
        fun line(kind: Kind, anchor: Int, through: Vec2, direction: Vec2) {
            val d = direction.normalized()
            if (d != Vec2.ZERO) out += Guide(kind, anchor, Shape.Line(Line2(through, d)))
        }
        // Square corners at the neighbours.
        line(Kind.RIGHT_ANGLE, prev, p, (p - pp).perp())
        line(Kind.RIGHT_ANGLE, next, q, (nn - q).perp())
        // Square corner at the dragged corner itself (Thales circle over P-N).
        val radius = p.distanceTo(q) / 2
        if (radius > 1e-6) out += Guide(Kind.RIGHT_ANGLE, index, Shape.Circle(Vec2.lerp(p, q, 0.5), radius))
        // Walls parallel to the room's axes.
        for ((anchor, point) in listOf(prev to p, next to q)) {
            line(Kind.AXIS, anchor, point, axis)
            line(Kind.AXIS, anchor, point, axis.perp())
        }
        // Straight continuation of the neighbouring walls (a triangle would collapse, so not for n = 3).
        if (n > 3) {
            line(Kind.STRAIGHT, prev, p, p - pp)
            line(Kind.STRAIGHT, next, q, nn - q)
        }
        // Alignment with the other corners of the room.
        for (j in corners.indices) {
            if (j == index || j == prev || j == next) continue
            line(Kind.ALIGN, j, corners[j], axis)
            line(Kind.ALIGN, j, corners[j], axis.perp())
        }
        return dedupe(out)
    }

    /**
     * Right-angle markers for the corners at and next to [index] of [corners] (the outline after the snap)
     * whose interior angle is 90 degrees within [toleranceDeg].
     */
    fun rightAngles(corners: List<Vec2>, index: Int, toleranceDeg: Double = 0.05): List<RightAngleMark> {
        val n = corners.size
        if (n < 3 || index !in corners.indices || corners.any { !it.isFinite() }) return emptyList()
        val angles = try {
            Polygons.interiorAngles(corners)
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return listOf((index - 1 + n) % n, index, (index + 1) % n).distinct().mapNotNull { k ->
            val angle = angles.getOrNull(k) ?: return@mapNotNull null
            if (abs(Math.toDegrees(angle) - 90.0) > toleranceDeg) return@mapNotNull null
            val v = corners[k]
            val a = (corners[(k - 1 + n) % n] - v).normalized()
            val b = (corners[(k + 1) % n] - v).normalized()
            if (a == Vec2.ZERO || b == Vec2.ZERO) null else RightAngleMark(v, a, b)
        }
    }

    private fun toleranceOf(g: Guide, tolerance: Double) =
        if (g.kind == Kind.RIGHT_ANGLE || g.kind == Kind.AXIS) tolerance else tolerance * SECONDARY_FACTOR

    private fun dedupe(guides: List<Guide>): List<Guide> {
        val kept = ArrayList<Guide>()
        for (g in guides.sortedBy { it.kind.ordinal }) {
            val shape = g.shape
            val duplicate = shape is Shape.Line && kept.any { k ->
                val other = k.shape
                other is Shape.Line && other.line.sinAngleTo(shape.line) < 1e-9 && other.line.distance(shape.line.point) < 1e-9
            }
            if (!duplicate) kept += g
        }
        return kept
    }

    internal fun distance(shape: Shape, p: Vec2): Double = when (shape) {
        is Shape.Line -> shape.line.distance(p)
        is Shape.Circle -> abs(shape.center.distanceTo(p) - shape.radius)
    }

    private fun nearest(shape: Shape, p: Vec2): Vec2? = when (shape) {
        is Shape.Line -> shape.line.project(p)
        is Shape.Circle -> (p - shape.center).normalized().takeIf { it != Vec2.ZERO }?.let { shape.center + it * shape.radius }
    }

    private fun intersections(a: Shape, b: Shape): List<Vec2> = when {
        a is Shape.Line && b is Shape.Line ->
            if (a.line.sinAngleTo(b.line) < MIN_INTERSECTION_SIN) emptyList() else listOfNotNull(a.line.intersect(b.line))
        a is Shape.Line && b is Shape.Circle -> lineCircle(a.line, b)
        a is Shape.Circle && b is Shape.Line -> lineCircle(b.line, a)
        else -> emptyList()
    }

    private fun lineCircle(line: Line2, circle: Shape.Circle): List<Vec2> {
        val foot = line.project(circle.center)
        val d = foot.distanceTo(circle.center)
        if (d > circle.radius) return emptyList()
        val half = sqrt(circle.radius * circle.radius - d * d)
        return listOf(foot + line.direction * half, foot - line.direction * half)
    }
}

/** Finds the room a snapped room now shares a wall with, to name it in "Snapped to Hallway". */
object SnapNeighbour {
    /**
     * The room on [floor] with a wall facing a wall of [roomId] across a gap of [wallThickness] (within
     * [gapTolerance] meters and [angleToleranceDeg]); the longest shared length wins. Null when no pair qualifies.
     *
     * Interior outlines are counter-clockwise, so each edge's outward normal points to its right; a neighbour's
     * facing edge runs the opposite way, [wallThickness] further out along that normal.
     */
    fun find(
        floor: Floor,
        roomId: String,
        wallThickness: Double,
        gapTolerance: Double = 0.03,
        angleToleranceDeg: Double = 2.0,
    ): Room? {
        val room = floor.rooms.firstOrNull { it.id == roomId } ?: return null
        if (!wallThickness.isFinite()) return null
        val minCos = cos(Math.toRadians(angleToleranceDeg))
        var best: Room? = null
        var bestOverlap = 1e-3
        val ownEdges = usableEdges(room)
        for (other in floor.rooms) {
            if (other.id == roomId) continue
            for (e in ownEdges) {
                val outward = -e.direction.perp()
                for (f in usableEdges(other)) {
                    if ((e.direction dot f.direction) > -minCos) continue
                    val gap = (f.midpoint - e.a) dot outward
                    if (abs(gap - wallThickness) > gapTolerance) continue
                    val t1 = (f.a - e.a) dot e.direction
                    val t2 = (f.b - e.a) dot e.direction
                    val overlap = min(e.length, maxOf(t1, t2)) - maxOf(0.0, min(t1, t2))
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap
                        best = other
                    }
                }
            }
        }
        return best
    }

    private fun usableEdges(room: Room): List<Segment2> {
        val c = room.corners
        if (c.size < 3 || c.any { !it.isFinite() }) return emptyList()
        return Polygons.edges(c).filter { it.length > 1e-6 }
    }
}

/** View-transform math for the editor's animated zoom-to-fit and "show this room". */
object ViewAnimation {
    /**
     * The view part-way ([t] in 0..1) from [from] to [to] on a [widthPx] x [heightPx] canvas. The plan point
     * at the canvas center moves linearly while the zoom changes geometrically, which reads as one smooth
     * camera move; t >= 1 returns [to] exactly.
     */
    fun interpolate(from: ViewTransform, to: ViewTransform, t: Double, widthPx: Double, heightPx: Double): ViewTransform {
        if (!t.isFinite() || t >= 1.0) return to
        if (t <= 0.0) return from
        if (!(from.pxPerMeter > 0) || !(to.pxPerMeter > 0) || !from.pxPerMeter.isFinite() || !to.pxPerMeter.isFinite()) return to
        val screenCenter = Vec2(widthPx / 2, heightPx / 2)
        val c0 = from.toPlan(screenCenter)
        val c1 = to.toPlan(screenCenter)
        if (!c0.isFinite() || !c1.isFinite()) return to
        val scale = exp(ln(from.pxPerMeter) * (1 - t) + ln(to.pxPerMeter) * t)
        val c = Vec2.lerp(c0, c1, t)
        return ViewTransform(scale, Vec2(widthPx / 2 - c.x * scale, heightPx / 2 + c.y * scale), to.pxPerPt)
    }

    /**
     * A view that shows [bounds]: [current] when they are already fully visible inside [marginPx], otherwise
     * the bounds centered, at the current zoom when they fit at it, else zoomed out just enough to fit.
     */
    fun focus(current: ViewTransform, bounds: Bounds, widthPx: Double, heightPx: Double, marginPx: Double): ViewTransform {
        if (bounds.isEmpty || widthPx <= 0 || heightPx <= 0) return current
        val topLeft = current.toScreen(Vec2(bounds.minX, bounds.maxY))
        val bottomRight = current.toScreen(Vec2(bounds.maxX, bounds.minY))
        val visible = topLeft.x >= marginPx && topLeft.y >= marginPx &&
            bottomRight.x <= widthPx - marginPx && bottomRight.y <= heightPx - marginPx
        if (visible) return current
        val fit = ViewTransform.fit(bounds, widthPx, heightPx, marginPx, current.pxPerPt)
        val scale = min(fit.pxPerMeter, current.pxPerMeter)
        val c = bounds.center
        return ViewTransform(scale, Vec2(widthPx / 2 - c.x * scale, heightPx / 2 + c.y * scale), current.pxPerPt)
    }
}

/**
 * Squaring axis hint for re-solving [roomId] (radians, mod 90 degrees): the length-weighted dominant wall
 * direction of the other rooms on [floor], so a re-solved room comes out parallel to its neighbours. Null
 * when there are no other usable rooms.
 */
fun floorAxisHint(floor: Floor, roomId: String): Double? {
    val edges = floor.rooms
        .filter { it.id != roomId && it.corners.size >= 3 && it.corners.all { c -> c.isFinite() } }
        .flatMap { Polygons.edges(it.corners) }
        .filter { it.length > 0.05 }
    if (edges.isEmpty()) return null
    return RoomSolver.dominantAngle(edges.map { (it.b - it.a).angle() }, edges.map { it.length })
}
