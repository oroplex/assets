package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem

/** One line of the room schedule table. */
sealed interface ScheduleRow {
    data class FloorHeading(val title: String) : ScheduleRow

    data class RoomLine(
        val name: String,
        val dimensions: String,
        val area: String,
        /** Formatted height, followed by [ScheduleTable.ESTIMATE_MARK] when [ceilingEstimated]. */
        val ceiling: String,
        val ceilingEstimated: Boolean,
    ) : ScheduleRow

    data class Note(val text: String) : ScheduleRow

    data class Total(val label: String, val area: String, val grand: Boolean) : ScheduleRow
}

enum class ColumnAlign { START, END }

/** A schedule column: [fraction] of the table width. */
data class ScheduleColumn(val title: String, val fraction: Double, val align: ColumnAlign)

/**
 * Content of the "Room | L × W | Area | Ceiling" schedule, without any drawing (pure, unit-tested).
 *
 * Heights that were assumed rather than measured ([Room.ceilingEstimated]) carry an asterisk, and
 * every page that shows one prints [ESTIMATE_FOOTNOTE] under the table.
 */
object ScheduleTable {
    const val ESTIMATE_MARK = "*"
    const val ESTIMATE_FOOTNOTE = "* estimated, not measured"
    const val DASH = "—"

    val COLUMNS: List<ScheduleColumn> = listOf(
        ScheduleColumn("Room", 0.40, ColumnAlign.START),
        ScheduleColumn("L × W", 0.28, ColumnAlign.START),
        ScheduleColumn("Area", 0.16, ColumnAlign.END),
        ScheduleColumn("Ceiling", 0.16, ColumnAlign.END),
    )

    /**
     * Rows for [floors]: with several floors each gets a heading, its rooms and a subtotal, followed by
     * a grand total; a single floor lists its rooms and one total. Rooms whose measurements cannot be
     * computed show dashes instead of failing the export.
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
            val floorName = floor.name.ifBlank { "Floor" }
            if (multiFloor) out += ScheduleRow.FloorHeading(floorName)
            if (floor.rooms.isEmpty()) out += ScheduleRow.Note(FloorSheets.EMPTY_FLOOR_MESSAGE)
            floor.rooms.forEach { out += roomLine(it, system, denominator) }
            val area = finiteOrNull { Measurements.floorArea(floor) }
            if (area == null) totalAreaValid = false else totalArea += area
            totalRooms += floor.rooms.size
            if (multiFloor) {
                out += ScheduleRow.Total(
                    label = "$floorName total · ${FloorSheets.roomCountText(floor.rooms.size)}",
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

    /** True when any row on the page shows an estimated height (the page then needs the footnote). */
    fun hasEstimates(rows: List<ScheduleRow>): Boolean = rows.any { it is ScheduleRow.RoomLine && it.ceilingEstimated }

    /** Splits [rows] into pages of [ScheduleLayout.rowsPerPage] rows, never stranding a floor heading. */
    fun paginate(rows: List<ScheduleRow>, pageHeightPt: Double): List<List<ScheduleRow>> {
        val capacity = ScheduleLayout.rowsPerPage(pageHeightPt)
        return Pagination.paginate(rows, capacity, capacity) { it is ScheduleRow.FloorHeading }
    }

    internal fun roomLine(room: Room, system: UnitSystem, denominator: Int): ScheduleRow.RoomLine {
        val stats = try {
            Measurements.room(room)
        } catch (e: Exception) {
            null
        }
        val dims = stats?.takeIf { it.length.isFinite() && it.width.isFinite() && it.length > 0 }?.let {
            "${Units.formatLength(it.length, system, denominator)} × ${Units.formatLength(it.width, system, denominator)}"
        } ?: DASH
        val area = stats?.area?.takeIf { it.isFinite() }?.let { Units.formatArea(it, system) } ?: DASH
        val height = room.ceilingHeight?.takeIf { it.isFinite() && it > 0 }
        val estimated = height != null && room.ceilingEstimated
        val ceiling = when {
            height == null -> DASH
            estimated -> "${Units.formatLength(height, system, denominator)} $ESTIMATE_MARK"
            else -> Units.formatLength(height, system, denominator)
        }
        return ScheduleRow.RoomLine(
            name = room.name.trim().ifEmpty { room.type.displayName },
            dimensions = dims,
            area = area,
            ceiling = ceiling,
            ceilingEstimated = estimated,
        )
    }

    private inline fun finiteOrNull(block: () -> Double): Double? =
        try {
            block().takeIf { it.isFinite() }
        } catch (e: Exception) {
            null
        }
}
