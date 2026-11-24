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
import kotlin.math.max
import kotlin.math.min

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

    /** The settings' wall thickness, or the default when it is not a usable positive number. */
    fun wallThickness(settings: PlanSettings): Double =
        settings.wallThickness.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_WALL_THICKNESS)
            ?: PlanSettings().wallThickness

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
}
