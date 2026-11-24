package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.core.solve.QualityLevel
import com.oroplex.floorplan.core.solve.RoomQualityReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityTest {

    private fun observation(
        confidence: Double = 0.85,
        rms: Double = 0.008,
        points: Int = 200,
        source: WallSource = WallSource.DEPTH_FIT,
    ) = WallObservation(
        line = Line2.of(Vec2(0.0, 2.0), Vec2(-1.0, 0.0)),
        extentStart = Vec2(1.0, 2.0),
        extentEnd = Vec2(-1.0, 2.0),
        source = source,
        rmsResidual = rms,
        pointCount = points,
        confidence = confidence,
        viewer = Vec2.ZERO,
    )

    @Test
    fun wallGradeFollowsConfidence() {
        assertEquals(CaptureGrade.GOOD, wallGrade(observation(confidence = 0.85)))
        assertEquals(CaptureGrade.GOOD, wallGrade(observation(confidence = GOOD_WALL_CONFIDENCE)))
        assertEquals(CaptureGrade.FAIR, wallGrade(observation(confidence = 0.5)))
        assertEquals(CaptureGrade.POOR, wallGrade(observation(confidence = 0.2)))
    }

    @Test
    fun wallGradeIsCappedByNoiseAndPointCount() {
        assertEquals(CaptureGrade.POOR, wallGrade(observation(confidence = 0.9, rms = 0.06)))
        assertEquals(CaptureGrade.FAIR, wallGrade(observation(confidence = 0.9, points = 30)))
        // A thin detected plane has no point count to speak of; it is graded by confidence only.
        assertEquals(CaptureGrade.GOOD, wallGrade(observation(confidence = 0.8, points = 0, rms = 0.0, source = WallSource.DETECTED_PLANE)))
    }

    @Test
    fun tappedWallsAreGoodAndBadDataIsPoor() {
        assertEquals(CaptureGrade.GOOD, wallGrade(observation(confidence = 0.0, points = 2, rms = 0.0, source = WallSource.BASELINE_TAPS)))
        assertEquals(CaptureGrade.POOR, wallGrade(observation(confidence = Double.NaN)))
        assertEquals(CaptureGrade.POOR, wallGrade(observation(rms = Double.POSITIVE_INFINITY)))
    }

    @Test
    fun wallQualityTextExplainsAndAdvises() {
        val metric = PlanSettings(unitSystem = UnitSystem.METRIC)
        assertEquals("Good reading · ±8 mm noise · 200 points", wallQualityText(observation(), metric))
        val poor = wallQualityText(observation(confidence = 0.2, rms = 0.024), metric)
        assertTrue(poor, poor.startsWith("Rough reading · ±2.4 cm noise"))
        assertTrue(poor, poor.contains("Re-capture"))
        assertEquals("Marked at the wall base", wallQualityText(observation(source = WallSource.BASELINE_TAPS), metric))
    }

    @Test
    fun noiseAndDistanceFormatting() {
        assertEquals("6 mm", formatNoise(0.006, UnitSystem.METRIC))
        assertEquals("1.5 cm", formatNoise(0.015, UnitSystem.METRIC))
        assertEquals("0.5 in", formatNoise(0.0127, UnitSystem.IMPERIAL))
        assertEquals("0 mm", formatNoise(Double.NaN, UnitSystem.METRIC))

        assertEquals("4 m", approxDistance(4.0, UnitSystem.METRIC))
        assertEquals("1.5 m", approxDistance(1.5, UnitSystem.METRIC))
        assertEquals("50 cm", approxDistance(0.5, UnitSystem.METRIC))
        assertEquals("13 ft", approxDistance(4.0, UnitSystem.IMPERIAL))
        assertEquals("3 ft", approxDistance(1.0, UnitSystem.IMPERIAL))
        assertEquals("20 in", approxDistance(0.5, UnitSystem.IMPERIAL))
        assertEquals("0 cm", approxDistance(Double.NaN, UnitSystem.METRIC))
    }

    private val square = Room(
        id = "r",
        name = "Room",
        corners = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0)),
    )

    @Test
    fun reviewStatsMarkEstimatedCeilings() {
        val metric = PlanSettings(unitSystem = UnitSystem.METRIC, defaultCeilingHeight = 2.4)
        val measured = reviewStats(square.copy(ceilingHeight = 2.5, ceilingEstimated = false), metric)
        assertEquals(formatLengthSafe(2.5, metric), measured.ceiling)
        assertTrue(measured.size?.contains("×") == true)
        assertEquals(formatLengthSafe(14.0, metric), measured.perimeter)

        val estimated = reviewStats(square.copy(ceilingHeight = 2.4, ceilingEstimated = true), metric)
        assertEquals("${formatLengthSafe(2.4, metric)} (est.)", estimated.ceiling)

        // No height at all: the project default, marked estimated.
        val unknown = reviewStats(square.copy(ceilingHeight = null), metric)
        assertEquals("${formatLengthSafe(2.4, metric)} (est.)", unknown.ceiling)
    }

    @Test
    fun reviewStatsNeverThrowOnDegenerateRooms() {
        val stats = reviewStats(Room(id = "x", name = "x", corners = listOf(Vec2.ZERO)), PlanSettings())
        assertNull(stats.size)
    }

    @Test
    fun reviewQualityKeepsReasonsBelowGood() {
        assertNull(reviewQuality(null))
        val good = reviewQuality(RoomQualityReport(QualityLevel.GOOD, 0.95, listOf("ignored")))
        assertEquals("Good", good?.label)
        assertTrue(good?.reasons?.isEmpty() == true)

        val poor = reviewQuality(RoomQualityReport(QualityLevel.POOR, 0.3, listOf(" Wall 3 was noisy ", "", "Wall 3 was noisy", "a", "b", "c")))
        assertEquals("Needs a check", poor?.label)
        assertEquals(listOf("Wall 3 was noisy", "a", "b", "c"), poor?.reasons)
    }

    @Test
    fun calibrationNoteOnlyForRealCalibrations() {
        assertNull(calibrationNote(null))
        val c = Calibration(factor = 1.025, roomId = "r", wallIndex = 0, measuredLength = 4.0, trueLength = 4.1, appliedAt = 0L)
        val note = calibrationNote(c)
        assertTrue(note, note?.contains("+2.5 %") == true)
        assertNull(calibrationNote(c.copy(factor = 1.0)))
    }
}
