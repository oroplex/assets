package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 2D vector / point in PLAN coordinates (meters).
 *
 * Plan frame convention (used everywhere in the app):
 *   plan.x =  ARCore world X
 *   plan.y = -ARCore world Z
 * i.e. looking straight down from above, x points right and y points "up the page".
 * Positive angles are counter-clockwise when viewed from above.
 */
@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    operator fun unaryMinus() = Vec2(-x, -y)

    infix fun dot(o: Vec2): Double = x * o.x + y * o.y

    /** z-component of the 3D cross product; > 0 when [o] is counter-clockwise from this. */
    infix fun cross(o: Vec2): Double = x * o.y - y * o.x

    val length: Double get() = hypot(x, y)
    val lengthSquared: Double get() = x * x + y * y

    /** Unit vector in the same direction. Returns [ZERO] for a zero-length vector. */
    fun normalized(): Vec2 {
        val l = length
        return if (l < 1e-12) ZERO else Vec2(x / l, y / l)
    }

    /** Rotated +90 degrees (counter-clockwise). */
    fun perp(): Vec2 = Vec2(-y, x)

    fun rotated(angleRad: Double): Vec2 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    fun distanceTo(o: Vec2): Double = hypot(x - o.x, y - o.y)

    /** Angle of this vector in radians, in (-PI, PI]. */
    fun angle(): Double = atan2(y, x)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite()

    fun approxEquals(o: Vec2, eps: Double = 1e-9): Boolean =
        kotlin.math.abs(x - o.x) <= eps && kotlin.math.abs(y - o.y) <= eps

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun fromAngle(angleRad: Double, length: Double = 1.0) = Vec2(cos(angleRad) * length, sin(angleRad) * length)
        fun lerp(a: Vec2, b: Vec2, t: Double) = Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }
}
