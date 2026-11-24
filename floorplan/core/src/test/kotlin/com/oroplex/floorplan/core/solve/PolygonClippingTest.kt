package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.PolygonClipping
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.solve.Ops.rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The overlap-area geometry behind [FloorChecks.overlappingRooms] and [FloorOps.snapRoom]. */
class PolygonClippingTest {
    private val u = listOf(v(0.0, 0.0), v(6.0, 0.0), v(6.0, 4.0), v(4.0, 4.0), v(4.0, 2.0), v(2.0, 2.0), v(2.0, 4.0), v(0.0, 4.0))

    private fun assertTiles(poly: List<Vec2>) {
        val triangles = assertNotNull(PolygonClipping.triangulate(poly), "triangulates")
        assertTrue(triangles.all { it.size == 3 && Polygons.signedArea(it) > 0 }, "CCW triangles")
        assertEquals(Polygons.area(poly), triangles.sumOf { Polygons.area(it) }, 1e-9)
        // No two triangles overlap.
        for (i in triangles.indices) for (j in i + 1 until triangles.size) {
            assertEquals(0.0, Polygons.area(PolygonClipping.clipConvex(triangles[i], triangles[j])), 1e-9)
        }
    }

    @Test fun triangulatesNonConvexRooms() {
        assertTiles(rect(0.0, 0.0, 4.0, 3.0))
        assertTiles(Shapes.lShape)
        assertTiles(Shapes.lShape.reversed()) // clockwise input
        assertTiles(u)
        // Collinear and duplicate vertices (a wall split by an inserted corner, a double tap).
        assertTiles(listOf(v(0.0, 0.0), v(2.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(4.0, 3.0), v(0.0, 3.0)))
        // A comb with reflex vertices lying exactly on candidate diagonals.
        assertTiles(listOf(v(0.0, 0.0), v(5.0, 0.0), v(5.0, 3.0), v(4.0, 3.0), v(4.0, 1.0), v(3.0, 1.0), v(3.0, 3.0), v(2.0, 3.0), v(2.0, 1.0), v(1.0, 1.0), v(1.0, 3.0), v(0.0, 3.0)))
    }

    @Test fun triangulateRejectsWhatItCannotTile() {
        assertNull(PolygonClipping.triangulate(listOf(v(0.0, 0.0), v(3.0, 2.0), v(3.0, 0.0), v(0.0, 1.0))), "self-intersecting")
        assertNull(PolygonClipping.triangulate(listOf(v(0.0, 0.0), v(Double.NaN, 0.0), v(1.0, 1.0))))
        assertEquals(emptyList(), PolygonClipping.triangulate(listOf(v(0.0, 0.0), v(1.0, 0.0))))
        assertEquals(emptyList(), PolygonClipping.triangulate(listOf(v(0.0, 0.0), v(1.0, 0.0), v(2.0, 0.0))))
    }

    @Test fun clipConvexSquares() {
        val a = rect(0.0, 0.0, 2.0, 2.0)
        val b = rect(1.0, 1.0, 3.0, 3.0)
        assertEquals(1.0, Polygons.area(PolygonClipping.clipConvex(a, b)), 1e-12)
        assertTrue(PolygonClipping.clipConvex(a, rect(5.0, 5.0, 6.0, 6.0)).isEmpty())
        assertEquals(4.0, Polygons.area(PolygonClipping.clipConvex(a, rect(-1.0, -1.0, 3.0, 3.0))), 1e-12)
        // A diamond inside a square.
        val diamond = listOf(v(1.0, 0.0), v(2.0, 1.0), v(1.0, 2.0), v(0.0, 1.0))
        assertEquals(2.0, Polygons.area(PolygonClipping.clipConvex(diamond, a)), 1e-12)
    }

    @Test fun intersectionAreaOfNonConvexRooms() {
        val l = Shapes.lShape
        // Square over the L's inside corner: 2 m² in the bottom arm + 1 m² in the left arm.
        assertEquals(3.0, PolygonClipping.intersectionArea(l, rect(2.0, 2.0, 4.0, 4.0)), 1e-9)
        // Square in the notch: none, although it is inside the L's bounding box.
        assertEquals(0.0, PolygonClipping.intersectionArea(l, rect(3.5, 3.5, 5.5, 4.5)), 1e-12)
        // U with a block in its gap that dips 0.5 m into the base.
        assertEquals(1.0, PolygonClipping.intersectionArea(u, rect(2.0, 1.5, 4.0, 3.5)), 1e-9)
        // Symmetric, orientation-independent, and the contained room's full area.
        val other = RoomOps.transform(Ops.drawn("x", u), RigidTransform2.rotationAround(v(3.0, 2.0), 0.3)).corners
        val ab = PolygonClipping.intersectionArea(l, other)
        assertEquals(ab, PolygonClipping.intersectionArea(other, l), 1e-9)
        assertEquals(ab, PolygonClipping.intersectionArea(l.reversed(), other.reversed()), 1e-9)
        assertEquals(1.0, PolygonClipping.intersectionArea(l, rect(0.5, 0.5, 1.5, 1.5)), 1e-12)
        assertEquals(Polygons.area(l), PolygonClipping.intersectionArea(l, l), 1e-9)
        // Touching along an edge or at a corner is not an overlap.
        assertEquals(0.0, PolygonClipping.intersectionArea(rect(0.0, 0.0, 1.0, 1.0), rect(1.0, 0.0, 2.0, 1.0)), 1e-12)
        assertEquals(0.0, PolygonClipping.intersectionArea(rect(0.0, 0.0, 1.0, 1.0), rect(1.0, 1.0, 2.0, 2.0)), 1e-12)
    }

    @Test fun intersectionMatchesSamplingOnRandomRotations() {
        val rnd = java.util.Random(7)
        repeat(20) {
            val angle = rnd.nextDouble() * 2 * PI
            val shift = v(rnd.nextDouble() * 4 - 2, rnd.nextDouble() * 4 - 2)
            val moved = u.map { RigidTransform2(angle, shift).apply(it) }
            val exact = PolygonClipping.intersectionArea(Shapes.lShape, moved)
            val sampled = PolygonClipping.sampledIntersectionArea(Shapes.lShape, moved)
            assertTrue(abs(exact - sampled) <= 0.02 * Polygons.area(Shapes.lShape), "exact $exact vs sampled $sampled")
        }
    }

    @Test fun degenerateInputFallsBackOrGivesZero() {
        // Self-intersecting: falls back to sampling (even-odd rule) instead of failing.
        val bowtie = listOf(v(0.0, 0.0), v(2.0, 2.0), v(2.0, 0.0), v(0.0, 2.0))
        val area = PolygonClipping.intersectionArea(bowtie, rect(0.0, 0.0, 2.0, 2.0))
        assertEquals(2.0, area, 0.05)
        assertEquals(0.0, PolygonClipping.intersectionArea(emptyList(), rect(0.0, 0.0, 1.0, 1.0)))
        assertEquals(0.0, PolygonClipping.intersectionArea(listOf(v(0.0, 0.0), v(1.0, 1.0)), rect(0.0, 0.0, 1.0, 1.0)))
        val withNan = listOf(v(0.0, 0.0), v(Double.NaN, 0.0), v(1.0, 1.0), v(0.0, 1.0))
        assertTrue(PolygonClipping.intersectionArea(withNan, rect(0.0, 0.0, 1.0, 1.0)).isFinite())
    }
}
