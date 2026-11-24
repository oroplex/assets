package com.oroplex.floorplan.core.projection

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3

data class Ray3(val origin: Vec3, val direction: Vec3)

/**
 * Pure-Kotlin camera math for the AR overlay. Matrices are 4x4 COLUMN-MAJOR float arrays, the same
 * layout as android.opengl.Matrix and ARCore's Camera.getViewMatrix/getProjectionMatrix.
 * Screen coordinates are pixels with origin at the TOP-LEFT, x right, y down.
 */
object Projection {
    /** result = a * b (column-major). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray = TODO("implement")

    /** General 4x4 inverse, null if singular. */
    fun invert(m: FloatArray): FloatArray? = TODO("implement")

    /** Project a world point; null if it is behind the camera (clip w <= small epsilon). */
    fun worldToScreen(viewProj: FloatArray, p: Vec3, width: Int, height: Int): Vec2? = TODO("implement")

    /**
     * Project a world segment, clipping it against the near plane (w > epsilon) first.
     * Returns null when the segment is entirely behind the camera. Endpoints may lie off-screen.
     */
    fun projectSegment(viewProj: FloatArray, a: Vec3, b: Vec3, width: Int, height: Int): Pair<Vec2, Vec2>? = TODO("implement")

    /** World-space ray through screen pixel (sx, sy) using the inverse view-projection matrix. */
    fun screenRay(invViewProj: FloatArray, sx: Double, sy: Double, width: Int, height: Int): Ray3 = TODO("implement")

    /** Intersection with the horizontal plane Y = [y]; null if parallel or behind the ray origin. */
    fun intersectHorizontalPlane(ray: Ray3, y: Double): Vec3? = TODO("implement")
}
