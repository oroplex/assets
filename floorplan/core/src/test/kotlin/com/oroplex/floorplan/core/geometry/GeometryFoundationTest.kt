package com.oroplex.floorplan.core.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryFoundationTest {
    private val square = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0))
    private val lShape = listOf(
        Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 3.0), Vec2(3.0, 3.0), Vec2(3.0, 5.0), Vec2(0.0, 5.0),
    )

    @Test fun planMappingMatchesArWorld() {
        val w = Vec3(1.0, 1.5, -2.0)
        assertEquals(Vec2(1.0, 2.0), w.toPlan())
        assertEquals(Vec3(1.0, 0.0, -2.0), Vec3.fromPlan(Vec2(1.0, 2.0), 0.0))
    }

    @Test fun lineIntersectAndDistances() {
        val a = Line2.through(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        val b = Line2.through(Vec2(2.0, -1.0), Vec2(2.0, 5.0))
        val p = assertNotNull(a.intersect(b))
        assertTrue(p.approxEquals(Vec2(2.0, 0.0), 1e-12))
        assertNull(a.intersect(a.offset(1.0)))
        assertEquals(1.0, a.signedDistance(Vec2(5.0, 1.0)), 1e-12) // left side positive
        assertEquals(-1.0, a.signedDistance(Vec2(5.0, -1.0)), 1e-12)
        val r = a.rotatedAround(Vec2(1.0, 0.0), PI / 2)
        assertEquals(0.0, r.distance(Vec2(1.0, 7.0)), 1e-12)
    }

    @Test fun areaCentroidOrientation() {
        assertEquals(12.0, Polygons.signedArea(square), 1e-12)
        assertEquals(-12.0, Polygons.signedArea(square.reversed()), 1e-12)
        assertTrue(Polygons.centroid(square).approxEquals(Vec2(2.0, 1.5), 1e-12))
        assertEquals(14.0, Polygons.perimeter(square), 1e-12)
        assertEquals(24.0, Polygons.area(lShape), 1e-12)
    }

    @Test fun simplicity() {
        assertTrue(Polygons.isSimple(square))
        assertTrue(Polygons.isSimple(lShape))
        val bowtie = listOf(Vec2(0.0, 0.0), Vec2(2.0, 2.0), Vec2(2.0, 0.0), Vec2(0.0, 2.0))
        assertFalse(Polygons.isSimple(bowtie))
    }

    @Test fun offsetGrowsCcwOutward() {
        val o = Polygons.offset(square, 0.1)
        assertEquals(4, o.size)
        assertTrue(o[0].approxEquals(Vec2(-0.1, -0.1), 1e-9))
        assertTrue(o[2].approxEquals(Vec2(4.1, 3.1), 1e-9))
        // Clockwise input offsets the same way physically when given the same polygon reversed? No: CW shrinks.
        val oL = Polygons.offset(lShape, 0.1)
        assertEquals((6.2 * 5.2) - (3.0 * 2.0) + 0.0, Polygons.area(oL), 0.2)
        // The reflex corner (3,3) must move diagonally INTO the notch: (3.1, 3.1)
        assertTrue(oL.any { it.approxEquals(Vec2(3.1, 3.1), 1e-9) })
    }

    @Test fun orientedBoxOfRotatedRectangle() {
        val rot = square.map { it.rotated(0.3) + Vec2(10.0, -2.0) }
        val box = Polygons.orientedBoundingBox(rot)
        assertEquals(4.0, box.length, 1e-9)
        assertEquals(3.0, box.width, 1e-9)
        val dAng = abs(Polygons.normalizeAngle(box.angle - 0.3))
        assertTrue(dAng < 1e-9 || abs(dAng - PI) < 1e-9)
    }

    @Test fun poleOfInaccessibilityInsideLShape() {
        val p = Polygons.poleOfInaccessibility(lShape, 0.001)
        assertTrue(Polygons.contains(lShape, p))
        // Largest inscribed circle radius in this L is 1.5; centroid-ish points would be closer to the notch.
        assertTrue(Polygons.signedDistanceToOutline(lShape, p) > 1.45)
    }

    @Test fun interiorAnglesReflex() {
        val a = Polygons.interiorAngles(lShape)
        assertEquals(PI / 2, a[0], 1e-12)
        assertEquals(3 * PI / 2, a[3], 1e-12)
        val ar = Polygons.interiorAngles(lShape.reversed())
        assertEquals(3 * PI / 2, ar[lShape.size - 1 - 3], 1e-12)
        assertEquals((lShape.size - 2) * PI, a.sum(), 1e-9)
    }

    @Test fun rigidTransformComposeInverse() {
        val t = RigidTransform2(0.7, Vec2(1.0, 2.0))
        val u = RigidTransform2(-0.2, Vec2(-3.0, 0.5))
        val p = Vec2(0.3, -1.1)
        assertTrue(t.compose(u).apply(p).approxEquals(t.apply(u.apply(p)), 1e-12))
        assertTrue(t.inverse().apply(t.apply(p)).approxEquals(p, 1e-12))
        val r = RigidTransform2.rotationAround(Vec2(1.0, 1.0), PI)
        assertTrue(r.apply(Vec2(2.0, 1.0)).approxEquals(Vec2(0.0, 1.0), 1e-12))
    }

    @Test fun arPoseYawIsCcwInPlan() {
        // Quaternion for +30deg about world +Y.
        val phi = Math.toRadians(30.0)
        val t = RigidTransform2.fromArPose(0.0, kotlin.math.sin(phi / 2), 0.0, kotlin.math.cos(phi / 2), 1.0, -2.0)
        assertEquals(phi, t.angle, 1e-12)
        assertEquals(Vec2(1.0, 2.0), t.translation)
        // World +X rotated by +30deg about +Y goes to (cos, 0, -sin) -> plan (cos, sin): CCW. Consistent.
        val worldX = Vec3(kotlin.math.cos(phi), 0.0, -kotlin.math.sin(phi)).toPlan()
        assertTrue(worldX.approxEquals(Vec2(1.0, 0.0).rotated(phi), 1e-12))
    }
}
