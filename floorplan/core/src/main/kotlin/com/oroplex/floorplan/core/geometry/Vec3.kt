package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * 3D vector / point in ARCore WORLD coordinates (meters).
 * ARCore world frame: +Y is up (opposite gravity), right-handed, X/Z horizontal.
 */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    val length: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val l = length
        return if (l < 1e-12) ZERO else Vec3(x / l, y / l, z / l)
    }

    fun distanceTo(o: Vec3): Double = (this - o).length

    /** Project onto the horizontal plan: plan.x = X, plan.y = -Z. Height (Y) is dropped. */
    fun toPlan(): Vec2 = Vec2(x, -z)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)

        /** Inverse of [toPlan]: places a plan point at world height [y]. */
        fun fromPlan(p: Vec2, y: Double) = Vec3(p.x, y, -p.y)

        fun of(f: FloatArray, offset: Int = 0) =
            Vec3(f[offset].toDouble(), f[offset + 1].toDouble(), f[offset + 2].toDouble())
    }
}
