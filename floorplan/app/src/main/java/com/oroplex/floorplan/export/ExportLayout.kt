package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.model.UnitSystem
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Pure layout math shared by the PDF / PNG exporters and the on-screen page preview.
 * Everything here is in points (1/72 in) and has no Android dependencies so it can be unit-tested.
 */

/** Axis-aligned rectangle in sheet points, y down (top < bottom). */
data class SheetRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
}

/**
 * Fixed geometry of a plan sheet: header band (who and which property), plan area, footer title block
 * (which sheet, area, scale bar, date, page).
 */
object SheetLayout {
    /** Page margin on every side (0.5 in). */
    const val MARGIN = 36.0
    const val HEADER_HEIGHT = 54.0
    const val FOOTER_HEIGHT = 52.0

    /** Space between the header/footer bands and the plan area. */
    const val GAP = 10.0

    /** Inner margin inside the plan area so wall bands and labels never touch the bands. */
    const val PLAN_FIT_MARGIN = 14.0

    /** Plans wider than this (width / height) go on landscape pages. */
    const val LANDSCAPE_ASPECT = 1.15

    /**
     * Smallest / largest plan-area height of a PNG sheet, and the height used for empty floors.
     * The maximum keeps a 3000 px wide ARGB bitmap under ~50 MB.
     */
    const val PNG_MIN_PLAN_HEIGHT = 220.0
    const val PNG_MAX_PLAN_HEIGHT = 900.0
    const val PNG_EMPTY_PLAN_HEIGHT = 320.0

    /** Vertical space used by everything except the plan area. */
    const val CHROME_HEIGHT = 2 * MARGIN + HEADER_HEIGHT + FOOTER_HEIGHT + 2 * GAP

    fun isLandscape(bounds: Bounds?): Boolean =
        bounds != null && !bounds.isEmpty && bounds.height > 1e-9 && bounds.width / bounds.height > LANDSCAPE_ASPECT

    fun header(widthPt: Double): SheetRect = SheetRect(MARGIN, MARGIN, widthPt - MARGIN, MARGIN + HEADER_HEIGHT)

    fun footer(widthPt: Double, heightPt: Double): SheetRect =
        SheetRect(MARGIN, heightPt - MARGIN - FOOTER_HEIGHT, widthPt - MARGIN, heightPt - MARGIN)

    fun planArea(widthPt: Double, heightPt: Double): SheetRect {
        val top = MARGIN + HEADER_HEIGHT + GAP
        val bottom = (heightPt - MARGIN - FOOTER_HEIGHT - GAP).coerceAtLeast(top + 1.0)
        return SheetRect(MARGIN, top, widthPt - MARGIN, bottom)
    }

    /**
     * Height of a PNG sheet [widthPt] wide whose plan area matches the aspect of [bounds]
     * (clamped so extremely long or flat plans still produce a sensible image).
     */
    fun pngSheetHeight(widthPt: Double, bounds: Bounds?): Double {
        val planHeight = if (bounds == null || bounds.isEmpty || bounds.width <= 1e-9) {
            PNG_EMPTY_PLAN_HEIGHT
        } else {
            val innerWidth = widthPt - 2 * MARGIN - 2 * PLAN_FIT_MARGIN
            (innerWidth * bounds.height / bounds.width + 2 * PLAN_FIT_MARGIN)
                .coerceIn(PNG_MIN_PLAN_HEIGHT, PNG_MAX_PLAN_HEIGHT)
        }
        return CHROME_HEIGHT + planHeight
    }
}

/** Where the parts of the company block go inside the header band (sheet points, y down). */
data class BrandBlockLayout(
    /** Rectangle the logo is drawn into (aspect preserved), or null without a usable logo. */
    val logo: SheetRect?,
    /** Left edge of the company name / contact text. */
    val textLeft: Double,
)

object HeaderLayout {
    /** The logo is fitted into this box at the left of the header (about 0.55 x 1.7 in). */
    const val LOGO_MAX_HEIGHT = 40.0
    const val LOGO_MAX_WIDTH = 120.0

    /** Space between the logo and the company text. */
    const val LOGO_TEXT_GAP = 10.0

    /** The project block on the right never takes more than this fraction of the header width. */
    const val PROJECT_MAX_FRACTION = 0.45

    /** Minimum space between the left and right blocks. */
    const val BLOCK_GAP = 18.0

    /**
     * Size of an [imageWidth] x [imageHeight] image scaled to fit inside [maxWidth] x [maxHeight]
     * with its aspect ratio preserved (scaled up or down). Null for empty / invalid sizes.
     */
    fun containSize(imageWidth: Double, imageHeight: Double, maxWidth: Double, maxHeight: Double): Pair<Double, Double>? {
        if (!(imageWidth > 0 && imageHeight > 0 && maxWidth > 0 && maxHeight > 0)) return null
        if (!imageWidth.isFinite() || !imageHeight.isFinite() || !maxWidth.isFinite() || !maxHeight.isFinite()) return null
        val f = minOf(maxWidth / imageWidth, maxHeight / imageHeight)
        return imageWidth * f to imageHeight * f
    }

    /**
     * Company block layout inside [header]: the logo (when [logoPixels] is a valid size) left-aligned and
     * vertically centered in the band, the text starting [LOGO_TEXT_GAP] to its right.
     */
    fun brandBlock(header: SheetRect, logoPixels: Pair<Int, Int>?): BrandBlockLayout {
        val size = logoPixels?.let {
            containSize(it.first.toDouble(), it.second.toDouble(), LOGO_MAX_WIDTH, minOf(LOGO_MAX_HEIGHT, header.height - 6))
        } ?: return BrandBlockLayout(logo = null, textLeft = header.left)
        val (w, h) = size
        // The band's hairline sits 2 pt above its bottom; center the logo in the space above it.
        val centerY = header.top + (header.height - 2) / 2
        val logo = SheetRect(header.left, centerY - h / 2, header.left + w, centerY + h / 2)
        return BrandBlockLayout(logo, logo.right + LOGO_TEXT_GAP)
    }
}

/** Geometry of the room schedule pages (below the same header band as the plan sheets). */
object ScheduleLayout {
    /** Space for the "Room schedule" title between the header band and the table. */
    const val TITLE_SPACE = 30.0
    const val HEADER_ROW_HEIGHT = 20.0
    const val ROW_HEIGHT = 18.0

    /** Line under the table for the "* estimated, not measured" footnote. */
    const val FOOTNOTE_SPACE = 16.0

    /** Bottom line (disclaimer, date, page number) plus breathing room. */
    const val BOTTOM_RESERVE = 22.0

    fun titleBaseline(heightPt: Double): Double = SheetLayout.planArea(0.0, heightPt).top + 14.0

    fun tableTop(heightPt: Double): Double = SheetLayout.planArea(0.0, heightPt).top + TITLE_SPACE

    fun tableBottom(heightPt: Double): Double = heightPt - SheetLayout.MARGIN - BOTTOM_RESERVE - FOOTNOTE_SPACE

    /** How many body rows fit under the column header on a page [heightPt] tall (at least 1). */
    fun rowsPerPage(heightPt: Double): Int =
        floor((tableBottom(heightPt) - tableTop(heightPt) - HEADER_ROW_HEIGHT) / ROW_HEIGHT).toInt().coerceAtLeast(1)
}

/** A scale bar of [segments] alternating black / white blocks, [lengthPt] long, labeled [label]. */
data class ScaleBarSpec(val lengthMeters: Double, val lengthPt: Double, val segments: Int, val label: String)

object ScaleBars {
    /** Candidate lengths in feet (imperial) and meters (metric); "nice" round values only. */
    private val IMPERIAL_FEET = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0)
    private val METRIC_METERS = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)

    /** The bar aims at this fraction of the plan width (acceptable range roughly 15-25 %). */
    const val TARGET_FRACTION = 0.20

    /**
     * Chooses the nice length whose drawn size is closest (in ratio) to [TARGET_FRACTION] of
     * [planWidthPt] without exceeding [maxLengthPt]. When even the smallest candidate is too long
     * (a tiny plan drawn at a huge scale) the smallest one is used anyway.
     * Returns null for a non-finite / non-positive scale.
     */
    fun choose(ptPerMeter: Double, planWidthPt: Double, system: UnitSystem, maxLengthPt: Double): ScaleBarSpec? {
        if (!ptPerMeter.isFinite() || ptPerMeter <= 0 || !planWidthPt.isFinite() || planWidthPt <= 0) return null
        val unitMeters = if (system == UnitSystem.IMPERIAL) 0.3048 else 1.0
        val values = if (system == UnitSystem.IMPERIAL) IMPERIAL_FEET else METRIC_METERS
        val target = planWidthPt * TARGET_FRACTION
        val fitting = values.filter { it * unitMeters * ptPerMeter <= maxLengthPt }
        val value = fitting.minByOrNull { abs(ln(it * unitMeters * ptPerMeter / target)) } ?: values.first()
        val meters = value * unitMeters
        return ScaleBarSpec(meters, meters * ptPerMeter, segmentsFor(value), label(value, system))
    }

    /** 1, 10, 100 and 5, 50 split into 5 blocks; 2, 20, 200 into 4. */
    internal fun segmentsFor(value: Double): Int {
        val mantissa = value / 10.0.pow(floor(log10(value)))
        return if (abs(mantissa - 2.0) < 1e-6) 4 else 5
    }

    internal fun label(value: Double, system: UnitSystem): String = when (system) {
        UnitSystem.IMPERIAL -> if (value < 1.0) "${(value * 12).roundToInt()} in" else "${trim(value)} ft"
        UnitSystem.METRIC -> if (value < 1.0) "${(value * 100).roundToInt()} cm" else "${trim(value)} m"
    }

    private fun trim(v: Double): String = if (v == floor(v)) v.roundToLong().toString() else v.toString()

    /**
     * Representative fraction "1:N" of a drawing printed at 100 %: one real meter is drawn
     * [ptPerMeter] points = ptPerMeter * 0.0254 / 72 meters of paper. Null for invalid scales.
     */
    fun ratio(ptPerMeter: Double): Long? {
        if (!ptPerMeter.isFinite() || ptPerMeter <= 0) return null
        val n = 72.0 / 0.0254 / ptPerMeter
        return if (n.isFinite() && n >= 1) n.roundToLong() else null
    }
}

object Pagination {
    /**
     * Splits [rows] into pages holding at most [firstPageCapacity] rows on the first page and
     * [otherPageCapacity] on the following ones (both forced to >= 1). A row for which
     * [keepWithNext] is true (e.g. a section heading) is never left as the last row of a page when
     * more rows follow; it moves to the next page instead (unless it would be alone on its page).
     * Always returns at least one (possibly empty) page.
     */
    fun <T> paginate(
        rows: List<T>,
        firstPageCapacity: Int,
        otherPageCapacity: Int,
        keepWithNext: (T) -> Boolean = { false },
    ): List<List<T>> {
        val first = firstPageCapacity.coerceAtLeast(1)
        val other = otherPageCapacity.coerceAtLeast(1)
        if (rows.isEmpty()) return listOf(emptyList())
        val pages = ArrayList<List<T>>()
        var index = 0
        while (index < rows.size) {
            val capacity = if (pages.isEmpty()) first else other
            var end = minOf(index + capacity, rows.size)
            // Don't strand a heading at the bottom of a page.
            while (end < rows.size && end - 1 > index && keepWithNext(rows[end - 1])) end--
            pages += rows.subList(index, end).toList()
            index = end
        }
        return pages
    }
}
