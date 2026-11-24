package com.oroplex.floorplan.core.io

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SampleProjectsTest {
    private val now = 1_700_000_000_000L
    private val project = SampleProjects.demo(now)
    private val floor = project.floors.single()
    private val rooms = floor.rooms
    private val wall = project.settings.wallThickness

    @Test fun projectMetadata() {
        assertEquals("Sample House", project.name)
        assertEquals("123 Example St", project.address)
        assertEquals("Main Floor", floor.name)
        assertEquals(now, project.createdAt)
        assertEquals(now, project.updatedAt)
        assertTrue(rooms.size in 6..8)
        assertTrue(rooms.all { it.captureMethod == CaptureMethod.MANUAL && it.ceilingHeight == 2.44 })
        val types = rooms.map { it.type }
        assertTrue(types.count { it == RoomType.BEDROOM || it == RoomType.PRIMARY_BEDROOM } in 2..3)
        assertTrue(listOf(RoomType.BATHROOM, RoomType.HALLWAY, RoomType.CLOSET, RoomType.ENTRY, RoomType.LIVING).all { it in types })
        // Ids are unique, deterministic for a given time and different for another time.
        val ids = listOf(project.id, floor.id) + rooms.map { it.id } + rooms.flatMap { r -> r.openings.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(project, SampleProjects.demo(now))
        assertNotEquals(project.id, SampleProjects.demo(now + 1).id)
    }

    @Test fun livingKitchenIsLShaped() {
        val living = rooms.single { it.type == RoomType.LIVING }
        assertEquals(6, living.corners.size)
        val reflex = Polygons.interiorAngles(living.corners).count { it > PI + 1e-6 }
        assertEquals(1, reflex)
    }

    @Test fun outlinesAreCounterClockwiseAndSimple() {
        for (r in rooms) {
            assertTrue(Polygons.isCounterClockwise(r.corners), "${r.name} CCW")
            assertTrue(Polygons.isSimple(r.corners), "${r.name} simple")
            assertTrue(Polygons.edges(r.corners).all { it.length > 0.5 }, "${r.name} has no sliver walls")
        }
    }

    @Test fun roomsDoNotOverlapAndKeepAWallBetweenThem() {
        for (i in rooms.indices) for (j in i + 1 until rooms.size) {
            val a = rooms[i]
            val b = rooms[j]
            assertTrue(a.corners.none { Polygons.contains(b.corners, it) }, "${a.name} inside ${b.name}")
            assertTrue(b.corners.none { Polygons.contains(a.corners, it) }, "${b.name} inside ${a.name}")
            val gap = minDistance(a.corners, b.corners)
            assertTrue(gap >= wall - 1e-9, "${a.name} / ${b.name} at least one wall apart: $gap")
        }
    }

    @Test fun neighbouringWallsAreExactlyOneWallThicknessApart() {
        var facingPairs = 0
        for (i in rooms.indices) for (j in i + 1 until rooms.size) {
            for (ea in Polygons.edges(rooms[i].corners)) for (eb in Polygons.edges(rooms[j].corners)) {
                if ((ea.direction dot eb.direction) > -0.999) continue // facing walls run in opposite directions
                val separation = ea.toLine().distance(eb.a)
                if (separation > 0.5) continue
                if (overlap(ea, eb) < 0.05) continue
                facingPairs++
                assertTrue(abs(separation - wall) < 1e-9, "${rooms[i].name} / ${rooms[j].name}: $separation")
            }
        }
        assertTrue(facingPairs >= 10, "the rooms actually share walls ($facingPairs)")
    }

    @Test fun openingsLieWithinTheirWalls() {
        for (r in rooms) for (o in r.openings) {
            assertTrue(o.wallIndex in r.corners.indices, "${r.name} ${o.id}")
            val length = Polygons.edges(r.corners)[o.wallIndex].length
            assertTrue(o.offset >= 0.0 && o.width > 0.2 && o.offset + o.width <= length + 1e-9, "${r.name} ${o.type} fits its wall")
            assertTrue(o.height > 0.0)
            if (o.type == OpeningType.WINDOW) assertTrue(o.sillHeight > 0.0)
        }
        val swings = rooms.flatMap { it.openings }.filter { it.type == OpeningType.DOOR }.map { it.swing }.toSet()
        assertEquals(4, swings.size, "all door swings are exercised")
    }

    @Test fun doorsConnectRoomsAndWindowsFaceOutside() {
        var exteriorDoors = 0
        for (r in rooms) for (o in r.openings) {
            val edge = Polygons.edges(r.corners)[o.wallIndex]
            val mid = edge.a + edge.direction * (o.offset + o.width / 2)
            val outside = mid - edge.direction.perp() * (wall + 0.05)
            val neighbour = rooms.firstOrNull { it !== r && Polygons.contains(it.corners, outside) }
            when (o.type) {
                OpeningType.WINDOW -> assertEquals(null, neighbour, "${r.name} window on an exterior wall")
                OpeningType.OPENING -> assertTrue(neighbour != null, "${r.name} passage leads somewhere")
                OpeningType.DOOR -> if (neighbour == null) exteriorDoors++
            }
        }
        assertEquals(1, exteriorDoors, "one front door")
        // Every room can be reached through doors / passages from the entry.
        val links = rooms.associate { it.id to mutableSetOf<String>() }
        for (r in rooms) for (o in r.openings.filter { it.type != OpeningType.WINDOW }) {
            val edge = Polygons.edges(r.corners)[o.wallIndex]
            val mid = edge.a + edge.direction * (o.offset + o.width / 2)
            val other = rooms.firstOrNull { it !== r && Polygons.contains(it.corners, mid - edge.direction.perp() * (wall + 0.05)) }
                ?: continue
            links.getValue(r.id) += other.id
            links.getValue(other.id) += r.id
        }
        val seen = mutableSetOf(rooms.single { it.type == RoomType.ENTRY }.id)
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) for (n in links.getValue(queue.removeFirst())) if (seen.add(n)) queue += n
        assertEquals(rooms.map { it.id }.toSet(), seen)
    }

    @Test fun rendersCleanly() {
        val dl = PlanRenderer.render(rooms, project.settings)
        assertFalse(dl.ops.any { it is DrawOp.Polyline && it.color == PlanColors.WARNING })
        assertEquals(rooms.size, dl.ops.count { it is DrawOp.FillPolygon && it.tag?.startsWith("room:") == true })
        // Every door is drawn (no false duplicate detection between neighbouring doors).
        assertEquals(rooms.sumOf { r -> r.openings.count { it.type == OpeningType.DOOR } }, dl.ops.count { it is DrawOp.Arc })
    }

    private fun overlap(a: Segment2, b: Segment2): Double {
        val d = a.direction
        val a0 = 0.0
        val a1 = a.length
        val b0 = (b.a - a.a) dot d
        val b1 = (b.b - a.a) dot d
        return min(a1, max(b0, b1)) - max(a0, min(b0, b1))
    }

    private fun minDistance(a: List<Vec2>, b: List<Vec2>): Double {
        val ea = Polygons.edges(a)
        val eb = Polygons.edges(b)
        return min(
            a.minOf { p -> eb.minOf { it.distanceTo(p) } },
            b.minOf { p -> ea.minOf { it.distanceTo(p) } },
        )
    }
}
