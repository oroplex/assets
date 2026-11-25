package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderLayoutTest {

    private val header = SheetLayout.header(612.0)

    @Test
    fun `contain fit keeps the aspect ratio and scales up or down`() {
        val (w, h) = HeaderLayout.containSize(900.0, 300.0, 120.0, 40.0)!!
        assertEquals(120.0, w, 1e-9)
        assertEquals(40.0, h, 1e-9)
        assertEquals(40.0 to 40.0, HeaderLayout.containSize(512.0, 512.0, 120.0, 40.0))
        assertEquals(20.0 to 40.0, HeaderLayout.containSize(10.0, 20.0, 120.0, 40.0)) // small logos scale up
        assertNull(HeaderLayout.containSize(0.0, 10.0, 120.0, 40.0))
        assertNull(HeaderLayout.containSize(10.0, Double.NaN, 120.0, 40.0))
        assertNull(HeaderLayout.containSize(10.0, 10.0, 0.0, 40.0))
    }

    @Test
    fun `logo sits at the left edge, centered in the band, text follows it`() {
        val layout = HeaderLayout.brandBlock(header, 600 to 200)
        val logo = assertNotNullAndGet(layout.logo)
        assertEquals(header.left, logo.left, 1e-9)
        assertEquals(HeaderLayout.LOGO_MAX_WIDTH, logo.width, 1e-9)
        assertEquals(40.0, logo.height, 1e-9)
        assertTrue(logo.top >= header.top && logo.bottom <= header.bottom - 2)
        assertEquals(header.top + (header.height - 2) / 2, logo.centerY, 1e-9)
        assertEquals(logo.right + HeaderLayout.LOGO_TEXT_GAP, layout.textLeft, 1e-9)
    }

    @Test
    fun `a tall logo is limited by height`() {
        val logo = assertNotNullAndGet(HeaderLayout.brandBlock(header, 100 to 400).logo)
        assertEquals(HeaderLayout.LOGO_MAX_HEIGHT, logo.height, 1e-9)
        assertEquals(10.0, logo.width, 1e-9)
    }

    @Test
    fun `without a usable logo the text starts at the margin`() {
        assertEquals(BrandBlockLayout(null, header.left), HeaderLayout.brandBlock(header, null))
        assertEquals(BrandBlockLayout(null, header.left), HeaderLayout.brandBlock(header, 0 to 50))
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

class SheetTextRulesTest {

    @Test
    fun `bottom line combines the generated date and page label`() {
        assertEquals("Generated November 24, 2026 · Page 1 of 3", FloorSheets.bottomLineText("November 24, 2026", "Page 1 of 3"))
        assertEquals("Generated November 24, 2026", FloorSheets.bottomLineText("November 24, 2026", null))
        assertEquals("Page 2 of 2", FloorSheets.bottomLineText(" ", "Page 2 of 2"))
        assertEquals("", FloorSheets.bottomLineText("", null))
    }

    @Test
    fun `page labels`() {
        assertEquals("Page 1 of 3", FloorSheets.pageLabel(1, 3))
    }

    @Test
    fun `sheet branding is null when nothing would print and trims text`() {
        assertNull(SheetBranding.of("  ", "\t", null))
        assertEquals(SheetBranding("Acme", "", null), SheetBranding.of(" Acme ", " ", null))
        assertEquals(SheetBranding("", "555-0100", null), SheetBranding.of("", "555-0100 ", null))
    }

    @Test
    fun `schedule pages leave room for the title, footnote and bottom line`() {
        for (paper in PaperSize.entries) {
            val h = paper.heightPt.toDouble()
            val rows = ScheduleLayout.rowsPerPage(h)
            assertTrue(rows >= 25)
            val tableEnd = ScheduleLayout.tableTop(h) + ScheduleLayout.HEADER_ROW_HEIGHT + rows * ScheduleLayout.ROW_HEIGHT
            assertTrue(tableEnd <= ScheduleLayout.tableBottom(h) + 1e-9)
            assertTrue(ScheduleLayout.tableBottom(h) + ScheduleLayout.FOOTNOTE_SPACE <= h - SheetLayout.MARGIN - 8)
            assertTrue(ScheduleLayout.titleBaseline(h) < ScheduleLayout.tableTop(h))
            assertTrue(ScheduleLayout.titleBaseline(h) > SheetLayout.header(paper.widthPt.toDouble()).bottom)
        }
        assertEquals(1, ScheduleLayout.rowsPerPage(50.0)) // absurd page still holds a row
    }
}

/** Page counting and labels of the prepared PDF (no Android graphics involved). */
class PdfPagesTest {

    private fun room(id: String, x: Double) = Room(
        id = id,
        name = "Room $id",
        corners = listOf(Vec2(x, 0.0), Vec2(x + 3.0, 0.0), Vec2(x + 3.0, 3.0), Vec2(x, 3.0)),
    )

    private fun floor(id: String, rooms: Int) = Floor(id, "Floor $id", rooms = (0 until rooms).map { room("$id$it", it * 3.5) })

    private fun project(vararg floors: Floor) = Project(
        id = "p",
        name = "  ",
        address = " 1 Main St ",
        createdAt = 0,
        updatedAt = 0,
        floors = floors.toList(),
        settings = PlanSettings(unitSystem = UnitSystem.METRIC),
    )

    @Test
    fun `one plan page per floor plus the schedule`() {
        val floors = listOf(floor("a", 2), floor("b", 1))
        val prepared = PdfExporter.prepare(project(*floors.toTypedArray()), floors, PdfOptions(), "Nov 24, 2026")
        assertEquals(3, prepared.pageCount)
        assertEquals(listOf("Page 1 of 3", "Page 2 of 3", "Page 3 of 3"), prepared.pageLabels)
        assertEquals(listOf("Page 1 of 3", "Page 2 of 3"), prepared.labeledSheets.map { it.pageLabel })
        assertTrue(prepared.labeledSheets.all { it.showScaleRatio })
        assertEquals("Untitled project", prepared.projectName)
        assertEquals("1 Main St", prepared.address)
    }

    @Test
    fun `no schedule pages when the schedule is off`() {
        val floors = listOf(floor("a", 2))
        val prepared = PdfExporter.prepare(project(*floors.toTypedArray()), floors, PdfOptions(includeRoomSchedule = false), "d")
        assertEquals(1, prepared.pageCount)
        assertEquals(listOf("Page 1 of 1"), prepared.pageLabels)
    }

    @Test
    fun `a long schedule spills onto several pages`() {
        val floors = listOf(floor("a", 70))
        val prepared = PdfExporter.prepare(project(*floors.toTypedArray()), floors, PdfOptions(paper = PaperSize.A4), "d")
        val expectedRows = 70 + 2 // rooms + living area + total
        val capacity = ScheduleLayout.rowsPerPage(PaperSize.A4.heightPt.toDouble())
        val expectedSchedulePages = (expectedRows + capacity - 1) / capacity
        assertEquals(1 + expectedSchedulePages, prepared.pageCount)
        assertEquals(1, prepared.planPageCount)
        assertEquals("Page ${prepared.pageCount} of ${prepared.pageCount}", prepared.pageLabels.last())
    }

    @Test
    fun `a floor the user picked still gets a page with a message when it is empty`() {
        val floors = listOf(Floor("e", "Attic"))
        val prepared = PdfExporter.prepare(project(*floors.toTypedArray()), floors, PdfOptions(), "d")
        assertEquals(2, prepared.pageCount)
        assertNull(prepared.labeledSheets.single().drawList)
        // Nothing to scale: no scale is claimed, on the sheet or in the summary.
        assertNull(prepared.sheetScale)
        assertNull(prepared.labeledSheets.single().scale)
        assertEquals("Letter portrait · 2 pages", prepared.summary)
    }

    @Test
    fun `all floors of a project skip the empty ones, in pages, schedule and summary alike`() {
        val used = floor("a", 2)
        val p = project(used, Floor("e", "Attic"), floor("b", 1))
        val request = ExportRequest(ExportFormat.PDF, null, paper = PaperSize.A4)
        val covered = ProjectExporter.floorsFor(p, request)
        val prepared = PdfExporter.prepare(p, covered, PdfOptions(paper = PaperSize.A4), "d")
        assertEquals(2, prepared.planPageCount)
        assertEquals(3, prepared.pageCount)
        assertEquals(listOf("Floor a", "Floor b"), prepared.labeledSheets.map { it.floorName })
        assertTrue(prepared.labeledSheets.all { it.drawList != null })
        val scheduleRows = prepared.schedulePages.flatten()
        assertEquals(
            listOf("Floor a", "Floor b"),
            scheduleRows.filterIsInstance<ScheduleRow.FloorHeading>().map { it.title },
        )
        assertTrue(scheduleRows.none { it is ScheduleRow.Note })
        assertTrue(prepared.summary.endsWith("· 3 pages"))
    }

    @Test
    fun `sheets state the net living area`() {
        val floors = listOf(floor("a", 2))
        val sheet = PdfExporter.prepare(project(*floors.toTypedArray()), floors, PdfOptions(), "d").labeledSheets.single()
        assertEquals("Net living area (interior): 18.0 m²", sheet.areaText)
        assertEquals("2 rooms", sheet.roomCountText)
        // The PNG image is the same sheet: same wording.
        val png = FloorSheets.build(project(*floors.toTypedArray()), floors.single(), PlanLabels(), "d")
        assertEquals(sheet.areaText, png.areaText)
        assertNull(png.scale) // an image has no paper scale
    }

    @Test
    fun `branding reaches every sheet`() {
        val floors = listOf(floor("a", 1), floor("b", 1))
        val brand = SheetBranding.of("Acme", "555-0100", null)
        val prepared = PdfExporter.prepare(project(*floors.toTypedArray()), floors, PdfOptions(), "d", brand)
        assertTrue(prepared.labeledSheets.all { it.branding == brand })
    }

    @Test
    fun `plan labels pass through to the request`() {
        val request = ExportRequest(ExportFormat.PDF, null, showWallLengths = false, showAreas = true, showOverallDimensions = false)
        assertEquals(PlanLabels(showWallLengths = false, showAreas = true, showOverallDimensions = false), request.labels)
    }
}
