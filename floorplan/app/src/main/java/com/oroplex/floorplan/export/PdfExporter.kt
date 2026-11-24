package com.oroplex.floorplan.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.PlanColors
import java.io.OutputStream
import kotlin.math.floor

data class PdfOptions(
    val paper: PaperSize = PaperSize.LETTER,
    val showWallLengths: Boolean = true,
    val showAreas: Boolean = true,
    val includeRoomSchedule: Boolean = true,
)

/**
 * Multi-page PDF (android.graphics.pdf.PdfDocument, 1 unit = 1 pt): one plan page per floor
 * (landscape when the plan is wide), followed by the room schedule page(s).
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
    ) {
        val pageCount: Int get() = sheets.size + schedulePages.size
    }

    /**
     * Renders every floor and lays out the schedule. CPU-only (no I/O); throws [ExportException]
     * when a floor cannot be drawn.
     */
    fun prepare(project: Project, floors: List<Floor>, options: PdfOptions, dateText: String): Prepared {
        val sheets = floors.map { floor ->
            FloorSheets.build(project, floor, options.showWallLengths, options.showAreas, dateText)
                .copy(showScaleRatio = true)
        }
        val schedulePages = if (options.includeRoomSchedule && floors.isNotEmpty()) {
            val rows = RoomSchedule.rows(project, floors)
            val capacity = RoomSchedule.rowsPerPage(options.paper.heightPt.toDouble())
            Pagination.paginate(rows, capacity, capacity) { it is ScheduleRow.FloorHeading }
        } else {
            emptyList()
        }
        return Prepared(
            projectName = project.name.ifBlank { "Untitled project" },
            address = project.address.trim(),
            dateText = dateText,
            paper = options.paper,
            sheets = sheets,
            schedulePages = schedulePages,
        )
    }

    /** Draws all pages and streams the document to [out]. Blocking. */
    fun write(prepared: Prepared, out: OutputStream) {
        val total = prepared.pageCount
        val document = PdfDocument()
        try {
            var pageNumber = 0
            for (sheet in prepared.sheets) {
                pageNumber++
                val landscape = SheetLayout.isLandscape(sheet.drawList?.bounds)
                val w = prepared.paper.widthPt(landscape)
                val h = prepared.paper.heightPt(landscape)
                val page = document.startPage(PdfDocument.PageInfo.Builder(w, h, pageNumber).create())
                PlanSheetPainter.draw(
                    page.canvas,
                    sheet.copy(pageLabel = "Page $pageNumber of $total"),
                    w.toDouble(),
                    h.toDouble(),
                    scale = 1.0,
                )
                document.finishPage(page)
            }
            prepared.schedulePages.forEachIndexed { index, rows ->
                pageNumber++
                val w = prepared.paper.widthPt
                val h = prepared.paper.heightPt
                val page = document.startPage(PdfDocument.PageInfo.Builder(w, h, pageNumber).create())
                RoomSchedule.drawPage(
                    canvas = page.canvas,
                    widthPt = w.toDouble(),
                    heightPt = h.toDouble(),
                    rows = rows,
                    title = if (index == 0) "Room schedule" else "Room schedule (continued)",
                    prepared = prepared,
                    pageLabel = "Page $pageNumber of $total",
                )
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }
}

/** One line of the room schedule table. */
sealed interface ScheduleRow {
    data class FloorHeading(val title: String) : ScheduleRow
    data class RoomLine(
        val name: String,
        val type: String,
        val dimensions: String,
        val area: String,
        val ceiling: String,
    ) : ScheduleRow
    data class Note(val text: String) : ScheduleRow
    data class Total(val label: String, val area: String, val grand: Boolean) : ScheduleRow
}

/** Builds and draws the "Room | Type | L × W | Area | Ceiling" table. */
internal object RoomSchedule {
    private const val HEADER_ROW_HEIGHT = 20.0
    private const val ROW_HEIGHT = 18.0
    private const val CELL_PADDING = 5.0

    /** Bottom of the table area: room for the disclaimer line below it. */
    private const val BOTTOM_RESERVE = 22.0

    private const val HEADER_FILL: Int = 0xFFF1F3F4.toInt()
    private const val ZEBRA_FILL: Int = 0xFFF8F9FA.toInt()
    private const val FLOOR_FILL: Int = 0xFFE8F0FE.toInt()
    private const val GRID: Int = 0xFFE0E0E0.toInt()

    private enum class Align { START, END }

    private class Column(val title: String, val fraction: Double, val align: Align)

    private val COLUMNS = listOf(
        Column("Room", 0.29, Align.START),
        Column("Type", 0.20, Align.START),
        Column("L × W", 0.25, Align.START),
        Column("Area", 0.13, Align.END),
        Column("Ceiling", 0.13, Align.END),
    )

    private const val DASH = "—"

    /**
     * Rows for [floors]: with several floors each gets a heading, its rooms and a subtotal, followed
     * by a grand total; a single floor lists its rooms and one total. Rooms whose measurements
     * cannot be computed show dashes instead of failing the export.
     */
    fun rows(project: Project, floors: List<Floor>): List<ScheduleRow> {
        val system = project.settings.unitSystem
        val denominator = project.settings.imperialDenominator
        val multiFloor = floors.size > 1
        val out = ArrayList<ScheduleRow>()
        var totalArea = 0.0
        var totalAreaValid = true
        var totalRooms = 0
        for (floor in floors) {
            if (multiFloor) out += ScheduleRow.FloorHeading(floor.name.ifBlank { "Floor" })
            if (floor.rooms.isEmpty()) out += ScheduleRow.Note(FloorSheets.EMPTY_FLOOR_MESSAGE)
            floor.rooms.forEach { out += roomLine(it, system, denominator) }
            val area = runCatchingArea { Measurements.floorArea(floor) }
            if (area == null) totalAreaValid = false else totalArea += area
            totalRooms += floor.rooms.size
            if (multiFloor) {
                out += ScheduleRow.Total(
                    label = "${floor.name.ifBlank { "Floor" }} total · ${FloorSheets.roomCountText(floor.rooms.size)}",
                    area = area?.let { Units.formatArea(it, system) } ?: DASH,
                    grand = false,
                )
            }
        }
        out += ScheduleRow.Total(
            label = "Total net floor area · ${FloorSheets.roomCountText(totalRooms)}",
            area = if (totalAreaValid) Units.formatArea(totalArea, system) else DASH,
            grand = true,
        )
        return out
    }

    private fun roomLine(room: Room, system: UnitSystem, denominator: Int): ScheduleRow.RoomLine {
        val stats = try {
            Measurements.room(room)
        } catch (e: Exception) {
            null
        }
        val dims = stats?.takeIf { it.length.isFinite() && it.width.isFinite() }?.let {
            "${Units.formatLength(it.length, system, denominator)} × ${Units.formatLength(it.width, system, denominator)}"
        } ?: DASH
        val area = stats?.area?.takeIf { it.isFinite() }?.let { Units.formatArea(it, system) } ?: DASH
        val ceiling = room.ceilingHeight?.takeIf { it.isFinite() && it > 0 }
            ?.let { Units.formatLength(it, system, denominator) } ?: DASH
        return ScheduleRow.RoomLine(
            name = room.name.ifBlank { room.type.displayName },
            type = room.type.displayName,
            dimensions = dims,
            area = area,
            ceiling = ceiling,
        )
    }

    private inline fun runCatchingArea(block: () -> Double): Double? =
        try {
            block().takeIf { it.isFinite() }
        } catch (e: Exception) {
            null
        }

    private fun tableTop(heightPt: Double): Double = SheetLayout.planArea(0.0, heightPt).top

    private fun tableBottom(heightPt: Double): Double = heightPt - SheetLayout.MARGIN - BOTTOM_RESERVE

    /** How many body rows fit under the column header on a page [heightPt] tall. */
    fun rowsPerPage(heightPt: Double): Int =
        floor((tableBottom(heightPt) - tableTop(heightPt) - HEADER_ROW_HEIGHT) / ROW_HEIGHT).toInt().coerceAtLeast(1)

    fun drawPage(
        canvas: Canvas,
        widthPt: Double,
        heightPt: Double,
        rows: List<ScheduleRow>,
        title: String,
        prepared: PdfExporter.Prepared,
        pageLabel: String,
    ) {
        PlanSheetPainter.drawHeader(canvas, widthPt, prepared.projectName, prepared.address, title, prepared.dateText)

        val left = SheetLayout.MARGIN
        val right = widthPt - SheetLayout.MARGIN
        val tableWidth = right - left
        val columnLefts = DoubleArray(COLUMNS.size)
        var x = left
        COLUMNS.forEachIndexed { i, c ->
            columnLefts[i] = x
            x += c.fraction * tableWidth
        }
        fun columnRight(i: Int) = if (i == COLUMNS.lastIndex) right else columnLefts[i + 1]

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
            val anchor = if (COLUMNS[spanTo].align == Align.END && column == spanTo) {
                paint.textAlign = Paint.Align.RIGHT
                cellRight
            } else {
                paint.textAlign = Paint.Align.LEFT
                cellLeft
            }
            SheetText.draw(canvas, fitted, anchor, baseline, paint)
        }

        // Column header.
        var y = tableTop(heightPt)
        band(y, HEADER_ROW_HEIGHT, HEADER_FILL)
        val headerPaint = SheetText.paint(8.5, bold = true, color = PlanColors.TEXT_SECONDARY)
        COLUMNS.forEachIndexed { i, c -> cell(c.title.uppercase(), i, y, HEADER_ROW_HEIGHT, headerPaint) }
        y += HEADER_ROW_HEIGHT
        SheetText.hairline(canvas, left, right, y, PlanSheetPainter.RULE_COLOR, 0.75)

        val bodyPaint = SheetText.paint(9.0)
        val secondaryPaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY)
        val notePaint = SheetText.paint(9.0, color = PlanColors.TEXT_SECONDARY, italic = true)
        val floorPaint = SheetText.paint(10.0, bold = true)
        val subtotalPaint = SheetText.paint(9.0, bold = true)
        val grandPaint = SheetText.paint(10.0, bold = true)

        var zebra = false
        for (row in rows) {
            when (row) {
                is ScheduleRow.FloorHeading -> {
                    band(y, ROW_HEIGHT, FLOOR_FILL)
                    cell(row.title, 0, y, ROW_HEIGHT, floorPaint, spanTo = COLUMNS.lastIndex)
                    zebra = false
                }
                is ScheduleRow.RoomLine -> {
                    if (zebra) band(y, ROW_HEIGHT, ZEBRA_FILL)
                    zebra = !zebra
                    cell(row.name, 0, y, ROW_HEIGHT, bodyPaint)
                    cell(row.type, 1, y, ROW_HEIGHT, secondaryPaint)
                    cell(row.dimensions, 2, y, ROW_HEIGHT, bodyPaint)
                    cell(row.area, 3, y, ROW_HEIGHT, bodyPaint)
                    cell(row.ceiling, 4, y, ROW_HEIGHT, bodyPaint)
                    SheetText.hairline(canvas, left, right, y + ROW_HEIGHT, GRID, 0.4)
                }
                is ScheduleRow.Note -> {
                    cell(row.text, 0, y, ROW_HEIGHT, notePaint, spanTo = COLUMNS.lastIndex)
                    SheetText.hairline(canvas, left, right, y + ROW_HEIGHT, GRID, 0.4)
                }
                is ScheduleRow.Total -> {
                    val paint = if (row.grand) grandPaint else subtotalPaint
                    if (row.grand) {
                        band(y, ROW_HEIGHT, HEADER_FILL)
                        SheetText.hairline(canvas, left, right, y + 1.2, PlanColors.TEXT, 0.5)
                    }
                    SheetText.hairline(canvas, left, right, y, PlanColors.TEXT, 0.75)
                    cell(row.label, 0, y, ROW_HEIGHT, paint, spanTo = 2)
                    cell(row.area, 3, y, ROW_HEIGHT, paint)
                    zebra = false
                }
            }
            y += ROW_HEIGHT
        }

        PlanSheetPainter.drawSmallFooter(canvas, widthPt, heightPt, pageLabel)
    }
}
