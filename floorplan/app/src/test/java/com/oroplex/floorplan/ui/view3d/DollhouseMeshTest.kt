package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.Dollhouse
import com.oroplex.floorplan.core.render.Face3
import com.oroplex.floorplan.core.render.FaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DollhouseMeshTest {
    private val settings = PlanSettings(wallThickness = 0.1)

    private fun rect(x: Double, y: Double, w: Double, h: Double) =
        listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))

    private fun room(id: String, corners: List<Vec2>, type: RoomType = RoomType.OTHER, openings: List<Opening> = emptyList(), name: String = id) =
        Room(id = id, name = name, type = type, corners = corners, ceilingHeight = 2.5, openings = openings)

    /** Vertical quad in the plane z = [z], spanning x0..x1 and y0..y1, facing +z. */
    private fun quadZ(x0: Double, x1: Double, y0: Double, y1: Double, z: Double = 0.0, kind: FaceKind = FaceKind.WALL_INNER) =
        Face3(listOf(Vec3(x0, y0, z), Vec3(x1, y0, z), Vec3(x1, y1, z), Vec3(x0, y1, z)), kind, "r")

    private fun outlineLength(mesh: DollhouseMesh, face: Int): Double =
        (mesh.lineStart[face] until mesh.lineStart[face + 1]).sumOf { s ->
            val a = mesh.lineVertex[2 * s]
            val b = mesh.lineVertex[2 * s + 1]
            Vec3(mesh.vx[a], mesh.vy[a], mesh.vz[a]).distanceTo(Vec3(mesh.vx[b], mesh.vy[b], mesh.vz[b]))
        }

    @Test fun flattensEveryFace() {
        val rooms = listOf(room("r", rect(0.0, 0.0, 4.0, 3.0)))
        val faces = Dollhouse.build(rooms, settings)
        val mesh = DollhouseMesh.build(faces, rooms)
        assertEquals(faces.size, mesh.faceCount)
        assertEquals(faces.sumOf { it.points.size }, mesh.faceStart[mesh.faceCount])
        for (i in 0 until mesh.faceCount) {
            // Plane equation holds for every vertex of the face.
            for (v in mesh.faceStart[i] until mesh.faceStart[i + 1]) {
                val d = mesh.nx[i] * mesh.vx[v] + mesh.ny[i] * mesh.vy[v] + mesh.nz[i] * mesh.vz[v]
                assertEquals(mesh.planeD[i], d, 1e-9)
            }
            assertEquals(faces[i].kind, mesh.kinds[i])
        }
        assertEquals(-0.1, mesh.bounds.minX, 1e-12)
        assertEquals(2.5, mesh.bounds.maxY, 1e-12)
    }

    @Test fun badFacesAreDropped() {
        val good = quadZ(0.0, 1.0, 0.0, 1.0)
        val faces = listOf(
            good,
            Face3(listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0)), FaceKind.FLOOR, null),
            Face3(listOf(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0)), FaceKind.FLOOR, null),
            Face3(listOf(Vec3.ZERO, Vec3(Double.NaN, 0.0, 0.0), Vec3(0.0, 1.0, 0.0)), FaceKind.FLOOR, null),
        )
        assertEquals(1, DollhouseMesh.build(faces, emptyList()).faceCount)
        assertEquals(0, DollhouseMesh.EMPTY.faceCount)
        assertTrue(DollhouseMesh.EMPTY.bounds.isEmpty)
    }

    @Test fun seamBetweenCoplanarPiecesIsNotOutlined() {
        val left = quadZ(0.0, 1.0, 0.0, 2.0)
        val right = quadZ(1.0, 3.0, 0.0, 2.0)
        val mesh = DollhouseMesh.build(listOf(left, right), emptyList())
        // Each piece: its perimeter minus the shared 2 m edge.
        assertEquals(2 * 1.0 + 2 * 2.0 - 2.0, outlineLength(mesh, 0), 1e-9)
        assertEquals(2 * 2.0 + 2 * 2.0 - 2.0, outlineLength(mesh, 1), 1e-9)
    }

    @Test fun tJunctionSeamIsOnlyPartlyHidden() {
        // A full-height piece next to a header piece (above a door): only the part beside the header is a seam.
        val side = quadZ(0.0, 1.0, 0.0, 2.5)
        val header = quadZ(1.0, 1.8, 2.0, 2.5)
        val mesh = DollhouseMesh.build(listOf(side, header), emptyList())
        assertEquals(2 * 1.0 + 2 * 2.5 - 0.5, outlineLength(mesh, 0), 1e-9)
        assertEquals(2 * 0.8 + 2 * 0.5 - 0.5, outlineLength(mesh, 1), 1e-9)
    }

    @Test fun creasesDifferentKindsAndDuplicatesKeepTheirOutlines() {
        val wall = quadZ(0.0, 1.0, 0.0, 1.0)
        // Perpendicular face sharing the edge x = 1: a real crease.
        val side = Face3(listOf(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 0.0, -1.0), Vec3(1.0, 1.0, -1.0), Vec3(1.0, 1.0, 0.0)), FaceKind.WALL_INNER, "r")
        val crease = DollhouseMesh.build(listOf(wall, side), emptyList())
        assertEquals(4.0, outlineLength(crease, 0), 1e-9)
        // Coplanar neighbour of another kind (e.g. an outer wall above a lower room's inner wall): color change, kept.
        val other = quadZ(1.0, 2.0, 0.0, 1.0, kind = FaceKind.WALL_OUTER)
        assertEquals(4.0, outlineLength(DollhouseMesh.build(listOf(wall, other), emptyList()), 0), 1e-9)
        // The same jamb built from both rooms of a shared wall: coincident, both keep every edge.
        val dupes = DollhouseMesh.build(listOf(wall, wall.copy(roomId = "other")), emptyList())
        assertEquals(4.0, outlineLength(dupes, 0), 1e-9)
        assertEquals(4.0, outlineLength(dupes, 1), 1e-9)
    }

    @Test fun overlappingCoplanarFacesHideTheirInnerEnds() {
        // Two rooms' collinear exterior walls overlapping by a wall thickness at the shared corner.
        val a = quadZ(0.0, 4.1, 0.0, 2.5, kind = FaceKind.WALL_OUTER)
        val b = quadZ(4.0, 7.2, 0.0, 2.5, kind = FaceKind.WALL_OUTER)
        val mesh = DollhouseMesh.build(listOf(a, b), emptyList())
        // a keeps its left end, top and bottom; its right end (inside b) is hidden. Same for b's left end.
        assertEquals(2 * 4.1 + 2.5, outlineLength(mesh, 0), 1e-9)
        assertEquals(2 * 3.2 + 2.5, outlineLength(mesh, 1), 1e-9)
    }

    @Test fun wallWithADoorIsOutlinedAsOneSurface() {
        val door = Opening("d", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.8, height = 2.0)
        val rooms = listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door)))
        val faces = Dollhouse.build(rooms, settings)
        val mesh = DollhouseMesh.build(faces, rooms)
        val wall0 = faces.indices.filter { i ->
            faces[i].kind == FaceKind.WALL_INNER && faces[i].points.all { abs(it.z) < 1e-9 }
        }
        assertEquals(3, wall0.size)
        // Wall outline (2L + 2H) plus the two door sides; the door's head line is on the header, its floor is open.
        val expected = 2 * 4.0 + 2 * 2.5 + 2 * 2.0
        assertEquals(expected, wall0.sumOf { outlineLength(mesh, it) }, 1e-9)
    }

    @Test fun shadingKeepsUpFacingColorsAndDarkensWalls() {
        assertEquals(1.0, DollhousePalette.brightness(Vec3.UP), 1e-12)
        val walls = listOf(Vec3(1.0, 0.0, 0.0), Vec3(-1.0, 0.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0))
        val bright = walls.map { DollhousePalette.brightness(it) }
        assertTrue(bright.all { it < 1.0 && it > 0.7 })
        assertTrue(bright.distinct().size > 1) // walls facing different ways are told apart
        assertEquals(DollhousePalette.INNER_WALL, DollhousePalette.shade(DollhousePalette.INNER_WALL, Vec3.UP))
        val shaded = DollhousePalette.shade(0x80FFFFFF.toInt(), Vec3(0.0, 0.0, -1.0))
        assertEquals(0x80, (shaded ushr 24) and 0xFF) // alpha kept
        assertTrue((shaded and 0xFF) < 0xFF)
    }

    @Test fun floorTintsAreSubtleButDistinct() {
        val base = DollhousePalette.FLOOR
        assertEquals(base, DollhousePalette.floorColor(null))
        assertNotEquals(base, DollhousePalette.floorColor(RoomType.BATHROOM))
        for (type in RoomType.entries) {
            val c = DollhousePalette.floorColor(type)
            for (shift in listOf(0, 8, 16)) {
                val d = abs(((c shr shift) and 0xFF) - ((base shr shift) and 0xFF))
                assertTrue("$type differs too much", d <= 16)
            }
        }
        val rooms = listOf(room("bath", rect(0.0, 0.0, 2.0, 2.0), type = RoomType.BATHROOM))
        val mesh = DollhouseMesh.build(Dollhouse.build(rooms, settings), rooms)
        val floor = (0 until mesh.faceCount).single { mesh.isFloor(it) }
        assertEquals(DollhousePalette.floorColor(RoomType.BATHROOM), mesh.fillColor[floor])
    }

    @Test fun labelsSitOnModeledFloors() {
        val l = listOf(Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 1.5), Vec2(1.5, 1.5), Vec2(1.5, 6.0), Vec2(0.0, 6.0))
        val rooms = listOf(
            room("box", rect(10.0, 0.0, 4.0, 3.0), name = "  Kitchen\n"),
            room("ell", l, type = RoomType.HALLWAY, name = ""),
            room("broken", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0))),
        )
        val mesh = DollhouseMesh.build(Dollhouse.build(rooms, settings), rooms)
        assertEquals(listOf("box", "ell"), mesh.labels.map { it.roomId })
        assertEquals("Kitchen", mesh.labels[0].text)
        assertEquals(RoomType.HALLWAY.displayName, mesh.labels[1].text)
        val box = mesh.labels[0].anchor
        assertEquals(12.0, box.x, 1e-9)
        assertEquals(-1.5, box.z, 1e-9) // plan y 1.5
        assertEquals(DollhouseMesh.LABEL_HEIGHT, box.y, 1e-12)
        // Occlusion is judged at half the wall height above the same spot; area ranks labels.
        val probe = mesh.labels[0].probe
        assertEquals(box.x, probe.x, 1e-12)
        assertEquals(box.z, probe.z, 1e-12)
        assertEquals(1.25, probe.y, 1e-12)
        assertEquals(12.0, mesh.labels[0].area, 1e-9)
        // The L's centroid is near the inside corner; its label must still be on the floor.
        val ell = mesh.labels[1].anchor
        assertTrue(Polygons.contains(l, Vec2(ell.x, -ell.z)))
        assertTrue(Polygons.signedDistanceToOutline(l, Vec2(ell.x, -ell.z)) > 0.5)
    }

    @Test fun labelAnchorRejectsUnusableOutlines() {
        assertNull(DollhouseMesh.labelAnchor(emptyList()))
        assertNull(DollhouseMesh.labelAnchor(listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0))))
        assertNotNull(DollhouseMesh.labelAnchor(rect(0.0, 0.0, 1.0, 1.0)))
    }

    @Test fun insideIntervalsOfASegment() {
        val square = rect(0.0, 0.0, 1.0, 1.0)
        val through = OutlineEdges.insideIntervals(Vec2(-1.0, 0.5), Vec2(2.0, 0.5), square)
        assertEquals(1, through.size)
        assertEquals(1.0 / 3, through[0].first, 1e-12)
        assertEquals(2.0 / 3, through[0].second, 1e-12)
        assertTrue(OutlineEdges.insideIntervals(Vec2(-1.0, 2.0), Vec2(2.0, 2.0), square).isEmpty())
        val inside = OutlineEdges.insideIntervals(Vec2(0.2, 0.5), Vec2(0.8, 0.5), square)
        assertEquals(listOf(0.0 to 1.0), inside)
    }
}
