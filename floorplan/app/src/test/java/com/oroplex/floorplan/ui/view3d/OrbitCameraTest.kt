package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.core.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class OrbitCameraTest {
    private val eps = 1e-9
    private val box = Box3(0.0, 0.0, -6.0, 8.0, 2.5, 0.0) // an 8 × 6 m floor, 2.5 m walls
    private val limits = CameraLimits.around(box)
    private val lens = Lens()

    private fun assertVec(expected: Vec3, actual: Vec3, tol: Double = eps) {
        assertEquals(expected.x, actual.x, tol)
        assertEquals(expected.y, actual.y, tol)
        assertEquals(expected.z, actual.z, tol)
    }

    @Test fun eyeOrbitsTheTarget() {
        val t = Vec3(1.0, 2.0, 3.0)
        // yaw 0, level: the eye is "south" of the target (world +z = plan -y).
        assertVec(Vec3(1.0, 2.0, 13.0), OrbitCamera(t, 0.0, 0.0, 10.0).eye())
        // Looking straight down.
        assertVec(Vec3(1.0, 12.0, 3.0), OrbitCamera(t, 0.0, PI / 2, 10.0).eye(), 1e-9)
        // Positive yaw swings the eye toward +x (counter-clockwise seen from above).
        assertVec(Vec3(11.0, 2.0, 3.0), OrbitCamera(t, PI / 2, 0.0, 10.0).eye())
    }

    @Test fun basisIsOrthonormalAndNotMirrored() {
        for (yaw in listOf(-2.0, 0.0, 0.7, 3.0)) for (pitch in listOf(0.3, 0.9, 1.4)) {
            val b = OrbitCamera(Vec3.ZERO, yaw, pitch, 5.0).basis()
            assertEquals(1.0, b.right.length, eps)
            assertEquals(1.0, b.up.length, eps)
            assertEquals(1.0, b.forward.length, eps)
            assertEquals(0.0, b.right dot b.up, eps)
            assertEquals(0.0, b.right dot b.forward, eps)
            assertEquals(0.0, b.up dot b.forward, eps)
            assertVec(-b.forward, b.right cross b.up) // right-handed: the image is not mirrored
            assertEquals(0.0, b.right.y, eps) // the horizon stays level
            assertTrue(b.up.y > 0)
        }
    }

    @Test fun topDownViewMatchesThePlanOrientation() {
        val b = OrbitCamera(Vec3.ZERO, 0.0, PI / 2 - 1e-6, 5.0).basis()
        val planRight = Vec3.fromPlan(com.oroplex.floorplan.core.geometry.Vec2(1.0, 0.0), 0.0)
        val planUp = Vec3.fromPlan(com.oroplex.floorplan.core.geometry.Vec2(0.0, 1.0), 0.0)
        assertTrue(b.toView(planRight).x > 0.99)
        assertTrue(b.toView(planUp).y > 0.99)
    }

    @Test fun targetIsStraightAhead() {
        val c = OrbitCamera(Vec3(2.0, 1.0, -3.0), 0.4, 0.8, 7.0)
        assertVec(Vec3(0.0, 0.0, 7.0), c.basis().toView(c.target))
    }

    @Test fun coerceInClampsPitchDistanceAndTarget() {
        val wild = OrbitCamera(Vec3(100.0, 1.3, -100.0), 7.0, 2.0, 1e6)
        val c = wild.coerceIn(limits)
        assertEquals(Math.toRadians(85.0), c.pitch, eps)
        assertEquals(limits.maxDistance, c.distance, eps)
        assertEquals(limits.targetBox.maxX, c.target.x, eps)
        assertEquals(limits.targetBox.minZ, c.target.z, eps)
        assertEquals(1.3, c.target.y, eps) // height is not clamped
        assertEquals(wrapAngle(7.0), c.yaw, eps)
        val low = OrbitCamera(box.center, 0.0, -1.0, 0.0).coerceIn(limits)
        assertEquals(Math.toRadians(15.0), low.pitch, eps)
        assertEquals(limits.minDistance, low.distance, eps)
    }

    @Test fun oneFingerDragOrbits() {
        val c = OrbitCamera(box.center, 0.0, Math.toRadians(50.0), 15.0)
        val right = OrbitGestures.orbit(c, 100.0, 0.0, 2.0, limits)
        assertEquals(-Math.toRadians(50 * OrbitGestures.YAW_DEG_PER_DP), right.yaw, eps)
        assertEquals(c.pitch, right.pitch, eps)
        val down = OrbitGestures.orbit(c, 0.0, 40.0, 2.0, limits)
        assertEquals(c.pitch + Math.toRadians(20 * OrbitGestures.PITCH_DEG_PER_DP), down.pitch, eps)
        // Pitch stays within 15..85 degrees however far the finger goes.
        assertEquals(Math.toRadians(85.0), OrbitGestures.orbit(c, 0.0, 1e5, 2.0, limits).pitch, eps)
        assertEquals(Math.toRadians(15.0), OrbitGestures.orbit(c, 0.0, -1e5, 2.0, limits).pitch, eps)
        // Garbage input changes nothing.
        assertSame(c, OrbitGestures.orbit(c, Double.NaN, 0.0, 2.0, limits))
        assertSame(c, OrbitGestures.orbit(c, 1.0, 1.0, 0.0, limits))
    }

    @Test fun pinchZooms() {
        val c = OrbitCamera(box.center, 0.0, 0.8, 20.0)
        assertEquals(10.0, OrbitGestures.zoom(c, 2.0, limits).distance, eps)
        assertEquals(40.0, OrbitGestures.zoom(c, 0.5, limits).distance, eps)
        assertEquals(limits.minDistance, OrbitGestures.zoom(c, 1e9, limits).distance, eps)
        assertEquals(limits.maxDistance, OrbitGestures.zoom(c, 1e-9, limits).distance, eps)
        assertSame(c, OrbitGestures.zoom(c, 0.0, limits))
        assertSame(c, OrbitGestures.zoom(c, Double.NaN, limits))
    }

    @Test fun twoFingerDragPansOverTheFloor() {
        val c = OrbitCamera(box.center, 0.0, PI / 2 - 0.01, 10.0)
        val panned = OrbitGestures.pan(c, 100.0, 0.0, 1000.0, lens, limits)
        // Fingers right -> the model follows right -> the target moves left (-x at yaw 0).
        assertTrue(panned.target.x < c.target.x)
        assertEquals(c.target.y, panned.target.y, eps)
        assertEquals(c.target.z, panned.target.z, 1e-9)
        // Roughly finger-following at the target's depth: 100 px of a 1000 px viewport.
        val expected = 100 * 2 * 10.0 * kotlin.math.tan(lens.fovY / 2) / 1000
        assertEquals(expected, c.target.x - panned.target.x, 1e-9)
        // Fingers down -> the floor comes toward the viewer -> the target moves away (-z at yaw 0).
        assertTrue(OrbitGestures.pan(c, 0.0, 50.0, 1000.0, lens, limits).target.z < c.target.z)
        // Panning cannot run away from the model.
        val far = OrbitGestures.pan(c, -1e7, 0.0, 1000.0, lens, limits)
        assertEquals(limits.targetBox.maxX, far.target.x, eps)
        assertSame(c, OrbitGestures.pan(c, 1.0, 1.0, 0.0, lens, limits))
    }

    @Test fun lerpTakesTheShortWayRound() {
        val a = OrbitCamera(Vec3.ZERO, Math.toRadians(170.0), 0.5, 4.0)
        val b = OrbitCamera(Vec3(2.0, 0.0, 0.0), Math.toRadians(-170.0), 1.0, 16.0)
        val mid = OrbitCamera.lerp(a, b, 0.5)
        assertEquals(PI, abs(mid.yaw), 1e-9) // through 180, not through 0
        assertEquals(0.75, mid.pitch, eps)
        assertEquals(8.0, mid.distance, 1e-9) // geometric
        assertVec(Vec3(1.0, 0.0, 0.0), mid.target)
        assertEquals(a, OrbitCamera.lerp(a, b, 0.0).copy(yaw = a.yaw))
        val end = OrbitCamera.lerp(a, b, 1.0)
        assertEquals(b.distance, end.distance, eps)
        assertEquals(b.yaw, end.yaw, 1e-12)
    }

    @Test fun framingFitsEveryCornerTightly() {
        val width = 1080.0
        val height = 1900.0
        val margin = 60.0
        val cam = OrbitCamera.framing(box, width, height, lens, margin)
        val basis = cam.basis()
        val f = lens.focalPx(height)
        var touches = false
        for (p in box.corners()) {
            val v = basis.toView(p)
            assertTrue(v.z > lens.near)
            val sx = width / 2 + f * v.x / v.z
            val sy = height / 2 - f * v.y / v.z
            assertTrue("x $sx", sx >= margin - 1e-6 && sx <= width - margin + 1e-6)
            assertTrue("y $sy", sy >= margin - 1e-6 && sy <= height - margin + 1e-6)
            if (abs(sx - margin) < 1e-3 || abs(sx - (width - margin)) < 1e-3 || abs(sy - margin) < 1e-3 || abs(sy - (height - margin)) < 1e-3) touches = true
        }
        assertTrue("framing should be tight", touches)
        assertEquals(OrbitCamera.DEFAULT_PITCH, cam.pitch, eps)
        assertVec(box.center, cam.target)
    }

    @Test fun longModelOnAPortraitScreenIsTurnedToFillIt() {
        val long = Box3(0.0, 0.0, -4.0, 16.0, 2.5, 0.0) // 16 m east-west, 4 m deep
        val portrait = OrbitCamera.framing(long, 1080.0, 2300.0, lens, 60.0)
        assertEquals(OrbitCamera.ALTERNATE_YAW, portrait.yaw, eps)
        val unturned = fitDistance(long.corners(), long.center, OrbitCamera.DEFAULT_YAW, OrbitCamera.DEFAULT_PITCH, 1080.0, 2300.0, lens, 60.0)
        assertTrue(portrait.distance < unturned * (1 - OrbitCamera.ALTERNATE_GAIN))
        // On a landscape screen the plan keeps its usual orientation.
        assertEquals(OrbitCamera.DEFAULT_YAW, OrbitCamera.framing(long, 2300.0, 1080.0, lens, 60.0).yaw, eps)
        // A square-ish model is never turned.
        val square = Box3(0.0, 0.0, -8.0, 8.0, 2.5, 0.0)
        assertEquals(OrbitCamera.DEFAULT_YAW, OrbitCamera.framing(square, 1080.0, 2300.0, lens, 60.0).yaw, eps)
    }

    @Test fun framingAnEmptyBoxIsStillUsable() {
        val cam = OrbitCamera.framing(Box3.EMPTY, 100.0, 100.0, lens, 10.0)
        assertTrue(cam.distance > 0 && cam.eye().isFinite())
        assertEquals(10.0, fitDistance(emptyList(), Vec3.ZERO, 0.0, 1.0, 100.0, 100.0, lens, 10.0), eps)
    }

    @Test fun limitsScaleWithTheModel() {
        val small = CameraLimits.around(Box3(0.0, 0.0, 0.0, 0.5, 0.5, 0.5))
        assertEquals(0.5, small.minDistance, eps)
        assertEquals(8.0, small.maxDistance, eps)
        val big = CameraLimits.around(Box3(0.0, 0.0, 0.0, 40.0, 3.0, 30.0))
        assertTrue(big.maxDistance > 200)
        assertTrue(big.targetBox.maxX > 40.0 && big.targetBox.minZ < 0.0)
        val empty = CameraLimits.around(Box3.EMPTY)
        assertTrue(empty.minDistance > 0 && empty.maxDistance > empty.minDistance)
    }

    @Test fun boxBasics() {
        assertTrue(Box3.EMPTY.isEmpty)
        val b = Box3.of(listOf(Vec3(1.0, 2.0, 3.0), Vec3(-1.0, 0.0, 5.0), Vec3(Double.NaN, 0.0, 0.0)))
        assertEquals(Box3(-1.0, 0.0, 3.0, 1.0, 2.0, 5.0), b)
        assertEquals(8, b.corners().size)
        assertEquals(Vec3(2.0, 2.0, 2.0).length / 2, b.radius, eps)
    }

    @Test fun wrapAngleRange() {
        assertEquals(PI, wrapAngle(-PI), eps)
        assertEquals(PI, wrapAngle(PI), eps)
        assertEquals(-PI / 2, wrapAngle(3 * PI / 2), eps)
        assertEquals(0.0, wrapAngle(Double.NaN), eps)
    }
}
