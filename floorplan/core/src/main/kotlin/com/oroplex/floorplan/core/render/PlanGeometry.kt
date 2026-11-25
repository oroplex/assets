package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.RoomOps
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Plan-symbol geometry shared by PlanRenderer (DrawList) and DxfExporter (CAD entities), so a plan looks
 * the same on screen, in PDF/PNG/SVG and in CAD: wall bands, door leaves and swings, window symbols and
 * room-label layout. Everything is in plan meters unless a name says otherwise.
 */

/**
 * A room whose outline can be drawn as a real plan: at least 3 finite corners, a simple (non
 * self-intersecting) outline and a non-zero area. [orientation] is +1 for the conventional
 * counter-clockwise outline and -1 for a clockwise one (bad data that is still drawn correctly, so the
 * interior side of every edge is `direction.perp() * orientation`).
 */
internal class DrawableRoom(val room: Room, val orientation: Double) {
    val corners: List<Vec2> get() = room.corners

    /** Outer outline of the wall band: the interior outline grown outward by [thickness]. */
    fun band(thickness: Double): List<Vec2> = Polygons.offset(corners, thickness)
}

/**
 * An opening resolved onto its wall: [start] -> [end] along the interior face, [direction] the unit
 * direction of the wall edge, [inward] the unit normal pointing into the room.
 */
internal data class OpeningFrame(
    val roomIndex: Int,
    val opening: Opening,
    val start: Vec2,
    val end: Vec2,
    val direction: Vec2,
    val inward: Vec2,
) {
    val width: Double get() = start.distanceTo(end)
    val midpoint: Vec2 get() = Vec2.lerp(start, end, 0.5)
    val outward: Vec2 get() = -inward
}

/**
 * A door drawn fully open: the leaf runs [hinge] -> [openEnd]; the swing arc of [radius] (= door width)
 * starts at the closed position ([closedEnd], along the wall) at [startAngle] and sweeps [sweep] (+-PI/2)
 * to the open position. Angles are plan radians (CCW positive).
 */
internal data class DoorGeometry(
    val hinge: Vec2,
    val closedEnd: Vec2,
    val openEnd: Vec2,
    val radius: Double,
    val startAngle: Double,
    val sweep: Double,
)

/**
 * Stair symbol of a STAIRS room ([PlanGeometry.stairSymbol]): tread lines, the arrow's [shaft] along the run
 * (null when the run's middle line misses the room) and its [head] polyline (empty without a shaft).
 */
internal data class StairSymbol(val treads: List<Segment2>, val shaft: Segment2?, val head: List<Vec2>)

/** One line of a room label; [sizePt] already includes the text scale and small-room shrink. */
internal data class LabelLine(val text: String, val sizePt: Double, val bold: Boolean, val color: Int)

/**
 * A room label block centered on [anchor] with baseline direction [angle] (upright, in (-PI/2, PI/2]).
 * Line i is shifted by [offsetsPt][i] points toward the text's "up" (perpendicular to the baseline).
 */
internal data class LabelLayout(
    val anchor: Vec2,
    val angle: Double,
    val lines: List<LabelLine>,
    val offsetsPt: List<Double>,
)

internal object PlanGeometry {
    /** Wall thicknesses above this are treated as typos (meters). */
    const val MAX_WALL_THICKNESS = 1.0

    /** Minimum opening width that still gets a gap / symbol (meters). */
    const val MIN_OPENING_WIDTH = 0.01

    /** Opening cuts reach this far beyond twice the wall thickness (meters). */
    const val CUT_EXTRA = 0.02

    /** Opening cuts overlap whatever they abut by this much so anti-aliasing leaves no hairline (meters). */
    const val CUT_OVERLAP = 0.001

    /** Name text size and detail (dimensions / area) text size before scaling, points. */
    const val NAME_PT = 10.0
    const val DETAIL_PT = 8.0

    /** Distance between stacked label lines as a multiple of the line's text size. */
    const val LINE_SPACING = 1.25

    /** Inscribed-circle radius (meters) below which labels shrink, and below which the "L × W" line goes. */
    const val SMALL_ROOM_RADIUS = 0.6
    const val TINY_ROOM_RADIUS = 0.4

    /** Rooms narrower than this (meters) and at least twice as long get their label along the long axis. */
    const val NARROW_ROOM_WIDTH = 1.5

    /** Stair treads are spaced as close to this as the run allows, within [MIN_TREAD]..[MAX_TREAD] when possible (m). */
    const val TREAD = 0.265
    const val MIN_TREAD = 0.25
    const val MAX_TREAD = 0.28

    /** Stairs shorter / narrower than this (m) get no symbol. */
    const val MIN_STAIR_RUN = 0.5
    const val MIN_STAIR_WIDTH = 0.3

    /** The settings' wall thickness, or the default when it is not a usable positive number. */
    fun wallThickness(settings: PlanSettings): Double =
        settings.wallThickness.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_WALL_THICKNESS)
            ?: PlanSettings().wallThickness

    /**
     * Thickness the overall dimensions measure exterior walls with: [PlanSettings.exteriorWallThickness], or the
     * interior [wallThickness] when it is null or not a usable number (0 is allowed: interior extents). Capped at
     * [MAX_WALL_THICKNESS] like the interior thickness.
     */
    fun exteriorWallThickness(settings: PlanSettings): Double =
        settings.exteriorWallThickness?.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(MAX_WALL_THICKNESS)
            ?: wallThickness(settings)

    /**
     * Plan symbol of a stair room: tread lines across the short side of its oriented bounding box, spaced about
     * [TREAD] apart along the run (the count is chosen so the spacing stays within [MIN_TREAD]..[MAX_TREAD] when
     * the run allows), and a direction arrow along the middle of the run pointing toward the box's long-axis
     * direction (the walking direction is not recorded, so "up" is drawn toward +[OrientedBox.angle]). Every line
     * is clipped to the room's outline, so L-shaped stair rooms stay clean. Null for a room too small for a symbol.
     */
    fun stairSymbol(room: DrawableRoom): StairSymbol? {
        val corners = room.corners
        val box = Polygons.orientedBoundingBox(corners)
        if (!box.length.isFinite() || !box.width.isFinite() || !box.angle.isFinite()) return null
        if (box.length < MIN_STAIR_RUN || box.width < MIN_STAIR_WIDTH) return null
        val u = Vec2.fromAngle(box.angle)
        val v = u.perp()
        val run = box.length
        val count = max(2, Math.round(run / TREAD).toInt())
        val spacing = run / count
        val across = box.width / 2 + 0.01
        val treads = (1 until count).flatMap { k ->
            val at = box.center + u * (-run / 2 + k * spacing)
            clipToPolygon(corners, at - v * across, at + v * across)
        }
        val inset = min(max(spacing, 0.15), run * 0.2)
        val shaft = clipToPolygon(corners, box.center - u * (run / 2 - inset), box.center + u * (run / 2 - inset))
            .maxByOrNull { it.length } ?: return StairSymbol(treads, null, emptyList())
        val head = min(min(0.25, 0.35 * box.width), 0.4 * shaft.length)
        val tip = shaft.b
        val back = u * (head * cos(PI / 6))
        val side = v * (head * sin(PI / 6))
        return StairSymbol(treads, shaft, listOf(tip - back + side, tip, tip - back - side))
    }

    /**
     * The parts of segment [a]-[b] inside [polygon] (any orientation), in order from [a]: the segment is cut at
     * every edge crossing and the pieces whose midpoints are inside are kept.
     */
    fun clipToPolygon(polygon: List<Vec2>, a: Vec2, b: Vec2): List<Segment2> {
        val d = b - a
        val length = d.length
        if (polygon.size < 3 || !(length > 1e-9) || !a.isFinite() || !b.isFinite()) return emptyList()
        val cuts = mutableListOf(0.0, 1.0)
        for (edge in Polygons.edges(polygon)) {
            val e = edge.b - edge.a
            val denom = d cross e
            if (abs(denom) < 1e-12) continue
            val ap = edge.a - a
            val t = (ap cross e) / denom
            val s = (ap cross d) / denom
            if (t > 0.0 && t < 1.0 && s >= -1e-9 && s <= 1 + 1e-9) cuts += t
        }
        cuts.sort()
        val out = ArrayList<Segment2>()
        for (k in 0 until cuts.size - 1) {
            val t0 = cuts[k]
            val t1 = cuts[k + 1]
            if (t1 - t0 < 1e-9) continue
            if (!Polygons.contains(polygon, a + d * ((t0 + t1) / 2))) continue
            val p0 = a + d * t0
            val p1 = a + d * t1
            val last = out.lastOrNull()
            if (last != null && last.b.distanceTo(p0) < 1e-9) out[out.size - 1] = Segment2(last.a, p1) else out += Segment2(p0, p1)
        }
        return out
    }

    /** [room] as a [DrawableRoom], or null when its outline is unusable (see [DrawableRoom]). */
    fun drawable(room: Room): DrawableRoom? {
        val c = room.corners
        if (c.size < 3 || c.any { !it.isFinite() }) return null
        val area = Polygons.signedArea(c)
        if (!area.isFinite() || abs(area) < 1e-6 || !Polygons.isSimple(c)) return null
        return DrawableRoom(room, if (area > 0) 1.0 else -1.0)
    }

    /** Unit direction of edge [index] of [corners], or null for a degenerate edge. */
    fun edgeDirection(corners: List<Vec2>, index: Int): Vec2? {
        if (index !in corners.indices || corners.size < 2) return null
        val a = corners[index]
        val b = corners[(index + 1) % corners.size]
        val d = (b - a).normalized()
        return if (d == Vec2.ZERO || !d.isFinite()) null else d
    }

    /** Resolve [opening] onto its wall via [RoomOps.openingSegment]; null when it references no usable edge. */
    fun openingFrame(roomIndex: Int, room: DrawableRoom, opening: Opening): OpeningFrame? {
        val direction = edgeDirection(room.corners, opening.wallIndex) ?: return null
        val (s, e) = RoomOps.openingSegment(room.room, opening)
        if (!s.isFinite() || !e.isFinite() || s.distanceTo(e) < MIN_OPENING_WIDTH) return null
        return OpeningFrame(roomIndex, opening, s, e, direction, direction.perp() * room.orientation)
    }

    /** Every usable opening of every room, in room order then opening order. */
    fun openingFrames(rooms: List<DrawableRoom?>): List<OpeningFrame> =
        rooms.flatMapIndexed { i, room ->
            room?.room?.openings?.mapNotNull { openingFrame(i, room, it) } ?: emptyList()
        }

    /**
     * Door symbol for [frame]. LEFT_* hinges at the opening's start, RIGHT_* at its end (left/right as seen
     * when walking into the room). *_IN doors hinge on the interior face and open into the room; *_OUT doors
     * hinge on the outer face of the wall ([wallThickness] outward) and open away from the room. The arc goes
     * from the closed position (leaf along the wall towards the other jamb) to the open position
     * (perpendicular to the wall), so it always sweeps exactly 90 degrees.
     */
    fun door(frame: OpeningFrame, swing: DoorSwing, wallThickness: Double): DoorGeometry {
        val hingeAtStart = swing == DoorSwing.LEFT_IN || swing == DoorSwing.LEFT_OUT
        val swingsIn = swing == DoorSwing.LEFT_IN || swing == DoorSwing.RIGHT_IN
        val face = if (swingsIn) Vec2.ZERO else frame.outward * wallThickness
        val hinge = (if (hingeAtStart) frame.start else frame.end) + face
        val closedDir = if (hingeAtStart) frame.direction else -frame.direction
        val openDir = if (swingsIn) frame.inward else frame.outward
        val radius = frame.width
        val sweep = if ((closedDir cross openDir) > 0) PI / 2 else -PI / 2
        return DoorGeometry(hinge, hinge + closedDir * radius, hinge + openDir * radius, radius, closedDir.angle(), sweep)
    }

    /**
     * Window symbol: the frame rectangle (interior face, end caps, outer face [wallThickness] outward) as a
     * closed outline, plus the glass line along the wall center.
     */
    fun windowOutline(frame: OpeningFrame, wallThickness: Double): List<Vec2> {
        val out = frame.outward * wallThickness
        return listOf(frame.start, frame.end, frame.end + out, frame.start + out)
    }

    fun windowCenterLine(frame: OpeningFrame, wallThickness: Double): Segment2 {
        val half = frame.outward * (wallThickness / 2)
        return Segment2(frame.start + half, frame.end + half)
    }

    /**
     * Quad that clears the wall band(s) at an opening: from the interior face (overlapping the room by
     * [CUT_OVERLAP]) outward by [depth].
     */
    fun cutQuad(frame: OpeningFrame, depth: Double): List<Vec2> {
        val inset = frame.inward * CUT_OVERLAP
        val out = frame.outward * depth
        return listOf(frame.start + inset, frame.end + inset, frame.end + out, frame.start + out)
    }

    /**
     * How deep an opening must cut: 2 * wall thickness + [CUT_EXTRA] clears this room's band and a
     * neighbour's band even when the two interior outlines are up to two wall thicknesses apart. When the
     * opening leads straight into a neighbouring room, the cut stops just inside that room instead (its
     * interior is already clear), so it never paints a notch into a neighbour that is highlighted as selected.
     * Rays are cast outward from several points across the opening; only when every ray reaches another room
     * within the maximum depth is the cut shortened, to the deepest of those distances.
     */
    fun cutDepth(frame: OpeningFrame, wallThickness: Double, rooms: List<DrawableRoom?>): Double {
        val maxDepth = 2 * wallThickness + CUT_EXTRA
        val inset = min(0.01, frame.width * 0.1)
        val samples = 5
        var deepest = 0.0
        for (k in 0 until samples) {
            val t = k / (samples - 1.0)
            val along = inset + (frame.width - 2 * inset) * t
            val p = frame.start + frame.direction * along
            var nearest = Double.POSITIVE_INFINITY
            rooms.forEachIndexed { i, other ->
                if (other == null || i == frame.roomIndex) return@forEachIndexed
                val d = entryDistance(other.corners, p, frame.outward, maxDepth)
                if (d != null && d < nearest) nearest = d
            }
            if (nearest == Double.POSITIVE_INFINITY) return maxDepth
            deepest = max(deepest, nearest)
        }
        return min(maxDepth, deepest + CUT_OVERLAP)
    }

    /** Distance along the ray [origin] + [dir] * s (0 <= s <= [limit]) at which it enters [polygon], or null. */
    fun entryDistance(polygon: List<Vec2>, origin: Vec2, dir: Vec2, limit: Double): Double? {
        if (Polygons.contains(polygon, origin)) return 0.0
        var best: Double? = null
        for (edge in Polygons.edges(polygon)) {
            val ab = edge.b - edge.a
            val denom = dir cross ab
            if (abs(denom) < 1e-12) continue
            val ap = edge.a - origin
            val s = (ap cross ab) / denom
            val u = (ap cross dir) / denom
            if (s < 0.0 || s > limit || u < -1e-9 || u > 1 + 1e-9) continue
            if (best == null || s < best) best = s
        }
        return best
    }

    /**
     * True when [candidate] is the same physical opening as one of [drawn] marked from the room on the other
     * side of the wall (a door captured once from each room): same type, facing walls (antiparallel), the
     * candidate's face within two wall thicknesses outside the other's face, and overlapping by at least
     * half of the narrower opening. Such duplicates get a gap but no second symbol.
     */
    fun isDuplicate(candidate: OpeningFrame, drawn: List<OpeningFrame>, wallThickness: Double): Boolean =
        drawn.any { other ->
            if (other.roomIndex == candidate.roomIndex || other.opening.type != candidate.opening.type) return@any false
            if ((other.direction dot candidate.direction) > -0.95) return@any false
            val across = (candidate.midpoint - other.start) dot other.outward
            if (across < -0.01 || across > 2 * wallThickness + 0.05) return@any false
            val a0 = (candidate.start - other.start) dot other.direction
            val a1 = (candidate.end - other.start) dot other.direction
            val overlap = min(max(a0, a1), other.width) - max(min(a0, a1), 0.0)
            overlap >= 0.5 * min(candidate.width, other.width)
        }

    /** Points bounding a circular arc: its end points plus every axis extreme it passes. */
    fun arcExtremes(center: Vec2, radius: Double, startAngle: Double, sweep: Double): List<Vec2> {
        val pts = mutableListOf(center + Vec2.fromAngle(startAngle, radius), center + Vec2.fromAngle(startAngle + sweep, radius))
        val lo = min(startAngle, startAngle + sweep)
        val hi = max(startAngle, startAngle + sweep)
        var k = ceil(lo / (PI / 2))
        while (k * (PI / 2) <= hi && pts.size < 8) {
            pts += center + Vec2.fromAngle(k * (PI / 2), radius)
            k += 1.0
        }
        return pts
    }

    fun doorBounds(d: DoorGeometry): Bounds =
        Bounds.of(arcExtremes(d.hinge, d.radius, d.startAngle, d.sweep) + listOf(d.hinge, d.openEnd, d.closedEnd))

    /** [angle] turned by a multiple of PI into (-PI/2, PI/2], so text along it reads upright. */
    fun uprightAngle(angle: Double): Double {
        var a = Polygons.normalizeAngle(angle)
        if (a > PI / 2) a -= PI
        if (a <= -PI / 2) a += PI
        return a
    }

    /**
     * Label anchor: the pole of inaccessibility (the interior point farthest from the walls, which keeps
     * labels out of the narrow arm of an L), except that the area centroid is preferred when it is inside
     * and practically as far from the walls, so rectangular rooms get a visually centered label.
     */
    fun labelAnchor(corners: List<Vec2>): Vec2 {
        val pole = Polygons.poleOfInaccessibility(corners, 0.01)
        val poleDistance = Polygons.signedDistanceToOutline(corners, pole)
        val centroid = Polygons.centroid(corners)
        val centroidDistance = Polygons.signedDistanceToOutline(corners, centroid)
        val slack = min(0.05, 0.1 * poleDistance)
        return if (centroidDistance > 0 && centroidDistance >= poleDistance - slack) centroid else pole
    }

    /**
     * Label block of a room: name (bold), "L × W" from the oriented bounding box, and area. Small rooms
     * (inscribed radius below [SMALL_ROOM_RADIUS]) get smaller text; tiny ones (closets, below
     * [TINY_ROOM_RADIUS]) also drop the dimension line so the label does not spill over the walls. Long
     * narrow rooms (hallways) and small elongated rooms (closets) are labelled along their long axis.
     * Null when nothing is to be shown.
     */
    fun roomLabel(
        room: DrawableRoom,
        settings: PlanSettings,
        showName: Boolean,
        showDimensions: Boolean,
        showArea: Boolean,
        textScale: Double,
        textColor: Int = PlanColors.TEXT,
        secondaryColor: Int = PlanColors.TEXT_SECONDARY,
    ): LabelLayout? {
        val corners = room.corners
        val anchor = labelAnchor(corners)
        val radius = Polygons.signedDistanceToOutline(corners, anchor).coerceAtLeast(0.0)
        val shrink = when {
            radius >= SMALL_ROOM_RADIUS -> 1.0
            radius >= TINY_ROOM_RADIUS -> 0.85
            else -> 0.7
        }
        val stats = Measurements.room(room.room)
        val unit = settings.unitSystem
        val lines = buildList {
            val name = room.room.name.replace('\n', ' ').trim().ifEmpty { room.room.type.displayName }
            if (showName) add(LabelLine(name, NAME_PT * textScale * shrink, true, textColor))
            if (showDimensions && radius >= TINY_ROOM_RADIUS) {
                val l = Units.formatLength(stats.length, unit, settings.imperialDenominator)
                val w = Units.formatLength(stats.width, unit, settings.imperialDenominator)
                add(LabelLine("$l × $w", DETAIL_PT * textScale * shrink, false, secondaryColor))
            }
            if (showArea) add(LabelLine(Units.formatArea(stats.area, unit), DETAIL_PT * textScale * shrink, false, secondaryColor))
        }
        if (lines.isEmpty()) return null
        // Long narrow rooms (hallways), and small rooms that are clearly longer than wide (closets), read
        // along their long axis: that is the direction with room for the text.
        val narrow = (stats.width < NARROW_ROOM_WIDTH && stats.length >= 2 * stats.width) ||
            (radius < SMALL_ROOM_RADIUS && stats.length >= 1.25 * stats.width)
        val angle = if (narrow && stats.box.angle.isFinite()) uprightAngle(stats.box.angle) else 0.0
        // Stack the lines top to bottom, centering the whole block on the anchor.
        val heights = lines.map { it.sizePt * LINE_SPACING }
        val total = heights.sum()
        var top = total / 2
        val offsets = heights.map { h -> (top - h / 2).also { top -= h } }
        return LabelLayout(anchor, angle, lines, offsets)
    }

    /** Rooms at least this much longer than wide may have their label turned along the long axis to show more. */
    const val ELONGATED_ROOM = 1.5

    /**
     * [roomLabel] for a KNOWN output scale ([metersPerPt]): the same block (name, "L × W", area) at the same
     * anchor, but only as much of it as fits inside the room at that scale, see [LabelFit]. The block is
     * horizontal unless the room is a hallway-like strip ([NARROW_ROOM_WIDTH]), which reads along its long
     * axis; a room at least [ELONGATED_ROOM] times longer than wide is turned the other way when that shows
     * more lines or avoids shortening the name. Null when nothing fits or nothing is to be shown.
     */
    fun fittedRoomLabel(
        room: DrawableRoom,
        settings: PlanSettings,
        showName: Boolean,
        showDimensions: Boolean,
        showArea: Boolean,
        textScale: Double,
        metersPerPt: Double,
        textColor: Int = PlanColors.TEXT,
        secondaryColor: Int = PlanColors.TEXT_SECONDARY,
    ): LabelFit.RoomLabel? {
        val corners = room.corners
        val anchor = labelAnchor(corners)
        val stats = Measurements.room(room.room)
        val unit = settings.unitSystem
        val name = if (showName) room.room.name.replace('\n', ' ').trim().ifEmpty { room.room.type.displayName } else null
        val details = buildList {
            if (showDimensions) {
                val l = Units.formatLength(stats.length, unit, settings.imperialDenominator)
                val w = Units.formatLength(stats.width, unit, settings.imperialDenominator)
                add("$l × $w")
            }
            if (showArea) add(Units.formatArea(stats.area, unit))
        }
        val longAxis = stats.box.angle.takeIf { it.isFinite() }?.let(::uprightAngle)
        val strip = stats.width < NARROW_ROOM_WIDTH && stats.length >= 2 * stats.width
        val elongated = stats.length >= ELONGATED_ROOM * stats.width
        val preferred = if (strip && longAxis != null) longAxis else 0.0
        val alternative = when {
            !elongated -> null
            preferred == 0.0 -> longAxis
            else -> 0.0
        }
        return LabelFit.roomLabel(
            corners, anchor, preferred, alternative, name, details,
            NAME_PT * textScale, DETAIL_PT * textScale, textColor, secondaryColor, metersPerPt,
        )
    }
}
