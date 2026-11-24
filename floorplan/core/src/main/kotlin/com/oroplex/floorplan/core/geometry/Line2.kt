package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Infinite 2D line through [point] with UNIT [direction].
 * Always construct via [Line2.of] / [Line2.through] so the direction is normalized.
 */
@Serializable
data class Line2(val point: Vec2, val direction: Vec2) {

    /** Unit normal = direction rotated +90deg (points to the LEFT of the direction). */
    val normal: Vec2 get() = direction.perp()

    /** Signed perpendicular distance; positive on the left side (the [normal] side). */
    fun signedDistance(p: Vec2): Double = (p - point) dot normal

    fun distance(p: Vec2): Double = abs(signedDistance(p))

    /** Parameter t such that [at] (t) is the orthogonal projection of [p]. */
    fun param(p: Vec2): Double = (p - point) dot direction

    fun at(t: Double): Vec2 = point + direction * t

    fun project(p: Vec2): Vec2 = at(param(p))

    /** Angle of the direction in radians, (-PI, PI]. */
    fun angle(): Double = direction.angle()

    /**
     * Intersection point, or null when the lines are (nearly) parallel:
     * |sin(angle between them)| < [minSin].
     */
    fun intersect(other: Line2, minSin: Double = 1e-6): Vec2? {
        val denom = direction cross other.direction
        if (abs(denom) < minSin) return null
        val t = ((other.point - point) cross other.direction) / denom
        return at(t)
    }

    /** |sin| of the angle between the two lines (0 = parallel, 1 = perpendicular). */
    fun sinAngleTo(other: Line2): Double = abs(direction cross other.direction)

    /** Rotate the line rigidly around [pivot] by [angleRad] (CCW). */
    fun rotatedAround(pivot: Vec2, angleRad: Double): Line2 =
        Line2((point - pivot).rotated(angleRad) + pivot, direction.rotated(angleRad).normalized())

    /** Same line with direction reversed. */
    fun flipped(): Line2 = Line2(point, -direction)

    /** Line translated along its normal by [d] meters. */
    fun offset(d: Double): Line2 = Line2(point + normal * d, direction)

    companion object {
        fun of(point: Vec2, direction: Vec2): Line2 {
            val d = direction.normalized()
            require(d != Vec2.ZERO) { "Line2 direction must be non-zero" }
            return Line2(point, d)
        }

        fun through(a: Vec2, b: Vec2): Line2 = of(a, b - a)

        fun fromAngle(point: Vec2, angleRad: Double): Line2 = Line2(point, Vec2.fromAngle(angleRad))
    }
}
