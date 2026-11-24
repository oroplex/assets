package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.core.solve.QualityLevel
import com.oroplex.floorplan.core.solve.RoomQuality
import com.oroplex.floorplan.core.solve.RoomQualityReport
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Traffic-light grade of one captured wall (shown as a green / amber / red chip). */
enum class CaptureGrade { GOOD, FAIR, POOR }

/** Depth-fit confidence at or above this is a good wall (WallEstimator's 0..1 score). */
internal const val GOOD_WALL_CONFIDENCE = 0.6

/** Depth-fit confidence below this is a poor wall worth re-capturing. */
internal const val FAIR_WALL_CONFIDENCE = 0.35

/** A depth fit with fewer inlier points than this can't be better than fair. */
internal const val MIN_GOOD_POINT_COUNT = 40

/** A fit band thicker than this (RMS, meters) is rough whatever the confidence says. */
internal const val MAX_FAIR_RMS = 0.05

/**
 * Grade one wall observation. Baseline taps and manual walls are exact by construction (GOOD). Depth fits
 * and detected planes are graded by [WallObservation.confidence], which already combines inlier ratio,
 * noise, point count, observed extent and range; [WallObservation.rmsResidual] and the point count only
 * cap the grade (the RMS is per-point noise, which grows with range even for a well-determined line).
 * Non-finite data is POOR.
 */
internal fun wallGrade(o: WallObservation): CaptureGrade {
    if (o.source == WallSource.BASELINE_TAPS || o.source == WallSource.MANUAL) return CaptureGrade.GOOD
    val confidence = o.confidence
    if (!confidence.isFinite() || !o.rmsResidual.isFinite()) return CaptureGrade.POOR
    val byConfidence = when {
        confidence >= GOOD_WALL_CONFIDENCE -> CaptureGrade.GOOD
        confidence >= FAIR_WALL_CONFIDENCE -> CaptureGrade.FAIR
        else -> CaptureGrade.POOR
    }
    val capped = when {
        o.rmsResidual > MAX_FAIR_RMS -> CaptureGrade.POOR
        o.source == WallSource.DEPTH_FIT && o.pointCount < MIN_GOOD_POINT_COUNT -> CaptureGrade.FAIR
        else -> CaptureGrade.GOOD
    }
    return maxOf(byConfidence, capped) // enum order GOOD < FAIR < POOR: the worse of the two
}

/** Short headline for a wall's grade, e.g. in the wall menu. */
internal fun gradeHeadline(grade: CaptureGrade): String = when (grade) {
    CaptureGrade.GOOD -> "Good reading"
    CaptureGrade.FAIR -> "Fair reading"
    CaptureGrade.POOR -> "Rough reading"
}

/**
 * One line describing how wall [o] was measured, e.g. "Good reading · ±6 mm noise · 214 points" or
 * "Marked at the wall base". Followed by advice when it is not good.
 */
internal fun wallQualityText(o: WallObservation, settings: PlanSettings): String {
    val grade = wallGrade(o)
    val detail = when (o.source) {
        WallSource.BASELINE_TAPS -> "Marked at the wall base"
        WallSource.MANUAL -> "Entered by hand"
        WallSource.DETECTED_PLANE -> "${gradeHeadline(grade)} · detected wall surface"
        WallSource.DEPTH_FIT ->
            "${gradeHeadline(grade)} · ±${formatNoise(o.rmsResidual, settings.unitSystem)} noise · ${o.pointCount} points"
    }
    return when (grade) {
        CaptureGrade.GOOD -> detail
        CaptureGrade.FAIR -> "$detail. Re-capture from closer if you can."
        CaptureGrade.POOR -> "$detail. Re-capture it: aim at a textured spot above furniture, 1–3 m away."
    }
}

/** Fit noise for display: millimeters / centimeters, or tenths of an inch. Never throws. */
internal fun formatNoise(meters: Double, system: UnitSystem): String {
    val m = if (meters.isFinite()) meters.coerceAtLeast(0.0) else 0.0
    return when (system) {
        UnitSystem.METRIC -> if (m < 0.01) "${(m * 1000).roundToInt()} mm" else String.format(Locale.US, "%.1f cm", m * 100)
        UnitSystem.IMPERIAL -> String.format(Locale.US, "%.1f in", m / 0.0254)
    }
}

/** A distance rounded for coaching copy ("about 13 ft", "about 4 m", "20 in", "50 cm"). */
internal fun approxDistance(meters: Double, system: UnitSystem): String {
    val m = if (meters.isFinite()) meters.coerceAtLeast(0.0) else 0.0
    return when (system) {
        UnitSystem.METRIC -> if (m < 1.0) {
            "${(m * 100).roundToLong()} cm"
        } else {
            val tenths = (m * 10).roundToLong()
            if (tenths % 10 == 0L) "${tenths / 10} m" else String.format(Locale.US, "%.1f m", tenths / 10.0)
        }
        UnitSystem.IMPERIAL -> {
            val feet = m / 0.3048
            if (feet < 3.0) "${(m / 0.0254).roundToLong()} in" else "${feet.roundToLong()} ft"
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Review summary
// -------------------------------------------------------------------------------------------------

/** Formatted room statistics for the review (null fields are not shown). */
internal data class ReviewStats(
    val size: String?,
    val area: String,
    val perimeter: String,
    val ceiling: String,
)

/**
 * Review statistics of [room]: L × W of its oriented bounding box, area, perimeter and ceiling height.
 * An estimated ceiling (step skipped) is marked "(est.)"; a room without a height falls back to the
 * project default, also marked estimated. Never throws.
 */
internal fun reviewStats(room: Room, settings: PlanSettings): ReviewStats {
    fun len(m: Double) = formatLengthSafe(m, settings)
    val stats = try {
        Measurements.room(room)
    } catch (e: Exception) {
        null
    }
    val area = stats?.area ?: runCatching { Polygons.area(room.corners) }.getOrDefault(0.0)
    val perimeter = stats?.perimeter ?: runCatching { Polygons.perimeter(room.corners) }.getOrDefault(0.0)
    val size = stats?.takeIf { it.length > 0.0 && it.width > 0.0 }?.let { "${len(it.length)} × ${len(it.width)}" }
    val areaText = try {
        Units.formatArea(area, settings.unitSystem)
    } catch (e: Exception) {
        String.format(Locale.US, "%.1f m²", area)
    }
    val measured = room.ceilingHeight?.takeIf { it.isFinite() && it > 0.0 }
    val ceiling = if (measured != null && !room.ceilingEstimated) {
        len(measured)
    } else {
        "${len(measured ?: settings.defaultCeilingHeight)} (est.)"
    }
    return ReviewStats(size, areaText, len(perimeter), ceiling)
}

/** The quality badge of the review: a short label and the reasons behind anything below GOOD. */
internal data class ReviewQuality(val level: QualityLevel, val label: String, val reasons: List<String>)

internal fun qualityLabel(level: QualityLevel): String = when (level) {
    QualityLevel.GOOD -> "Good"
    QualityLevel.FAIR -> "Fair"
    QualityLevel.POOR -> "Needs a check"
    QualityLevel.MANUAL -> "Drawn by hand"
}

/** Map a [RoomQualityReport] to the review badge; at most [maxReasons] reasons, blank ones dropped. */
internal fun reviewQuality(report: RoomQualityReport?, maxReasons: Int = 4): ReviewQuality? {
    val r = report ?: return null
    val reasons = r.reasons.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(maxReasons)
    return ReviewQuality(r.level, qualityLabel(r.level), if (r.level == QualityLevel.GOOD) emptyList() else reasons)
}

/**
 * [RoomQuality.assess] for the review badge. The badge is advisory, so a grading failure of any kind
 * (including a not-yet-available grader) hides the badge instead of blocking the save.
 */
internal fun safeAssess(room: Room): RoomQualityReport? = runCatching { RoomQuality.assess(room) }.getOrNull()

/** Short note about the floor's tape calibration being applied to the room on save, or null. */
internal fun calibrationNote(calibration: Calibration?): String? {
    val scale = AppliedScale.of(calibration) ?: return null
    val percent = (scale.factor - 1.0) * 100
    val change = String.format(Locale.US, "%+.1f %%", percent)
    return "This floor is tape-calibrated, so the room is scaled to match ($change) when you save."
}
