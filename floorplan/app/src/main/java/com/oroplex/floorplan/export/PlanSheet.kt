package com.oroplex.floorplan.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.render.PlanPainter
import java.text.DateFormat
import java.util.Date

/** Everything printed on one floor's plan sheet (PDF page, PNG image, on-screen page preview). */
data class FloorSheet(
    val projectName: String,
    val address: String,
    val floorName: String,
    val dateText: String,
    /** Null when there is nothing to draw; [emptyMessage] is printed in the plan area instead. */
    val drawList: DrawList?,
    val emptyMessage: String,
    /** e.g. "Total net floor area: 1,234 sq ft". */
    val areaText: String,
    /** e.g. "7 rooms". */
    val roomCountText: String,
    val unitSystem: UnitSystem,
    /** e.g. "Page 1 of 3"; null to omit. */
    val pageLabel: String? = null,
    /** Print the "1:N at 100 %" ratio under the scale bar (only meaningful for paper output). */
    val showScaleRatio: Boolean = false,
)

object FloorSheets {
    const val EMPTY_FLOOR_MESSAGE = "No rooms on this floor"
    const val DISCLAIMER = "Interior dimensions measured with phone AR; approximate. Verify critical measurements."

    /** Text scale used for plans on paper-sized sheets (PDF, PNG and their preview). */
    const val SHEET_TEXT_SCALE = 0.85

    fun dateText(epochMillis: Long): String = DateFormat.getDateInstance(DateFormat.LONG).format(Date(epochMillis))

    fun roomCountText(count: Int): String = if (count == 1) "1 room" else "$count rooms"

    /**
     * Renders [floor] with the project's settings and the export toggles. Returns null for a floor
     * without rooms. Throws [ExportException] when the rooms cannot be drawn (corrupt geometry).
     */
    fun renderFloor(
        project: Project,
        floor: Floor,
        showWallLengths: Boolean,
        showAreas: Boolean,
        textScale: Double = SHEET_TEXT_SCALE,
    ): DrawList? {
        if (floor.rooms.isEmpty()) return null
        val drawList = try {
            PlanRenderer.render(
                floor.rooms,
                project.settings,
                RenderOptions(textScale = textScale, showWallLengths = showWallLengths, showAreas = showAreas),
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

    /** Net floor area formatted for the project's units, or an em dash when it cannot be computed. */
    fun formatFloorArea(floor: Floor, system: UnitSystem): String =
        try {
            val area = Measurements.floorArea(floor)
            if (area.isFinite()) Units.formatArea(area, system) else "—"
        } catch (e: Exception) {
            "—"
        }

    fun build(
        project: Project,
        floor: Floor,
        showWallLengths: Boolean,
        showAreas: Boolean,
        dateText: String,
    ): FloorSheet {
        val system = project.settings.unitSystem
        return FloorSheet(
            projectName = project.name.ifBlank { "Untitled project" },
            address = project.address.trim(),
            floorName = floor.name.ifBlank { "Floor" },
            dateText = dateText,
            drawList = renderFloor(project, floor, showWallLengths, showAreas),
            emptyMessage = EMPTY_FLOOR_MESSAGE,
            areaText = "Total net floor area: ${formatFloorArea(floor, system)}",
            roomCountText = roomCountText(floor.rooms.size),
            unitSystem = system,
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
            val view = ViewTransform.fit(
                drawList.bounds,
                planRect.width * scale,
                planRect.height * scale,
                SheetLayout.PLAN_FIT_MARGIN * scale,
                pxPerPt = scale,
            )
            canvas.save()
            canvas.translate((planRect.left * scale).toFloat(), (planRect.top * scale).toFloat())
            PlanPainter.draw(canvas, drawList, view)
            canvas.restore()
            ptPerMeter = view.pxPerMeter / scale
        }

        canvas.save()
        canvas.scale(scale.toFloat(), scale.toFloat())
        drawHeader(canvas, widthPt, sheet.projectName, sheet.address, sheet.floorName, sheet.dateText)
        if (drawList == null) {
            val paint = SheetText.paint(12.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.CENTER)
            SheetText.drawCentered(canvas, sheet.emptyMessage, planRect, paint)
        }
        drawFooter(canvas, widthPt, heightPt, sheet, planRect.width, ptPerMeter)
        canvas.restore()
    }

    /**
     * Title block: project name (bold 16 pt) and address on the left, floor / sheet title and date
     * on the right, a hairline underneath. Long texts are ellipsized so the columns never collide.
     */
    internal fun drawHeader(
        canvas: Canvas,
        widthPt: Double,
        projectName: String,
        address: String,
        rightTitle: String,
        dateText: String,
    ) {
        val rect = SheetLayout.header(widthPt)
        val titlePaint = SheetText.paint(16.0, bold = true)
        val addressPaint = SheetText.paint(10.0, color = PlanColors.TEXT_SECONDARY)
        val rightTitlePaint = SheetText.paint(12.0, bold = true, align = Paint.Align.RIGHT)
        val datePaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)

        val maxRight = rect.width * 0.42
        val rightTitleText = SheetText.ellipsize(rightTitle, rightTitlePaint, maxRight)
        val dateLine = SheetText.ellipsize(dateText, datePaint, maxRight)
        val rightWidth = maxOf(rightTitlePaint.measureText(rightTitleText), datePaint.measureText(dateLine)).toDouble()
        val leftMax = (rect.width - rightWidth - 18.0).coerceAtLeast(40.0)

        SheetText.draw(canvas, SheetText.ellipsize(projectName, titlePaint, leftMax), rect.left, rect.top + 16, titlePaint)
        if (address.isNotEmpty()) {
            SheetText.draw(canvas, SheetText.ellipsize(address, addressPaint, leftMax), rect.left, rect.top + 31, addressPaint)
        }
        SheetText.draw(canvas, rightTitleText, rect.right, rect.top + 16, rightTitlePaint)
        SheetText.draw(canvas, dateLine, rect.right, rect.top + 31, datePaint)
        SheetText.hairline(canvas, rect.left, rect.right, rect.bottom - 2, RULE_COLOR, 0.75)
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
        val leftMax = rect.width - (bar?.lengthPt ?: 0.0) - 24.0

        val areaPaint = SheetText.paint(11.0, bold = true)
        val countPaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY)
        SheetText.draw(canvas, SheetText.ellipsize(sheet.areaText, areaPaint, leftMax), rect.left, rect.top + 16, areaPaint)
        SheetText.draw(canvas, SheetText.ellipsize(sheet.roomCountText, countPaint, leftMax), rect.left, rect.top + 29, countPaint)

        if (bar != null) {
            val ratio = if (sheet.showScaleRatio) ScaleBars.ratio(bar.lengthPt / bar.lengthMeters) else null
            drawScaleBar(canvas, bar, right = rect.right, top = rect.top + 9, ratio = ratio)
        }

        drawSmallFooter(canvas, widthPt, heightPt, sheet.pageLabel)
    }

    /** Disclaimer (7 pt) on the bottom line, with the page label right-aligned on the same line. */
    internal fun drawSmallFooter(canvas: Canvas, widthPt: Double, heightPt: Double, pageLabel: String?) {
        val left = SheetLayout.MARGIN
        val right = widthPt - SheetLayout.MARGIN
        val baseline = heightPt - SheetLayout.MARGIN
        val disclaimerPaint = SheetText.paint(7.0, color = PlanColors.TEXT_SECONDARY)
        var disclaimerMax = right - left
        if (pageLabel != null) {
            val pagePaint = SheetText.paint(7.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)
            SheetText.draw(canvas, pageLabel, right, baseline, pagePaint)
            disclaimerMax -= pagePaint.measureText(pageLabel) + 12.0
        }
        SheetText.draw(
            canvas,
            SheetText.ellipsize(FloorSheets.DISCLAIMER, disclaimerPaint, disclaimerMax),
            left,
            baseline,
            disclaimerPaint,
        )
    }

    /** Alternating black / white blocks with "0" and the length label underneath, right-aligned at [right]. */
    private fun drawScaleBar(canvas: Canvas, bar: ScaleBarSpec, right: Double, top: Double, ratio: Long?) {
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
        if (ratio != null) {
            val ratioPaint = SheetText.paint(6.0, color = PlanColors.TEXT_SECONDARY, align = Paint.Align.RIGHT)
            SheetText.draw(canvas, "Scale 1:$ratio when printed at 100%", right, labelBaseline + 8.5, ratioPaint)
        }
    }
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
        if (maxWidth <= 0) return ""
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
