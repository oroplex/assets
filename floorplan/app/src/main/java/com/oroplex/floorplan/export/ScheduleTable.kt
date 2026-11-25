package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.measure.AreaSummary
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.AreaCategory
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

    /**
     * One line of a block of area totals (see [ScheduleTable.rows]). A block lists the area by category
     * (living, garage & unfinished, outdoor) and ends with the row that adds them up.
     */
    data class Total(
        val label: String,
        val area: String,
        /** True for the block that covers the whole document, false for one floor's block. */
        val grand: Boolean,
        /** True for the block's last row, the sum of the category rows above it (printed bold). */
        val sum: Boolean = true,
        /** True for the block's first row, which carries the rule that separates the block from the rooms. */
        val startsBlock: Boolean = true,
    ) : ScheduleRow
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

    /** Label of the row that adds up a block's categories. */
    const val TOTAL_LABEL = "Total"

    val COLUMNS: List<ScheduleColumn> = listOf(
        ScheduleColumn("Room", 0.40, ColumnAlign.START),
        ScheduleColumn("L × W", 0.28, ColumnAlign.START),
        ScheduleColumn("Area", 0.16, ColumnAlign.END),
        ScheduleColumn("Ceiling", 0.16, ColumnAlign.END),
    )

    /**
     * Rows for [floors]: with several floors each gets a heading, its rooms and its block of totals, followed
     * by the block for all of them; a single floor lists its rooms and one block. Rooms whose measurements
     * cannot be computed show dashes instead of failing the export.
     *
     * A block of totals ([ScheduleRow.Total]) splits the net (interior) floor area by what it counts toward
     * ([Measurements.areaSummary]): "Net living area (interior)", "Garage & unfinished" and "Outdoor", each
     * left out when it is zero, then "Total" (their sum) with the room count. Rooms the user marked "Not
     * counted" are in none of them; a note above the block says how much area that is, so the rows visibly
     * add up. The living figure is interior net area, never "GLA" (see [FloorSheets.livingAreaText]).
     */
    fun rows(project: Project, floors: List<Floor>): List<ScheduleRow> {
        val system = project.settings.unitSystem
        val denominator = project.settings.imperialDenominator
        val multiFloor = floors.size > 1
        val out = ArrayList<ScheduleRow>()
        var all: AreaSummary? = AreaSummary.ZERO
        var totalRooms = 0
        for (floor in floors) {
            val floorName = floor.name.ifBlank { "Floor" }
            if (multiFloor) out += ScheduleRow.FloorHeading(floorName)
            if (floor.rooms.isEmpty()) out += ScheduleRow.Note(FloorSheets.EMPTY_FLOOR_MESSAGE)
            floor.rooms.forEach { out += roomLine(it, system, denominator) }
            val summary = summaryOf(floor.rooms)
            all = if (summary == null) null else all?.plus(summary)
            totalRooms += floor.rooms.size
            if (multiFloor) {
                out += totals(summary, "$floorName total · ${FloorSheets.roomCountText(floor.rooms.size)}", grand = false, system)
            }
        }
        out += totals(all, "$TOTAL_LABEL · ${FloorSheets.roomCountText(totalRooms)}", grand = true, system)
        return out
    }

    /**
     * The block of totals for [summary] (see [rows]); [sumLabel] names its last row. A [summary] of null
     * (areas could not be computed) gives the sum row alone, with a dash.
     */
    internal fun totals(summary: AreaSummary?, sumLabel: String, grand: Boolean, system: UnitSystem): List<ScheduleRow> {
        if (summary == null) return listOf(ScheduleRow.Total(sumLabel, DASH, grand))
        val zero = Units.formatArea(0.0, system)
        fun text(area: Double) = Units.formatArea(area, system)
        val categories = listOf(
            FloorSheets.LIVING_AREA_LABEL to summary.living,
            AreaCategory.NON_LIVING.displayName to summary.nonLiving,
            AreaCategory.EXTERIOR.displayName to summary.exterior,
        ).filter { (_, area) -> text(area) != zero } // "omit rows that are zero": whatever would print as zero
        val out = ArrayList<ScheduleRow>()
        if (text(summary.excluded) != zero) out += ScheduleRow.Note("Not counted in the totals: ${text(summary.excluded)}")
        categories.forEachIndexed { i, (label, area) ->
            out += ScheduleRow.Total(label, text(area), grand, sum = false, startsBlock = i == 0)
        }
        val counted = summary.living + summary.nonLiving + summary.exterior
        out += ScheduleRow.Total(sumLabel, text(counted), grand, sum = true, startsBlock = categories.isEmpty())
        return out
    }

    /** True when any row on the page shows an estimated height (the page then needs the footnote). */
    fun hasEstimates(rows: List<ScheduleRow>): Boolean = rows.any { it is ScheduleRow.RoomLine && it.ceilingEstimated }

    /**
     * Splits [rows] into pages of [ScheduleLayout.rowsPerPage] rows, never stranding a floor heading or a
     * note at the bottom of a page and never separating a block of totals from the row that sums it.
     */
    fun paginate(rows: List<ScheduleRow>, pageHeightPt: Double): List<List<ScheduleRow>> {
        val capacity = ScheduleLayout.rowsPerPage(pageHeightPt)
        return Pagination.paginate(rows, capacity, capacity) {
            it is ScheduleRow.FloorHeading || it is ScheduleRow.Note || (it is ScheduleRow.Total && !it.sum)
        }
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

    /** [Measurements.areaSummary] of [rooms], or null when it fails or is not finite (corrupt geometry). */
    private fun summaryOf(rooms: List<Room>): AreaSummary? =
        try {
            Measurements.areaSummary(rooms).takeIf {
                it.living.isFinite() && it.nonLiving.isFinite() && it.exterior.isFinite() && it.excluded.isFinite()
            }
        } catch (e: Exception) {
            null
        }
}
