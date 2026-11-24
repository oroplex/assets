package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.model.Room

/** MANUAL = drawn/typed, no AR evidence to grade. */
enum class QualityLevel { GOOD, FAIR, POOR, MANUAL }

data class RoomQualityReport(
    val level: QualityLevel,
    /** 0..1, higher is better (1.0 for MANUAL). */
    val score: Double,
    /** Short user-facing reasons for anything below GOOD, e.g. "Wall 3 was noisy (±2.4 cm) — consider recapturing". */
    val reasons: List<String>,
)

object RoomQuality {
    /**
     * Grade how trustworthy a captured room's measurements are, from its raw evidence: per-wall fit residuals,
     * confidences and sources (depth fit / detected plane / baseline taps), synthesized connector walls, current
     * solver warnings, corner taps vs final corners, estimated ceiling. Deterministic and cheap (called from UI).
     */
    fun assess(room: Room): RoomQualityReport = TODO("implement")
}
