package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import kotlin.math.PI

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
     * "corner:<roomId>:<index>" on handles. Bounds include wall bands and labels' anchors.
     *
     * Op order (later ops paint over earlier ones): wall bands, room fills, warning outlines of unusable
     * rooms, opening gaps, door / window symbols, selection highlight, room labels, wall-length labels,
     * corner handles. Rooms with fewer than 3 finite corners, a self-intersecting outline or no area are
     * drawn only as a dashed [PlanColors.WARNING] outline (still tagged "room:<id>" so they can be selected
     * and repaired). A door captured from both sides of a wall is drawn once. Never throws on bad data.
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
