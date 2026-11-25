package com.oroplex.floorplan.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.render.PlanPainter
import java.text.DateFormat
import java.util.Date

/** Everything printed on one floor's plan sheet (PDF page, PNG image, on-screen page preview). */
data class FloorSheet(
    val projectName: String,
    val address: String,
    val floorName: String,
    /** e.g. "November 24, 2026"; printed as "Generated …" on the bottom line. */
    val dateText: String,
    /** Null when there is nothing to draw; [emptyMessage] is printed in the plan area instead. */
    val drawList: DrawList?,
    val emptyMessage: String,
    /** e.g. "Net living area (interior): 1,234 sq ft" (see [FloorSheets.livingAreaText]). */
    val areaText: String,
    /** e.g. "7 rooms". */
    val roomCountText: String,
    val unitSystem: UnitSystem,
    /** e.g. "Page 1 of 3"; null to omit. */
    val pageLabel: String? = null,
    /** Print the scale under the scale bar (only meaningful for paper output), see [ScaleBars.scaleNote]. */
    val showScaleRatio: Boolean = false,
    /** Company block for the header; null prints the project in its place. */
    val branding: SheetBranding? = null,
    /**
     * The standard scale the plan is drawn at ([drawList] was rendered for it); null fits the plan to the
     * plan area instead (PNG, the preview of drawings, and PDFs too large for any standard scale).
     */
    val scale: PlanScale? = null,
)

object FloorSheets {
    const val EMPTY_FLOOR_MESSAGE = "No rooms on this floor"
    const val DISCLAIMER = "Interior dimensions measured with phone AR; approximate. Verify critical measurements."

    /** Text scale used for plans on paper-sized sheets (PDF, PNG and their preview). */
    const val SHEET_TEXT_SCALE = 0.85

    fun dateText(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(epochMillis))

    fun roomCountText(count: Int): String = if (count == 1) "1 room" else "$count rooms"

    /** Right-hand text of the bottom line: "Generated <date> · Page i of n" (either part may be absent). */
    fun bottomLineText(dateText: String, pageLabel: String?): String {
        val date = dateText.trim().takeIf { it.isNotEmpty() }?.let { "Generated $it" }
        return listOfNotNull(date, pageLabel?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · ")
    }

    /** "Page 3 of 5". */
    fun pageLabel(page: Int, total: Int): String = "Page $page of $total"

    /**
     * Renders [floor] with the project's settings and the export toggles. Returns null for a floor
     * without rooms. Throws [ExportException] when the rooms cannot be drawn (corrupt geometry).
     *
     * [pointsPerMeter] is the scale the drawing will be printed at when it is known (a PDF sheet at a
     * standard scale): the renderer then sizes dimension-line offsets exactly in points. Null assumes a plan
     * fitted to a page.
     */
    fun renderFloor(
        project: Project,
        floor: Floor,
        labels: PlanLabels,
        textScale: Double = SHEET_TEXT_SCALE,
        pointsPerMeter: Double? = null,
    ): DrawList? {
        if (floor.rooms.isEmpty()) return null
        val drawList = try {
            PlanRenderer.render(
                floor.rooms,
                project.settings,
                RenderOptions(
                    textScale = textScale,
                    showWallLengths = labels.showWallLengths,
                    showAreas = labels.showAreas,
                    showOverallDimensions = labels.showOverallDimensions,
                    pointsPerMeter = pointsPerMeter,
                ),
            )
        } catch (e: Exception) {
            throw ExportException(
                "Couldn't draw the plan of “${floor.name}”. Open it in the editor and check its rooms.",
                e,
            )
        }
        if (drawList.bounds.isEmpty || !drawList.bounds.width.isFinite() || !drawList.bounds.height.isFinite()) {
            throw ExportException("The rooms on “${floor.name}” have no drawable outline.")
        }
        return drawList
    }

    /**
     * The area figure of a sheet's title block: "Net living area (interior): 1,234 sq ft", the sum of the
     * interior (net) areas of the floor's living rooms (garages, unfinished and outdoor spaces are left
     * out, see [Measurements.areaSummary]). It is deliberately not called GLA: ANSI Z765 gross living area
     * is measured to the exterior faces and comes out roughly 8-10 % larger. An em dash stands in when the
     * area cannot be computed.
     */
    fun livingAreaText(floor: Floor, system: UnitSystem): String {
        val area = try {
            Measurements.areaSummary(floor.rooms).living.takeIf { it.isFinite() }
        } catch (e: Exception) {
            null
        }
        return "$LIVING_AREA_LABEL: ${area?.let { Units.formatArea(it, system) } ?: "—"}"
    }

    /** Label of the living-area figure on sheets and in the schedule (never "GLA", see [livingAreaText]). */
    const val LIVING_AREA_LABEL = "Net living area (interior)"

    /**
     * One floor's sheet. With [scale] the plan is rendered for, and later drawn at, that standard scale
     * (PDF); without, it is fitted to the sheet's plan area (PNG).
     */
    fun build(
        project: Project,
        floor: Floor,
        labels: PlanLabels,
        dateText: String,
        branding: SheetBranding? = null,
        scale: PlanScale? = null,
    ): FloorSheet {
        val system = project.settings.unitSystem
        val drawList = renderFloor(project, floor, labels, pointsPerMeter = scale?.pointsPerMeter)
        return FloorSheet(
            projectName = project.name.trim().ifEmpty { "Untitled project" },
            address = project.address.trim(),
            floorName = floor.name.trim().ifEmpty { "Floor" },
            dateText = dateText,
            drawList = drawList,
            emptyMessage = EMPTY_FLOOR_MESSAGE,
            areaText = livingAreaText(floor, system),
            roomCountText = roomCountText(floor.rooms.size),
            unitSystem = system,
            branding = branding,
            scale = scale?.takeIf { drawList != null },
        )
    }
}

/**
 * Draws [FloorSheet]s onto any android.graphics.Canvas.
 *
 * Header, footer and scale bar are laid out in sheet points and drawn under `canvas.scale(scale)`;
 * the plan itself is drawn unscaled through [PlanPainter] with `pxPerPt = scale`, so line widths and
 * label sizes in the DrawList come out at their intended point sizes on every output
 * (PDF: scale 1, PNG: bitmap px per pt, preview: screen px per pt).
 *
 * A sheet with a [FloorSheet.scale] is drawn at exactly that drawing scale, centered in the plan area;
 * other sheets are fitted to it (see [SheetLayout.planView]). The scale bar and the scale line under it
 * always describe the scale actually drawn.
 *
 * Layout (see [SheetLayout]): the header band names who made the plan (company block, when branding
 * is set) and for which property; the footer title block names the sheet (floor), its area and room
 * count, and carries the scale bar; the bottom line has the disclaimer, generated date and page number.
 * There is deliberately no north arrow: AR headings are arbitrary.
 */
object PlanSheetPainter {
    internal const val RULE_COLOR: Int = 0xFFBDC1C6.toInt()
    private const val BLACK: Int = 0xFF000000.toInt()
    private const val WHITE: Int = 0xFFFFFFFF.toInt()

    fun draw(canvas: Canvas, sheet: FloorSheet, widthPt: Double, heightPt: Double, scale: Double) {
        if (!scale.isFinite() || scale <= 0 || widthPt <= 0 || heightPt <= 0) return
        val planRect = SheetLayout.planArea(widthPt, heightPt)

        var ptPerMeter: Double? = null
        val drawList = sheet.drawList
        if (drawList != null) {
            val view = SheetLayout.planView(drawList.bounds, planRect, sheet.scale?.pointsPerMeter, pxPerPt = scale)
            canvas.save()
            canvas.translate((planRect.left * scale).toFloat(), (planRect.top * scale).toFloat())
            PlanPainter.draw(canvas, drawList, view)
            canvas.restore()
            ptPerMeter = view.pxPerMeter / scale
        }

        canvas.save()
        canvas.scale(scale.toFloat(), scale.toFloat())
        drawHeader(canvas, widthPt, sheet.projectName, sheet.address, sheet.branding)
        if (drawList == null) {
            val paint = SheetText.paint(12.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.CENTER)
            SheetText.drawCentered(canvas, sheet.emptyMessage, planRect, paint)
        }
        drawFooter(canvas, widthPt, heightPt, sheet, planRect.width, ptPerMeter)
        canvas.restore()
    }

    /**
     * Header band. With [branding]: logo + company name + contact line on the left, project name and
     * address right-aligned on the right. Without: project name (bold 16 pt) and address on the left.
     * Long texts are ellipsized so the blocks never collide; a hairline closes the band.
     */
    internal fun drawHeader(
        canvas: Canvas,
        widthPt: Double,
        projectName: String,
        address: String,
        branding: SheetBranding?,
    ) {
        val rect = SheetLayout.header(widthPt)
        if (branding == null) {
            val titlePaint = SheetText.paint(16.0, bold = true)
            val addressPaint = SheetText.paint(10.0, color = PlanColors.TEXT_SECONDARY)
            SheetText.draw(canvas, SheetText.ellipsize(projectName, titlePaint, rect.width), rect.left, rect.top + 20, titlePaint)
            if (address.isNotEmpty()) {
                SheetText.draw(canvas, SheetText.ellipsize(address, addressPaint, rect.width), rect.left, rect.top + 35, addressPaint)
            }
        } else {
            drawBrandedHeader(canvas, rect, projectName, address, branding)
        }
        SheetText.hairline(canvas, rect.left, rect.right, rect.bottom - 2, RULE_COLOR, 0.75)
    }

    private fun drawBrandedHeader(canvas: Canvas, rect: SheetRect, projectName: String, address: String, branding: SheetBranding) {
        // Right: the property.
        val projectPaint = SheetText.paint(13.0, bold = true, align = Paint.Align.RIGHT)
        val addressPaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)
        val rightMax = rect.width * HeaderLayout.PROJECT_MAX_FRACTION
        val projectText = SheetText.ellipsize(projectName, projectPaint, rightMax)
        val addressText = SheetText.ellipsize(address, addressPaint, rightMax)
        val rightWidth = maxOf(projectPaint.measureText(projectText), addressPaint.measureText(addressText)).toDouble()
        SheetText.draw(canvas, projectText, rect.right, rect.top + 21, projectPaint)
        SheetText.draw(canvas, addressText, rect.right, rect.top + 35, addressPaint)

        // Left: the company.
        val logo = branding.logo?.takeUnless { it.isRecycled }
        val layout = HeaderLayout.brandBlock(rect, logo?.let { it.width to it.height })
        val logoRect = layout.logo
        if (logo != null && logoRect != null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val dst = RectF(logoRect.left.toFloat(), logoRect.top.toFloat(), logoRect.right.toFloat(), logoRect.bottom.toFloat())
            canvas.drawBitmap(logo, null, dst, paint)
        }
        val textMax = (rect.right - rightWidth - HeaderLayout.BLOCK_GAP - layout.textLeft).coerceAtLeast(0.0)
        val namePaint = SheetText.paint(12.0, bold = true)
        val contactPaint = SheetText.paint(8.5, color = PlanColors.TEXT_SECONDARY)
        when {
            branding.companyName.isNotEmpty() && branding.contactLine.isNotEmpty() -> {
                SheetText.draw(canvas, SheetText.ellipsize(branding.companyName, namePaint, textMax), layout.textLeft, rect.top + 21, namePaint)
                SheetText.draw(canvas, SheetText.ellipsize(branding.contactLine, contactPaint, textMax), layout.textLeft, rect.top + 34, contactPaint)
            }
            branding.companyName.isNotEmpty() ->
                SheetText.draw(canvas, SheetText.ellipsize(branding.companyName, namePaint, textMax), layout.textLeft, rect.top + 29, namePaint)
            branding.contactLine.isNotEmpty() -> {
                val single = SheetText.paint(10.0, color = PlanColors.TEXT_SECONDARY)
                SheetText.draw(canvas, SheetText.ellipsize(branding.contactLine, single, textMax), layout.textLeft, rect.top + 29, single)
            }
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        widthPt: Double,
        heightPt: Double,
        sheet: FloorSheet,
        planWidthPt: Double,
        ptPerMeter: Double?,
    ) {
        val rect = SheetLayout.footer(widthPt, heightPt)
        SheetText.hairline(canvas, rect.left, rect.right, rect.top, RULE_COLOR, 0.75)

        val maxBar = minOf(rect.width * 0.38, 230.0)
        val bar = ptPerMeter?.let { ScaleBars.choose(it, planWidthPt, sheet.unitSystem, maxBar) }
        // The scale line belongs to the bar: the standard scale the sheet was drawn at, or the fitted ratio.
        val scaleNote = if (bar != null && sheet.showScaleRatio) {
            ScaleBars.scaleNote(sheet.scale, bar.lengthPt / bar.lengthMeters)
        } else {
            null
        }
        val notePaint = SheetText.paint(SCALE_NOTE_PT, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)
        val noteWidth = scaleNote?.let { notePaint.measureText(it).toDouble() } ?: 0.0
        // The left block stops short of whichever is wider, the bar or the scale line under it.
        val leftMax = rect.width - maxOf(bar?.lengthPt ?: 0.0, noteWidth) - 24.0

        val titlePaint = SheetText.paint(12.0, bold = true)
        val detailPaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY)
        val detail = "${sheet.areaText} · ${sheet.roomCountText}"
        SheetText.draw(canvas, SheetText.ellipsize(sheet.floorName, titlePaint, leftMax), rect.left, rect.top + 17, titlePaint)
        SheetText.draw(canvas, SheetText.ellipsize(detail, detailPaint, leftMax), rect.left, rect.top + 31, detailPaint)

        if (bar != null) drawScaleBar(canvas, bar, right = rect.right, top = rect.top + 10, note = scaleNote, notePaint = notePaint)

        drawBottomLine(canvas, widthPt, heightPt, FloorSheets.bottomLineText(sheet.dateText, sheet.pageLabel))
    }

    /** Disclaimer (7 pt) on the bottom line, with "Generated … · Page i of n" right-aligned on the same line. */
    internal fun drawBottomLine(canvas: Canvas, widthPt: Double, heightPt: Double, rightText: String) {
        val left = SheetLayout.MARGIN
        val right = widthPt - SheetLayout.MARGIN
        val baseline = heightPt - SheetLayout.MARGIN
        val disclaimerPaint = SheetText.paint(7.0, color = PlanColors.TEXT_SECONDARY)
        var disclaimerMax = right - left
        if (rightText.isNotEmpty()) {
            val pagePaint = SheetText.paint(7.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)
            val fitted = SheetText.ellipsize(rightText, pagePaint, (right - left) * 0.5)
            SheetText.draw(canvas, fitted, right, baseline, pagePaint)
            disclaimerMax -= pagePaint.measureText(fitted) + 12.0
        }
        SheetText.draw(
            canvas,
            SheetText.ellipsize(FloorSheets.DISCLAIMER, disclaimerPaint, disclaimerMax),
            left,
            baseline,
            disclaimerPaint,
        )
    }

    /**
     * Alternating black / white blocks with "0" and the length label underneath, right-aligned at [right];
     * [note] (the scale line, see [ScaleBars.scaleNote]) goes under the labels.
     */
    private fun drawScaleBar(canvas: Canvas, bar: ScaleBarSpec, right: Double, top: Double, note: String?, notePaint: Paint) {
        val height = 5.0
        val left = right - bar.lengthPt
        val segment = bar.lengthPt / bar.segments
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (i in 0 until bar.segments) {
            fill.color = if (i % 2 == 0) BLACK else WHITE
            canvas.drawRect(
                (left + i * segment).toFloat(),
                top.toFloat(),
                (left + (i + 1) * segment).toFloat(),
                (top + height).toFloat(),
                fill,
            )
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.6f
            color = BLACK
        }
        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), (top + height).toFloat(), outline)

        val labelBaseline = top + height + 8.5
        SheetText.draw(canvas, "0", left, labelBaseline, SheetText.paint(7.0, align = Paint.Align.LEFT))
        SheetText.draw(canvas, bar.label, right, labelBaseline, SheetText.paint(7.0, bold = true, align = Paint.Align.RIGHT))
        if (note != null) SheetText.draw(canvas, note, right, labelBaseline + 8.5, notePaint)
    }

    /** Text size of the scale line under the scale bar. */
    private const val SCALE_NOTE_PT = 6.5
}

/** Small text helpers for sheet drawing; sizes are in the canvas' current units (points). */
internal object SheetText {
    fun paint(
        sizePt: Double,
        bold: Boolean = false,
        color: Int = PlanColors.TEXT,
        align: Paint.Align = Paint.Align.LEFT,
        italic: Boolean = false,
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = sizePt.toFloat()
        this.color = color
        textAlign = align
        typeface = when {
            bold && italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            bold -> Typeface.DEFAULT_BOLD
            italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    fun ellipsize(text: String, paint: TextPaint, maxWidth: Double): String {
        if (maxWidth <= 0 || text.isEmpty()) return ""
        if (paint.measureText(text) <= maxWidth) return text
        return TextUtils.ellipsize(text, paint, maxWidth.toFloat(), TextUtils.TruncateAt.END).toString()
    }

    fun draw(canvas: Canvas, text: String, x: Double, baseline: Double, paint: Paint) {
        if (text.isNotEmpty()) canvas.drawText(text, x.toFloat(), baseline.toFloat(), paint)
    }

    /** Draws [text] centered in [rect] (paint should use Align.CENTER). */
    fun drawCentered(canvas: Canvas, text: String, rect: SheetRect, paint: TextPaint) {
        val fitted = ellipsize(text, paint, rect.width - 8)
        val baseline = rect.centerY - (paint.descent() + paint.ascent()) / 2
        draw(canvas, fitted, rect.centerX, baseline, paint)
    }

    fun hairline(canvas: Canvas, x0: Double, x1: Double, y: Double, color: Int, widthPt: Double) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPt.toFloat()
            this.color = color
        }
        canvas.drawLine(x0.toFloat(), y.toFloat(), x1.toFloat(), y.toFloat(), p)
    }
}
