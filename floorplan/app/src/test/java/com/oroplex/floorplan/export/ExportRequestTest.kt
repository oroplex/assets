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
        val imperial = ExportSelection().resolve(project)
        assertEquals(ExportFormat.PDF, imperial.format)
        assertNull(imperial.floorId)
        assertEquals(PaperSize.LETTER, imperial.paper)
        assertEquals(project.settings.showWallLengths, imperial.showWallLengths)
        assertEquals(project.settings.showRoomAreas, imperial.showAreas)
        assertTrue(imperial.includeRoomSchedule)

        val metricProject = project.copy(
            settings = PlanSettings(unitSystem = UnitSystem.METRIC, showWallLengths = false, showRoomAreas = false),
        )
        val metric = ExportSelection().resolve(metricProject)
        assertEquals(PaperSize.A4, metric.paper)
        assertEquals(false, metric.showWallLengths)
        assertEquals(false, metric.showAreas)
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
