package com.oroplex.floorplan.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.atan2

/**
 * 2D rigid transform in the plan: p' = R(angle) * p + translation.
 * Used to re-anchor rooms when ARCore refines its map, and to move/rotate rooms in the editor.
 */
@Serializable
data class RigidTransform2(val angle: Double = 0.0, val translation: Vec2 = Vec2.ZERO) {

    fun apply(p: Vec2): Vec2 = p.rotated(angle) + translation

    /** Rotate a direction vector (no translation). */
    fun applyToDirection(d: Vec2): Vec2 = d.rotated(angle)

    fun apply(line: Line2): Line2 = Line2(apply(line.point), applyToDirection(line.direction).normalized())

    /** (this ∘ other)(p) = this.apply(other.apply(p)). */
    fun compose(other: RigidTransform2): RigidTransform2 =
        RigidTransform2(angle + other.angle, other.translation.rotated(angle) + translation)

    fun inverse(): RigidTransform2 = RigidTransform2(-angle, (-translation).rotated(-angle))

    companion object {
        val IDENTITY = RigidTransform2()

        fun rotationAround(pivot: Vec2, angle: Double): RigidTransform2 =
            RigidTransform2(angle, pivot - pivot.rotated(angle))

        /**
         * Plan transform corresponding to an ARCore pose (quaternion qx,qy,qz,qw + translation tx,ty,tz),
         * keeping only the yaw (rotation about world +Y) and the horizontal translation.
         * A world rotation about +Y by phi is a CCW plan rotation by phi (plan.y = -world.z).
         */
        fun fromArPose(qx: Double, qy: Double, qz: Double, qw: Double, tx: Double, tz: Double): RigidTransform2 {
            // Rotate the world X axis (1,0,0) by the quaternion and read its horizontal heading.
            val vx = 1 - 2 * (qy * qy + qz * qz)
            val vz = 2 * (qx * qz - qw * qy)
            val yaw = atan2(-vz, vx)
            return RigidTransform2(yaw, Vec2(tx, -tz))
        }

        /** Yaw-only rotation matrix helper for tests. */
        fun rotation(angle: Double) = RigidTransform2(angle, Vec2.ZERO)
    }
}
