package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class MeasurementPolicyTest {
    private val ms = 1_000_000L

    @Test
    fun deadline_continuesWhileTrackingWithinTimeout() {
        val d = MeasurementDeadline(startNs = 0)
        for (t in 0L..4_000L step 100) assertNull("at $t ms", d.check(t * ms, tracking = true))
    }

    @Test
    fun deadline_timesOutAfterFourSeconds() {
        val d = MeasurementDeadline(startNs = 10 * ms)
        assertNull(d.check(4_010 * ms, tracking = true))
        assertEquals(MeasurementExpiry.TIMED_OUT, d.check(4_011 * ms, tracking = true))
    }

    @Test
    fun deadline_failsAfterSustainedTrackingLoss() {
        val d = MeasurementDeadline(startNs = 0)
        assertNull(d.check(100 * ms, tracking = false)) // loss starts at 100 ms
        assertNull(d.check(1_600 * ms, tracking = false)) // exactly 1.5 s: still tolerated
        assertEquals(MeasurementExpiry.TRACKING_LOST, d.check(1_601 * ms, tracking = false))
    }

    @Test
    fun deadline_briefLossesDoNotAccumulate() {
        val d = MeasurementDeadline(startNs = 0)
        assertNull(d.check(0, tracking = false))
        assertNull(d.check(1_000 * ms, tracking = false))
        assertNull(d.check(1_100 * ms, tracking = true)) // recovered: the loss timer resets
        assertNull(d.check(1_200 * ms, tracking = false))
        assertNull(d.check(2_600 * ms, tracking = false))
        assertEquals(MeasurementExpiry.TRACKING_LOST, d.check(2_800 * ms, tracking = false))
    }

    @Test
    fun deadline_timeoutWinsOverTrackingLoss() {
        val d = MeasurementDeadline(startNs = 0, timeoutNs = 1_000 * ms, maxLostNs = 5_000 * ms)
        assertNull(d.check(500 * ms, tracking = false))
        assertEquals(MeasurementExpiry.TIMED_OUT, d.check(1_001 * ms, tracking = false))
    }

    @Test
    fun grid_isCenteredSquareOfQuarterShortSide() {
        for ((w, h) in listOf(1440 to 3120, 3120 to 1440, 1000 to 1000)) {
            val out = FloatArray(WallSampleGrid.COUNT * 2)
            WallSampleGrid.positions(w, h, out)
            val side = min(w, h) * WallSampleGrid.SIDE_FRACTION
            val xs = (0 until WallSampleGrid.COUNT).map { out[2 * it] }
            val ys = (0 until WallSampleGrid.COUNT).map { out[2 * it + 1] }
            assertEquals(w / 2f - side / 2, xs.min(), 1e-3f)
            assertEquals(w / 2f + side / 2, xs.max(), 1e-3f)
            assertEquals(h / 2f - side / 2, ys.min(), 1e-3f)
            assertEquals(h / 2f + side / 2, ys.max(), 1e-3f)
            // The middle sample is exactly the view center (the crosshair).
            val mid = WallSampleGrid.COUNT / 2
            assertEquals(w / 2f, out[2 * mid], 1e-3f)
            assertEquals(h / 2f, out[2 * mid + 1], 1e-3f)
            // 9 distinct, evenly spaced columns and rows.
            val step = side / (WallSampleGrid.SIZE - 1)
            val columns = xs.distinct().sorted()
            assertEquals(WallSampleGrid.SIZE, columns.size)
            columns.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, 1e-3f) }
            assertEquals(WallSampleGrid.SIZE, ys.distinct().size)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun grid_rejectsShortOutputArray() {
        WallSampleGrid.positions(100, 100, FloatArray(10))
    }

    @Test
    fun mean_ofPoints() {
        val m = MeasurementMath.mean(listOf(Vec3(0.0, 0.0, 0.0), Vec3(2.0, -1.0, 4.0), Vec3(1.0, 1.0, -1.0)))
        assertTrue(abs(m.x - 1.0) < 1e-12 && abs(m.y - 0.0) < 1e-12 && abs(m.z - 1.0) < 1e-12)
        assertEquals(Vec3.ZERO, MeasurementMath.mean(emptyList()))
    }
}
