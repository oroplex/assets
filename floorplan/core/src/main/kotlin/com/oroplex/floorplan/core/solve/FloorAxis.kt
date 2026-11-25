package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Room
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The main wall direction of a whole floor (mod 90°), used to straighten a floor, to tell whether a plan is
 * turned, to compare the heading of separately scanned rooms and to orient overall dimension lines.
 *
 * Self-contained on purpose (no call into [RoomSolver], which delegates its shared axis here), and a consensus
 * rather than a mean: a plain length-weighted 4θ circular mean is pulled by angled walls (a 3 m wall at 30°
 * among 40 m of square walls tilts it by about 1°), and when two scans of one floor are turned against each
 * other (say 37°) the mean lands between them and matches neither. So:
 * 1. Candidates: the plain 4θ mean and the direction of every wall (mod 90°).
 * 2. Each candidate is scored by the total length of the walls within 5° of it (mod 90°); the best wins
 *    (ties: the earlier candidate, i.e. the mean, then wall order). This is the direction most wall length
 *    agrees on, so bays, chamfers and a minority of turned rooms are ignored.
 * 3. Refinement: the length-weighted mean deviation of only those supporting walls, re-selecting the
 *    supporters around the new axis until it settles.
 */
internal object FloorAxis {
    /** Walls within this angle (mod 90°) of an axis support it. */
    private val SUPPORT_WINDOW = Math.toRadians(5.0)

    /** Refinement passes at most (it normally settles after one or two). */
    private const val MAX_REFINE_PASSES = 8

    /** Candidate directions closer than this (radians) are scored once. */
    private const val CANDIDATE_BUCKET = 1e-4

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
                if (!(length > 1e-6) || !length.isFinite()) continue
                angles += (b - a).angle()
                weights += length
            }
        }
        if (angles.isEmpty()) return null

        fun support(axis: Double): Double {
            var s = 0.0
            for (k in angles.indices) if (abs(deviation(angles[k], axis)) <= SUPPORT_WINDOW) s += weights[k]
            return s
        }

        var axis = mean4(angles, weights)
        var best = support(axis)
        // Walls of a floor run in few distinct directions: score each direction once (0.0001 rad buckets), which
        // keeps this near-linear even for big or noisy floors.
        val seen = HashSet<Long>()
        for (a in angles) {
            val candidate = canonical(a)
            if (!seen.add(Math.round(candidate / CANDIDATE_BUCKET))) continue
            val s = support(candidate)
            if (s > best + 1e-9) {
                best = s
                axis = candidate
            }
        }
        repeat(MAX_REFINE_PASSES) {
            var sum = 0.0
            var weight = 0.0
            for (k in angles.indices) {
                val d = deviation(angles[k], axis)
                if (abs(d) <= SUPPORT_WINDOW) {
                    sum += weights[k] * d
                    weight += weights[k]
                }
            }
            if (weight <= 0.0) return axis
            val next = canonical(axis + sum / weight)
            val moved = abs(deviation(next, axis))
            axis = next
            if (moved < 1e-13) return axis
        }
        return axis
    }

    /**
     * [axis] (in [0, PI/2)) as the signed angle of the floor's nearest-to-horizontal wall direction, in
     * (-PI/4, PI/4]: how far the plan is turned from square. Rotating the floor by minus this straightens it.
     */
    fun tilt(axis: Double): Double = if (axis > PI / 4) axis - PI / 2 else axis

    /** [angle] - [axis] wrapped to (-PI/4, PI/4]: how far a wall is from the nearest axis direction. */
    internal fun deviation(angle: Double, axis: Double): Double {
        val quarter = PI / 2
        var d = (angle - axis) % quarter
        if (d <= -quarter / 2) d += quarter
        if (d > quarter / 2) d -= quarter
        return d
    }

    /** Length-weighted 4θ circular mean (perpendicular walls reinforce each other), in [0, PI/2). */
    private fun mean4(angles: List<Double>, weights: List<Double>): Double {
        var c = 0.0
        var s = 0.0
        for (k in angles.indices) {
            c += weights[k] * cos(4 * angles[k])
            s += weights[k] * sin(4 * angles[k])
        }
        if (hypot(c, s) < 1e-12) return 0.0
        return canonical(atan2(s, c) / 4)
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
