package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.core.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/*
 * Camera math of the 3D dollhouse view. Pure Kotlin (no Android), unit-tested.
 *
 * Frame: the dollhouse frame of core's Face3 — x = plan.x, y = height above the floor, z = -plan.y.
 * View space: x to the right of the screen, y up the screen, z = depth along the viewing direction
 * (positive in front of the camera). Screen: pixels, origin top-left, y down.
 */

/** Axis-aligned box in the dollhouse frame. [EMPTY] contains nothing. */
data class Box3(val minX: Double, val minY: Double, val minZ: Double, val maxX: Double, val maxY: Double, val maxZ: Double) {
    val isEmpty: Boolean get() = !(maxX >= minX && maxY >= minY && maxZ >= minZ)
    val center: Vec3 get() = Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)

    /** Radius of the bounding sphere around [center] (0 when empty). */
    val radius: Double get() = if (isEmpty) 0.0 else Vec3(maxX - minX, maxY - minY, maxZ - minZ).length / 2

    fun corners(): List<Vec3> = if (isEmpty) emptyList() else listOf(
        Vec3(minX, minY, minZ), Vec3(maxX, minY, minZ), Vec3(minX, maxY, minZ), Vec3(maxX, maxY, minZ),
        Vec3(minX, minY, maxZ), Vec3(maxX, minY, maxZ), Vec3(minX, maxY, maxZ), Vec3(maxX, maxY, maxZ),
    )

    fun include(p: Vec3): Box3 = if (isEmpty) Box3(p.x, p.y, p.z, p.x, p.y, p.z) else Box3(
        min(minX, p.x), min(minY, p.y), min(minZ, p.z), max(maxX, p.x), max(maxY, p.y), max(maxZ, p.z),
    )

    /** Grown by [horizontal] meters in x and z (not in height). */
    fun expandedHorizontally(horizontal: Double) =
        if (isEmpty) this else Box3(minX - horizontal, minY, minZ - horizontal, maxX + horizontal, maxY, maxZ + horizontal)

    companion object {
        val EMPTY = Box3(
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
        )

        fun of(points: Iterable<Vec3>): Box3 = points.fold(EMPTY) { b, p -> if (p.isFinite()) b.include(p) else b }
    }
}

/** Unit camera axes in the dollhouse frame and the eye position. */
class ViewBasis(val eye: Vec3, val right: Vec3, val up: Vec3, val forward: Vec3) {
    /** View-space coordinates of [p]: x right, y up, z depth in front of the camera. */
    fun toView(p: Vec3): Vec3 {
        val r = p - eye
        return Vec3(r dot right, r dot up, r dot forward)
    }
}

/** Perspective lens: vertical field of view and near clipping distance (meters). */
data class Lens(val fovY: Double = Math.toRadians(40.0), val near: Double = 0.05) {
    /** Focal length in pixels for a viewport [heightPx] tall. */
    fun focalPx(heightPx: Double): Double = heightPx / 2 / tan(fovY / 2)
}

/**
 * An orbit camera looking at [target] from [distance] meters away. [pitch] is the elevation above the
 * horizon (radians); [yaw] is the compass direction it looks from: 0 looks "up the plan" (toward plan +y)
 * from below it, so a top-down view matches the 2D plan's orientation; positive yaw orbits
 * counter-clockwise seen from above.
 */
data class OrbitCamera(val target: Vec3, val yaw: Double, val pitch: Double, val distance: Double) {
    /** Unit vector from [target] to the eye. */
    private fun offsetDirection(): Vec3 {
        val c = cos(pitch)
        return Vec3(c * sin(yaw), sin(pitch), c * cos(yaw))
    }

    fun eye(): Vec3 = target + offsetDirection() * distance

    fun basis(): ViewBasis {
        val forward = -offsetDirection()
        val right = Vec3(cos(yaw), 0.0, -sin(yaw))
        return ViewBasis(eye(), right, right cross forward, forward)
    }

    /** Clamped into [limits]: pitch and distance ranges, target inside the target box (height unchanged). */
    fun coerceIn(limits: CameraLimits): OrbitCamera {
        val box = limits.targetBox
        val t = if (box.isEmpty) target else Vec3(target.x.coerceIn(box.minX, box.maxX), target.y, target.z.coerceIn(box.minZ, box.maxZ))
        return OrbitCamera(
            target = t,
            yaw = wrapAngle(yaw),
            pitch = pitch.coerceIn(limits.minPitch, limits.maxPitch),
            distance = distance.coerceIn(limits.minDistance, limits.maxDistance),
        )
    }

    companion object {
        val DEFAULT_YAW = Math.toRadians(30.0)
        val DEFAULT_PITCH = Math.toRadians(50.0)

        /** The other default 3/4 view, a quarter turn round: used when it shows the model clearly larger. */
        val ALTERNATE_YAW = DEFAULT_YAW - PI / 2

        /** The alternate view must bring the camera at least this much closer (relative) to be chosen. */
        const val ALTERNATE_GAIN = 0.15

        /**
         * The camera that frames [box] from the default angle: looking at its center from just far enough that
         * every corner of the box projects inside the viewport inset by [marginPx]. A long model on a narrow
         * (portrait) screen is turned a quarter round, so its long side runs up the screen and it is drawn larger;
         * otherwise the view keeps the 2D plan's orientation (seen from the lower right).
         */
        fun framing(box: Box3, widthPx: Double, heightPx: Double, lens: Lens, marginPx: Double): OrbitCamera {
            if (box.isEmpty) return OrbitCamera(Vec3.ZERO, DEFAULT_YAW, DEFAULT_PITCH, 10.0)
            val target = box.center
            val corners = box.corners()
            val d = fitDistance(corners, target, DEFAULT_YAW, DEFAULT_PITCH, widthPx, heightPx, lens, marginPx)
            val alt = fitDistance(corners, target, ALTERNATE_YAW, DEFAULT_PITCH, widthPx, heightPx, lens, marginPx)
            return if (alt < d * (1 - ALTERNATE_GAIN)) OrbitCamera(target, ALTERNATE_YAW, DEFAULT_PITCH, alt)
            else OrbitCamera(target, DEFAULT_YAW, DEFAULT_PITCH, d)
        }

        /**
         * Interpolation for animating between two cameras ([f] 0..1): target and pitch linearly, yaw the short way
         * round, distance geometrically (so zooming feels uniform).
         */
        fun lerp(a: OrbitCamera, b: OrbitCamera, f: Double): OrbitCamera {
            val k = f.coerceIn(0.0, 1.0)
            val ratio = if (a.distance > 0 && b.distance > 0) b.distance / a.distance else 1.0
            return OrbitCamera(
                target = a.target + (b.target - a.target) * k,
                yaw = wrapAngle(a.yaw + wrapAngle(b.yaw - a.yaw) * k),
                pitch = a.pitch + (b.pitch - a.pitch) * k,
                distance = if (k >= 1.0) b.distance else a.distance * ratio.pow(k),
            )
        }
    }
}

/** What the user may do with the camera. Angles in radians, distances in meters. */
data class CameraLimits(
    val minDistance: Double,
    val maxDistance: Double,
    val targetBox: Box3,
    val minPitch: Double = Math.toRadians(15.0),
    val maxPitch: Double = Math.toRadians(85.0),
) {
    companion object {
        /**
         * Limits for exploring a model with bounds [box]: zoom from a fifth of its radius (close enough to look
         * into one room) out to twelve radii, and pan no further than half a radius (at least 1 m) past its sides.
         */
        fun around(box: Box3): CameraLimits {
            val r = box.radius.takeIf { it.isFinite() && it > 0 } ?: 5.0
            return CameraLimits(
                minDistance = max(0.5, r * 0.2),
                maxDistance = max(8.0, r * 12),
                targetBox = box.expandedHorizontally(max(1.0, r * 0.5)),
            )
        }
    }
}

/** Gesture → camera mapping. Pixel deltas come from the touch events; [density] is pixels per dp. */
object OrbitGestures {
    /** Degrees of orbit per dp dragged: a full-width drag on a phone turns the model a bit over half a turn. */
    const val YAW_DEG_PER_DP = 0.45
    const val PITCH_DEG_PER_DP = 0.35

    /** Panning at a grazing pitch is foreshortened; the compensation is capped so it never jumps. */
    const val MAX_PAN_FORESHORTENING = 2.5

    /** One-finger drag: right turns the model's near side to the right, down tilts toward a top view. */
    fun orbit(c: OrbitCamera, dxPx: Double, dyPx: Double, density: Double, limits: CameraLimits): OrbitCamera {
        if (!dxPx.isFinite() || !dyPx.isFinite() || !(density > 0)) return c
        return c.copy(
            yaw = c.yaw - Math.toRadians(dxPx / density * YAW_DEG_PER_DP),
            pitch = c.pitch + Math.toRadians(dyPx / density * PITCH_DEG_PER_DP),
        ).coerceIn(limits)
    }

    /** Pinch: [factor] > 1 (fingers apart) moves closer. */
    fun zoom(c: OrbitCamera, factor: Double, limits: CameraLimits): OrbitCamera {
        if (!factor.isFinite() || factor <= 0.0) return c
        return c.copy(distance = c.distance / factor).coerceIn(limits)
    }

    /**
     * Two-finger drag: slides the target over the floor so the model follows the fingers (approximately, at
     * the target's depth). Screen-right maps to the camera's horizontal right, screen-down to "away from the
     * viewer" along the ground.
     */
    fun pan(c: OrbitCamera, dxPx: Double, dyPx: Double, viewportHeightPx: Double, lens: Lens, limits: CameraLimits): OrbitCamera {
        if (!dxPx.isFinite() || !dyPx.isFinite() || !(viewportHeightPx > 0)) return c
        val metersPerPx = 2 * c.distance * tan(lens.fovY / 2) / viewportHeightPx
        val right = Vec3(cos(c.yaw), 0.0, -sin(c.yaw))
        val ahead = Vec3(-sin(c.yaw), 0.0, -cos(c.yaw))
        val foreshortening = (1.0 / sin(c.pitch.coerceAtLeast(1e-3))).coerceAtMost(MAX_PAN_FORESHORTENING)
        val move = right * (-dxPx * metersPerPx) + ahead * (dyPx * metersPerPx * foreshortening)
        return c.copy(target = c.target + move).coerceIn(limits)
    }
}

/** Wrap to (-PI, PI]. */
fun wrapAngle(a: Double): Double {
    if (!a.isFinite()) return 0.0
    var x = a % (2 * PI)
    if (x <= -PI) x += 2 * PI
    if (x > PI) x -= 2 * PI
    return x
}

/**
 * Smallest camera distance at which every point of [points] projects inside the viewport inset by
 * [marginPx], for a camera looking at [target] from [yaw]/[pitch].
 *
 * Closed form: with a point's view coordinates (x, y, z0) relative to the target (axes do not depend on the
 * distance d), its depth is d + z0 and its screen offset f·x / (d + z0); it fits horizontally when
 * d >= f·|x| / halfWidth - z0, likewise vertically. Every point also stays in front of the near plane.
 */
fun fitDistance(
    points: List<Vec3>,
    target: Vec3,
    yaw: Double,
    pitch: Double,
    widthPx: Double,
    heightPx: Double,
    lens: Lens,
    marginPx: Double,
): Double {
    if (points.isEmpty() || !(widthPx > 0) || !(heightPx > 0)) return 10.0
    val basis = OrbitCamera(target, yaw, pitch, 1.0).basis()
    val f = lens.focalPx(heightPx)
    val halfW = max(1.0, widthPx / 2 - marginPx)
    val halfH = max(1.0, heightPx / 2 - marginPx)
    var d = lens.near * 2
    for (p in points) {
        if (!p.isFinite()) continue
        val r = p - target
        val x = r dot basis.right
        val y = r dot basis.up
        val z0 = r dot basis.forward
        d = max(d, max(f * abs(x) / halfW - z0, f * abs(y) / halfH - z0))
        d = max(d, lens.near * 2 - z0)
    }
    return d
}
