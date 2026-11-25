package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.ui.export.ExportSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRequestTest {

    private fun room(id: String) = Room(
        id = id,
        name = "Room $id",
        corners = listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 4.0), Vec2(0.0, 4.0)),
    )

    private val main = Floor("f1", "Main Floor", rooms = listOf(room("a"), room("b")))
    private val upper = Floor("f2", "Upper Floor", rooms = listOf(room("c")))
    private val project = Project(
        id = "p",
        name = "Smith House",
        createdAt = 0,
        updatedAt = 0,
        floors = listOf(main, upper),
    )

    @Test
    fun `multi floor formats export every floor unless one is chosen`() {
        assertEquals(listOf(main, upper), ProjectExporter.floorsFor(project, ExportRequest(ExportFormat.PDF, null)))
        assertEquals(listOf(upper), ProjectExporter.floorsFor(project, ExportRequest(ExportFormat.PDF, "f2")))
        // A floor that no longer exists falls back to all floors.
        assertEquals(listOf(main, upper), ProjectExporter.floorsFor(project, ExportRequest(ExportFormat.PDF, "gone")))
    }

    @Test
    fun `single file formats use the chosen or first floor`() {
        for (format in listOf(ExportFormat.PNG, ExportFormat.SVG, ExportFormat.DXF)) {
            assertEquals(listOf(main), ProjectExporter.floorsFor(project, ExportRequest(format, null)))
            assertEquals(listOf(upper), ProjectExporter.floorsFor(project, ExportRequest(format, "f2")))
            assertEquals(listOf(main), ProjectExporter.floorsFor(project, ExportRequest(format, "gone")))
        }
    }

    @Test
    fun `a project without floors covers nothing`() {
        val empty = project.copy(floors = emptyList())
        for (format in ExportFormat.entries) {
            assertTrue(ProjectExporter.floorsFor(empty, ExportRequest(format, null)).isEmpty())
        }
        assertEquals("Smith_House.pdf", ProjectExporter.fileName(empty, ExportRequest(ExportFormat.PDF, null)))
    }

    @Test
    fun `file names describe what is inside`() {
        assertEquals("Smith_House-All_floors.pdf", ProjectExporter.fileName(project, ExportRequest(ExportFormat.PDF, null)))
        assertEquals("Smith_House-Upper_Floor.pdf", ProjectExporter.fileName(project, ExportRequest(ExportFormat.PDF, "f2")))
        assertEquals("Smith_House-Main_Floor.png", ProjectExporter.fileName(project, ExportRequest(ExportFormat.PNG, null)))
        assertEquals("Smith_House-Upper_Floor.svg", ProjectExporter.fileName(project, ExportRequest(ExportFormat.SVG, "f2")))
        assertEquals("Smith_House-Main_Floor.dxf", ProjectExporter.fileName(project, ExportRequest(ExportFormat.DXF, null)))
        assertEquals("Smith_House.json", ProjectExporter.fileName(project, ExportRequest(ExportFormat.JSON, "f2")))
        val single = project.copy(floors = listOf(main))
        assertEquals("Smith_House-Main_Floor.pdf", ProjectExporter.fileName(single, ExportRequest(ExportFormat.PDF, null)))
    }

    @Test
    fun `formats declare the right mime types and extensions`() {
        assertEquals("application/pdf", ExportFormat.PDF.mimeType)
        assertEquals("image/png", ExportFormat.PNG.mimeType)
        assertEquals("image/svg+xml", ExportFormat.SVG.mimeType)
        assertEquals("application/dxf", ExportFormat.DXF.mimeType)
        assertEquals("application/json", ExportFormat.JSON.mimeType)
        ExportFormat.entries.forEach { assertEquals(it.name.lowercase(), it.extension) }
    }

    @Test
    fun `selection defaults come from the project settings`() {
        val imperial = ExportSelection().resolve(project, country = "US")
        assertEquals(ExportFormat.PDF, imperial.format)
        assertNull(imperial.floorId)
        assertEquals(PaperSize.LETTER, imperial.paper)
        assertEquals(project.settings.showWallLengths, imperial.showWallLengths)
        assertEquals(project.settings.showRoomAreas, imperial.showAreas)
        assertEquals(project.settings.showOverallDimensions, imperial.showOverallDimensions)
        assertTrue(imperial.includeRoomSchedule)

        val metricProject = project.copy(
            settings = PlanSettings(
                unitSystem = UnitSystem.METRIC, showWallLengths = false, showRoomAreas = false, showOverallDimensions = false,
            ),
        )
        val metric = ExportSelection().resolve(metricProject, country = "DE")
        assertEquals(PaperSize.A4, metric.paper)
        assertEquals(false, metric.showWallLengths)
        assertEquals(false, metric.showAreas)
        assertEquals(false, metric.showOverallDimensions)
    }

    @Test
    fun `paper follows the country, and the units only when the country is unknown`() {
        // Letter in the US and Canada whatever the units (Canada is metric but prints on Letter).
        assertEquals(PaperSize.LETTER, PaperSize.defaultFor(UnitSystem.IMPERIAL, "US"))
        assertEquals(PaperSize.LETTER, PaperSize.defaultFor(UnitSystem.METRIC, "US"))
        assertEquals(PaperSize.LETTER, PaperSize.defaultFor(UnitSystem.METRIC, "CA"))
        assertEquals(PaperSize.LETTER, PaperSize.defaultFor(UnitSystem.METRIC, " ca "))
        // A4 everywhere else, also for a project in feet and inches (common in the UK).
        assertEquals(PaperSize.A4, PaperSize.defaultFor(UnitSystem.METRIC, "DE"))
        assertEquals(PaperSize.A4, PaperSize.defaultFor(UnitSystem.IMPERIAL, "GB"))
        assertEquals(PaperSize.A4, PaperSize.defaultFor(UnitSystem.METRIC, "AU"))
        // No usable country (a locale such as plain "en"): fall back on the project's units.
        assertEquals(PaperSize.LETTER, PaperSize.defaultFor(UnitSystem.IMPERIAL, ""))
        assertEquals(PaperSize.A4, PaperSize.defaultFor(UnitSystem.METRIC, ""))
        assertEquals(PaperSize.A4, PaperSize.defaultFor(UnitSystem.METRIC, "419")) // UN M.49 area code, not a country

        assertEquals(PaperSize.LETTER, ExportSelection().resolve(project.copy(settings = PlanSettings(unitSystem = UnitSystem.METRIC)), "CA").paper)
        // An explicit choice always wins.
        assertEquals(PaperSize.A4, ExportSelection(paper = PaperSize.A4).resolve(project, "US").paper)
    }

    @Test
    fun `all floors of a document leave out floors without rooms`() {
        val unused = Floor("f3", "Third Floor")
        val basement = Floor("f0", "Basement")
        val withEmpty = project.copy(floors = listOf(basement, main, unused, upper))
        val allPdf = ExportRequest(ExportFormat.PDF, null)
        assertEquals(listOf(main, upper), ProjectExporter.floorsFor(withEmpty, allPdf))
        assertEquals(listOf(basement, unused), ProjectExporter.skippedEmptyFloors(withEmpty, allPdf))
        assertEquals("Smith_House-All_floors.pdf", ProjectExporter.fileName(withEmpty, allPdf))

        // With one floor left the file is named after it, like the single page it holds.
        val oneUsed = project.copy(floors = listOf(main, unused))
        assertEquals(listOf(main), ProjectExporter.floorsFor(oneUsed, allPdf))
        assertEquals("Smith_House-Main_Floor.pdf", ProjectExporter.fileName(oneUsed, allPdf))

        // A floor the user picked is exported even when it is empty (its page says so), and nothing is skipped.
        val picked = ExportRequest(ExportFormat.PDF, "f3")
        assertEquals(listOf(unused), ProjectExporter.floorsFor(withEmpty, picked))
        assertTrue(ProjectExporter.skippedEmptyFloors(withEmpty, picked).isEmpty())

        // The JSON backup always holds every floor.
        val json = ExportRequest(ExportFormat.JSON, null)
        assertEquals(withEmpty.floors, ProjectExporter.floorsFor(withEmpty, json))
        assertTrue(ProjectExporter.skippedEmptyFloors(withEmpty, json).isEmpty())

        // Single-floor formats are unaffected: still the first floor, even an empty one (the screen explains it).
        assertEquals(listOf(basement), ProjectExporter.floorsFor(withEmpty, ExportRequest(ExportFormat.PNG, null)))
        assertTrue(ProjectExporter.skippedEmptyFloors(withEmpty, ExportRequest(ExportFormat.PNG, null)).isEmpty())
    }

    @Test
    fun `when every floor is empty all of them are kept`() {
        val empty = project.copy(floors = listOf(Floor("a", "Main"), Floor("b", "Upper")))
        val allPdf = ExportRequest(ExportFormat.PDF, null)
        assertEquals(empty.floors, ProjectExporter.floorsFor(empty, allPdf))
        assertTrue(ProjectExporter.skippedEmptyFloors(empty, allPdf).isEmpty())
        assertEquals("Smith_House-All_floors.pdf", ProjectExporter.fileName(empty, allPdf))
    }

    @Test
    fun `labels describe what each format really carries`() {
        val all = ExportRequest(ExportFormat.SVG, null, showWallLengths = true, showAreas = true, showOverallDimensions = true)
        assertEquals(PlanLabels(showWallLengths = true, showAreas = true, showOverallDimensions = true), all.labels)
        for (format in listOf(ExportFormat.PDF, ExportFormat.PNG, ExportFormat.SVG)) {
            assertTrue(all.copy(format = format).labels.showWallLengths)
        }
        // DXF never has wall-length labels; areas and overall dimensions follow the switches.
        val dxf = all.copy(format = ExportFormat.DXF)
        assertEquals(PlanLabels(showWallLengths = false, showAreas = true, showOverallDimensions = true), dxf.labels)
        assertEquals(
            PlanLabels(showWallLengths = false, showAreas = false, showOverallDimensions = false),
            dxf.copy(showAreas = false, showOverallDimensions = false).labels,
        )
    }

    @Test
    fun `explicit choices win and stale floors are dropped`() {
        val chosen = ExportSelection(
            format = ExportFormat.PNG,
            floorId = "f2",
            paper = PaperSize.A4,
            showWallLengths = false,
            showAreas = true,
            includeRoomSchedule = false,
        ).resolve(project)
        assertEquals(ExportRequest(ExportFormat.PNG, "f2", PaperSize.A4, false, true, false), chosen)

        assertNull(ExportSelection(floorId = "deleted").resolve(project).floorId)
    }
}
