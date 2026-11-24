package com.oroplex.floorplan.core.projection

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionTest {
    private val width = 1080
    private val height = 2340
    private val fovyDeg = 60.0
    private val near = 0.1
    private val far = 100.0
    private val aspect = width.toDouble() / height

    // ---------------------------------------------------------------- OpenGL-convention test matrices

    /** Same as android.opengl.Matrix.perspectiveM (column-major). */
    private fun perspective(fovy: Double, aspect: Double, zNear: Double, zFar: Double): FloatArray {
        val f = 1.0 / tan(fovy * PI / 360.0)
        val rangeReciprocal = 1.0 / (zNear - zFar)
        return FloatArray(16).also {
            it[0] = (f / aspect).toFloat()
            it[5] = f.toFloat()
            it[10] = ((zFar + zNear) * rangeReciprocal).toFloat()
            it[11] = -1f
            it[14] = (2.0 * zFar * zNear * rangeReciprocal).toFloat()
        }
    }

    /** Same as android.opengl.Matrix.setLookAtM (column-major view matrix). */
    private fun lookAt(eye: Vec3, center: Vec3, up: Vec3 = Vec3.UP): FloatArray {
        val f = (center - eye).normalized()
        val s = (f cross up).normalized()
        val u = s cross f
        return floatArrayOf(
            s.x.toFloat(), u.x.toFloat(), (-f.x).toFloat(), 0f,
            s.y.toFloat(), u.y.toFloat(), (-f.y).toFloat(), 0f,
            s.z.toFloat(), u.z.toFloat(), (-f.z).toFloat(), 0f,
            (-(s dot eye)).toFloat(), (-(u dot eye)).toFloat(), (f dot eye).toFloat(), 1f,
        )
    }

    private fun viewProj(eye: Vec3, center: Vec3): FloatArray =
        Projection.multiply(perspective(fovyDeg, aspect, near, far), lookAt(eye, center))

    private val identity = FloatArray(16).also { for (i in 0 until 4) it[i * 5] = 1f }

    private fun translation(x: Float, y: Float, z: Float) = identity.copyOf().also {
        it[12] = x
        it[13] = y
        it[14] = z
    }

    private fun scale(x: Float, y: Float, z: Float) = FloatArray(16).also {
        it[0] = x
        it[5] = y
        it[10] = z
        it[15] = 1f
    }

    /** Apply a column-major matrix to a point (w = 1) and divide. */
    private fun apply(m: FloatArray, p: Vec3): Vec3 {
        val x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
        val y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
        val z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        val w = m[3] * p.x + m[7] * p.y + m[11] * p.z + m[15]
        return Vec3(x / w, y / w, z / w)
    }

    private fun assertMatrixEquals(expected: FloatArray, actual: FloatArray, tol: Double) {
        assertEquals(16, actual.size)
        for (i in 0 until 16) assertEquals(expected[i].toDouble(), actual[i].toDouble(), tol, "element $i")
    }

    private fun distanceToRay(p: Vec3, ray: Ray3): Double = ((p - ray.origin) cross ray.direction).length

    // ---------------------------------------------------------------- multiply / invert

    @Test fun multiplyIsColumnMajor() {
        assertMatrixEquals(translation(1f, 2f, 3f), Projection.multiply(identity, translation(1f, 2f, 3f)), 0.0)
        // T * S applies S first: p -> 2p -> 2p + t.
        val ts = Projection.multiply(translation(1f, 2f, 3f), scale(2f, 2f, 2f))
        val p = apply(ts, Vec3(1.0, 1.0, 1.0))
        assertEquals(Vec3(3.0, 4.0, 5.0), p)
        // S * T applies T first: p -> p + t -> 2(p + t).
        val st = Projection.multiply(scale(2f, 2f, 2f), translation(1f, 2f, 3f))
        assertEquals(Vec3(4.0, 6.0, 8.0), apply(st, Vec3(1.0, 1.0, 1.0)))
        assertFailsWith<IllegalArgumentException> { Projection.multiply(FloatArray(9), identity) }
    }

    @Test fun invertTimesMatrixIsIdentity() {
        val vp = viewProj(Vec3(0.7, 1.4, 0.3), Vec3(2.0, 0.5, -3.0))
        val inv = assertNotNull(Projection.invert(vp))
        assertMatrixEquals(identity, Projection.multiply(inv, vp), 1e-5)
        assertMatrixEquals(identity, Projection.multiply(vp, inv), 1e-5)

        val rnd = Random(4)
        repeat(20) {
            val m = FloatArray(16) { (rnd.nextDouble() * 4 - 2).toFloat() }
            val mi = Projection.invert(m) ?: return@repeat // a random matrix is almost never singular
            assertMatrixEquals(identity, Projection.multiply(mi, m), 1e-3)
        }
        assertMatrixEquals(translation(-1f, -2f, -3f), assertNotNull(Projection.invert(translation(1f, 2f, 3f))), 1e-7)
        assertMatrixEquals(identity, assertNotNull(Projection.invert(identity)), 0.0)
        // Uniformly tiny but well-conditioned matrices still invert (scale-independent singularity test).
        val tiny = assertNotNull(Projection.invert(scale(1e-3f, 1e-3f, 1e-3f).also { it[15] = 1e-3f }))
        assertEquals(1000.0, tiny[0].toDouble(), 1e-2)
    }

    @Test fun invertRejectsSingularAndBrokenMatrices() {
        assertNull(Projection.invert(FloatArray(16)))
        assertNull(Projection.invert(scale(1f, 0f, 1f)))
        // Two equal columns.
        val dup = identity.copyOf().also { for (r in 0 until 4) it[4 + r] = it[r] }
        assertNull(Projection.invert(dup))
        assertNull(Projection.invert(identity.copyOf().also { it[3] = Float.NaN }))
        assertNull(Projection.invert(FloatArray(12)))
    }

    // ---------------------------------------------------------------- worldToScreen

    @Test fun worldToScreenKnownPoints() {
        val vp = viewProj(Vec3.ZERO, Vec3(0.0, 0.0, -1.0)) // camera at origin looking down -Z
        val t30 = tan(Math.toRadians(fovyDeg / 2))
        fun s(p: Vec3) = assertNotNull(Projection.worldToScreen(vp, p, width, height), "projection of $p")

        assertTrue(s(Vec3(0.0, 0.0, -5.0)).approxEquals(Vec2(width / 2.0, height / 2.0), 1e-3))
        assertEquals(0.0, s(Vec3(0.0, 5 * t30, -5.0)).y, 1e-3) // top edge
        assertEquals(height.toDouble(), s(Vec3(0.0, -2 * t30, -2.0)).y, 1e-3) // bottom edge
        assertEquals(width.toDouble(), s(Vec3(5 * t30 * aspect, 0.0, -5.0)).x, 1e-3) // right edge
        assertEquals(0.0, s(Vec3(-3 * t30 * aspect, 0.0, -3.0)).x, 1e-3) // left edge
        // Quarter offsets.
        val q = s(Vec3(0.5 * 4 * t30 * aspect, -0.5 * 4 * t30, -4.0))
        assertTrue(q.approxEquals(Vec2(0.75 * width, 0.75 * height), 1e-3), "$q")

        assertNull(Projection.worldToScreen(vp, Vec3(0.0, 0.0, 5.0), width, height)) // behind
        assertNull(Projection.worldToScreen(vp, Vec3(1.0, 0.0, 0.0), width, height)) // camera plane, w = 0
        assertNull(Projection.worldToScreen(vp, Vec3(Double.NaN, 0.0, -1.0), width, height))
        assertNull(Projection.worldToScreen(FloatArray(4), Vec3(0.0, 0.0, -1.0), width, height))
    }

    @Test fun worldToScreenWithGeneralPose() {
        val eye = Vec3(1.0, 1.5, 2.0)
        val target = Vec3(0.0, 0.0, -3.0)
        val vp = viewProj(eye, target)
        val c = assertNotNull(Projection.worldToScreen(vp, target, width, height))
        assertTrue(c.approxEquals(Vec2(width / 2.0, height / 2.0), 1e-3))
        // A point straight above the target projects above the screen center (smaller y).
        val above = assertNotNull(Projection.worldToScreen(vp, target + Vec3(0.0, 0.5, 0.0), width, height))
        assertTrue(above.y < c.y)
        assertNull(Projection.worldToScreen(vp, eye + (eye - target), width, height))
    }

    // ---------------------------------------------------------------- screenRay

    @Test fun screenRayRoundTrip() {
        val eye = Vec3(0.7, 1.4, 0.3)
        val target = Vec3(2.0, 0.5, -3.0)
        val vp = viewProj(eye, target)
        val inv = assertNotNull(Projection.invert(vp))
        val forward = (target - eye).normalized()
        val right = (forward cross Vec3.UP).normalized()
        val up = right cross forward
        val rnd = Random(77)
        repeat(200) {
            val d = 0.3 + rnd.nextDouble() * 8.0
            val p = eye + forward * d + right * ((rnd.nextDouble() - 0.5) * 0.4 * d) + up * ((rnd.nextDouble() - 0.5) * 0.9 * d)
            val px = assertNotNull(Projection.worldToScreen(vp, p, width, height))
            val ray = Projection.screenRay(inv, px.x, px.y, width, height)
            assertEquals(1.0, ray.direction.length, 1e-9)
            assertTrue(distanceToRay(p, ray) < 1e-4, "point $p is ${distanceToRay(p, ray)} m from its ray")
            assertTrue(((p - ray.origin) dot ray.direction) > 0, "point must be in front of the ray origin")
            // Origin is on the near plane, i.e. ~near meters in front of the eye.
            assertTrue(ray.origin.distanceTo(eye) in near * 0.99..near * 1.6)
        }
    }

    @Test fun screenRayThroughCenterLooksForward() {
        val eye = Vec3(-1.0, 1.2, 4.0)
        val target = Vec3(1.0, 0.2, 0.0)
        val inv = assertNotNull(Projection.invert(viewProj(eye, target)))
        val ray = Projection.screenRay(inv, width / 2.0, height / 2.0, width, height)
        val forward = (target - eye).normalized()
        assertTrue((ray.direction - forward).length < 1e-5, "direction ${ray.direction} vs $forward")
        assertTrue(distanceToRay(eye, ray) < 1e-5)
        // Top-left pixel ray points up-left of forward.
        val corner = Projection.screenRay(inv, 0.0, 0.0, width, height)
        val right = (forward cross Vec3.UP).normalized()
        assertTrue((corner.direction dot right) < 0)
        assertTrue(corner.direction.y > ray.direction.y)
    }

    @Test fun screenRayDegenerateInputsGiveZeroDirection() {
        val inv = assertNotNull(Projection.invert(viewProj(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))))
        assertEquals(Vec3.ZERO, Projection.screenRay(inv, 10.0, 10.0, 0, height).direction)
        assertEquals(Vec3.ZERO, Projection.screenRay(inv, Double.NaN, 10.0, width, height).direction)
        assertEquals(Vec3.ZERO, Projection.screenRay(FloatArray(16), 10.0, 10.0, width, height).direction)
        assertNull(Projection.intersectHorizontalPlane(Projection.screenRay(FloatArray(16), 1.0, 1.0, width, height), 0.0))
    }

    // ---------------------------------------------------------------- projectSegment

    @Test fun projectSegmentInFrontMatchesEndpoints() {
        val vp = viewProj(Vec3(0.0, 1.5, 0.0), Vec3(0.0, 0.0, -3.0))
        val a = Vec3(-1.0, 0.0, -2.0)
        val b = Vec3(1.5, 0.0, -4.0)
        val (sa, sb) = assertNotNull(Projection.projectSegment(vp, a, b, width, height))
        assertTrue(sa.approxEquals(assertNotNull(Projection.worldToScreen(vp, a, width, height)), 1e-6))
        assertTrue(sb.approxEquals(assertNotNull(Projection.worldToScreen(vp, b, width, height)), 1e-6))
    }

    @Test fun projectSegmentClipsAtTheNearPlane() {
        val vp = viewProj(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))
        val front = Vec3(0.5, -0.3, -2.0)
        val behind = Vec3(0.5, -0.3, 3.0)
        val (sa, sb) = assertNotNull(Projection.projectSegment(vp, front, behind, width, height))
        assertTrue(sa.approxEquals(assertNotNull(Projection.worldToScreen(vp, front, width, height)), 1e-6))
        // The clipped end is the segment's crossing of the near plane (z = -near), far off-screen right/below.
        val crossing = assertNotNull(Projection.worldToScreen(vp, Vec3(0.5, -0.3, -near), width, height))
        assertEquals(crossing.x, sb.x, 0.5)
        assertEquals(crossing.y, sb.y, 0.5)
        assertTrue(sb.x > width && sb.y > height, "clipped end keeps the visible direction: $sb")
        // Naive projection of the behind point would wrap to the opposite side of the screen.
        val naiveX = (apply(vp, behind).x + 1) / 2 * width
        assertTrue(naiveX < width / 2.0)

        // Order is preserved when the first endpoint is the one behind the camera.
        val (ra, rb) = assertNotNull(Projection.projectSegment(vp, behind, front, width, height))
        assertTrue(ra.approxEquals(sb, 1e-6) && rb.approxEquals(sa, 1e-6))
    }

    @Test fun projectSegmentFullyBehindOrBrokenIsNull() {
        val vp = viewProj(Vec3.ZERO, Vec3(0.0, 0.0, -1.0))
        assertNull(Projection.projectSegment(vp, Vec3(0.0, 0.0, 1.0), Vec3(2.0, 1.0, 5.0), width, height))
        // Between the eye and the near plane is also clipped away.
        assertNull(Projection.projectSegment(vp, Vec3(0.0, 0.0, -0.01), Vec3(0.1, 0.0, -0.05), width, height))
        assertNull(Projection.projectSegment(vp, Vec3(Double.NaN, 0.0, -1.0), Vec3(0.0, 0.0, -2.0), width, height))
        assertNull(Projection.projectSegment(FloatArray(3), Vec3(0.0, 0.0, -1.0), Vec3(0.0, 0.0, -2.0), width, height))
    }

    // ---------------------------------------------------------------- intersectHorizontalPlane

    @Test fun rayFloorIntersection() {
        val eye = Vec3(0.0, 1.5, 0.0)
        val target = Vec3(0.0, 0.0, -3.0)
        val vp = viewProj(eye, target)
        val inv = assertNotNull(Projection.invert(vp))
        val center = Projection.screenRay(inv, width / 2.0, height / 2.0, width, height)
        val hit = assertNotNull(Projection.intersectHorizontalPlane(center, 0.0))
        assertTrue(hit.distanceTo(target) < 1e-4, "hit $hit")

        // Round trip of several floor points through the screen.
        for (fp in listOf(Vec3(0.8, 0.0, -2.0), Vec3(-1.2, 0.0, -4.5), Vec3(0.1, 0.0, -1.2))) {
            val px = assertNotNull(Projection.worldToScreen(vp, fp, width, height))
            val h = assertNotNull(Projection.intersectHorizontalPlane(Projection.screenRay(inv, px.x, px.y, width, height), 0.0))
            assertTrue(h.distanceTo(fp) < 1e-4, "floor point $fp came back as $h")
        }
        // Plane above the ray origin while the ray points down: behind -> null.
        assertNull(Projection.intersectHorizontalPlane(center, 3.0))
        // Horizontal ray never meets a horizontal plane.
        assertNull(Projection.intersectHorizontalPlane(Ray3(eye, Vec3(1.0, 0.0, 0.0)), 0.0))
        assertNull(Projection.intersectHorizontalPlane(Ray3(eye, Vec3(1.0, 1e-12, 0.0)), 0.0))
        // Upward ray hits a ceiling.
        val up = assertNotNull(Projection.intersectHorizontalPlane(Ray3(eye, Vec3(0.0, 1.0, -1.0).normalized()), 2.5))
        assertTrue(up.distanceTo(Vec3(0.0, 2.5, -1.0)) < 1e-12)
        assertTrue(abs(up.y - 2.5) < 1e-12)
    }
}
