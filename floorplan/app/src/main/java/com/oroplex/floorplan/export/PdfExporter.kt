package com.oroplex.floorplan.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.render.PlanColors
import java.io.OutputStream

data class PdfOptions(
    val paper: PaperSize = PaperSize.LETTER,
    val labels: PlanLabels = PlanLabels(),
    val includeRoomSchedule: Boolean = true,
)

/**
 * Multi-page PDF (android.graphics.pdf.PdfDocument, 1 unit = 1 pt): one plan page per floor, followed by
 * the room schedule page(s).
 *
 * All plan pages share one standard architectural scale and one orientation (see [PlanScales.choose]):
 * the largest scale at which every floor fits, so the set can be read with a scale ruler and floors can be
 * compared side by side. Only when even the smallest standard scale is too large is each floor fitted to its
 * own page (landscape when its plan is wide), as a drawing "not to a standard scale".
 */
object PdfExporter {

    /** Rendered content of a PDF, prepared up-front so page numbers ("Page i of n") are known. */
    class Prepared internal constructor(
        internal val projectName: String,
        internal val address: String,
        internal val dateText: String,
        internal val paper: PaperSize,
        internal val sheets: List<FloorSheet>,
        internal val schedulePages: List<List<ScheduleRow>>,
        internal val branding: SheetBranding?,
        /** The scale and orientation of every plan page; null = each floor is fitted to its own page. */
        val sheetScale: SheetScale? = null,
    ) {
        val pageCount: Int get() = sheets.size + schedulePages.size

        /** Number of plan pages (one per exported floor). */
        val planPageCount: Int get() = sheets.size

        /** "Page i of n" labels in print order: plan sheets first, then the schedule pages. */
        val pageLabels: List<String> get() = (1..pageCount).map { FloorSheets.pageLabel(it, pageCount) }

        /** Plan sheets with their final page labels (what [write] prints). */
        val labeledSheets: List<FloorSheet>
            get() = sheets.mapIndexed { i, sheet -> sheet.copy(pageLabel = FloorSheets.pageLabel(i + 1, pageCount)) }

        /**
         * Whether [sheet]'s page is landscape: the document's common orientation, or, for fit-to-page
         * documents, the shape of the sheet's own plan.
         */
        fun isLandscape(sheet: FloorSheet): Boolean =
            sheetScale?.landscape ?: SheetLayout.isLandscape(sheet.drawList?.bounds)

        /**
         * One line describing the document, shown under the export preview, e.g.
         * `Letter landscape · 1/4" = 1'-0" · 3 pages`. Fit-to-page documents say "fit to page" in place of
         * the scale and name the first sheet's orientation; a document without any drawing (only "no rooms"
         * pages) has no scale to mention.
         */
        val summary: String
            get() {
                val landscape = sheets.firstOrNull()?.let(::isLandscape) ?: false
                val pages = if (pageCount == 1) "1 page" else "$pageCount pages"
                val scale = when {
                    sheetScale != null -> sheetScale.scale.label
                    sheets.any { it.drawList != null } -> "fit to page"
                    else -> null
                }
                return listOfNotNull("${paper.label} ${if (landscape) "landscape" else "portrait"}", scale, pages)
                    .joinToString(" · ")
            }
    }

    /**
     * Renders every floor and lays out the schedule. CPU-only (no I/O, no Android graphics); throws
     * [ExportException] when a floor cannot be drawn.
     *
     * Each floor is rendered once per candidate scale until all of them fit (largest scale first); the
     * renders at the chosen scale become the sheets. An empty floor has nothing to scale and prints its
     * "no rooms" message on a page in the common orientation.
     */
    fun prepare(
        project: Project,
        floors: List<Floor>,
        options: PdfOptions,
        dateText: String,
        branding: SheetBranding? = null,
    ): Prepared {
        // Sheets by scale (null key = fitted to the page), so the scale search never renders a floor twice.
        val built = HashMap<PlanScale?, List<FloorSheet>>()
        fun sheetsAt(scale: PlanScale?): List<FloorSheet> = built.getOrPut(scale) {
            floors.map { floor ->
                FloorSheets.build(project, floor, options.labels, dateText, branding, scale).copy(showScaleRatio = true)
            }
        }
        val sheetScale = PlanScales.choose(project.settings.unitSystem, options.paper) { scale ->
            sheetsAt(scale).mapNotNull { it.drawList?.bounds }
        }
        val schedulePages = if (options.includeRoomSchedule && floors.isNotEmpty()) {
            ScheduleTable.paginate(ScheduleTable.rows(project, floors), options.paper.heightPt.toDouble())
        } else {
            emptyList()
        }
        return Prepared(
            projectName = project.name.trim().ifEmpty { "Untitled project" },
            address = project.address.trim(),
            dateText = dateText,
            paper = options.paper,
            sheets = sheetsAt(sheetScale?.scale),
            schedulePages = schedulePages,
            branding = branding,
            sheetScale = sheetScale,
        )
    }

    /**
     * Draws all pages and streams the document to [out]. Blocking.
     *
     * Every started page is finished and the document is always closed, even when drawing fails
     * half-way; the original failure (including an OutOfMemoryError) is what the caller sees, with any
     * cleanup problem attached as suppressed (see [PagedDocumentGuard]).
     */
    fun write(prepared: Prepared, out: OutputStream) {
        val total = prepared.pageCount
        val document = PdfDocument()
        PagedDocumentGuard<PdfDocument.Page>(document::finishPage, document::close).run {
            var pageNumber = 0
            for (sheet in prepared.labeledSheets) {
                pageNumber++
                val landscape = prepared.isLandscape(sheet)
                val w = prepared.paper.widthPt(landscape)
                val h = prepared.paper.heightPt(landscape)
                val page = started(document.startPage(PdfDocument.PageInfo.Builder(w, h, pageNumber).create()))
                PlanSheetPainter.draw(page.canvas, sheet, w.toDouble(), h.toDouble(), scale = 1.0)
                finish(page)
            }
            prepared.schedulePages.forEachIndexed { index, rows ->
                pageNumber++
                val w = prepared.paper.widthPt
                val h = prepared.paper.heightPt
                val page = started(document.startPage(PdfDocument.PageInfo.Builder(w, h, pageNumber).create()))
                RoomSchedulePainter.drawPage(
                    canvas = page.canvas,
                    widthPt = w.toDouble(),
                    heightPt = h.toDouble(),
                    rows = rows,
                    title = if (index == 0) "Room schedule" else "Room schedule (continued)",
                    prepared = prepared,
                    pageLabel = FloorSheets.pageLabel(pageNumber, total),
                )
                finish(page)
            }
            document.writeTo(out)
        }
    }
}

/** Draws one page of the "Room | L × W | Area | Ceiling" table built by [ScheduleTable]. */
internal object RoomSchedulePainter {
    private const val CELL_PADDING = 5.0

    private const val HEADER_FILL: Int = 0xFFF1F3F4.toInt()
    private const val ZEBRA_FILL: Int = 0xFFF8F9FA.toInt()
    private const val FLOOR_FILL: Int = 0xFFE8F0FE.toInt()
    private const val GRID: Int = 0xFFE0E0E0.toInt()

    fun drawPage(
        canvas: Canvas,
        widthPt: Double,
        heightPt: Double,
        rows: List<ScheduleRow>,
        title: String,
        prepared: PdfExporter.Prepared,
        pageLabel: String,
    ) {
        PlanSheetPainter.drawHeader(canvas, widthPt, prepared.projectName, prepared.address, prepared.branding)

        val columns = ScheduleTable.COLUMNS
        val left = SheetLayout.MARGIN
        val right = widthPt - SheetLayout.MARGIN
        val tableWidth = right - left
        val columnLefts = DoubleArray(columns.size)
        var x = left
        columns.forEachIndexed { i, c ->
            columnLefts[i] = x
            x += c.fraction * tableWidth
        }
        fun columnRight(i: Int) = if (i == columns.lastIndex) right else columnLefts[i + 1]

        val titlePaint = SheetText.paint(14.0, bold = true)
        SheetText.draw(canvas, SheetText.ellipsize(title, titlePaint, tableWidth), left, ScheduleLayout.titleBaseline(heightPt), titlePaint)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        fun band(top: Double, height: Double, color: Int) {
            fill.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), (top + height).toFloat(), fill)
        }
        fun cell(text: String, column: Int, top: Double, height: Double, paint: TextPaint, spanTo: Int = column) {
            val cellLeft = columnLefts[column] + CELL_PADDING
            val cellRight = columnRight(spanTo) - CELL_PADDING
            val fitted = SheetText.ellipsize(text, paint, cellRight - cellLeft)
            val baseline = top + height / 2 - (paint.descent() + paint.ascent()) / 2
            val anchor = if (columns[spanTo].align == ColumnAlign.END && column == spanTo) {
                paint.textAlign = Paint.Align.RIGHT
                cellRight
            } else {
                paint.textAlign = Paint.Align.LEFT
                cellLeft
            }
            SheetText.draw(canvas, fitted, anchor, baseline, paint)
        }

        // Column header.
        val rowHeight = ScheduleLayout.ROW_HEIGHT
        var y = ScheduleLayout.tableTop(heightPt)
        band(y, ScheduleLayout.HEADER_ROW_HEIGHT, HEADER_FILL)
        val headerPaint = SheetText.paint(8.5, bold = true, color = PlanColors.TEXT_SECONDARY)
        columns.forEachIndexed { i, c -> cell(c.title.uppercase(), i, y, ScheduleLayout.HEADER_ROW_HEIGHT, headerPaint) }
        y += ScheduleLayout.HEADER_ROW_HEIGHT
        SheetText.hairline(canvas, left, right, y, PlanSheetPainter.RULE_COLOR, 0.75)

        val bodyPaint = SheetText.paint(9.0)
        val estimatedPaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY, italic = true)
        val notePaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY, italic = true)
        val floorPaint = SheetText.paint(10.0, bold = true)
        val subtotalPaint = SheetText.paint(9.0, bold = true)
        val grandPaint = SheetText.paint(10.0, bold = true)
        val grandPartPaint = SheetText.paint(10.0)

        var zebra = false
        for ((index, row) in rows.withIndex()) {
            when (row) {
                is ScheduleRow.FloorHeading -> {
                    band(y, rowHeight, FLOOR_FILL)
                    cell(row.title, 0, y, rowHeight, floorPaint, spanTo = columns.lastIndex)
                    zebra = false
                }
                is ScheduleRow.RoomLine -> {
                    if (zebra) band(y, rowHeight, ZEBRA_FILL)
                    zebra = !zebra
                    cell(row.name, 0, y, rowHeight, bodyPaint)
                    cell(row.dimensions, 1, y, rowHeight, bodyPaint)
                    cell(row.area, 2, y, rowHeight, bodyPaint)
                    cell(row.ceiling, 3, y, rowHeight, if (row.ceilingEstimated) estimatedPaint else bodyPaint)
                    SheetText.hairline(canvas, left, right, y + rowHeight, GRID, 0.4)
                }
                is ScheduleRow.Note -> {
                    cell(row.text, 0, y, rowHeight, notePaint, spanTo = columns.lastIndex)
                    SheetText.hairline(canvas, left, right, y + rowHeight, GRID, 0.4)
                }
                is ScheduleRow.Total -> {
                    // A block of totals: one rule above its first row (doubled for the document's block),
                    // category rows in regular weight, the row that sums them in bold.
                    val paint = when {
                        !row.sum -> if (row.grand) grandPartPaint else bodyPaint
                        row.grand -> grandPaint
                        else -> subtotalPaint
                    }
                    if (row.grand) band(y, rowHeight, HEADER_FILL)
                    if (row.startsBlock || index == 0) {
                        if (row.grand) SheetText.hairline(canvas, left, right, y + 1.2, PlanColors.TEXT, 0.5)
                        SheetText.hairline(canvas, left, right, y, PlanColors.TEXT, 0.75)
                    }
                    cell(row.label, 0, y, rowHeight, paint, spanTo = 1)
                    cell(row.area, 2, y, rowHeight, paint)
                    zebra = false
                }
            }
            y += rowHeight
        }

        if (ScheduleTable.hasEstimates(rows)) {
            val footnotePaint = SheetText.paint(8.0, color = PlanColors.TEXT_SECONDARY, italic = true)
            SheetText.draw(canvas, ScheduleTable.ESTIMATE_FOOTNOTE, left + CELL_PADDING, y + 12.0, footnotePaint)
        }

        PlanSheetPainter.drawBottomLine(canvas, widthPt, heightPt, FloorSheets.bottomLineText(prepared.dateText, pageLabel))
    }
}
