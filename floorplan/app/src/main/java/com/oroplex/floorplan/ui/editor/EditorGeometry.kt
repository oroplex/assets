package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.TextAlign
import com.oroplex.floorplan.core.render.ViewTransform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
