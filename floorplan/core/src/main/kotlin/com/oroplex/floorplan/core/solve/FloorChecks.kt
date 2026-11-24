package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings

enum class IssueSeverity { ERROR, WARNING, INFO }

/** A pre-export / editor check result. [roomId] is null for floor-wide issues. Messages are short and actionable. */
data class FloorIssue(val severity: IssueSeverity, val roomId: String?, val message: String)

object FloorChecks {
    /**
     * Pre-flight checks for a floor, most severe first. Covers at least: rooms whose interiors overlap
     * (ERROR — usually a room from another scan session that was not moved into place), invalid outlines
     * (ERROR), rooms with unresolved solver warnings (WARNING), POOR-quality rooms per RoomQuality (WARNING),
     * estimated ceiling heights (INFO, one combined message listing the rooms), rooms captured in different AR
     * sessions that are not snapped/aligned to each other (INFO), and an uncalibrated floor (INFO, suggests a tape check).
     */
    fun check(floor: Floor, settings: PlanSettings): List<FloorIssue> = TODO("implement")

    /** Ids of room pairs whose interiors overlap by more than [minOverlapArea] m². */
    fun overlappingRooms(floor: Floor, minOverlapArea: Double = 0.05): List<Pair<String, String>> = TODO("implement")
}
