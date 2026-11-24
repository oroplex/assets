package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.FloorChecks
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.FloorOps
import com.oroplex.floorplan.core.solve.RoomQuality
import com.oroplex.floorplan.core.solve.RoomQualityReport

/**
 * The floor-level tools from :core that the editor relies on (snapping, straightening, pre-export checks,
 * quality grades), behind one seam so [PlanEditorViewModel] can be unit-tested with small deterministic
 * fakes. Production code always uses [Core].
 *
 * Implementations may throw on unexpected data; the ViewModel treats any exception as "not available" and
 * degrades with a message instead of crashing.
 */
interface PlanTools {
    /** See [FloorOps.snapRoom]: the floor with [roomId] snapped against its best neighbour, or null. */
    fun snapRoom(floor: Floor, roomId: String, wallThickness: Double, maxDistance: Double, maxAngleDeg: Double): Floor?

    /** See [FloorOps.straighten]. */
    fun straighten(floor: Floor): Floor

    /** See [FloorOps.rotate]; [angle] in radians, counter-clockwise. */
    fun rotate(floor: Floor, angle: Double): Floor

    /** See [FloorChecks.check]: pre-flight issues, most severe first. */
    fun checks(floor: Floor, settings: PlanSettings): List<FloorIssue>

    /** See [FloorChecks.overlappingRooms]. */
    fun overlaps(floor: Floor): List<Pair<String, String>>

    /** See [RoomQuality.assess]. */
    fun quality(room: Room): RoomQualityReport

    /** The real implementations. */
    object Core : PlanTools {
        override fun snapRoom(floor: Floor, roomId: String, wallThickness: Double, maxDistance: Double, maxAngleDeg: Double) =
            FloorOps.snapRoom(floor, roomId, wallThickness, maxDistance, maxAngleDeg)

        override fun straighten(floor: Floor) = FloorOps.straighten(floor)

        override fun rotate(floor: Floor, angle: Double) = FloorOps.rotate(floor, angle)

        override fun checks(floor: Floor, settings: PlanSettings) = FloorChecks.check(floor, settings)

        override fun overlaps(floor: Floor) = FloorChecks.overlappingRooms(floor)

        override fun quality(room: Room) = RoomQuality.assess(room)
    }
}
