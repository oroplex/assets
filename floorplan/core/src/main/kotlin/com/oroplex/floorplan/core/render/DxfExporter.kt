package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import java.util.Locale
import kotlin.math.PI

object DxfExporter {
    const val INCHES_PER_METER = 39.37007874
    const val MILLIMETERS_PER_METER = 1000.0

    /** DXF layer names with their AutoCAD Color Index (ACI) colors. */
    const val LAYER_ROOMS = "ROOMS"
    const val LAYER_WALLS = "WALLS"
    const val LAYER_DOORS = "DOORS"
    const val LAYER_WINDOWS = "WINDOWS"
    const val LAYER_TEXT = "TEXT"
    const val LAYER_DIMENSIONS = "DIMENSIONS"
    private val LAYERS = listOf(
        "0" to 7,
        LAYER_ROOMS to 8, // gray
        LAYER_WALLS to 7, // white / black
        LAYER_DOORS to 1, // red
        LAYER_WINDOWS to 5, // blue
        LAYER_TEXT to 3, // green
        LAYER_DIMENSIONS to 4, // cyan
    )

    /**
     * Label point sizes from the on-screen layout are turned into model-space text heights with this factor
     * (meters per point): a 10 pt room name becomes ~0.18 m (7") tall text, readable at 1:50 / 1/4" scale.
     */
    private const val TEXT_METERS_PER_PT = 0.018

    /**
     * ASCII DXF (AutoCAD R12 / AC1009, maximally compatible) of the rooms, in the plan frame (y up).
     * Units: inches when settings.unitSystem == IMPERIAL, millimeters when METRIC ($INSUNITS 1 / 4 in header).
     * Layers: ROOMS (closed interior outlines), WALLS (closed outer outlines offset by wall thickness),
     * DOORS (leaf LINE + swing ARC), WINDOWS (LINEs), TEXT (room name / dims / area TEXT entities),
     * DIMENSIONS (when settings.showOverallDimensions: the overall width / depth dimension lines, extension
     * lines and 45° ticks as LINEs plus their labels as TEXT, laid out like the on-screen plan with the same
     * meters-per-point scale as the DXF text, see [OverallDimensions]).
     *
     * R12 has no LWPOLYLINE, so outlines are POLYLINE / VERTEX / SEQEND with the closed flag. Door and window
     * symbols use the same geometry as the on-screen plan. Rooms with an unusable outline (fewer than 3 finite
     * corners, self-intersecting, no area) only get their outline on ROOMS (open when it has 2 points).
     * Text is written as ASCII ("×" becomes "x", "²" becomes "2", other non-ASCII characters become
     * \U+XXXX escapes).
     */
    fun export(rooms: List<Room>, settings: PlanSettings): String {
        val scale = if (settings.unitSystem == UnitSystem.IMPERIAL) INCHES_PER_METER else MILLIMETERS_PER_METER
        val insUnits = if (settings.unitSystem == UnitSystem.IMPERIAL) 1 else 4
        val thickness = PlanGeometry.wallThickness(settings)
        val drawable = rooms.map { PlanGeometry.drawable(it) }
        val entities = Writer(scale)

        rooms.forEachIndexed { i, room ->
            val d = drawable[i]
            if (d != null) {
                entities.polyline(LAYER_ROOMS, d.corners, closed = true)
                entities.polyline(LAYER_WALLS, d.band(thickness), closed = true)
            } else {
                val pts = room.corners.filter { it.isFinite() }
                if (pts.size >= 2) entities.polyline(LAYER_ROOMS, pts, closed = pts.size >= 3)
            }
        }

        val drawn = ArrayList<OpeningFrame>()
        for (f in PlanGeometry.openingFrames(drawable)) {
            if (f.opening.type == OpeningType.OPENING || PlanGeometry.isDuplicate(f, drawn, thickness)) continue
            drawn += f
            when (f.opening.type) {
                OpeningType.DOOR -> {
                    val door = PlanGeometry.door(f, f.opening.swing, thickness)
                    entities.line(LAYER_DOORS, door.hinge, door.openEnd)
                    entities.arc(LAYER_DOORS, door.hinge, door.radius, door.startAngle, door.sweep)
                }
                OpeningType.WINDOW -> {
                    val outline = PlanGeometry.windowOutline(f, thickness)
                    for (k in outline.indices) entities.line(LAYER_WINDOWS, outline[k], outline[(k + 1) % outline.size])
                    val center = PlanGeometry.windowCenterLine(f, thickness)
                    entities.line(LAYER_WINDOWS, center.a, center.b)
                }
                OpeningType.OPENING -> Unit
            }
        }

        for (d in drawable) {
            if (d == null) continue
            val label = PlanGeometry.roomLabel(
                d, settings, showName = true, showDimensions = true, showArea = settings.showRoomAreas, textScale = 1.0,
            ) ?: continue
            val up = Vec2.fromAngle(label.angle + PI / 2)
            label.lines.forEachIndexed { k, line ->
                val at = label.anchor + up * (label.offsetsPt[k] * TEXT_METERS_PER_PT)
                entities.text(LAYER_TEXT, at, line.sizePt * TEXT_METERS_PER_PT, label.angle, line.text)
            }
        }

        if (settings.showOverallDimensions) {
            for (dim in OverallDimensions.compute(drawable, settings, thickness, TEXT_METERS_PER_PT, textScale = 1.0)) {
                entities.line(LAYER_DIMENSIONS, dim.line.a, dim.line.b)
                for (s in dim.extensions + dim.ticks) entities.line(LAYER_DIMENSIONS, s.a, s.b)
                val up = Vec2.fromAngle(dim.labelAngle + PI / 2)
                val at = dim.labelAnchor + up * (dim.labelOffsetPt * TEXT_METERS_PER_PT)
                entities.text(LAYER_DIMENSIONS, at, dim.labelSizePt * TEXT_METERS_PER_PT, dim.labelAngle, dim.label)
            }
        }

        val out = Writer(scale)
        out.header(entities.bounds, insUnits)
        out.tables()
        out.pair(0, "SECTION")
        out.pair(2, "ENTITIES")
        out.raw(entities.body())
        out.pair(0, "ENDSEC")
        out.pair(0, "EOF")
        return out.body()
    }

    /** Group-code / value line pairs; coordinates are converted from plan meters to drawing units. */
    private class Writer(private val scale: Double) {
        private val sb = StringBuilder(16 * 1024)
        var bounds: Bounds = Bounds.EMPTY
            private set

        fun body(): String = sb.toString()

        fun raw(text: String) {
            sb.append(text)
        }

        fun pair(code: Int, value: String) {
            sb.append(code.toString().padStart(3)).append('\n').append(value).append('\n')
        }

        fun pair(code: Int, value: Int) = pair(code, value.toString())

        fun pair(code: Int, value: Double) = pair(code, num(value))

        private fun point(base: Int, p: Vec2) {
            bounds = bounds.include(p)
            pair(base, p.x * scale)
            pair(base + 10, p.y * scale)
            pair(base + 20, 0.0)
        }

        fun polyline(layer: String, pts: List<Vec2>, closed: Boolean) {
            if (pts.size < 2 || pts.any { !it.isFinite() }) return
            pair(0, "POLYLINE")
            pair(8, layer)
            pair(66, 1)
            pair(10, 0.0)
            pair(20, 0.0)
            pair(30, 0.0)
            pair(70, if (closed) 1 else 0)
            for (p in pts) {
                pair(0, "VERTEX")
                pair(8, layer)
                point(10, p)
            }
            pair(0, "SEQEND")
            pair(8, layer)
        }

        fun line(layer: String, a: Vec2, b: Vec2) {
            if (!a.isFinite() || !b.isFinite()) return
            pair(0, "LINE")
            pair(8, layer)
            point(10, a)
            point(11, b)
        }

        /** DXF arcs always run counter-clockwise from start (50) to end (51), in degrees. */
        fun arc(layer: String, center: Vec2, radius: Double, startAngle: Double, sweep: Double) {
            if (!center.isFinite() || !radius.isFinite() || radius <= 0.0 || !startAngle.isFinite() || !sweep.isFinite()) return
            val from = if (sweep >= 0) startAngle else startAngle + sweep
            val to = if (sweep >= 0) startAngle + sweep else startAngle
            for (p in PlanGeometry.arcExtremes(center, radius, startAngle, sweep)) bounds = bounds.include(p)
            pair(0, "ARC")
            pair(8, layer)
            pair(10, center.x * scale)
            pair(20, center.y * scale)
            pair(30, 0.0)
            pair(40, radius * scale)
            pair(50, degrees(from))
            pair(51, degrees(to))
        }

        /** Middle-center justified TEXT ([at] is the alignment point, so it is written as both 10 and 11). */
        fun text(layer: String, at: Vec2, height: Double, angle: Double, text: String) {
            val value = ascii(text)
            if (value.isBlank() || !at.isFinite() || !height.isFinite() || height <= 0.0) return
            pair(0, "TEXT")
            pair(8, layer)
            point(10, at)
            pair(40, height * scale)
            pair(1, value)
            pair(50, if (angle.isFinite()) degrees(angle) else 0.0)
            pair(7, "STANDARD")
            pair(72, 1)
            point(11, at)
            pair(73, 2)
        }

        fun header(extents: Bounds, insUnits: Int) {
            val (min, max) = if (extents.isEmpty) Vec2.ZERO to Vec2.ZERO
            else Vec2(extents.minX, extents.minY) to Vec2(extents.maxX, extents.maxY)
            pair(0, "SECTION")
            pair(2, "HEADER")
            pair(9, "\$ACADVER")
            pair(1, "AC1009")
            pair(9, "\$INSBASE")
            pair(10, 0.0)
            pair(20, 0.0)
            pair(30, 0.0)
            pair(9, "\$EXTMIN")
            pair(10, min.x * scale)
            pair(20, min.y * scale)
            pair(30, 0.0)
            pair(9, "\$EXTMAX")
            pair(10, max.x * scale)
            pair(20, max.y * scale)
            pair(30, 0.0)
            pair(9, "\$INSUNITS")
            pair(70, insUnits)
            pair(0, "ENDSEC")
        }

        /** LTYPE (CONTINUOUS), LAYER and STYLE (STANDARD) tables: what R12 readers expect entities to reference. */
        fun tables() {
            pair(0, "SECTION")
            pair(2, "TABLES")

            pair(0, "TABLE")
            pair(2, "LTYPE")
            pair(70, 1)
            pair(0, "LTYPE")
            pair(2, "CONTINUOUS")
            pair(70, 0)
            pair(3, "Solid line")
            pair(72, 65)
            pair(73, 0)
            pair(40, 0.0)
            pair(0, "ENDTAB")

            pair(0, "TABLE")
            pair(2, "LAYER")
            pair(70, LAYERS.size)
            for ((name, color) in LAYERS) {
                pair(0, "LAYER")
                pair(2, name)
                pair(70, 0)
                pair(62, color)
                pair(6, "CONTINUOUS")
            }
            pair(0, "ENDTAB")

            pair(0, "TABLE")
            pair(2, "STYLE")
            pair(70, 1)
            pair(0, "STYLE")
            pair(2, "STANDARD")
            pair(70, 0)
            pair(40, 0.0)
            pair(41, 1.0)
            pair(50, 0.0)
            pair(71, 0)
            pair(42, 2.5)
            pair(3, "txt")
            pair(4, "")
            pair(0, "ENDTAB")

            pair(0, "ENDSEC")
        }

        private fun degrees(radians: Double): Double {
            var d = Math.toDegrees(radians) % 360.0
            if (d < 0) d += 360.0
            return if (d >= 360.0 - 1e-9) 0.0 else d
        }

        /** Locale-independent decimal with at most 6 decimals, always with a decimal point. */
        private fun num(v: Double): String {
            if (!v.isFinite()) return "0.0"
            var s = String.format(Locale.ROOT, "%.6f", v).trimEnd('0')
            if (s.endsWith('.')) s += "0"
            return if (s == "-0.0") "0.0" else s
        }
    }

    /**
     * Plain-ASCII text for a DXF TEXT value: line breaks become spaces, control characters are dropped,
     * "×" -> "x", "²" -> "2", other non-ASCII BMP characters -> AutoCAD's \U+XXXX escape, and characters
     * outside the BMP -> "?". A "%%" sequence would be read as a control code, so '%' is then escaped as "%%%".
     */
    internal fun ascii(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                ch < ' ' || ch == '\u007F' -> Unit
                ch.code < 0x80 -> sb.append(ch)
                ch == '×' -> sb.append('x')
                ch == '²' -> sb.append('2')
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) i++
                    sb.append('?')
                }
                Character.isLowSurrogate(ch) -> sb.append('?')
                else -> sb.append("\\U+").append(String.format(Locale.ROOT, "%04X", ch.code))
            }
            i++
        }
        val s = sb.toString().trim()
        return if ("%%" in s) s.replace("%", "%%%") else s
    }
}
