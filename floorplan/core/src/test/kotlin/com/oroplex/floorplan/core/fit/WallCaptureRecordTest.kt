package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.sim.CAMERA_HEIGHT
import com.oroplex.floorplan.core.sim.SIM_CEILING_Y
import com.oroplex.floorplan.core.sim.SIM_FLOOR_Y
import com.oroplex.floorplan.core.sim.ScanSimulator
import com.oroplex.floorplan.core.sim.SimScenarios
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WallCaptureRecordTest {
    private fun record(
        samples: List<WallCaptureRecord.Sample>,
        ceilingY: Double? = 1.25,
        aim: Vec3? = Vec3(0.5, 0.2, -3.0),
        plane: Segment2? = null,
        note: String = "samples 3 of 81 rays | result DEPTH_FIT",
    ) = WallCaptureRecord(
        timeMillis = 1_764_000_000_123L,
        floorY = -1.3,
        ceilingY = ceilingY,
        viewer = Vec3(0.01, 0.1, 0.02),
        aimPoint = aim,
        cameras = listOf(Vec3(0.0, 0.1, 0.0), Vec3(0.02, 0.1, 0.04)),
        samples = samples,
        plane = plane,
        note = note,
    )

    private val threeSamples = listOf(
        WallCaptureRecord.Sample(0, 'D', false, Vec3(-1.2345, 0.5, -3.0001)),
        WallCaptureRecord.Sample(0, 'P', true, Vec3(0.5, 0.2, -3.0)),
        WallCaptureRecord.Sample(1, 'F', false, Vec3(1.25, -0.75, -2.9999)),
    )

    @Test
    fun aCaptureSurvivesTheTextFormUnchanged() {
        val original = record(threeSamples, plane = Segment2(Vec2(-2.0, 3.0), Vec2(2.0, 3.0)))
        val decoded = WallCaptureRecord.decodeAll(original.encode()).single()
        // Every value above is a multiple of 0.1 mm, so nothing is lost to rounding.
        assertEquals(original, decoded)
    }

    @Test
    fun missingCeilingAimAndPlaneAreKeptAsMissing() {
        val decoded = WallCaptureRecord.decodeAll(record(threeSamples, ceilingY = null, aim = null).encode()).single()
        assertNull(decoded.ceilingY)
        assertNull(decoded.aimPoint)
        assertNull(decoded.plane)
    }

    @Test
    fun coordinatesAreRoundedToATenthOfAMillimetreWithADotWhateverTheLocale() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY) // decimal comma
        try {
            val text = record(listOf(WallCaptureRecord.Sample(0, 'D', false, Vec3(-0.00004, 12.34567, -0.05)))).encode()
            assertTrue(text.contains("s 0 D 0.0000 12.3457 -0.0500\n"), text)
            assertTrue(text.contains("floor -1.3000\n"), text)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun capturesAreFoundInsideALogAndDamagedOnesAreSkipped() {
        val first = record(threeSamples, note = "first")
        val second = record(threeSamples.take(1), note = "second")
        val cutOff = record(threeSamples, note = "cut off").encode().substringBefore("s 1 F")
        val damaged = record(threeSamples, note = "damaged").encode().replace("viewer 0.0100", "viewer 0,0100")
        val text = "11-25 03:00:01.000  App started on a test phone\n" +
            first.encode() +
            "11-25 03:00:09.000  perf 10s: camera 30.0 fps\n" +
            damaged +
            second.encode() +
            cutOff
        val decoded = WallCaptureRecord.decodeAll(text)
        assertEquals(listOf("first", "second"), decoded.map { it.note })
        assertEquals(first, decoded[0])
    }

    @Test
    fun aNoteNeverBreaksTheBlock() {
        val decoded = WallCaptureRecord.decodeAll(record(threeSamples, note = "line one\nline two  ").encode()).single()
        assertEquals("line one line two", decoded.note)
        assertEquals(3, decoded.samples.size)
    }

    @Test
    fun replayingARecordedCaptureGivesThePhonesOwnMeasurement() {
        var checked = 0
        for (room in SimScenarios.truthRooms.take(3)) {
            for (edge in 0 until room.size) {
                val aim = room.pointOn(edge, 0.5)
                val station = aim + room.inwardNormal(edge) * min(1.5, 0.5 * room.depthInFront(edge, aim))
                val cap = ScanSimulator.capture(
                    room, Vec3.fromPlan(station, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.4), Random(41L + edge),
                )
                val live = cap.fit.observation ?: continue
                val recorded = WallCaptureRecord(
                    timeMillis = 1L,
                    floorY = SIM_FLOOR_Y,
                    ceilingY = SIM_CEILING_Y,
                    viewer = cap.viewer,
                    aimPoint = cap.aimPoint,
                    cameras = emptyList(),
                    samples = cap.points.map { WallCaptureRecord.Sample(0, 'D', false, it) },
                    plane = null,
                    note = "",
                )
                val replayed = WallCaptureRecord.decodeAll(recorded.encode()).single().replay()
                val again = assertNotNull(replayed.observation, "${room.name} wall ${edge + 1}: ${replayed.failure}")
                // The text form rounds to 0.1 mm; the measured wall must not move by more than that order.
                assertTrue(live.extentStart.distanceTo(again.extentStart) < 2e-3, "${room.name} wall ${edge + 1} start")
                assertTrue(live.extentEnd.distanceTo(again.extentEnd) < 2e-3, "${room.name} wall ${edge + 1} end")
                assertTrue(abs(live.line.signedDistance(again.extentStart)) < 5e-4, "${room.name} wall ${edge + 1} offset")
                assertTrue((live.line.direction dot again.line.direction) > 0.9999999, "${room.name} wall ${edge + 1} direction")
                checked++
            }
        }
        assertTrue(checked >= 8, "only $checked captures were compared")
    }
}
