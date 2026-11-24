package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room

enum class FaceKind { FLOOR, WALL_INNER, WALL_OUTER, WALL_TOP, OPENING_FRAME }

/**
 * Planar polygon of the 3D "dollhouse" model, in a y-up frame where x = plan.x, y = height above the floor,
 * z = -plan.y (Vec3.fromPlan). [roomId] is null for shared/exterior pieces.
 */
data class Face3(val points: List<Vec3>, val kind: FaceKind, val roomId: String?)

object Dollhouse {
    /**
     * Build an extruded model of the rooms: a floor polygon per room, wall slabs of settings.wallThickness
     * around each room's interior outline up to its ceiling height (ceilingHeight ?: [defaultCeiling]),
     * with door/opening holes cut full-height-of-opening and window holes between sill and head
     * (walls split into the pieces around each opening). No ceilings (open-top dollhouse view).
     */
    fun build(rooms: List<Room>, settings: PlanSettings, defaultCeiling: Double = settings.defaultCeilingHeight): List<Face3> =
        TODO("implement")
}
