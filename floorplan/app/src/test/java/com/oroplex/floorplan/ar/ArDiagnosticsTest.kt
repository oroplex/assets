package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** The device-diagnostics helpers: what gets logged, how often, and that it can be read back reliably. */
class ArDiagnosticsTest {
    private val ms = 1_000_000L

    private fun assertAscii(text: String) = assertTrue(text, text.all { it.code in 32..126 })

    // ---------------------------------------------------------------------------------------------
    // Rate limiting
    // ---------------------------------------------------------------------------------------------

    @Test
    fun changeLog_logsTheFirstValueAndThenOnlyChanges() {
        val log = ChangeLog(minIntervalNs = 500 * ms)
        assertEquals("tracking: PAUSED", log.offer(0, "PAUSED") { "tracking: PAUSED" })
        // The same value on the following frames: nothing.
        for (t in 1..60) assertNull(log.offer(t * 33 * ms, "PAUSED") { error("message must not be built") })
        assertEquals("tracking: TRACKING", log.offer(2_100 * ms, "TRACKING") { "tracking: TRACKING" })
    }

    @Test
    fun changeLog_limitsTheRateAndReportsWhatItSkipped() {
        val log = ChangeLog(minIntervalNs = 500 * ms)
        assertNotNull(log.offer(0, "A") { "A" })
        // Flapping every frame for 300 ms: nothing is logged ...
        assertNull(log.offer(33 * ms, "B") { "B" })
        assertNull(log.offer(66 * ms, "A") { "A" })
        assertNull(log.offer(100 * ms, "B") { "B" })
        assertNull(log.offer(300 * ms, "B") { "B" })
        // ... until the interval has passed; the pending value is then logged with the skipped changes.
        assertEquals("B (+2 changes in between)", log.offer(520 * ms, "B") { "B" })
        assertNull(log.offer(560 * ms, "B") { "B" })
        // A value that flapped away and came back to the logged one is not logged again.
        assertNull(log.offer(600 * ms, "C") { "C" })
        assertNull(log.offer(640 * ms, "B") { "B" })
        assertNull(log.offer(2_000 * ms, "B") { "B" })
    }

    @Test
    fun changeLog_treatsNullAsAValueAndSurvivesAClockJump() {
        val log = ChangeLog(minIntervalNs = 500 * ms)
        assertEquals("none", log.offer(1_000 * ms, null) { "none" })
        assertNull(log.offer(1_033 * ms, null) { "none" })
        // The clock restarted (new process timeline): a change is logged rather than suppressed forever.
        assertEquals("floor", log.offer(10 * ms, 1.4) { "floor" })
    }

    @Test
    fun changeLog_canSlowDownForACall() {
        val log = ChangeLog(minIntervalNs = 1_000 * ms)
        assertNotNull(log.offer(0, 1) { "1" })
        // With the default interval this change would be logged after one second ...
        assertNull(log.offer(1_500 * ms, 2, intervalNs = 5_000 * ms) { "2" })
        assertNull(log.offer(4_900 * ms, 2, intervalNs = 5_000 * ms) { "2" })
        // ... with the longer one only after five.
        assertEquals("2", log.offer(5_000 * ms, 2, intervalNs = 5_000 * ms) { "2" })
        assertEquals("3", log.offer(6_000 * ms, 3) { "3" })
    }

    @Test
    fun framePerf_summarizesOncePerPeriod() {
        val perf = FramePerf(periodNs = 1_000 * ms)
        var line: String? = null
        var t = 0L
        var frames = 0
        // A 30 fps camera on a 60 Hz display: every other drawn frame has a new image.
        while (line == null && frames < 200) {
            val newImage = frames % 2 == 0
            // One slow image: 30 ms of processing on top of a 6 ms update.
            val processing = if (frames == 10) 30 * ms else 2 * ms
            line = perf.onFrame(t, newImage, updateNs = 6 * ms, processNs = processing)
            t += 16_666_667L
            frames++
        }
        assertNotNull(line)
        assertEquals("one line per second of frames", 61, frames)
        val text = line!!
        assertTrue(text, text.startsWith("perf 1s: camera 31.0 fps, drawn 61 fps"))
        assertTrue(text, text.contains("update avg 6.0 ms max 6.0"))
        assertTrue(text, text.contains("processing avg 2.9 ms max 30.0"))
        assertTrue(text, text.contains("slow frames (>33.0 ms) 1 of 31"))
        assertAscii(text)
        // The next period starts fresh.
        assertNull(perf.onFrame(t, true, 6 * ms, 2 * ms))
    }

    @Test
    fun framePerf_resetDropsAPauseFromTheStatistics() {
        val perf = FramePerf(periodNs = 1_000 * ms)
        assertNull(perf.onFrame(0, true, 5 * ms, 1 * ms))
        perf.reset()
        // A minute later (the app was in the background): no bogus one-minute period is reported.
        assertNull(perf.onFrame(60_000 * ms, true, 5 * ms, 1 * ms))
        assertNotNull(perf.onFrame(61_000 * ms, true, 5 * ms, 1 * ms))
    }

    @Test
    fun arLog_neverThrowsEvenWithoutAnAndroidRuntime() {
        // On the plain JVM android.util.Log is a stub that throws; logging must swallow that.
        ArLog.i("diagnostics self-test")
        ArLog.w("diagnostics self-test", IllegalStateException("expected"))
        ArLog.e("diagnostics self-test")
        ThrottledLog(every = 2).apply { repeat(5) { warn("repeating", IllegalStateException("expected")) } }
    }

    @Test
    fun numbersAreFormattedIndependentlyOfTheLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // decimal comma
            assertEquals("2.413", 2.4130.fmt(3))
            assertEquals("-0.37", (-0.371).fmt(2))
            assertEquals("8.2 mm", 0.0082.fmtMm())
            assertEquals("7.3", 7_300_000L.nsToMs())
        } finally {
            java.util.Locale.setDefault(previous)
        }
        assertEquals("n/a", Double.NaN.fmt(2))
        assertEquals("n/a", Double.POSITIVE_INFINITY.fmtMm())
        assertEquals("n/a", (null as Double?).fmtOrNa(2))
        assertEquals("1.50", (1.5 as Double?).fmtOrNa(2))
    }

    // ---------------------------------------------------------------------------------------------
    // The measurement line
    // ---------------------------------------------------------------------------------------------

    /** A room-left oriented wall along +x at plan y = [y] (room below it), as WallEstimator would return. */
    private fun wallAt(y: Double) = Line2.of(Vec2(1.0, y), Vec2(-1.0, 0.0))

    @Test
    fun wallLine_reportsAngleOffsetAndViewerDistance() {
        val viewer = Vec2(0.5, 0.6)
        val line = MeasurementReport.wallLine(wallAt(3.0), viewer)
        assertEquals("angle 180.00 deg offset 3.0000 m viewer 2.4000 m", line)
        // Angles are reported in 0..360, never negative.
        val down = Line2.of(Vec2(2.0, 0.0), Vec2(0.0, -1.0))
        assertTrue(MeasurementReport.wallLine(down, viewer).startsWith("angle 270.00 deg"))
        assertAscii(line)
    }

    @Test
    fun wallLine_offsetsOfOppositeWallsAddUpToTheRoomWidth() {
        // A 4.20 m wide room whose walls are captured from two different spots, far from the world origin.
        val north = Line2.of(Vec2(10.0, 7.0), Vec2(-1.0, 0.0)) // room below it
        val south = Line2.of(Vec2(12.0, 2.8), Vec2(1.0, 0.0)) // room above it
        assertTrue(north.signedDistance(Vec2(11.0, 5.0)) > 0)
        assertTrue(south.signedDistance(Vec2(11.0, 5.0)) > 0)
        val sum = north.signedDistance(Vec2.ZERO) + south.signedDistance(Vec2.ZERO)
        assertEquals(4.20, sum, 1e-9)
    }

    @Test
    fun planeVersusFit_positiveMeansThePlaneLiesBehindTheFit() {
        val fit = wallAt(3.0)
        val at = Vec2(0.4, 3.0)
        assertEquals("(plane - fit: +8.0 mm, +0.00 deg)", MeasurementReport.planeVersusFit(fit, wallAt(3.008), at))
        assertEquals("(plane - fit: -12.5 mm, +0.00 deg)", MeasurementReport.planeVersusFit(fit, wallAt(2.9875), at))
        // A plane turned by half a degree about the comparison point: no offset there, only the angle.
        val turned = fit.rotatedAround(at, Math.toRadians(0.5))
        assertEquals("(plane - fit: +0.0 mm, +0.50 deg)", MeasurementReport.planeVersusFit(fit, turned, at))
        val turnedBack = fit.rotatedAround(at, Math.toRadians(-0.5))
        assertEquals("(plane - fit: +0.0 mm, -0.50 deg)", MeasurementReport.planeVersusFit(fit, turnedBack, at))
    }

    @Test
    fun robustSigma_estimatesTheNoiseOnTheWallAndIgnoresEverythingElse() {
        val line = wallAt(3.0)
        val band = 0.2..2.35
        val points = ArrayList<Vec3>()
        // 400 wall samples with about 1 cm of pseudo-random depth noise (uniform, sigma = range / sqrt(12)).
        for (i in 0 until 400) {
            val noise = 0.0346 * (fraction(i) - 0.5)
            points += Vec3(-1.0 + 2.0 * i / 399.0, 0.5 + 1.5 * fraction(i + 7), -(3.0 + noise))
        }
        val clean = MeasurementReport.robustSigma(points, band, line, window = 0.10)
        assertEquals(0.0128, clean, 0.002) // 1.4826 x MAD of a uniform distribution of width 3.46 cm
        // Furniture half a meter in front, the floor, and garbage do not change it.
        for (i in 0 until 150) points += Vec3(0.0, 1.0, -2.5)
        for (i in 0 until 150) points += Vec3(0.0, 0.05, -3.0)
        points += Vec3(Double.NaN, 1.0, -3.0)
        assertEquals(clean, MeasurementReport.robustSigma(points, band, line, window = 0.10), 1e-12)
        // Too few points near the line: no number rather than a meaningless one.
        assertTrue(MeasurementReport.robustSigma(points.take(5), band, line, 0.10).isNaN())
        assertTrue(MeasurementReport.robustSigma(emptyList(), band, line, 0.10).isNaN())
    }

    /** Deterministic pseudo-random fraction in [0, 1). */
    private fun fraction(i: Int): Double {
        val v = sin(i * 12.9898 + 4.1414) * 43758.5453
        return v - kotlin.math.floor(v)
    }

    @Test
    fun line_describesEveryKindOfResult() {
        val wall = MeasureRequest.Wall(floorY = -1.4, ceilingY = 1.3)
        val observation = WallObservation(
            line = wallAt(3.0),
            extentStart = Vec2(1.0, 3.0),
            extentEnd = Vec2(-0.8, 3.0),
            source = WallSource.DEPTH_FIT,
            rmsResidual = 0.006,
            pointCount = 900,
            confidence = 0.8,
            viewer = Vec2(0.0, 0.5),
        )
        val ok = MeasurementReport.line(wall, MeasureResult.WallMeasured(WallFit(observation, null, 900, 1200)), 612 * ms, 15, "samples 1200")
        assertEquals("measure wall ok 612 ms frames=15 | samples 1200", ok)

        val failedFit = MeasureResult.WallMeasured(WallFit(null, WallFitFailure.TOO_NOISY, 300, 1200))
        assertEquals("measure wall FAILED (TOO_NOISY) 700 ms frames=15", MeasurementReport.line(wall, failedFit, 700 * ms, 15, ""))

        val timeout = MeasureResult.Failed("Wall measurement timed out. Hold the phone steady while it samples.")
        assertTrue(MeasurementReport.line(wall, timeout, 4_001 * ms, 9, "samples 40").startsWith("measure wall FAILED (Wall measurement timed out."))

        assertEquals(
            "measure floor-lock ok y=-1.412 35 ms frames=1 | path: plane under crosshair",
            MeasurementReport.line(MeasureRequest.LockFloor, MeasureResult.FloorLocked(-1.4121), 35 * ms, 1, "path: plane under crosshair"),
        )
        assertEquals(
            "measure floor-point ok at (1.250, -1.412, -2.000) samples=6 spread=4.2 mm 210 ms frames=6",
            MeasurementReport.line(MeasureRequest.FloorPoint(-1.412), MeasureResult.Point(Vec3(1.25, -1.412, -2.0), 6, 0.0042), 210 * ms, 6, ""),
        )
        assertEquals(
            "measure ceiling ok height=2.438 m samples=13 spread=3.0 mm 520 ms frames=15",
            MeasurementReport.line(MeasureRequest.CeilingHeight(-1.412), MeasureResult.Height(2.4381, 13, 0.003), 520 * ms, 15, ""),
        )
        val dropped = MeasurementReport.line(MeasureRequest.SurfacePoint(), null, 90 * ms, 2, "")
        assertEquals("measure surface-point DROPPED (cancelled or session paused) 90 ms frames=2", dropped)
        assertFalse(dropped.contains("null"))
        assertAscii(ok)
        assertAscii(dropped)
    }
}
