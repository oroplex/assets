package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import kotlin.math.PI
import kotlin.test.assertTrue

/** Shared builders for renderer / exporter tests. All outlines CCW, meters. */
internal object Fixtures {
    val settings = PlanSettings()
    val t: Double = settings.wallThickness

    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))

    fun room(id: String, corners: List<Vec2>, openings: List<Opening> = emptyList(), name: String = "Room $id") =
        Room(id = id, name = name, corners = corners, openings = openings)

    fun opening(
        type: OpeningType,
        wall: Int,
        offset: Double,
        width: Double,
        swing: DoorSwing = DoorSwing.LEFT_IN,
        id: String = "o-$type-$wall-$offset",
    ) = Opening(id = id, type = type, wallIndex = wall, offset = offset, width = width, height = 2.0, swing = swing)

    val lShape = listOf(
        Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 3.0), Vec2(3.0, 3.0), Vec2(3.0, 5.0), Vec2(0.0, 5.0),
    )
}

internal inline fun <reified T : DrawOp> DrawList.opsOf(): List<T> = ops.filterIsInstance<T>()

internal fun assertNear(expected: Double, actual: Double, eps: Double = 1e-9, message: String? = null) =
    assertTrue(kotlin.math.abs(expected - actual) <= eps, "${message ?: ""} expected $expected but was $actual")

internal fun assertNear(expected: Vec2, actual: Vec2, eps: Double = 1e-9, message: String? = null) =
    assertTrue(expected.approxEquals(actual, eps), "${message ?: ""} expected $expected but was $actual")

/** Angle difference wrapped to (-PI, PI]. */
internal fun angleDiff(a: Double, b: Double): Double {
    var d = (a - b) % (2 * PI)
    if (d <= -PI) d += 2 * PI
    if (d > PI) d -= 2 * PI
    return d
}
