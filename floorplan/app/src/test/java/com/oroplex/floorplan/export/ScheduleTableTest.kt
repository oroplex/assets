package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTableTest {

    private val metric = PlanSettings(unitSystem = UnitSystem.METRIC)

    private fun rect(id: String, w: Double, h: Double, name: String = "Room $id", ceiling: Double? = 2.5, estimated: Boolean = false) =
        Room(
            id = id,
            name = name,
            type = RoomType.BEDROOM,
            corners = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, h), Vec2(0.0, h)),
            ceilingHeight = ceiling,
            ceilingEstimated = estimated,
        )

    private fun project(vararg floors: Floor, settings: PlanSettings = metric) =
        Project(id = "p", name = "P", createdAt = 0, updatedAt = 0, floors = floors.toList(), settings = settings)

    @Test
    fun `columns are room, size, area and ceiling and fill the table width`() {
        assertEquals(listOf("Room", "L × W", "Area", "Ceiling"), ScheduleTable.COLUMNS.map { it.title })
        assertEquals(1.0, ScheduleTable.COLUMNS.sumOf { it.fraction }, 1e-9)
        assertEquals(ColumnAlign.END, ScheduleTable.COLUMNS[2].align)
        assertEquals(ColumnAlign.END, ScheduleTable.COLUMNS[3].align)
    }

    @Test
    fun `single floor lists its rooms and one grand total`() {
        val floor = Floor("f", "Main", rooms = listOf(rect("a", 4.0, 3.0), rect("b", 2.0, 2.0)))
        val rows = ScheduleTable.rows(project(floor), listOf(floor))
        assertEquals(3, rows.size)
        val a = rows[0] as ScheduleRow.RoomLine
        assertEquals("Room a", a.name)
        assertEquals("4.00 m × 3.00 m", a.dimensions)
        assertEquals("12.0 m²", a.area)
        assertEquals("2.50 m", a.ceiling)
        assertFalse(a.ceilingEstimated)
        val total = rows.last() as ScheduleRow.Total
        assertTrue(total.grand)
        assertEquals("16.0 m²", total.area)
        assertEquals("Total net floor area · 2 rooms", total.label)
    }

    @Test
    fun `estimated ceilings are marked and flagged for the footnote`() {
        val floor = Floor("f", "Main", rooms = listOf(rect("a", 3.0, 3.0, ceiling = 2.4384, estimated = true), rect("b", 3.0, 3.0)))
        val rows = ScheduleTable.rows(project(floor, settings = PlanSettings()), listOf(floor))
        val estimated = rows[0] as ScheduleRow.RoomLine
        assertTrue(estimated.ceilingEstimated)
        assertEquals("8' 0\" *", estimated.ceiling)
        assertTrue(ScheduleTable.hasEstimates(rows))
        assertFalse(ScheduleTable.hasEstimates(rows.drop(1)))
        assertEquals("* estimated, not measured", ScheduleTable.ESTIMATE_FOOTNOTE)
    }

    @Test
    fun `an unknown ceiling shows a dash and is never marked estimated`() {
        val line = ScheduleTable.roomLine(rect("a", 3.0, 3.0, ceiling = null, estimated = true), UnitSystem.METRIC, 2)
        assertEquals(ScheduleTable.DASH, line.ceiling)
        assertFalse(line.ceilingEstimated)
        val bad = ScheduleTable.roomLine(rect("b", 3.0, 3.0, ceiling = Double.NaN), UnitSystem.METRIC, 2)
        assertEquals(ScheduleTable.DASH, bad.ceiling)
    }

    @Test
    fun `several floors get headings, subtotals and a grand total`() {
        val main = Floor("f1", "Main", rooms = listOf(rect("a", 4.0, 3.0)))
        val upper = Floor("f2", " ", rooms = listOf(rect("b", 2.0, 2.0), rect("c", 1.0, 1.0)))
        val rows = ScheduleTable.rows(project(main, upper), listOf(main, upper))
        assertEquals(ScheduleRow.FloorHeading("Main"), rows[0])
        assertTrue(rows[1] is ScheduleRow.RoomLine)
        assertEquals(ScheduleRow.Total("Main total · 1 room", "12.0 m²", grand = false), rows[2])
        assertEquals(ScheduleRow.FloorHeading("Floor"), rows[3]) // blank name falls back
        assertEquals(ScheduleRow.Total("Floor total · 2 rooms", "5.0 m²", grand = false), rows[6])
        assertEquals(ScheduleRow.Total("Total net floor area · 3 rooms", "17.0 m²", grand = true), rows[7])
    }

    @Test
    fun `an empty floor gets a note and degenerate rooms show dashes`() {
        val empty = Floor("f1", "Basement")
        val broken = Floor("f2", "Main", rooms = listOf(Room(id = "x", name = "", type = RoomType.KITCHEN, corners = emptyList())))
        val rows = ScheduleTable.rows(project(empty, broken), listOf(empty, broken))
        assertEquals(ScheduleRow.Note(FloorSheets.EMPTY_FLOOR_MESSAGE), rows[1])
        val line = rows.filterIsInstance<ScheduleRow.RoomLine>().single()
        assertEquals("Kitchen", line.name) // blank name falls back to the type
        assertEquals(ScheduleTable.DASH, line.dimensions)
    }

    @Test
    fun `pagination never leaves a floor heading at the bottom of a page`() {
        val floors = (1..6).map { f -> Floor("f$f", "Floor $f", rooms = (1..9).map { rect("$f-$it", 3.0, 3.0) }) }
        val rows = ScheduleTable.rows(project(*floors.toTypedArray()), floors)
        val pages = ScheduleTable.paginate(rows, PaperSize.LETTER.heightPt.toDouble())
        assertEquals(rows, pages.flatten())
        val capacity = ScheduleLayout.rowsPerPage(PaperSize.LETTER.heightPt.toDouble())
        assertTrue(pages.size > 1)
        pages.forEach { page ->
            assertTrue(page.size <= capacity)
            assertFalse(page.last() is ScheduleRow.FloorHeading)
        }
    }
}
