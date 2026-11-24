package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Room
import kotlin.math.PI
import kotlin.math.abs

/**
 * The main wall direction of a whole floor (mod 90°), used to straighten a floor and to orient its overall
 * dimension lines.
 *
 * A plain length-weighted 4θ circular mean ([RoomSolver.dominantAngle]) is pulled by angled walls: a 3 m wall at
 * 30° among 40 m of square walls tilts it by about 1°. So that estimate is only the starting point; it is then
 * refined twice with the length-weighted mean deviation of the walls within 10° and then 5° of it, which
 * ignores bays, chamfers and other deliberately angled walls.
 */
internal object FloorAxis {
    private val REFINE_WINDOWS_DEG = listOf(10.0, 5.0)

    /** Axis of every edge of [rooms] in [0, PI/2), or null when there is no usable (finite, non-zero) edge. */
    fun of(rooms: List<Room>): Double? = ofOutlines(rooms.map { it.corners })

    /** Axis of every edge of the closed [outlines] in [0, PI/2), or null when there is no usable edge. */
    fun ofOutlines(outlines: List<List<Vec2>>): Double? {
        val angles = ArrayList<Double>()
        val weights = ArrayList<Double>()
        for (c in outlines) {
            if (c.size < 2) continue
            for (i in c.indices) {
                val a = c[i]
                val b = c[(i + 1) % c.size]
                if (!a.isFinite() || !b.isFinite()) continue
                val length = a.distanceTo(b)
                if (!(length > 1e-6)) continue
                angles += (b - a).angle()
                weights += length
            }
        }
        if (angles.isEmpty()) return null
        var axis = RoomSolver.dominantAngle(angles, weights)
        for (windowDeg in REFINE_WINDOWS_DEG) {
            val window = Math.toRadians(windowDeg)
            var sum = 0.0
            var weight = 0.0
            for (k in angles.indices) {
                val deviation = deviation(angles[k], axis)
                if (abs(deviation) <= window) {
                    sum += weights[k] * deviation
                    weight += weights[k]
                }
            }
            if (weight > 0.0) axis = canonical(axis + sum / weight)
        }
        return axis
    }

    /** [angle] - [axis] wrapped to (-PI/4, PI/4]: how far a wall is from the nearest axis direction. */
    internal fun deviation(angle: Double, axis: Double): Double {
        val quarter = PI / 2
        var d = (angle - axis) % quarter
        if (d <= -quarter / 2) d += quarter
        if (d > quarter / 2) d -= quarter
        return d
    }

    /** [a] wrapped into [0, PI/2). */
    private fun canonical(a: Double): Double {
        val quarter = PI / 2
        var r = a % quarter
        if (r < 0) r += quarter
        if (r >= quarter - 1e-12) r = 0.0
        return r
    }
}
