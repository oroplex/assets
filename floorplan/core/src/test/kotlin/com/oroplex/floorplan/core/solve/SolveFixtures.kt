package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared shapes and builders for solver / editing tests. All CCW, meters. */
internal object Shapes {
    val rect = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0))
    val lShape = listOf(
        Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 3.0), Vec2(3.0, 3.0), Vec2(3.0, 5.0), Vec2(0.0, 5.0),
    )
}

internal fun v(x: Double, y: Double) = Vec2(x, y)

/** A wall observed from [a] to [b] with the interior (and the viewer, 1 m away) on the LEFT of a -> b. */
internal fun wallObs(a: Vec2, b: Vec2, confidence: Double = 0.9, viewerDistance: Double = 1.0): WallObservation {
    val line = Line2.through(a, b)
    val mid = Vec2.lerp(a, b, 0.5)
    return WallObservation(
        line = line,
        extentStart = a,
        extentEnd = b,
        source = WallSource.DEPTH_FIT,
        rmsResidual = 0.005,
        pointCount = 200,
        confidence = confidence,
        viewer = mid + line.normal * viewerDistance,
    )
}

/** One observation per edge of the CCW [corners], each covering the middle [fraction] of the edge. */
internal fun wallsOf(corners: List<Vec2>, fraction: Double = 0.6, viewerDistance: Double = 1.0): List<WallObservation> {
    val inset = (1 - fraction) / 2
    return corners.indices.map { i ->
        val a = corners[i]
        val b = corners[(i + 1) % corners.size]
        wallObs(Vec2.lerp(a, b, inset), Vec2.lerp(a, b, 1 - inset), viewerDistance = viewerDistance)
    }
}

/**
 * One observation per edge of the CCW [corners], each covering only [length] meters (capped at 80 % of
 * the edge) around the point at [at] (0..1) along the edge: what a capture aimed there actually sees.
 */
internal fun patchWallsOf(
    corners: List<Vec2>,
    length: Double = 0.4,
    at: Double = 0.5,
    viewerDistance: Double = 1.0,
): List<WallObservation> = corners.indices.map { i -> patchObs(corners[i], corners[(i + 1) % corners.size], length, at, viewerDistance) }

/** A [length]-meter observed patch around the point at [at] (0..1) of the wall [a] -> [b] (interior on the left). */
internal fun patchObs(a: Vec2, b: Vec2, length: Double = 0.4, at: Double = 0.5, viewerDistance: Double = 1.0): WallObservation {
    val edge = a.distanceTo(b)
    val half = minOf(length, 0.8 * edge) / 2 / edge
    val c = at.coerceIn(half, 1 - half)
    return wallObs(Vec2.lerp(a, b, c - half), Vec2.lerp(a, b, c + half), viewerDistance = viewerDistance)
}

/** Rotate an observation's line and extent about the extent midpoint by [deg] degrees (viewer unchanged). */
internal fun perturb(o: WallObservation, deg: Double): WallObservation {
    val mid = Vec2.lerp(o.extentStart, o.extentEnd, 0.5)
    val r = RigidTransform2.rotationAround(mid, Math.toRadians(deg))
    return o.copy(line = r.apply(o.line), extentStart = r.apply(o.extentStart), extentEnd = r.apply(o.extentEnd))
}

internal fun assertVecEquals(expected: Vec2, actual: Vec2, eps: Double = 1e-9, message: String = "") {
    assertTrue(expected.approxEquals(actual, eps), "$message expected $expected but was $actual")
}

internal fun assertPolygonEquals(expected: List<Vec2>, actual: List<Vec2>, eps: Double = 1e-9) {
    assertEquals(expected.size, actual.size, "corner count of $actual")
    expected.indices.forEach { assertVecEquals(expected[it], actual[it], eps, "corner $it:") }
}

/** Same closed outline up to which corner it starts at (same orientation). */
internal fun assertSameOutline(expected: List<Vec2>, actual: List<Vec2>, eps: Double = 1e-9) {
    assertEquals(expected.size, actual.size, "corner count of $actual")
    val shift = actual.indices.firstOrNull { actual[it].approxEquals(expected[0], eps) }
    assertTrue(shift != null, "corner ${expected[0]} missing from $actual")
    expected.indices.forEach { assertVecEquals(expected[it], actual[(it + shift) % actual.size], eps, "corner $it:") }
}

/** Shortest distance between two angles modulo [period]. */
internal fun angleDistance(a: Double, b: Double, period: Double = PI / 2): Double {
    val d = ((a - b) % period + period) % period
    return minOf(d, period - d)
}

internal fun assertAllRightAngles(corners: List<Vec2>, eps: Double = 1e-9) {
    Polygons.interiorAngles(corners).forEachIndexed { i, a ->
        val d = minOf(abs(a - PI / 2), abs(a - 3 * PI / 2))
        assertTrue(d < eps, "corner $i angle ${Math.toDegrees(a)} is not 90/270 degrees")
    }
}
