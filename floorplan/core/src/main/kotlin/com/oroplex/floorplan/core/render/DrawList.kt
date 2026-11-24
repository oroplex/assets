package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2

/*
 * Device-independent drawing primitives produced by PlanRenderer and consumed by every backend
 * (Android Canvas on screen, PDF, PNG, SVG). Geometry is in PLAN METERS (y up). Line widths, text
 * sizes and text offsets are in POINTS (1/72 inch at the output) so labels stay readable at any zoom.
 * Colors are ARGB ints (0xAARRGGBB).
 */

enum class TextAlign { START, CENTER, END }

sealed class DrawOp {
    /** Tag for hit-testing / selection in the editor, e.g. "room:<id>", "wall:<roomId>:<index>". */
    abstract val tag: String?

    data class FillPolygon(
        val points: List<Vec2>,
        val color: Int,
        override val tag: String? = null,
    ) : DrawOp()

    data class Polyline(
        val points: List<Vec2>,
        val closed: Boolean,
        val widthPt: Double,
        val color: Int,
        /** Dash length in points; 0 = solid. */
        val dashPt: Double = 0.0,
        override val tag: String? = null,
    ) : DrawOp()

    /** Circular arc in plan space; angles in plan radians (CCW), sweep may be negative. */
    data class Arc(
        val center: Vec2,
        val radius: Double,
        val startAngle: Double,
        val sweepAngle: Double,
        val widthPt: Double,
        val color: Int,
        val dashPt: Double = 0.0,
        override val tag: String? = null,
    ) : DrawOp()

    /** Marker with a fixed on-screen size (e.g. editor corner handles). */
    data class Dot(
        val center: Vec2,
        val radiusPt: Double,
        val fillColor: Int,
        val strokeColor: Int? = null,
        override val tag: String? = null,
    ) : DrawOp()

    /**
     * Text anchored at a plan point. [angle] is the baseline direction in plan radians (CCW); renderers keep
     * it within (-PI/2, PI/2] so text is upright. [offsetPt] shifts the text in its own rotated frame
     * (x along the baseline, y toward the text's "up"), in points. Text is vertically centered on the anchor.
     */
    data class Text(
        val anchor: Vec2,
        val text: String,
        val sizePt: Double,
        val color: Int,
        val bold: Boolean = false,
        val align: TextAlign = TextAlign.CENTER,
        val angle: Double = 0.0,
        val offsetPt: Vec2 = Vec2.ZERO,
        /** Optional background halo for legibility over walls. */
        val haloColor: Int? = null,
        override val tag: String? = null,
    ) : DrawOp()
}

data class DrawList(val ops: List<DrawOp>, val bounds: Bounds)

object PlanColors {
    const val WALL: Int = 0xFF262626.toInt()
    const val ROOM_FILL: Int = 0xFFFFFFFF.toInt()
    const val ROOM_FILL_SELECTED: Int = 0xFFE3F0FF.toInt()
    const val OPENING_GAP: Int = 0xFFFFFFFF.toInt()
    const val DOOR: Int = 0xFF262626.toInt()
    const val WINDOW: Int = 0xFF4A90D9.toInt()
    const val TEXT: Int = 0xFF1A1A1A.toInt()
    const val TEXT_SECONDARY: Int = 0xFF5F6368.toInt()
    const val DIMENSION: Int = 0xFF5F6368.toInt()
    const val SELECTION: Int = 0xFF1A73E8.toInt()
    const val HANDLE_FILL: Int = 0xFFFFFFFF.toInt()
    const val WARNING: Int = 0xFFD93025.toInt()
    const val BACKGROUND: Int = 0xFFFFFFFF.toInt()
}
