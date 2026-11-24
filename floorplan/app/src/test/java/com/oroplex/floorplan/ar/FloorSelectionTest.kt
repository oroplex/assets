package com.oroplex.floorplan.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer

class FloorSelectionTest {

    @Test
    fun polygonArea_unitSquare() {
        val square = FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertEquals(1.0, FloorSelection.polygonArea(square), 1e-9)
    }

    @Test
    fun polygonArea_isOrientationIndependent() {
        val ccw = floatArrayOf(-1f, -0.5f, 1f, -0.5f, 1f, 0.5f, -1f, 0.5f)
        val cw = floatArrayOf(-1f, 0.5f, 1f, 0.5f, 1f, -0.5f, -1f, -0.5f)
        assertEquals(2.0, FloorSelection.polygonArea(FloatBuffer.wrap(ccw)), 1e-9)
        assertEquals(2.0, FloorSelection.polygonArea(FloatBuffer.wrap(cw)), 1e-9)
    }

    @Test
    fun polygonArea_triangle() {
        val tri = FloatBuffer.wrap(floatArrayOf(0f, 0f, 4f, 0f, 0f, 3f))
        assertEquals(6.0, FloorSelection.polygonArea(tri), 1e-9)
    }

    @Test
    fun polygonArea_degenerateInputsAreZero() {
        assertEquals(0.0, FloorSelection.polygonArea(FloatBuffer.wrap(FloatArray(0))), 0.0)
        assertEquals(0.0, FloorSelection.polygonArea(FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 1f))), 0.0)
        // Odd trailing float is ignored (2.5 vertices -> 2 vertices).
        assertEquals(0.0, FloorSelection.polygonArea(FloatBuffer.wrap(floatArrayOf(0f, 0f, 1f, 1f, 3f))), 0.0)
    }

    @Test
    fun polygonArea_readsFromPositionWithoutConsuming() {
        val data = floatArrayOf(99f, 99f, 0f, 0f, 2f, 0f, 2f, 2f, 0f, 2f)
        val buffer = FloatBuffer.wrap(data)
        buffer.position(2)
        assertEquals(4.0, FloorSelection.polygonArea(buffer), 1e-9)
        assertEquals(2, buffer.position())
    }

    @Test
    fun isBelowCamera_requiresHalfMeterDrop() {
        assertTrue(FloorSelection.isBelowCamera(planeY = -1.4, cameraY = 0.0))
        assertTrue(FloorSelection.isBelowCamera(planeY = -0.5, cameraY = 0.0))
        assertFalse(FloorSelection.isBelowCamera(planeY = -0.49, cameraY = 0.0))
        assertFalse(FloorSelection.isBelowCamera(planeY = 0.2, cameraY = 0.0))
        assertFalse(FloorSelection.isBelowCamera(planeY = Double.NaN, cameraY = 0.0))
        assertFalse(FloorSelection.isBelowCamera(planeY = -1.0, cameraY = Double.POSITIVE_INFINITY))
    }

    @Test
    fun select_picksLargestPlaneWellBelowCamera() {
        val cameraY = 0.0
        val floorPatch = FloorCandidate(y = -1.42, area = 1.2)
        val bigFloor = FloorCandidate(y = -1.40, area = 6.5)
        val table = FloorCandidate(y = -0.35, area = 9.0) // larger, but too close to the camera
        val best = FloorSelection.select(listOf(floorPatch, table, bigFloor), cameraY)
        assertEquals(bigFloor, best)
    }

    @Test
    fun select_ignoresNonFiniteAreas() {
        val bad = FloorCandidate(y = -1.4, area = Double.NaN)
        val good = FloorCandidate(y = -1.4, area = 0.3)
        assertEquals(good, FloorSelection.select(listOf(bad, good), cameraY = 0.0))
    }

    @Test
    fun select_returnsNullWithoutCandidates() {
        assertNull(FloorSelection.select(emptyList(), cameraY = 0.0))
        assertNull(FloorSelection.select(listOf(FloorCandidate(y = -0.2, area = 3.0)), cameraY = 0.0))
    }
}
