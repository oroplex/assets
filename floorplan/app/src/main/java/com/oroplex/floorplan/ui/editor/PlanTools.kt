package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.solve.FloorChecks
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.FloorOps
import com.oroplex.floorplan.core.solve.RoomQuality
import com.oroplex.floorplan.core.solve.RoomQualityReport
import com.oroplex.floorplan.core.solve.WallNumbering

/**
 * The floor-level tools from :core that the editor relies on (snapping, straightening, pre-export checks,
 * quality grades), behind one seam so [PlanEditorViewModel] can be unit-tested with small deterministic
 * fakes. Production code always uses [Core].
 *
 * Implementations may throw on unexpected data; the ViewModel treats any exception as "not available" and
 * degrades with a message instead of crashing.
 */
interface PlanTools {
    /**
     * See [FloorOps.snapGroup]: the floor with the rooms [groupIds] (a whole scan, or just `setOf(roomId)`) moved
     * as one block so a wall of [roomId] lines up with its best neighbour outside the group; the same instance
     * when already in place, null when nothing qualifies.
     */
    fun snapGroup(
        floor: Floor,
        roomId: String,
        groupIds: Set<String>,
        wallThickness: Double,
        maxDistance: Double,
        maxAngleDeg: Double,
    ): Floor?

    /** See [FloorOps.straighten]. */
    fun straighten(floor: Floor): Floor

    /** See [FloorOps.rotate]; [angle] in radians, counter-clockwise. */
    fun rotate(floor: Floor, angle: Double): Floor

    /** See [FloorChecks.check]: pre-flight issues, most severe first. */
    fun checks(floor: Floor, settings: PlanSettings): List<FloorIssue>

    /** See [FloorChecks.overlappingRooms]. */
    fun overlaps(floor: Floor): List<Pair<String, String>>

    /** See [RoomQuality.assess] (outline numbering, as the editor shows walls); lengths in [unitSystem]. */
    fun quality(room: Room, unitSystem: UnitSystem): RoomQualityReport

    /** The real implementations. */
    object Core : PlanTools {
        override fun snapGroup(
            floor: Floor,
            roomId: String,
            groupIds: Set<String>,
            wallThickness: Double,
            maxDistance: Double,
            maxAngleDeg: Double,
        ) = FloorOps.snapGroup(floor, roomId, groupIds, wallThickness, maxDistance, maxAngleDeg)

        override fun straighten(floor: Floor) = FloorOps.straighten(floor)

        override fun rotate(floor: Floor, angle: Double) = FloorOps.rotate(floor, angle)

        override fun checks(floor: Floor, settings: PlanSettings) = FloorChecks.check(floor, settings)

        override fun overlaps(floor: Floor) = FloorChecks.overlappingRooms(floor)

        override fun quality(room: Room, unitSystem: UnitSystem) =
            RoomQuality.assess(room, WallNumbering.OUTLINE, unitSystem)
    }
}
