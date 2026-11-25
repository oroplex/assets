package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.UnitSystem
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
        assertEquals(listOf("2 scan warnings to review", "Ceiling height is estimated — measure it so wall areas and volumes are right"), r.reasons)
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

    // ---------------------------------------------------------------- capture numbering (SEAM-1, CAPX-2)

    /**
     * A 4 x 3 m room captured clockwise (north, east, south, west), stored as the solver stores it: counter-
     * clockwise, starting at the first capture. So outline edges are [N, W, S, E] while captures are [N, E, S, W].
     */
    private val cwCorners = listOf(v(4.0, 3.0), v(0.0, 3.0), v(0.0, 0.0), v(4.0, 0.0))
    private val north = wallObs(v(4.0, 3.0), v(0.0, 3.0))
    private val east = wallObs(v(4.0, 0.0), v(4.0, 3.0))
    private val south = wallObs(v(0.0, 0.0), v(4.0, 0.0))
    private val west = wallObs(v(0.0, 3.0), v(0.0, 0.0))
    private fun clockwise(vararg captures: com.oroplex.floorplan.core.model.WallObservation) =
        clean.copy(corners = cwCorners, observations = captures.toList())

    @Test fun captureNumbersNameTheWallToRecapture() {
        // Capture 4 (west) was noisy: the capture review must say "Wall 4" (its chip), the editor "Wall 2" (its edge).
        val room = clockwise(north, east, south, west.copy(rmsResidual = 0.031))
        assertEquals(listOf("Wall 4 was noisy (±3.1 cm) — consider recapturing"), RoomQuality.assess(room, WallNumbering.CAPTURE).reasons)
        assertEquals(listOf("Wall 2 was noisy (±3.1 cm) — consider recapturing"), RoomQuality.assess(room, WallNumbering.OUTLINE).reasons)
        assertEquals(RoomQuality.assess(room).reasons, RoomQuality.assess(room, WallNumbering.OUTLINE).reasons, "OUTLINE is the default")
        // A weak capture 2 (east) is outline edge 4.
        val weak = clockwise(north, east.copy(confidence = 0.2), south, west)
        assertEquals(listOf("Wall 2 was a weak capture — recapture it from closer"), RoomQuality.assess(weak, WallNumbering.CAPTURE).reasons)
        assertEquals(listOf("Wall 4 was a weak capture — recapture it from closer"), RoomQuality.assess(weak, WallNumbering.OUTLINE).reasons)
        // The grade itself does not depend on the numbering.
        assertEquals(RoomQuality.assess(weak, WallNumbering.CAPTURE).score, RoomQuality.assess(weak).score, 0.0)
    }

    @Test fun uncapturedWallsAreNamedByTheirNeighbours() {
        // West never captured (the solver closed the room): it lies between captures 1 (north) and 3 (south).
        val room = clockwise(north, east, south)
        assertEquals(
            listOf("The wall between walls 1 and 3 wasn't measured directly — check it with a tape"),
            RoomQuality.assess(room, WallNumbering.CAPTURE).reasons,
        )
        assertEquals(listOf("Wall 2 wasn't measured directly — check it with a tape"), RoomQuality.assess(room).reasons)
        // Two gaps.
        val two = clean.copy(
            corners = listOf(v(0.0, 0.0), v(4.0, 0.0), v(4.0, 3.0), v(2.0, 3.0), v(2.0, 4.0), v(0.0, 4.0)),
            observations = listOf(wallObs(v(0.0, 0.0), v(4.0, 0.0)), wallObs(v(2.0, 3.0), v(2.0, 4.0)), wallObs(v(0.0, 4.0), v(0.0, 0.0))),
        )
        assertEquals(
            listOf("The walls between walls 1 and 2 and between 2 and 3 weren't measured directly — check them with a tape"),
            RoomQuality.assess(two, WallNumbering.CAPTURE).reasons,
        )
    }

    @Test fun clockwiseCornerTapsAreNamedByTap() {
        // Taps t1..t4 clockwise; the solver stores the outline CCW as [t2, t1, t4, t3]. Tap 4 is 20 cm off.
        val taps = listOf(v(0.0, 3.0), v(4.0, 3.0), v(4.0, 0.0), v(0.0, 0.0))
        val outline = listOf(v(4.0, 3.0), v(0.0, 3.0), v(0.0, 0.2), v(4.0, 0.0))
        val room = tapped("c", outline).copy(cornerTaps = taps)
        assertEquals(listOf("Corner 4 is 20 cm (8\") from where you tapped — check it"), RoomQuality.assess(room, WallNumbering.CAPTURE).reasons)
        assertEquals(listOf("Corner 3 is 20 cm (8\") from where you tapped — check it"), RoomQuality.assess(room).reasons)
        // Units follow the project.
        assertEquals(listOf("Corner 4 is 20 cm from where you tapped — check it"), RoomQuality.assess(room, WallNumbering.CAPTURE, UnitSystem.METRIC).reasons)
        assertEquals(listOf("Corner 4 is 8\" from where you tapped — check it"), RoomQuality.assess(room, WallNumbering.CAPTURE, UnitSystem.IMPERIAL).reasons)
    }

    @Test fun noiseIsShownInTheProjectsUnits() {
        val room = withObservation(2) { it.copy(rmsResidual = 0.031) }
        assertEquals(listOf("Wall 3 was noisy (±1.2 in) — consider recapturing"), RoomQuality.assess(room, unitSystem = UnitSystem.IMPERIAL).reasons)
        assertEquals(listOf("Wall 3 was noisy (±3.1 cm) — consider recapturing"), RoomQuality.assess(room, unitSystem = UnitSystem.METRIC).reasons)
    }

    // ---------------------------------------------------------------- range-adaptive noise (MATH-3, CAPX-1)

    @Test fun accurateCapturesFromAcrossTheRoomAreGood() {
        // A 6 x 5 m living room captured from its middle: walls 2.5 m and 3 m away. Per-point RMS grows with range
        // (about 0.9 sigma, sigma = 5 mm + 1.2 % of the range), so these clean captures read 3.2 and 3.7 cm.
        val corners = rect(0.0, 0.0, 6.0, 5.0)
        val viewer = v(3.0, 2.5)
        val config = com.oroplex.floorplan.core.fit.WallEstimatorConfig()
        val obs = wallsOf(corners).map { o ->
            val range = viewer.distanceTo(Vec2.lerp(o.extentStart, o.extentEnd, 0.5))
            o.copy(viewer = viewer, rmsResidual = 0.9 * config.expectedSigma(range), confidence = 0.8)
        }
        assertTrue(obs.all { it.rmsResidual > 0.03 }, "every capture is above the old fixed 2.5 cm limit")
        val room = scanned("lk", corners, name = "Living").copy(observations = obs)
        val report = RoomQuality.assess(room)
        assertEquals(QualityLevel.GOOD, report.level)
        assertTrue(report.reasons.isEmpty(), "${report.reasons}")
        assertTrue(obs.all { it.rmsResidual < RoomQuality.noisyLimit(it) })
        // The same 3.1 cm RMS from 1 m away is genuinely noisy.
        val close = withObservation(2) { it.copy(rmsResidual = 0.031) }
        assertEquals(QualityLevel.FAIR, RoomQuality.assess(close).level)
        // Noisy means above 1.3 x the expected sigma at that range (never below 2.5 cm).
        val at3m = obs.first { it.viewer.distanceTo(Vec2.lerp(it.extentStart, it.extentEnd, 0.5)) > 2.9 }
        assertEquals(1.3 * config.expectedSigma(3.0), RoomQuality.noisyLimit(at3m), 1e-12)
        val noisy3m = room.copy(observations = obs.map { if (it === at3m) it.copy(rmsResidual = 1.4 * config.expectedSigma(3.0)) else it })
        assertTrue(RoomQuality.assess(noisy3m).reasons.single().contains("was noisy"))
        assertEquals(0.025, RoomQuality.noisyLimit(clean.observations[0]), 1e-12)
        assertEquals(0.025, RoomQuality.noisyLimit(clean.observations[0].copy(viewer = v(Double.NaN, 0.0))), 1e-12)
    }

    // ---------------------------------------------------------------- hand-set walls (PREM-9)

    @Test fun wallsCorrectedWithATapeKeepTheRoomGood() {
        // A 4 x 3 m bedroom scans as GOOD; the user tapes wall 1 at 4.20 m and types it.
        assertEquals(QualityLevel.GOOD, RoomQuality.assess(clean).level)
        val taped = RoomOps.setWallLength(clean, 0, 4.2)
        val report = RoomQuality.assess(taped)
        assertEquals(QualityLevel.GOOD, report.level)
        assertTrue(report.reasons.none { "tape" in it || "weak" in it }, "${report.reasons}")
        // Even a second correction that moves another wall far from its capture.
        val twice = RoomOps.setWallLength(taped, 1, 3.4)
        val r2 = RoomQuality.assess(twice)
        assertEquals(QualityLevel.GOOD, r2.level)
        assertTrue(r2.reasons.isEmpty(), "${r2.reasons}")
        // A typed wall scores at least 0.95 even when its own capture was weak, and gets no capture reason.
        val weakTyped = RoomOps.setWallLength(withObservation(0) { it.copy(confidence = 0.2) }, 0, 4.0)
        val wr = RoomQuality.assess(weakTyped)
        assertTrue(wr.reasons.isEmpty(), "${wr.reasons}")
        assertTrue(wr.score >= 0.95 * 0.4 + 0.6 * 0.95 - 1e-9, "${wr.score}")
        // A dragged (not typed) wall with no capture scores 0.8: no "check it with a tape".
        val dragged = RoomOps.moveCorner(clean, 2, v(4.3, 3.3))
        val dr = RoomQuality.assess(dragged)
        assertTrue(dr.reasons.none { "tape" in it }, "${dr.reasons}")
        assertEquals(QualityLevel.GOOD, dr.level)
        // A re-solve restores the scan: back to the scan's own grade.
        assertEquals(RoomQuality.assess(clean).score, RoomQuality.assess(RoomOps.resolve(taped, SolverOptions())).score, 1e-9)
        // Tapped-corner rooms: corners of hand-set walls are not "far from where you tapped".
        val tappedRoom = RoomOps.moveCorner(tapped("c", box), 2, v(4.3, 3.3))
        assertTrue(RoomQuality.assess(tappedRoom).reasons.isEmpty())
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
