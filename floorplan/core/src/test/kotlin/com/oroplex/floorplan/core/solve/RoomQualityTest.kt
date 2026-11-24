package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.core.solve.Ops.drawn
import com.oroplex.floorplan.core.solve.Ops.rect
import com.oroplex.floorplan.core.solve.Ops.scanned
import com.oroplex.floorplan.core.solve.Ops.tapped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomQualityTest {
    private val box = rect(0.0, 0.0, 4.0, 3.0)
    private val clean = scanned("r", box, name = "Bedroom")

    private fun withObservation(index: Int, change: (com.oroplex.floorplan.core.model.WallObservation) -> com.oroplex.floorplan.core.model.WallObservation) =
        clean.copy(observations = clean.observations.mapIndexed { i, o -> if (i == index) change(o) else o })

    @Test fun manualRoomsAreNotGraded() {
        assertEquals(RoomQualityReport(QualityLevel.MANUAL, 1.0, emptyList()), RoomQuality.assess(drawn("m", box)))
        // Even a broken drawn room: it has no AR evidence to grade.
        assertEquals(QualityLevel.MANUAL, RoomQuality.assess(drawn("m", box.take(2))).level)
    }

    @Test fun cleanScanIsGoodWithNoReasons() {
        val r = RoomQuality.assess(clean)
        assertEquals(QualityLevel.GOOD, r.level)
        assertEquals(1.0, r.score, 1e-12)
        assertTrue(r.reasons.isEmpty())
        // Clean baseline taps and detected planes are trusted too.
        val taps = clean.copy(observations = clean.observations.map { it.copy(source = WallSource.BASELINE_TAPS, rmsResidual = 0.0, confidence = 0.9) })
        assertEquals(QualityLevel.GOOD, RoomQuality.assess(taps).level)
        val planes = clean.copy(observations = clean.observations.map { it.copy(source = WallSource.DETECTED_PLANE, rmsResidual = 0.0, confidence = 0.8) })
        assertEquals(QualityLevel.GOOD, RoomQuality.assess(planes).level)
    }

    @Test fun noisyWallIsCalledOutByItsWallNumber() {
        val r = RoomQuality.assess(withObservation(2) { it.copy(rmsResidual = 0.031) })
        assertEquals(QualityLevel.FAIR, r.level)
        assertEquals(listOf("Wall 3 was noisy (±3.1 cm) — consider recapturing"), r.reasons)
        val two = clean.copy(observations = clean.observations.mapIndexed { i, o -> if (i == 0 || i == 3) o.copy(rmsResidual = 0.026 + i * 0.001) else o })
        assertEquals(listOf("Walls 1 and 4 were noisy (up to ±2.9 cm) — consider recapturing"), RoomQuality.assess(two).reasons)
    }

    @Test fun weakCaptureLowersTheGrade() {
        val r = RoomQuality.assess(withObservation(1) { it.copy(confidence = 0.2) })
        assertEquals(QualityLevel.FAIR, r.level)
        assertEquals(listOf("Wall 2 was a weak capture — recapture it from closer"), r.reasons)
        assertTrue(r.score < RoomQuality.GOOD_SCORE && r.score >= RoomQuality.FAIR_SCORE)
        // A better re-capture of the same wall wins over the weak one.
        val recaptured = withObservation(1) { it.copy(confidence = 0.2) }.let { it.copy(observations = it.observations + clean.observations[1]) }
        assertEquals(QualityLevel.GOOD, RoomQuality.assess(recaptured).level)
    }

    @Test fun wallsNoCaptureSupportsAreFlagged() {
        // Only three walls captured: the solver filled in the fourth.
        val r = RoomQuality.assess(clean.copy(observations = clean.observations.take(3)))
        assertEquals(QualityLevel.FAIR, r.level)
        assertEquals(listOf("Wall 4 wasn't measured directly — check it with a tape"), r.reasons)
        // Two filled-in walls plus warnings: poor.
        val poor = RoomQuality.assess(clean.copy(observations = clean.observations.take(2), warnings = listOf("A wall seems to be missing")))
        assertEquals(QualityLevel.POOR, poor.level)
        assertEquals(listOf("Walls 3 and 4 weren't measured directly — check them with a tape", "1 scan warning to review"), poor.reasons)
        // A wall moved by hand away from what was scanned no longer counts as measured.
        val edited = clean.copy(corners = RoomOps.setWallLength(clean, 0, 4.6).corners)
        assertEquals(listOf("Wall 2 wasn't measured directly — check it with a tape"), RoomQuality.assess(edited).reasons)
        // A short filled-in jog (under 30 cm) is not worth a reason.
        val jog = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(2.0, 3.0), v(2.0, 3.2), v(0.0, 3.2))
        val jogRoom = clean.copy(corners = jog, observations = wallsOf(jog).filterIndexed { i, _ -> i != 3 })
        val jr = RoomQuality.assess(jogRoom)
        assertTrue(jr.reasons.isEmpty(), "${jr.reasons}")
        assertEquals(QualityLevel.GOOD, jr.level)
    }

    @Test fun warningsAndEstimatedCeilingAddReasons() {
        val r = RoomQuality.assess(clean.copy(warnings = listOf("x", "y"), ceilingEstimated = true))
        assertEquals(listOf("2 scan warnings to review", "Ceiling height is estimated — measure it for accurate volumes"), r.reasons)
        assertEquals(1.0 - 0.2 - 0.05, r.score, 1e-12)
        assertEquals(QualityLevel.FAIR, r.level)
        // Warning penalties are capped.
        val many = RoomQuality.assess(clean.copy(warnings = List(9) { "w$it" }))
        assertEquals(0.7, many.score, 1e-12)
        // An estimated ceiling alone keeps a clean scan GOOD (it does not affect the floor plan).
        assertEquals(QualityLevel.GOOD, RoomQuality.assess(clean.copy(ceilingEstimated = true)).level)
    }

    @Test fun cornerTapRooms() {
        val exact = RoomQuality.assess(tapped("c", box))
        assertEquals(QualityLevel.GOOD, exact.level)
        assertEquals(0.95, exact.score, 1e-12)
        assertTrue(exact.reasons.isEmpty())
        // Corner 3 ended up 20 cm from where it was tapped (e.g. squaring or a drag).
        val off = tapped("c", box).copy(cornerTaps = box.mapIndexed { i, p -> if (i == 2) p + v(0.0, -0.2) else p })
        val r = RoomQuality.assess(off)
        assertEquals(listOf("Corner 3 is 20 cm (8\") from where you tapped — check it"), r.reasons)
        assertEquals(QualityLevel.FAIR, r.level)
        // No taps kept at all.
        val none = RoomQuality.assess(tapped("c", box).copy(cornerTaps = emptyList()))
        assertEquals(QualityLevel.FAIR, none.level)
        assertTrue(none.reasons.single().startsWith("No corner taps were kept"))
    }

    @Test fun brokenScannedRoomsArePoor() {
        val bowtie = clean.copy(corners = listOf(v(0.0, 0.0), v(3.0, 2.0), v(3.0, 0.0), v(0.0, 1.0)))
        val r = RoomQuality.assess(bowtie)
        assertEquals(QualityLevel.POOR, r.level)
        assertEquals(0.0, r.score)
        assertEquals(1, r.reasons.size)
        val empty = RoomQuality.assess(clean.copy(observations = emptyList()))
        assertEquals(QualityLevel.FAIR, empty.level)
        assertTrue(empty.reasons.single().startsWith("No scan data was kept"))
    }

    @Test fun clockwiseOutlinesKeepTheirWallNumbers() {
        // Stored clockwise: edge 0 is the top wall (0,3) -> (4,3).
        val cw = listOf(v(0.0, 3.0), v(4.0, 3.0), v(4.0, 0.0), v(0.0, 0.0))
        val obs = wallsOf(box).map { if (it.extentStart.y > 2.9) it.copy(rmsResidual = 0.03) else it }
        val r = RoomQuality.assess(clean.copy(corners = cw, observations = obs))
        assertEquals(listOf("Wall 1 was noisy (±3.0 cm) — consider recapturing"), r.reasons)
        val taps = tapped("c", cw).copy(cornerTaps = cw.mapIndexed { i, p -> if (i == 1) p + v(0.3, 0.0) else p })
        assertTrue(RoomQuality.assess(taps).reasons.single().startsWith("Corner 2 is 30 cm"))
    }

    @Test fun badNumbersNeverCrash() {
        val weird = clean.copy(
            observations = clean.observations.mapIndexed { i, o ->
                when (i) {
                    0 -> o.copy(confidence = Double.NaN)
                    1 -> o.copy(rmsResidual = Double.POSITIVE_INFINITY)
                    2 -> o.copy(extentStart = v(Double.NaN, 0.0))
                    else -> o
                }
            },
        )
        val r = RoomQuality.assess(weird)
        assertTrue(r.score in 0.0..1.0)
        assertTrue(r.level != QualityLevel.MANUAL)
        assertEquals(CaptureMethod.WALLS, weird.captureMethod)
    }
}
