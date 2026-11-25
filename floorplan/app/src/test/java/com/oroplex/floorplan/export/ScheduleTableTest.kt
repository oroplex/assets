package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.AreaSummary
import com.oroplex.floorplan.core.model.AreaCategory
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

    private fun rect(
        id: String,
        w: Double,
        h: Double,
        name: String = "Room $id",
        ceiling: Double? = 2.5,
        estimated: Boolean = false,
        type: RoomType = RoomType.BEDROOM,
        category: AreaCategory? = null,
    ) = Room(
        id = id,
        name = name,
        type = type,
        corners = listOf(Vec2(0.0, 0.0), Vec2(w, 0.0), Vec2(w, h), Vec2(0.0, h)),
        ceilingHeight = ceiling,
        ceilingEstimated = estimated,
        areaCategory = category,
    )

    private fun project(vararg floors: Floor, settings: PlanSettings = metric) =
        Project(id = "p", name = "P", createdAt = 0, updatedAt = 0, floors = floors.toList(), settings = settings)

    private fun List<ScheduleRow>.totals() = filterIsInstance<ScheduleRow.Total>()

    private val living = FloorSheets.LIVING_AREA_LABEL

    @Test
    fun `columns are room, size, area and ceiling and fill the table width`() {
        assertEquals(listOf("Room", "L × W", "Area", "Ceiling"), ScheduleTable.COLUMNS.map { it.title })
        assertEquals(1.0, ScheduleTable.COLUMNS.sumOf { it.fraction }, 1e-9)
        assertEquals(ColumnAlign.END, ScheduleTable.COLUMNS[2].align)
        assertEquals(ColumnAlign.END, ScheduleTable.COLUMNS[3].align)
    }

    @Test
    fun `single floor lists its rooms, then the living area and the total`() {
        val floor = Floor("f", "Main", rooms = listOf(rect("a", 4.0, 3.0), rect("b", 2.0, 2.0)))
        val rows = ScheduleTable.rows(project(floor), listOf(floor))
        assertEquals(4, rows.size)
        val a = rows[0] as ScheduleRow.RoomLine
        assertEquals("Room a", a.name)
        assertEquals("4.00 m × 3.00 m", a.dimensions)
        assertEquals("12.0 m²", a.area)
        assertEquals("2.50 m", a.ceiling)
        assertFalse(a.ceilingEstimated)
        assertEquals(
            listOf(
                ScheduleRow.Total(living, "16.0 m²", grand = true, sum = false, startsBlock = true),
                ScheduleRow.Total("Total · 2 rooms", "16.0 m²", grand = true, sum = true, startsBlock = false),
            ),
            rows.totals(),
        )
    }

    @Test
    fun `the living area is never called GLA`() {
        assertEquals("Net living area (interior)", living)
        val floor = Floor("f", "Main", rooms = listOf(rect("a", 4.0, 3.0), rect("g", 6.0, 6.0, type = RoomType.GARAGE)))
        val labels = ScheduleTable.rows(project(floor), listOf(floor)).totals().map { it.label }
        assertTrue(labels.none { it.contains("GLA", ignoreCase = true) || it.contains("gross", ignoreCase = true) })
        assertEquals("Net living area (interior): 12.0 m²", FloorSheets.livingAreaText(floor, UnitSystem.METRIC))
        assertEquals("Net living area (interior): 129 sq ft", FloorSheets.livingAreaText(floor, UnitSystem.IMPERIAL))
    }

    @Test
    fun `totals split living, garage and outdoor and add them up`() {
        val floor = Floor(
            "f",
            "Main",
            rooms = listOf(
                rect("a", 4.0, 3.0), // living 12
                rect("k", 5.0, 4.0, type = RoomType.KITCHEN), // living 20
                rect("g", 6.0, 6.0, type = RoomType.GARAGE), // non-living 36
                rect("d", 3.0, 2.0, type = RoomType.DECK), // outdoor 6
            ),
        )
        val totals = ScheduleTable.rows(project(floor), listOf(floor)).totals()
        assertEquals(
            listOf(
                ScheduleRow.Total(living, "32.0 m²", grand = true, sum = false, startsBlock = true),
                ScheduleRow.Total("Garage & unfinished", "36.0 m²", grand = true, sum = false, startsBlock = false),
                ScheduleRow.Total("Outdoor", "6.0 m²", grand = true, sum = false, startsBlock = false),
                ScheduleRow.Total("Total · 4 rooms", "74.0 m²", grand = true, sum = true, startsBlock = false),
            ),
            totals,
        )
        // Exactly one row opens the block and exactly one sums it.
        assertEquals(1, totals.count { it.startsBlock })
        assertEquals(1, totals.count { it.sum })
        assertTrue(totals.last().sum)
    }

    @Test
    fun `categories that are zero are left out`() {
        val garageOnly = Floor("f", "Garage", rooms = listOf(rect("g", 6.0, 6.0, type = RoomType.GARAGE)))
        assertEquals(
            listOf(
                ScheduleRow.Total("Garage & unfinished", "36.0 m²", grand = true, sum = false, startsBlock = true),
                ScheduleRow.Total("Total · 1 room", "36.0 m²", grand = true, sum = true, startsBlock = false),
            ),
            ScheduleTable.rows(project(garageOnly), listOf(garageOnly)).totals(),
        )
        // The floor's sheet still states its living area, which is none.
        assertEquals("Net living area (interior): 0.0 m²", FloorSheets.livingAreaText(garageOnly, UnitSystem.METRIC))

        // An area that would print as zero counts as zero (0.04 m² is "0.0 m²").
        val sliver = AreaSummary(living = 10.0, nonLiving = 0.04, exterior = 0.0, excluded = 0.0)
        val rows = ScheduleTable.totals(sliver, "Total · 2 rooms", grand = false, UnitSystem.METRIC)
        assertEquals(listOf(living, "Total · 2 rooms"), rows.totals().map { it.label })

        // Nothing at all: the sum row alone opens and closes the block.
        assertEquals(
            listOf(ScheduleRow.Total("Total · 0 rooms", "0 sq ft", grand = true, sum = true, startsBlock = true)),
            ScheduleTable.totals(AreaSummary.ZERO, "Total · 0 rooms", grand = true, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `a room's own area category overrides its type`() {
        val floor = Floor(
            "f",
            "Main",
            rooms = listOf(
                rect("a", 4.0, 3.0), // living 12
                rect("b", 5.0, 4.0, type = RoomType.BASEMENT, category = AreaCategory.LIVING), // finished basement: living 20
                rect("s", 2.0, 2.0, type = RoomType.BEDROOM, category = AreaCategory.NON_LIVING), // unfinished: 4
            ),
        )
        val totals = ScheduleTable.rows(project(floor), listOf(floor)).totals()
        assertEquals(listOf("32.0 m²", "4.0 m²", "36.0 m²"), totals.map { it.area })
    }

    @Test
    fun `rooms marked not counted are in no total and are disclosed in a note`() {
        val floor = Floor(
            "f",
            "Main",
            rooms = listOf(rect("a", 4.0, 3.0), rect("x", 2.0, 2.5, category = AreaCategory.EXCLUDED)),
        )
        val rows = ScheduleTable.rows(project(floor), listOf(floor))
        assertEquals(
            listOf(
                ScheduleRow.Note("Not counted in the totals: 5.0 m²"),
                ScheduleRow.Total(living, "12.0 m²", grand = true, sum = false, startsBlock = true),
                ScheduleRow.Total("Total · 2 rooms", "12.0 m²", grand = true, sum = true, startsBlock = false),
            ),
            rows.drop(2),
        )
        // Without such rooms there is no note.
        val plain = Floor("f", "Main", rooms = listOf(rect("a", 4.0, 3.0)))
        assertTrue(ScheduleTable.rows(project(plain), listOf(plain)).none { it is ScheduleRow.Note })
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
    fun `several floors get headings, their own totals and the totals of the document`() {
        val main = Floor("f1", "Main", rooms = listOf(rect("a", 4.0, 3.0), rect("g", 5.0, 4.0, type = RoomType.GARAGE)))
        val upper = Floor("f2", " ", rooms = listOf(rect("b", 2.0, 2.0), rect("c", 1.0, 1.0)))
        val rows = ScheduleTable.rows(project(main, upper), listOf(main, upper))
        assertEquals(
            listOf(
                ScheduleRow.FloorHeading("Main"),
                rows[1],
                rows[2],
                ScheduleRow.Total(living, "12.0 m²", grand = false, sum = false, startsBlock = true),
                ScheduleRow.Total("Garage & unfinished", "20.0 m²", grand = false, sum = false, startsBlock = false),
                ScheduleRow.Total("Main total · 2 rooms", "32.0 m²", grand = false, sum = true, startsBlock = false),
                ScheduleRow.FloorHeading("Floor"), // blank name falls back
                rows[7],
                rows[8],
                ScheduleRow.Total(living, "5.0 m²", grand = false, sum = false, startsBlock = true),
                ScheduleRow.Total("Floor total · 2 rooms", "5.0 m²", grand = false, sum = true, startsBlock = false),
                ScheduleRow.Total(living, "17.0 m²", grand = true, sum = false, startsBlock = true),
                ScheduleRow.Total("Garage & unfinished", "20.0 m²", grand = true, sum = false, startsBlock = false),
                ScheduleRow.Total("Total · 4 rooms", "37.0 m²", grand = true, sum = true, startsBlock = false),
            ),
            rows,
        )
        assertTrue(listOf(rows[1], rows[2], rows[7], rows[8]).all { it is ScheduleRow.RoomLine })
    }

    @Test
    fun `an empty floor gets a note and degenerate rooms show dashes`() {
        val empty = Floor("f1", "Basement")
        val broken = Floor("f2", "Main", rooms = listOf(Room(id = "x", name = "", type = RoomType.KITCHEN, corners = emptyList())))
        val rows = ScheduleTable.rows(project(empty, broken), listOf(empty, broken))
        assertEquals(ScheduleRow.Note(FloorSheets.EMPTY_FLOOR_MESSAGE), rows[1])
        assertEquals(ScheduleRow.Total("Basement total · 0 rooms", "0.0 m²", grand = false), rows[2])
        val line = rows.filterIsInstance<ScheduleRow.RoomLine>().single()
        assertEquals("Kitchen", line.name) // blank name falls back to the type
        assertEquals(ScheduleTable.DASH, line.dimensions)
    }

    @Test
    fun `areas that cannot be computed show a dash instead of failing`() {
        assertEquals(
            listOf(ScheduleRow.Total("Total · 3 rooms", ScheduleTable.DASH, grand = true)),
            ScheduleTable.totals(null, "Total · 3 rooms", grand = true, UnitSystem.METRIC),
        )
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

    @Test
    fun `pagination keeps a block of totals together with the row that sums it`() {
        // Every room count from 1 to two pages' worth: wherever the page break falls, it never falls
        // inside a block of totals or right after a note.
        val capacity = ScheduleLayout.rowsPerPage(PaperSize.A4.heightPt.toDouble())
        for (count in 1..2 * capacity) {
            val rooms = (1..count).map { rect("r$it", 3.0, 3.0) } +
                rect("g", 6.0, 6.0, type = RoomType.GARAGE) +
                rect("d", 3.0, 2.0, type = RoomType.DECK) +
                rect("x", 1.0, 1.0, category = AreaCategory.EXCLUDED)
            val floor = Floor("f", "Main", rooms = rooms)
            val rows = ScheduleTable.rows(project(floor), listOf(floor))
            val pages = ScheduleTable.paginate(rows, PaperSize.A4.heightPt.toDouble())
            assertEquals(rows, pages.flatten())
            pages.forEach { page ->
                assertTrue("$count rooms", page.size <= capacity)
                val last = page.last()
                assertFalse("$count rooms: a page ends inside the totals", last is ScheduleRow.Total && !last.sum)
                assertFalse("$count rooms: a page ends on a note", last is ScheduleRow.Note)
            }
        }
    }
}
