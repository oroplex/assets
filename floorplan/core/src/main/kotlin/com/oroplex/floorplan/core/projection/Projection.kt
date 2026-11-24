package com.oroplex.floorplan.core.projection

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import kotlin.math.abs

data class Ray3(val origin: Vec3, val direction: Vec3)

/**
 * Pure-Kotlin camera math for the AR overlay. Matrices are 4x4 COLUMN-MAJOR float arrays, the same
 * layout as android.opengl.Matrix and ARCore's Camera.getViewMatrix/getProjectionMatrix.
 * Screen coordinates are pixels with origin at the TOP-LEFT, x right, y down.
 *
 * Element (row r, column c) lives at index `c * 4 + r`. All arithmetic is done in double precision;
 * inputs shorter than 16 elements or containing non-finite values yield null (or a degenerate ray for
 * [screenRay]) rather than an exception, because they can only come from a broken AR frame.
 */
object Projection {
    /** Points with clip-space w at or below this are treated as behind the camera. */
    private const val W_EPSILON = 1e-6

    /** Relative determinant threshold (|det| / max|mᵢⱼ|⁴) below which a matrix counts as singular. */
    private const val SINGULAR_RELATIVE_DET = 1e-12

    /** result = a * b (column-major). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size >= 16 && b.size >= 16) { "4x4 matrices need 16 elements (got ${a.size}, ${b.size})" }
        val out = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0.0
                for (k in 0 until 4) sum += a[k * 4 + row].toDouble() * b[col * 4 + k].toDouble()
                out[col * 4 + row] = sum.toFloat()
            }
        }
        return out
    }

    /**
     * General 4x4 inverse, null if singular.
     *
     * Cofactor expansion in double precision. The matrix is considered singular when it has non-finite
     * entries, is all zeros, or |det| ≤ 1e-12 · max|mᵢⱼ|⁴ (a scale-independent test, so uniformly tiny but
     * well-conditioned matrices still invert).
     */
    fun invert(m: FloatArray): FloatArray? {
        if (m.size < 16) return null
        val a = DoubleArray(16) { m[it].toDouble() }
        var maxAbs = 0.0
        for (v in a) {
            if (!v.isFinite()) return null
            maxAbs = maxOf(maxAbs, abs(v))
        }
        if (maxAbs == 0.0) return null

        // Index helper: element (row, col) of a column-major matrix.
        fun e(r: Int, c: Int) = a[c * 4 + r]

        // 2x2 minors of the top two rows (s) and the bottom two rows (c), Laplace expansion by rows.
        val s0 = e(0, 0) * e(1, 1) - e(1, 0) * e(0, 1)
        val s1 = e(0, 0) * e(1, 2) - e(1, 0) * e(0, 2)
        val s2 = e(0, 0) * e(1, 3) - e(1, 0) * e(0, 3)
        val s3 = e(0, 1) * e(1, 2) - e(1, 1) * e(0, 2)
        val s4 = e(0, 1) * e(1, 3) - e(1, 1) * e(0, 3)
        val s5 = e(0, 2) * e(1, 3) - e(1, 2) * e(0, 3)

        val c5 = e(2, 2) * e(3, 3) - e(3, 2) * e(2, 3)
        val c4 = e(2, 1) * e(3, 3) - e(3, 1) * e(2, 3)
        val c3 = e(2, 1) * e(3, 2) - e(3, 1) * e(2, 2)
        val c2 = e(2, 0) * e(3, 3) - e(3, 0) * e(2, 3)
        val c1 = e(2, 0) * e(3, 2) - e(3, 0) * e(2, 2)
        val c0 = e(2, 0) * e(3, 1) - e(3, 0) * e(2, 1)

        val det = s0 * c5 - s1 * c4 + s2 * c3 + s3 * c2 - s4 * c1 + s5 * c0
        val scale = maxAbs * maxAbs * maxAbs * maxAbs
        if (!det.isFinite() || abs(det) <= SINGULAR_RELATIVE_DET * scale) return null
        val inv = 1.0 / det

        // inverse(row, col) values, written to column-major storage.
        val r = DoubleArray(16)
        fun set(row: Int, col: Int, v: Double) {
            r[col * 4 + row] = v * inv
        }
        set(0, 0, e(1, 1) * c5 - e(1, 2) * c4 + e(1, 3) * c3)
        set(0, 1, -e(0, 1) * c5 + e(0, 2) * c4 - e(0, 3) * c3)
        set(0, 2, e(3, 1) * s5 - e(3, 2) * s4 + e(3, 3) * s3)
        set(0, 3, -e(2, 1) * s5 + e(2, 2) * s4 - e(2, 3) * s3)

        set(1, 0, -e(1, 0) * c5 + e(1, 2) * c2 - e(1, 3) * c1)
        set(1, 1, e(0, 0) * c5 - e(0, 2) * c2 + e(0, 3) * c1)
        set(1, 2, -e(3, 0) * s5 + e(3, 2) * s2 - e(3, 3) * s1)
        set(1, 3, e(2, 0) * s5 - e(2, 2) * s2 + e(2, 3) * s1)

        set(2, 0, e(1, 0) * c4 - e(1, 1) * c2 + e(1, 3) * c0)
        set(2, 1, -e(0, 0) * c4 + e(0, 1) * c2 - e(0, 3) * c0)
        set(2, 2, e(3, 0) * s4 - e(3, 1) * s2 + e(3, 3) * s0)
        set(2, 3, -e(2, 0) * s4 + e(2, 1) * s2 - e(2, 3) * s0)

        set(3, 0, -e(1, 0) * c3 + e(1, 1) * c1 - e(1, 2) * c0)
        set(3, 1, e(0, 0) * c3 - e(0, 1) * c1 + e(0, 2) * c0)
        set(3, 2, -e(3, 0) * s3 + e(3, 1) * s1 - e(3, 2) * s0)
        set(3, 3, e(2, 0) * s3 - e(2, 1) * s1 + e(2, 2) * s0)

        return FloatArray(16) { r[it].toFloat() }
    }

    /** Project a world point; null if it is behind the camera (clip w <= small epsilon). */
    fun worldToScreen(viewProj: FloatArray, p: Vec3, width: Int, height: Int): Vec2? {
        if (viewProj.size < 16 || !p.isFinite()) return null
        val c = transform(viewProj, p.x, p.y, p.z, 1.0)
        if (!(c.w > W_EPSILON)) return null
        return toScreen(c, width, height)
    }

    /**
     * Project a world segment, clipping it against the near plane (w > epsilon) first.
     * Returns null when the segment is entirely behind the camera. Endpoints may lie off-screen.
     *
     * Clipping happens in homogeneous clip space against the OpenGL near plane (z + w ≥ 0) and against
     * w ≥ epsilon, by linear interpolation of the clip coordinates (lines stay lines under projective
     * maps, so this is exact). Only afterwards is the perspective divide applied, which avoids the
     * "segment wraps through infinity" artefact of projecting a point behind the camera. The first
     * returned point corresponds to [a]'s end, the second to [b]'s.
     */
    fun projectSegment(viewProj: FloatArray, a: Vec3, b: Vec3, width: Int, height: Int): Pair<Vec2, Vec2>? {
        if (viewProj.size < 16 || !a.isFinite() || !b.isFinite()) return null
        var ca = transform(viewProj, a.x, a.y, a.z, 1.0)
        var cb = transform(viewProj, b.x, b.y, b.z, 1.0)

        // Clip against z + w >= 0 (near plane), then w - eps >= 0.
        for (plane in 0 until 2) {
            val da = if (plane == 0) ca.z + ca.w else ca.w - W_EPSILON
            val db = if (plane == 0) cb.z + cb.w else cb.w - W_EPSILON
            if (!da.isFinite() || !db.isFinite()) return null
            if (da < 0 && db < 0) return null
            if (da < 0) {
                ca = Clip.lerp(ca, cb, da / (da - db))
            } else if (db < 0) {
                cb = Clip.lerp(cb, ca, db / (db - da))
            }
        }
        // Interpolation can land a hair below epsilon through rounding; never divide by ~0.
        if (!(ca.w > 0.0) || !(cb.w > 0.0)) return null
        return toScreen(ca, width, height) to toScreen(cb, width, height)
    }

    /**
     * World-space ray through screen pixel (sx, sy) using the inverse view-projection matrix.
     *
     * The pixel is unprojected at NDC depth −1 (near plane, the ray origin) and +1 (far plane); the
     * direction is the normalized difference. If the far plane is at infinity (w = 0 after
     * unprojection) an intermediate depth is used instead. For an unusable matrix or viewport the
     * result is a ray at the origin with ZERO direction, which [intersectHorizontalPlane] rejects.
     */
    fun screenRay(invViewProj: FloatArray, sx: Double, sy: Double, width: Int, height: Int): Ray3 {
        val degenerate = Ray3(Vec3.ZERO, Vec3.ZERO)
        if (invViewProj.size < 16 || width <= 0 || height <= 0 || !sx.isFinite() || !sy.isFinite()) return degenerate
        val ndcX = 2.0 * sx / width - 1.0
        val ndcY = 1.0 - 2.0 * sy / height

        val near = unproject(invViewProj, ndcX, ndcY, -1.0) ?: return degenerate
        for (farZ in FAR_DEPTHS) {
            val far = unproject(invViewProj, ndcX, ndcY, farZ) ?: continue
            val dir = (far - near).normalized()
            if (dir != Vec3.ZERO && dir.isFinite()) return Ray3(near, dir)
        }
        return degenerate
    }

    /** Intersection with the horizontal plane Y = [y]; null if parallel or behind the ray origin. */
    fun intersectHorizontalPlane(ray: Ray3, y: Double): Vec3? {
        val dy = ray.direction.y
        if (!(abs(dy) >= 1e-9) || !ray.origin.isFinite() || !y.isFinite()) return null
        val t = (y - ray.origin.y) / dy
        if (!(t > 0.0) || t.isInfinite()) return null
        return ray.origin + ray.direction * t
    }

    /** NDC depths tried for the far point of [screenRay], in order of preference. */
    private val FAR_DEPTHS = doubleArrayOf(1.0, 0.5, 0.0)

    /** Homogeneous clip-space coordinates. */
    private data class Clip(val x: Double, val y: Double, val z: Double, val w: Double) {
        companion object {
            fun lerp(p: Clip, q: Clip, t: Double) = Clip(
                p.x + (q.x - p.x) * t,
                p.y + (q.y - p.y) * t,
                p.z + (q.z - p.z) * t,
                p.w + (q.w - p.w) * t,
            )
        }
    }

    private fun transform(m: FloatArray, x: Double, y: Double, z: Double, w: Double): Clip = Clip(
        m[0] * x + m[4] * y + m[8] * z + m[12] * w,
        m[1] * x + m[5] * y + m[9] * z + m[13] * w,
        m[2] * x + m[6] * y + m[10] * z + m[14] * w,
        m[3] * x + m[7] * y + m[11] * z + m[15] * w,
    )

    private fun toScreen(c: Clip, width: Int, height: Int): Vec2 {
        val ndcX = c.x / c.w
        val ndcY = c.y / c.w
        return Vec2((ndcX + 1.0) / 2.0 * width, (1.0 - ndcY) / 2.0 * height)
    }

    /** NDC point → world point (perspective divide by the unprojected w); null when w ≈ 0. */
    private fun unproject(inv: FloatArray, x: Double, y: Double, z: Double): Vec3? {
        val h = transform(inv, x, y, z, 1.0)
        if (!(abs(h.w) > 1e-12) || !h.w.isFinite()) return null
        val p = Vec3(h.x / h.w, h.y / h.w, h.z / h.w)
        return if (p.isFinite()) p else null
    }
}
