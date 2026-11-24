package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.Fixtures.opening
import com.oroplex.floorplan.core.render.Fixtures.rect
import com.oroplex.floorplan.core.render.Fixtures.room
import com.oroplex.floorplan.core.render.Fixtures.settings
import com.oroplex.floorplan.core.render.Fixtures.t
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DxfExporterTest {
    private data class Group(val code: Int, val value: String)

    /** An entity / table record: the "0" group that starts it plus its following groups. */
    private data class Record(val type: String, val groups: List<Group>) {
        fun all(code: Int) = groups.filter { it.code == code }.map { it.value }
        fun first(code: Int) = all(code).first()
        fun double(code: Int) = first(code).toDouble()
    }

    /** Parse strictly: an even number of lines, each pair an integer group code and a value. */
    private fun parse(dxf: String): List<Group> {
        val lines = dxf.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        assertEquals(0, lines.size % 2, "code/value pairs")
        return lines.chunked(2).map { (code, value) -> Group(code.trim().toInt(), value) }
    }

    private fun records(groups: List<Group>): List<Record> {
        val out = ArrayList<Record>()
        var i = 0
        while (i < groups.size) {
            check(groups[i].code == 0) { "record must start with group 0 at $i: ${groups[i]}" }
            val start = i++
            while (i < groups.size && groups[i].code != 0) i++
            out += Record(groups[start].value, groups.subList(start + 1, i))
        }
        return out
    }

    /** Records of the named section (between SECTION/2 name and ENDSEC). */
    private fun section(records: List<Record>, name: String): List<Record> {
        val start = records.indexOfFirst { it.type == "SECTION" && it.first(2) == name }
        assertTrue(start >= 0, "section $name present")
        val end = (start + 1 until records.size).first { records[it].type == "ENDSEC" }
        return records.subList(start + 1, end)
    }

    @Test fun structureSectionsAndEof() {
        val project = SampleProjects.demo(1L)
        val dxf = DxfExporter.export(project.floors.first().rooms, project.settings)
        assertTrue(dxf.all { it.code < 128 }, "pure ASCII")
        val groups = parse(dxf)
        val records = records(groups)
        assertEquals(listOf("HEADER", "TABLES", "ENTITIES"), records.filter { it.type == "SECTION" }.map { it.first(2) })
        assertEquals("EOF", records.last().type)
        assertEquals(3, records.count { it.type == "ENDSEC" })
        val header = records.first { it.type == "SECTION" }.groups
        val vars = header.withIndex().filter { it.value.code == 9 }.associate { it.value.value to header[it.index + 1] }
        assertEquals(Group(1, "AC1009"), vars["\$ACADVER"])
        assertEquals(Group(70, "1"), vars["\$INSUNITS"])
        assertTrue("\$EXTMIN" in vars && "\$EXTMAX" in vars)
    }

    @Test fun layersAreDefinedWithDistinctColors() {
        val records = records(parse(DxfExporter.export(listOf(room("a", rect(0.0, 0.0, 4.0, 3.0))), settings)))
        val layers = section(records, "TABLES").filter { it.type == "LAYER" }
        val names = layers.map { it.first(2) }
        assertTrue(names.containsAll(listOf("ROOMS", "WALLS", "DOORS", "WINDOWS", "TEXT")))
        val colors = layers.filter { it.first(2) != "0" }.map { it.first(62) }
        assertEquals(colors.size, colors.toSet().size, "distinct layer colors")
        assertTrue(layers.all { it.first(6) == "CONTINUOUS" })
        assertTrue(section(records, "TABLES").any { it.type == "LTYPE" && it.first(2) == "CONTINUOUS" })
        assertTrue(section(records, "TABLES").any { it.type == "STYLE" && it.first(2) == "STANDARD" })
    }

    @Test fun outlinesAreClosedPolylinesScaledToDrawingUnits() {
        val r = room("a", rect(0.0, 0.0, 4.0, 3.0))
        val metric = records(parse(DxfExporter.export(listOf(r), settings.copy(unitSystem = UnitSystem.METRIC))))
        val header = metric.first { it.type == "SECTION" }.groups
        assertEquals("4", header[header.indexOfFirst { it.value == "\$INSUNITS" } + 1].value)
        val entities = section(metric, "ENTITIES")
        val polylines = entities.withIndex().filter { it.value.type == "POLYLINE" }
        assertEquals(listOf("ROOMS", "WALLS"), polylines.map { it.value.first(8) })
        for ((index, poly) in polylines) {
            assertEquals("1", poly.first(70), "closed flag")
            assertEquals("1", poly.first(66), "vertices follow")
            val vertices = entities.drop(index + 1).takeWhile { it.type == "VERTEX" }
            assertEquals("SEQEND", entities[index + 1 + vertices.size].type)
            assertTrue(vertices.all { it.first(8) == poly.first(8) })
            if (poly.first(8) == "ROOMS") {
                assertEquals(r.corners.map { Vec2(it.x * 1000, it.y * 1000) }, vertices.map { Vec2(it.double(10), it.double(20)) })
            } else {
                val expected = Polygons.offset(r.corners, t).map { Vec2(it.x * 1000, it.y * 1000) }
                vertices.zip(expected).forEach { (v, e) -> assertNear(e, Vec2(v.double(10), v.double(20)), 1e-3) }
            }
        }
        // Imperial: inches.
        val imperial = section(records(parse(DxfExporter.export(listOf(r), settings))), "ENTITIES")
        val xs = imperial.filter { it.type == "VERTEX" && it.first(8) == "ROOMS" }.map { it.double(10) }
        assertNear(4.0 * 39.37007874, xs.max(), 1e-4)
        // Extents cover the walls.
        val hdr = records(parse(DxfExporter.export(listOf(r), settings))).first().groups
        val extMin = hdr.indexOfFirst { it.value == "\$EXTMIN" }
        assertNear(-t * 39.37007874, hdr[extMin + 1].value.toDouble(), 1e-4)
    }

    @Test fun doorsWindowsAndTextUseTheirLayers() {
        val r = room(
            "a", rect(0.0, 0.0, 4.0, 3.0),
            listOf(
                opening(OpeningType.DOOR, 0, 1.0, 0.9, DoorSwing.RIGHT_OUT),
                opening(OpeningType.WINDOW, 2, 1.0, 1.2),
                opening(OpeningType.OPENING, 3, 0.5, 1.0),
            ),
            name = "Office × 2",
        )
        val entities = section(records(parse(DxfExporter.export(listOf(r), settings.copy(unitSystem = UnitSystem.METRIC)))), "ENTITIES")
        val arc = entities.single { it.type == "ARC" }
        assertEquals("DOORS", arc.first(8))
        assertNear(900.0, arc.double(40), 1e-6)
        val start = arc.double(50)
        val end = arc.double(51)
        assertTrue(start in 0.0..360.0 && end in 0.0..360.0)
        // RIGHT_OUT on the south wall: hinge at (1.9, -t), sweeping CCW from 180 (closed) to 270 (open).
        assertNear(180.0, start, 1e-6)
        assertNear(270.0, end, 1e-6)
        assertNear(1900.0, arc.double(10), 1e-6)
        assertNear(-t * 1000, arc.double(20), 1e-6)
        val lines = entities.filter { it.type == "LINE" }
        assertEquals(1, lines.count { it.first(8) == "DOORS" })
        assertEquals(5, lines.count { it.first(8) == "WINDOWS" }, "frame (4) + glass line")
        val texts = entities.filter { it.type == "TEXT" }
        assertTrue(texts.all { it.first(8) == "TEXT" && it.first(72) == "1" && it.first(73) == "2" })
        assertEquals(listOf("Office x 2", "4.00 m x 3.00 m", "12.0 m2"), texts.map { it.first(1) })
        assertTrue(texts.all { it.double(40) > 0 })
    }

    @Test fun degenerateRoomsDoNotThrow() {
        val rooms = listOf(
            room("empty", emptyList()),
            room("two", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0))),
            room("bowtie", listOf(Vec2(0.0, 0.0), Vec2(2.0, 2.0), Vec2(2.0, 0.0), Vec2(0.0, 2.0))),
            room("nan", listOf(Vec2(Double.NaN, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))),
        )
        val dxf = DxfExporter.export(rooms, settings.copy(wallThickness = Double.NaN))
        assertTrue("NaN" !in dxf && "Infinity" !in dxf)
        val entities = section(records(parse(dxf)), "ENTITIES")
        val polylines = entities.filter { it.type == "POLYLINE" }
        assertTrue(polylines.all { it.first(8) == "ROOMS" })
        assertEquals(listOf("0", "1", "1"), polylines.map { it.first(70) }) // two-point outline stays open
        val empty = records(parse(DxfExporter.export(emptyList(), settings)))
        assertTrue(section(empty, "ENTITIES").isEmpty())
        assertEquals("EOF", empty.last().type)
    }

    @Test fun textIsAsciiSafe() {
        assertEquals("K\\U+00FCche 12 m2", DxfExporter.ascii("Küche\n12 m²"))
        assertEquals("100%%% %%%%%%d", DxfExporter.ascii("100% %%d"))
        assertEquals("Den ?", DxfExporter.ascii("Den 🏠\u0007"))
        assertEquals("50% off", DxfExporter.ascii("50% off"))
    }
}
