package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.DxfExporter
import com.oroplex.floorplan.core.render.DrawOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export screen's switches reach the DXF file, and the DXF preview is rendered with the same choices
 * (SEAM-3, APPX-4, PREM-7): before, the file followed the project's settings while the preview followed the
 * switches, and the preview showed wall lengths the file never has.
 */
class DxfOptionsTest {

    private val room = Room(
        id = "a",
        name = "Kitchen",
        corners = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0)),
    )
    private val floor = Floor("f", "Main", rooms = listOf(room))

    private fun project(settings: PlanSettings) =
        Project(id = "p", name = "House", createdAt = 0, updatedAt = 0, floors = listOf(floor), settings = settings)

    private val allOn = PlanSettings(unitSystem = UnitSystem.METRIC, showRoomAreas = true, showOverallDimensions = true)
    private val allOff = allOn.copy(showRoomAreas = false, showOverallDimensions = false, showWallLengths = false)

    private fun request(areas: Boolean, overall: Boolean, walls: Boolean = true) = ExportRequest(
        format = ExportFormat.DXF,
        floorId = null,
        showWallLengths = walls,
        showAreas = areas,
        showOverallDimensions = overall,
    )

    /** One entity of the ENTITIES section: its type (LINE, TEXT, ...), layer (group 8) and text (group 1). */
    private data class Entity(val type: String, val layer: String?, val text: String?)

    private fun entities(dxf: String): List<Entity> {
        val lines = dxf.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        assertEquals("code/value pairs", 0, lines.size % 2)
        val groups = lines.chunked(2).map { (code, value) -> code.trim().toInt() to value }
        val start = groups.indices.first { groups[it] == (0 to "SECTION") && groups.getOrNull(it + 1) == (2 to "ENTITIES") } + 2
        val end = (start until groups.size).first { groups[it] == (0 to "ENDSEC") }
        val out = ArrayList<Entity>()
        var i = start
        while (i < end) {
            val type = groups[i].second
            var j = i + 1
            while (j < end && groups[j].first != 0) j++
            val body = groups.subList(i + 1, j)
            out += Entity(type, body.firstOrNull { it.first == 8 }?.second, body.firstOrNull { it.first == 1 }?.second)
            i = j
        }
        return out
    }

    private fun List<Entity>.onLayer(layer: String) = filter { it.layer == layer }

    private fun List<Entity>.showAreas() = onLayer(DxfExporter.LAYER_TEXT).any { it.text?.endsWith("m2") == true }

    @Test
    fun `overall dimensions follow the export switch, not the project setting`() {
        // Project setting on, switch off: no DIMENSIONS entities.
        val off = entities(ProjectExporter.dxfFor(project(allOn), floor, request(areas = true, overall = false)))
        assertTrue(off.onLayer(DxfExporter.LAYER_DIMENSIONS).isEmpty())
        // Project setting off, switch on: the dimension lines and their two labels are there.
        val on = entities(ProjectExporter.dxfFor(project(allOff), floor, request(areas = false, overall = true)))
        val dims = on.onLayer(DxfExporter.LAYER_DIMENSIONS)
        assertTrue(dims.any { it.type == "LINE" })
        assertEquals(2, dims.count { it.type == "TEXT" })
        // Walls, room outline and the room label are there either way.
        for (file in listOf(off, on)) {
            assertTrue(file.onLayer(DxfExporter.LAYER_WALLS).isNotEmpty())
            assertTrue(file.onLayer(DxfExporter.LAYER_ROOMS).isNotEmpty())
            assertTrue(file.onLayer(DxfExporter.LAYER_TEXT).any { it.text == "Kitchen" })
        }
    }

    @Test
    fun `room areas follow the export switch, not the project setting`() {
        assertTrue(entities(ProjectExporter.dxfFor(project(allOff), floor, request(areas = true, overall = false))).showAreas())
        assertFalse(entities(ProjectExporter.dxfFor(project(allOn), floor, request(areas = false, overall = true))).showAreas())
    }

    @Test
    fun `the wall lengths switch changes nothing in a DXF`() {
        val p = project(allOn)
        assertEquals(
            ProjectExporter.dxfFor(p, floor, request(areas = true, overall = true, walls = true)),
            ProjectExporter.dxfFor(p, floor, request(areas = true, overall = true, walls = false)),
        )
    }

    @Test
    fun `switches left at the project's settings give the project's own drawing`() {
        for (settings in listOf(allOn, allOff)) {
            val request = request(areas = settings.showRoomAreas, overall = settings.showOverallDimensions)
            assertEquals(DxfExporter.export(floor.rooms, settings), ProjectExporter.dxfFor(project(settings), floor, request))
        }
    }

    @Test
    fun `the preview is rendered with what the file holds`() {
        // The preview draws request.labels. For every combination of the switches it must agree with the file:
        // dimensions and areas exactly when the file has them, wall lengths never.
        val p = project(allOn)
        for (areas in listOf(true, false)) {
            for (overall in listOf(true, false)) {
                for (walls in listOf(true, false)) {
                    val request = request(areas, overall, walls)
                    val file = entities(ProjectExporter.dxfFor(p, floor, request))
                    val preview = FloorSheets.renderFloor(p, floor, request.labels, 1.0)!!.ops
                    val case = "areas=$areas overall=$overall walls=$walls"
                    assertEquals(case, file.onLayer(DxfExporter.LAYER_DIMENSIONS).isNotEmpty(), preview.any { it.tag?.startsWith("dimension:") == true })
                    assertEquals(case, file.showAreas(), preview.any { it is DrawOp.Text && it.text.endsWith("m²") })
                    assertTrue(case, preview.none { it.tag?.startsWith("wall:") == true })
                }
            }
        }
    }

    @Test
    fun `other drawings keep their wall lengths`() {
        val p = project(allOn)
        val svg = ExportRequest(ExportFormat.SVG, null, showWallLengths = true)
        assertTrue(FloorSheets.renderFloor(p, floor, svg.labels, 1.0)!!.ops.any { it.tag?.startsWith("wall:") == true })
    }
}
