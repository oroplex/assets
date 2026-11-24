package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Room
import kotlin.math.abs
import kotlin.test.assertTrue

/** Builders for FloorOps / FloorChecks / RoomQuality tests (meters, CCW outlines). */
internal object Ops {
    /** Interior partition default (4.5"). */
    const val T = 0.1143

    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(v(x0, y0), v(x1, y0), v(x1, y1), v(x0, y1))

    /** A room scanned wall by wall: one clean observation per edge, so it grades GOOD and re-solves to itself. */
    fun scanned(
        id: String,
        corners: List<Vec2>,
        name: String = "Room $id",
        session: String? = "s1",
        ceiling: Double? = 2.4,
        ceilingEstimated: Boolean = false,
        openings: List<Opening> = emptyList(),
        warnings: List<String> = emptyList(),
    ) = Room(
        id = id, name = name, corners = corners, ceilingHeight = ceiling, ceilingEstimated = ceilingEstimated,
        openings = openings, captureMethod = CaptureMethod.WALLS, observations = wallsOf(corners), sessionId = session,
        warnings = warnings,
    )

    /** A room captured by tapping its corners exactly. */
    fun tapped(id: String, corners: List<Vec2>, name: String = "Room $id", session: String? = "s1") = Room(
        id = id, name = name, corners = corners, ceilingHeight = 2.4, captureMethod = CaptureMethod.CORNERS,
        cornerTaps = corners, sessionId = session,
    )

    fun drawn(id: String, corners: List<Vec2>, name: String = "Room $id") =
        Room(id = id, name = name, corners = corners, ceilingHeight = 2.4, captureMethod = CaptureMethod.MANUAL)

    fun floor(vararg rooms: Room) = Floor(id = "f", name = "Main Floor", rooms = rooms.toList())

    fun door(id: String, wall: Int, offset: Double, width: Double = 0.8) =
        Opening(id = id, type = OpeningType.DOOR, wallIndex = wall, offset = offset, width = width, height = 2.032)

    fun window(id: String, wall: Int, offset: Double, width: Double = 1.0) =
        Opening(id = id, type = OpeningType.WINDOW, wallIndex = wall, offset = offset, width = width, height = 1.2192, sillHeight = 0.9144)

    /** [room] rigidly turned by [deg] degrees about its centroid. */
    fun turned(room: Room, deg: Double): Room =
        RoomOps.transform(room, RigidTransform2.rotationAround(Polygons.centroid(room.corners), Math.toRadians(deg)))

    fun moved(room: Room, dx: Double, dy: Double): Room = RoomOps.transform(room, RigidTransform2(0.0, v(dx, dy)))

    fun Floor.room(id: String): Room = rooms.single { it.id == id }
}

/** All pairwise corner distances of [a] equal those of [b]: the same shape, moved rigidly. */
internal fun assertSameShape(a: List<Vec2>, b: List<Vec2>, eps: Double = 1e-9) {
    assertTrue(a.size == b.size, "corner count")
    for (i in a.indices) for (j in i + 1 until a.size) {
        val d = abs(a[i].distanceTo(a[j]) - b[i].distanceTo(b[j]))
        assertTrue(d <= eps, "distance $i-$j changed by $d")
    }
}

/** |angle| of [d] from the nearest plan axis, radians. */
internal fun offAxis(d: Vec2): Double = angleDistance(d.angle(), 0.0)
