package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.solve.FloorAxis
import kotlin.math.PI
import kotlin.math.max

data class RenderOptions(
    val showRoomNames: Boolean = true,
    val showRoomDimensions: Boolean = true,
    /** null = follow PlanSettings.showRoomAreas */
    val showAreas: Boolean? = null,
    /** null = follow PlanSettings.showWallLengths */
    val showWallLengths: Boolean? = null,
    val selectedRoomId: String? = null,
    /** Show draggable corner handles for the selected room (editor). */
    val showCornerHandles: Boolean = false,
    val selectedWall: Int? = null,
    /** Base text size multiplier (PDF may use 0.8, phone screen 1.0). */
    val textScale: Double = 1.0,
    /** null = follow PlanSettings.showOverallDimensions. Overall width/depth dimension lines outside the plan. */
    val showOverallDimensions: Boolean? = null,
    /** Rooms to flag (e.g. overlapping rooms) with a WARNING-colored outline. */
    val highlightRoomIds: Set<String> = emptySet(),
    /**
     * Output scale in points per plan meter when the caller knows it (a PDF at a fixed architectural scale, the
     * editor at its current zoom). Overall-dimension offsets, ticks and label gaps are then sized exactly in
     * points. null (or not a positive finite number) = assume the plan spans about 560 pt (fit-to-page).
     */
    val pointsPerMeter: Double? = null,
)

object PlanRenderer {
    private const val LEAF_PT = 1.5
    private const val SWING_PT = 0.75
    private const val WINDOW_PT = 0.75
    private const val SELECTION_PT = 2.0
    private const val SELECTED_WALL_PT = 3.0
    private const val HANDLE_RADIUS_PT = 7.0
    private const val WARNING_PT = 1.5
    private const val WARNING_DASH_PT = 4.0
    private const val WARNING_DOT_PT = 3.0
    private const val WALL_LABEL_PT = 7.0
    private const val WALL_LABEL_OFFSET_PT = 7.0
    private const val HIGHLIGHT_PT = 2.0
    private const val HIGHLIGHT_DASH_PT = 4.0
    private const val DIMENSION_LINE_PT = 0.75
    private const val EXTENSION_LINE_PT = 0.5
    private const val TICK_LINE_PT = 1.0
    private const val TREAD_PT = 0.6
    private const val STAIR_ARROW_PT = 0.75

    /** Walls shorter than this get no length label (meters). */
    private const val MIN_LABELED_WALL = 0.3

    /** Text scales outside this range are clamped (a 0 or 100x scale is a caller bug, not a request). */
    private const val MIN_TEXT_SCALE = 0.1
    private const val MAX_TEXT_SCALE = 10.0

    /**
     * Render rooms to a DrawList, CubiCasa-style: dark wall bands of [PlanSettings.wallThickness] drawn
     * OUTSIDE each room's interior outline (all bands first, then all room fills, so shared walls merge),
     * openings cut into the band (doors: gap + leaf line + swing arc; windows: gap + thin triple line;
     * openings: gap), room label at the pole of inaccessibility (name, "L × W", area), optional wall
     * lengths along each interior edge, selection highlight and corner handles.
     * DrawOp tags: "room:<id>" on room fills, "wall:<roomId>:<edgeIndex>" on wall-length labels,
     * "corner:<roomId>:<index>" on handles, "dimension:width" / "dimension:depth" on overall dimension lines
     * and their labels, "stairs:<roomId>" on stair symbols. Bounds include wall bands, labels' anchors and the
     * overall dimensions.
     *
     * Op order (later ops paint over earlier ones): wall bands, room fills, warning outlines of unusable
     * rooms, stair symbols of [RoomType.STAIRS] rooms (0.6 pt tread lines across the run plus a 0.75 pt
     * direction arrow, see [PlanGeometry.stairSymbol]), opening gaps, door / window symbols, selection highlight,
     * highlight outlines
     * ([RenderOptions.highlightRoomIds]: 2 pt dashed [PlanColors.WARNING]), overall dimension lines, their
     * labels, room labels, wall-length labels, corner handles. Rooms with fewer than 3 finite corners, a
     * self-intersecting outline or no area are drawn only as a dashed [PlanColors.WARNING] outline (still
     * tagged "room:<id>" so they can be selected and repaired). A door captured from both sides of a wall is
     * drawn once. Never throws on bad data.
     *
     * Overall dimensions ([RenderOptions.showOverallDimensions] ?: [PlanSettings.showOverallDimensions]): the
     * width and depth of the whole floor to the outside of its exterior walls (the interior extents padded by
     * [PlanSettings.exteriorWallThickness], see [OverallDimensions]), measured in the floor's own axis frame
     * (its dominant wall direction), drawn above and to the left of the plan with extension lines, 45° tick
     * slashes and a bold 8 pt label. Their offsets, ticks and gaps are sized in points at
     * [RenderOptions.pointsPerMeter] when given, else at a nominal fit-to-page scale.
     */
    fun render(rooms: List<Room>, settings: PlanSettings, options: RenderOptions = RenderOptions()): DrawList {
        if (rooms.isEmpty()) return DrawList(emptyList(), Bounds.EMPTY)
        val thickness = PlanGeometry.wallThickness(settings)
        val textScale = options.textScale.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) ?: 1.0
        val showAreas = options.showAreas ?: settings.showRoomAreas
        val showWallLengths = options.showWallLengths ?: settings.showWallLengths
        val drawable = rooms.map { PlanGeometry.drawable(it) }
        val ops = ArrayList<DrawOp>()
        var bounds = Bounds.EMPTY

        fun isSelected(room: Room) = options.selectedRoomId != null && room.id == options.selectedRoomId
        fun fillOf(room: Room) = if (isSelected(room)) PlanColors.ROOM_FILL_SELECTED else PlanColors.ROOM_FILL

        // Pass 1: wall bands. Pass 2: interiors on top, so neighbouring bands merge into one wall.
        for (d in drawable) {
            if (d == null) continue
            val band = d.band(thickness)
            ops += DrawOp.FillPolygon(band, PlanColors.WALL)
            bounds = bounds.union(Bounds.of(band))
        }
        for (d in drawable) {
            if (d == null) continue
            ops += DrawOp.FillPolygon(d.corners, fillOf(d.room), tag = roomTag(d.room))
        }
        rooms.forEachIndexed { i, room ->
            if (drawable[i] != null) return@forEachIndexed
            val pts = room.corners.filter { it.isFinite() }
            bounds = bounds.union(Bounds.of(pts))
            when {
                pts.size >= 2 -> ops += DrawOp.Polyline(
                    pts, closed = pts.size >= 3, widthPt = WARNING_PT, color = PlanColors.WARNING,
                    dashPt = WARNING_DASH_PT, tag = roomTag(room),
                )
                pts.size == 1 -> ops += DrawOp.Dot(pts[0], WARNING_DOT_PT, PlanColors.WARNING, tag = roomTag(room))
            }
        }

        // Pass 2b: stair symbols inside stair rooms (under openings, highlights and labels).
        for (d in drawable) {
            if (d == null || d.room.type != RoomType.STAIRS) continue
            val stairs = PlanGeometry.stairSymbol(d) ?: continue
            val tag = "stairs:${d.room.id}"
            for (t in stairs.treads) ops += DrawOp.Polyline(listOf(t.a, t.b), false, TREAD_PT, PlanColors.DIMENSION, tag = tag)
            stairs.shaft?.let { ops += DrawOp.Polyline(listOf(it.a, it.b), false, STAIR_ARROW_PT, PlanColors.TEXT_SECONDARY, tag = tag) }
            if (stairs.head.size >= 2) ops += DrawOp.Polyline(stairs.head, false, STAIR_ARROW_PT, PlanColors.TEXT_SECONDARY, tag = tag)
        }

        // Pass 3: openings. All gaps first so a later gap can never erase an earlier door's swing.
        val frames = PlanGeometry.openingFrames(drawable)
        for (f in frames) {
            ops += DrawOp.FillPolygon(PlanGeometry.cutQuad(f, PlanGeometry.cutDepth(f, thickness, drawable)), PlanColors.OPENING_GAP)
        }
        val drawn = ArrayList<OpeningFrame>()
        for (f in frames) {
            if (f.opening.type == OpeningType.OPENING || PlanGeometry.isDuplicate(f, drawn, thickness)) continue
            drawn += f
            when (f.opening.type) {
                OpeningType.DOOR -> {
                    val door = PlanGeometry.door(f, f.opening.swing, thickness)
                    ops += DrawOp.Polyline(listOf(door.hinge, door.openEnd), false, LEAF_PT, PlanColors.DOOR)
                    ops += DrawOp.Arc(door.hinge, door.radius, door.startAngle, door.sweep, SWING_PT, PlanColors.DOOR)
                    bounds = bounds.union(PlanGeometry.doorBounds(door))
                }
                OpeningType.WINDOW -> {
                    val center = PlanGeometry.windowCenterLine(f, thickness)
                    ops += DrawOp.Polyline(PlanGeometry.windowOutline(f, thickness), true, WINDOW_PT, PlanColors.WINDOW)
                    ops += DrawOp.Polyline(listOf(center.a, center.b), false, WINDOW_PT, PlanColors.WINDOW)
                }
                OpeningType.OPENING -> Unit
            }
        }

        // Pass 4: selection highlight (under the labels so they stay readable).
        val selected = rooms.filter { isSelected(it) }
        for (room in selected) {
            val pts = room.corners.filter { it.isFinite() }
            if (pts.size >= 2) ops += DrawOp.Polyline(pts, pts.size >= 3, SELECTION_PT, PlanColors.SELECTION)
            val wall = options.selectedWall
            val c = room.corners
            if (wall != null && wall in c.indices && c.size >= 2) {
                val a = c[wall]
                val b = c[(wall + 1) % c.size]
                if (a.isFinite() && b.isFinite()) {
                    ops += DrawOp.Polyline(listOf(a, b), false, SELECTED_WALL_PT, PlanColors.SELECTION)
                }
            }
        }

        // Pass 4b: flagged rooms (e.g. overlapping) get a dashed warning outline above fills and symbols.
        if (options.highlightRoomIds.isNotEmpty()) {
            for (room in rooms) {
                if (room.id !in options.highlightRoomIds) continue
                val pts = room.corners.filter { it.isFinite() }
                if (pts.size < 2) continue
                ops += DrawOp.Polyline(pts, pts.size >= 3, HIGHLIGHT_PT, PlanColors.WARNING, dashPt = HIGHLIGHT_DASH_PT)
            }
        }

        // Pass 4c: overall exterior dimensions (lines first, then labels, all under the room labels).
        if (options.showOverallDimensions ?: settings.showOverallDimensions) {
            val metersPerPt = options.pointsPerMeter?.takeIf { it.isFinite() && it > 0.0 }?.let { 1.0 / it }?.takeIf { it.isFinite() && it > 0.0 }
                ?: OverallDimensions.nominalMetersPerPt(drawable, thickness)
            val dims = if (metersPerPt == null) emptyList()
            else OverallDimensions.compute(drawable, settings, metersPerPt, textScale)
            for (dim in dims) {
                val tag = "dimension:${dim.kind}"
                ops += DrawOp.Polyline(listOf(dim.line.a, dim.line.b), false, DIMENSION_LINE_PT, PlanColors.DIMENSION, tag = tag)
                for (e in dim.extensions) ops += DrawOp.Polyline(listOf(e.a, e.b), false, EXTENSION_LINE_PT, PlanColors.DIMENSION, tag = tag)
                for (t in dim.ticks) ops += DrawOp.Polyline(listOf(t.a, t.b), false, TICK_LINE_PT, PlanColors.DIMENSION, tag = tag)
                bounds = bounds.union(dim.bounds)
            }
            for (dim in dims) {
                ops += DrawOp.Text(
                    anchor = dim.labelAnchor,
                    text = dim.label,
                    sizePt = dim.labelSizePt,
                    color = PlanColors.TEXT,
                    bold = true,
                    angle = dim.labelAngle,
                    offsetPt = Vec2(0.0, dim.labelOffsetPt),
                    haloColor = PlanColors.BACKGROUND,
                    tag = "dimension:${dim.kind}",
                )
            }
        }

        // Pass 5: room labels. Pass 6: wall lengths.
        for (d in drawable) {
            if (d == null) continue
            val label = PlanGeometry.roomLabel(
                d, settings, options.showRoomNames, options.showRoomDimensions, showAreas, textScale,
            ) ?: continue
            val halo = fillOf(d.room)
            label.lines.forEachIndexed { i, line ->
                ops += DrawOp.Text(
                    anchor = label.anchor,
                    text = line.text,
                    sizePt = line.sizePt,
                    color = line.color,
                    bold = line.bold,
                    angle = label.angle,
                    offsetPt = Vec2(0.0, label.offsetsPt[i]),
                    haloColor = halo,
                )
            }
            bounds = bounds.include(label.anchor)
        }
        if (showWallLengths) {
            for (d in drawable) {
                if (d == null) continue
                ops += wallLengthLabels(d, settings, textScale, fillOf(d.room))
            }
        }

        // Pass 7: corner handles on top of everything.
        if (options.showCornerHandles) {
            for (room in selected) {
                room.corners.forEachIndexed { i, p ->
                    if (!p.isFinite()) return@forEachIndexed
                    ops += DrawOp.Dot(p, HANDLE_RADIUS_PT, PlanColors.HANDLE_FILL, PlanColors.SELECTION, "corner:${room.id}:$i")
                }
            }
        }
        return DrawList(ops, bounds)
    }

    /**
     * One length label per edge of at least [MIN_LABELED_WALL]: centered on the edge, upright along it and
     * pushed [WALL_LABEL_OFFSET_PT] into the room (the sign is chosen from the room's inward normal versus
     * the text's up vector, so the label is inside whichever way the edge runs).
     */
    private fun wallLengthLabels(d: DrawableRoom, settings: PlanSettings, textScale: Double, halo: Int): List<DrawOp.Text> {
        val c = d.corners
        return c.indices.mapNotNull { i ->
            val a = c[i]
            val b = c[(i + 1) % c.size]
            val length = a.distanceTo(b)
            if (length < MIN_LABELED_WALL) return@mapNotNull null
            val dir = PlanGeometry.edgeDirection(c, i) ?: return@mapNotNull null
            val inward = dir.perp() * d.orientation
            val angle = PlanGeometry.uprightAngle(dir.angle())
            val up = Vec2.fromAngle(angle + PI / 2)
            val sign = if ((up dot inward) >= 0) 1.0 else -1.0
            DrawOp.Text(
                anchor = Vec2.lerp(a, b, 0.5),
                text = Units.formatLength(length, settings.unitSystem, settings.imperialDenominator),
                sizePt = WALL_LABEL_PT * textScale,
                color = PlanColors.DIMENSION,
                angle = angle,
                offsetPt = Vec2(0.0, sign * WALL_LABEL_OFFSET_PT * textScale),
                haloColor = halo,
                tag = "wall:${d.room.id}:$i",
            )
        }
    }

    private fun roomTag(room: Room) = "room:${room.id}"
}

/**
 * One overall dimension of the floor ([kind] "width" or "depth"): the dimension [line], [extensions] from near
 * the building to just past the line, 45° [ticks] at both ends and a label centered on the line.
 * [labelOffsetPt] shifts the label (along the text's up vector) so it sits just outside the line, away from
 * the plan. [bounds] covers everything including an estimate of the label box at the scale it was built for.
 * Geometry is in plan meters.
 */
internal data class DimensionLine(
    val kind: String,
    val length: Double,
    val line: Segment2,
    val extensions: List<Segment2>,
    val ticks: List<Segment2>,
    val label: String,
    val labelAnchor: Vec2,
    val labelAngle: Double,
    val labelOffsetPt: Double,
    val labelSizePt: Double,
    val bounds: Bounds,
)

/**
 * Overall exterior dimensions of a floor, shared by [PlanRenderer] (so also SVG / PDF / PNG) and [DxfExporter].
 *
 * The frame is the floor's dominant wall axis ([FloorAxis], turned into (-45°, 45°] so "width" is the more
 * horizontal direction): a straightened floor gets exactly horizontal / vertical dimensions and a tilted one
 * gets dimensions along its walls. Width and depth measure to the OUTSIDE of the exterior walls: the extents of
 * all interior corners in that frame, padded on each side by [PlanGeometry.exteriorWallThickness]
 * ([PlanSettings.exteriorWallThickness], default 6.5", else the interior wall thickness). The drawn wall bands
 * stay at the interior thickness (it is also the snapping gap). The width line runs above the plan, the depth
 * line to its left; each extension line starts just off the outer face of the outermost wall at that end.
 *
 * Offsets, tick sizes and label gaps are specified in points but have to be plan meters before the plan is
 * scaled onto an output, so they are converted with a meters-per-point scale: the caller's known output scale
 * ([RenderOptions.pointsPerMeter]), [nominalMetersPerPt] for DrawLists (the plan spread over about
 * [NOMINAL_PLAN_PT] points, i.e. a Letter / A4 page), or the DXF text scale. They are multiplied by the text
 * scale too, so labels and their gaps stay in proportion.
 */
internal object OverallDimensions {
    const val OFFSET_PT = 24.0
    const val EXTENSION_GAP_PT = 3.0
    const val EXTENSION_OVERSHOOT_PT = 3.0
    const val TICK_PT = 8.0
    const val LABEL_PT = 8.0
    const val LABEL_GAP_PT = 2.0

    /** Width (points) a floor plan is assumed to span on its output when converting point sizes to meters. */
    const val NOMINAL_PLAN_PT = 560.0

    /** Interior extents shorter than this (m) get no dimension. */
    const val MIN_EXTENT = 0.3

    /** Vertices within this distance (m) of an extreme count as "at" that extreme when placing extension lines. */
    private const val EXTREME_TOLERANCE = 1e-3

    /** Estimated label width per character, as a fraction of the text size. */
    private const val CHAR_WIDTH = 0.6

    /** Plan meters per point for a DrawList: the larger plan extent spread over [NOMINAL_PLAN_PT]; null if nothing is drawable. */
    fun nominalMetersPerPt(rooms: List<DrawableRoom?>, thickness: Double): Double? {
        val b = Bounds.of(rooms.filterNotNull().flatMap { it.band(thickness) }.filter { it.isFinite() })
        if (b.isEmpty) return null
        val extent = max(b.width, b.height)
        return if (extent.isFinite() && extent > 0.0) extent / NOMINAL_PLAN_PT else null
    }

    /** Width and depth dimensions of the drawable [rooms] (either may be missing for a very thin floor). */
    fun compute(
        rooms: List<DrawableRoom?>,
        settings: PlanSettings,
        metersPerPt: Double,
        textScale: Double,
    ): List<DimensionLine> {
        if (!metersPerPt.isFinite() || metersPerPt <= 0.0) return emptyList()
        val drawable = rooms.filterNotNull()
        val pts = drawable.flatMap { it.corners }.filter { it.isFinite() }
        if (pts.isEmpty()) return emptyList()
        val pad = PlanGeometry.exteriorWallThickness(settings)
        val axis = FloorAxis.ofOutlines(drawable.map { it.corners }) ?: 0.0
        val phi = FloorAxis.tilt(axis)
        fun toPlan(p: Vec2) = p.rotated(phi)
        val frame = pts.map { it.rotated(-phi) }
        val inner = Bounds.of(frame)
        val b = Bounds(inner.minX - pad, inner.minY - pad, inner.maxX + pad, inner.maxY + pad)
        val m = metersPerPt * textScale
        val sizePt = LABEL_PT * textScale
        val out = ArrayList<DimensionLine>(2)

        fun build(kind: String, start: Vec2, end: Vec2, extensions: List<Segment2>, outward: Vec2, length: Double) {
            val half = Vec2(1.0, 1.0).normalized() * (TICK_PT / 2 * m)
            val ticks = listOf(start, end).map { Segment2(toPlan(it - half), toPlan(it + half)) }
            val angle = PlanGeometry.uprightAngle(toPlan(end - start).angle())
            val up = Vec2.fromAngle(angle + PI / 2)
            val sign = if ((up dot toPlan(outward)) >= 0) 1.0 else -1.0
            val offsetPt = sign * (LABEL_GAP_PT * textScale + sizePt / 2)
            val label = Units.formatLength(length, settings.unitSystem, settings.imperialDenominator)
            val anchor = toPlan(Vec2.lerp(start, end, 0.5))
            val line = Segment2(toPlan(start), toPlan(end))
            // Label box: centered offsetPt along `up`, about CHAR_WIDTH x size per character wide.
            val center = anchor + up * (offsetPt * metersPerPt)
            val along = Vec2.fromAngle(angle) * (CHAR_WIDTH * sizePt * label.length / 2 * metersPerPt)
            val across = up * (sizePt / 2 * metersPerPt)
            var bounds = Bounds.of(listOf(line.a, line.b))
            for (s in extensions + ticks) bounds = bounds.include(s.a).include(s.b)
            for (corner in listOf(center + along + across, center + along - across, center - along + across, center - along - across)) {
                bounds = bounds.include(corner)
            }
            out += DimensionLine(kind, length, line, extensions, ticks, label, anchor, angle, offsetPt, sizePt, bounds)
        }

        // Extension lines start just off the outer face (interior corner + pad) of the outermost wall at each end.
        if (inner.width >= MIN_EXTENT) {
            val y = b.maxY + OFFSET_PT * m
            val leftTop = frame.filter { it.x <= inner.minX + EXTREME_TOLERANCE }.maxOf { it.y } + pad
            val rightTop = frame.filter { it.x >= inner.maxX - EXTREME_TOLERANCE }.maxOf { it.y } + pad
            val beyond = y + EXTENSION_OVERSHOOT_PT * m
            val extensions = listOf(
                Segment2(toPlan(Vec2(b.minX, leftTop + EXTENSION_GAP_PT * m)), toPlan(Vec2(b.minX, beyond))),
                Segment2(toPlan(Vec2(b.maxX, rightTop + EXTENSION_GAP_PT * m)), toPlan(Vec2(b.maxX, beyond))),
            )
            build("width", Vec2(b.minX, y), Vec2(b.maxX, y), extensions, Vec2(0.0, 1.0), b.width)
        }
        if (inner.height >= MIN_EXTENT) {
            val x = b.minX - OFFSET_PT * m
            val bottomLeft = frame.filter { it.y <= inner.minY + EXTREME_TOLERANCE }.minOf { it.x } - pad
            val topLeft = frame.filter { it.y >= inner.maxY - EXTREME_TOLERANCE }.minOf { it.x } - pad
            val beyond = x - EXTENSION_OVERSHOOT_PT * m
            val extensions = listOf(
                Segment2(toPlan(Vec2(bottomLeft - EXTENSION_GAP_PT * m, b.minY)), toPlan(Vec2(beyond, b.minY))),
                Segment2(toPlan(Vec2(topLeft - EXTENSION_GAP_PT * m, b.maxY)), toPlan(Vec2(beyond, b.maxY))),
            )
            build("depth", Vec2(x, b.minY), Vec2(x, b.maxY), extensions, Vec2(-1.0, 0.0), b.height)
        }
        return out
    }
}
