package com.oroplex.floorplan.ar

import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.projection.Projection
import kotlin.math.abs

/**
 * The crosshair resolved for one frame (GL thread only; [plane] must not leave the GL thread).
 * [fromHitTest] is false when the point is the screen-center ray intersected with the floor height
 * (featureless floors), i.e. not backed by any ARCore trackable.
 */
internal class ReticleResolution(val hit: ReticleHit, val plane: Plane?, val fromHitTest: Boolean)

/** Classification of ARCore hit results. All functions run on the GL thread. */
internal object HitTesting {
    /** An upward plane within this height of the floor counts as floor, meters. */
    const val FLOOR_PLANE_TOLERANCE = 0.10

    /** Floor-ray intersections farther than this are treated as "not aiming at the floor", meters. */
    const val MAX_FLOOR_RAY_RANGE = 12.0

    /**
     * First usable hit along the screen-center ray, classified for the overlay.
     *
     * Plane hits only count inside the plane polygon and from the plane's front side (the camera on the
     * side its normal points to), so the back of a wall plane or the floor seen through a gap at its edge
     * is never reported. When nothing is hit but the floor height is known, falls back to intersecting
     * the center ray with the horizontal plane Y = [floorY] so the reticle works on featureless floors.
     */
    fun resolveReticle(
        hits: List<HitResult>,
        camera: Vec3,
        floorY: Double?,
        invViewProjection: FloatArray?,
        viewportWidth: Int,
        viewportHeight: Int,
    ): ReticleResolution? {
        for (hit in hits) {
            val pose = hit.hitPose
            val world = pose.position()
            if (!world.isFinite()) continue
            val distance = hit.distance.toDouble().takeIf { it.isFinite() } ?: world.distanceTo(camera)
            when (val trackable = hit.trackable) {
                is Plane -> {
                    if (!isUsablePlaneHit(trackable, pose, camera)) continue
                    val kind = when (trackable.type) {
                        Plane.Type.HORIZONTAL_UPWARD_FACING ->
                            if (floorY != null && abs(world.y - floorY) <= FLOOR_PLANE_TOLERANCE) SurfaceKind.FLOOR
                            else SurfaceKind.OTHER
                        Plane.Type.VERTICAL -> SurfaceKind.WALL_PLANE
                        Plane.Type.HORIZONTAL_DOWNWARD_FACING -> SurfaceKind.CEILING
                    }
                    val normal = Vec3.of(pose.yAxis).normalized()
                    return ReticleResolution(ReticleHit(world, kind, distance, normal), trackable, fromHitTest = true)
                }
                is DepthPoint ->
                    return ReticleResolution(ReticleHit(world, SurfaceKind.DEPTH, distance, null), null, true)
                is Point ->
                    return ReticleResolution(ReticleHit(world, SurfaceKind.FEATURE_POINT, distance, null), null, true)
                else ->
                    return ReticleResolution(ReticleHit(world, SurfaceKind.OTHER, distance, null), null, true)
            }
        }
        if (floorY == null || invViewProjection == null) return null
        val onFloor = floorRayHit(invViewProjection, viewportWidth, viewportHeight, floorY, camera) ?: return null
        return ReticleResolution(
            ReticleHit(onFloor, SurfaceKind.FLOOR, onFloor.distanceTo(camera), Vec3.UP),
            plane = null,
            fromHitTest = false,
        )
    }

    /**
     * Screen-center ray intersected with Y = [floorY]; null when the ray points above the horizon, the
     * matrix is unusable, or the intersection is beyond [MAX_FLOOR_RAY_RANGE].
     */
    fun floorRayHit(
        invViewProjection: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        floorY: Double,
        camera: Vec3,
    ): Vec3? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || !floorY.isFinite()) return null
        val ray = Projection.screenRay(
            invViewProjection,
            viewportWidth / 2.0,
            viewportHeight / 2.0,
            viewportWidth,
            viewportHeight,
        )
        val p = Projection.intersectHorizontalPlane(ray, floorY) ?: return null
        if (!p.isFinite() || p.distanceTo(camera) > MAX_FLOOR_RAY_RANGE) return null
        return p
    }

    /** Unit direction of the screen-center ray, or null when the matrix / viewport is unusable. */
    fun centerRayDirection(invViewProjection: FloatArray, viewportWidth: Int, viewportHeight: Int): Vec3? {
        if (viewportWidth <= 0 || viewportHeight <= 0) return null
        val ray = Projection.screenRay(
            invViewProjection,
            viewportWidth / 2.0,
            viewportHeight / 2.0,
            viewportWidth,
            viewportHeight,
        )
        return ray.direction.takeIf { it != Vec3.ZERO && it.isFinite() }
    }

    /**
     * Wall sample from one grid ray: the first DepthPoint, feature Point, or VERTICAL plane hit (inside its
     * polygon, front side). Horizontal planes along the ray are skipped, not treated as blockers.
     */
    fun firstWallSample(hits: List<HitResult>, camera: Vec3): Vec3? {
        for (hit in hits) {
            val pose = hit.hitPose
            val usable = when (val trackable = hit.trackable) {
                is DepthPoint, is Point -> true
                is Plane -> trackable.type == Plane.Type.VERTICAL && isUsablePlaneHit(trackable, pose, camera)
                else -> false
            }
            if (!usable) continue
            val p = pose.position()
            if (p.isFinite()) return p
        }
        return null
    }

    /** First VERTICAL plane along the center ray (inside its polygon, front side), even behind other hits. */
    fun firstVerticalPlane(hits: List<HitResult>, camera: Vec3): Plane? {
        for (hit in hits) {
            val trackable = hit.trackable
            if (trackable is Plane && trackable.type == Plane.Type.VERTICAL &&
                isUsablePlaneHit(trackable, hit.hitPose, camera)
            ) return trackable
        }
        return null
    }

    /**
     * Height (world Y) of the ceiling sample along the center ray, or null (AR layer AR-2).
     *
     * Accepted hits ([CeilingSampling.isCeilingSample]): downward-facing planes (inside the polygon, seen
     * from below), and DepthPoints / feature Points only when the ray points steeply upward and, where
     * ARCore estimated one, the surface normal is roughly vertical, so wall faces, cabinet fronts and
     * door frames above 1.8 m are rejected. Everything must lie above the camera and more than
     * [CeilingSampling.MIN_HEIGHT_ABOVE_FLOOR] above [floorY]. Of the accepted hits the HIGHEST is
     * returned: along an upward ray that is the farthest surface, i.e. the ceiling behind a lamp.
     */
    fun ceilingSampleY(hits: List<HitResult>, camera: Vec3, floorY: Double): Double? {
        var best = Double.NEGATIVE_INFINITY
        for (hit in hits) {
            val pose = hit.hitPose
            val p = pose.position()
            if (!p.isFinite()) continue
            val evidence = when (val trackable = hit.trackable) {
                is Plane ->
                    if (trackable.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING && isUsablePlaneHit(trackable, pose, camera)) {
                        SurfaceEvidence.DOWNWARD_PLANE
                    } else {
                        continue
                    }
                is DepthPoint -> SurfaceEvidence.POINT_WITH_NORMAL
                is Point -> pointEvidence(trackable)
                else -> continue
            }
            val rayUp = unitDirectionY(camera, p) ?: continue
            val normalY = if (evidence == SurfaceEvidence.POINT_WITH_NORMAL) pose.yAxis[1].toDouble() else null
            if (CeilingSampling.isCeilingSample(evidence, p.y, camera.y, floorY, rayUp, normalY) && p.y > best) {
                best = p.y
            }
        }
        return best.takeIf { it.isFinite() }
    }

    /**
     * Floor-lock fallback sample (Capture flow AR-4): the FIRST surface along the center ray, when it is a
     * DepthPoint or feature Point that [FloorLockFallback.classify] accepts as floor. A plane in front of
     * it (a wall, a table edge) blocks the ray. Returns the hit or, when rejected, why.
     */
    fun floorFallbackSample(hits: List<HitResult>, camera: Vec3): FloorFallbackSample {
        for (hit in hits) {
            val pose = hit.hitPose
            val p = pose.position()
            if (!p.isFinite()) continue
            val evidence = when (val trackable = hit.trackable) {
                is Plane -> {
                    if (!isUsablePlaneHit(trackable, pose, camera)) continue
                    // An upward plane is handled by the plane path of LockFloor; anything else blocks the ray.
                    return FloorFallbackSample(null, FloorLockFallback.Rejection.NOT_FLOOR_LIKE)
                }
                is DepthPoint -> SurfaceEvidence.POINT_WITH_NORMAL
                is Point -> pointEvidence(trackable)
                else -> continue
            }
            val rayY = unitDirectionY(camera, p) ?: continue
            val normalY = if (evidence == SurfaceEvidence.POINT_WITH_NORMAL) pose.yAxis[1].toDouble() else null
            val rejection = FloorLockFallback.classify(p.y, camera.y, rayY, normalY)
            return FloorFallbackSample(p.takeIf { rejection == FloorLockFallback.Rejection.NONE }, rejection)
        }
        return FloorFallbackSample(null, FloorLockFallback.Rejection.NOT_FLOOR_LIKE)
    }

    /** Y component of the unit direction camera -> [p]; null when they coincide. */
    private fun unitDirectionY(camera: Vec3, p: Vec3): Double? {
        val d = p - camera
        val length = d.length
        if (!(length > MIN_RAY_LENGTH) || !length.isFinite()) return null
        return d.y / length
    }

    /** Feature points only carry a surface normal when ARCore estimated one. */
    private fun pointEvidence(point: Point): SurfaceEvidence =
        if (point.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
            SurfaceEvidence.POINT_WITH_NORMAL
        } else {
            SurfaceEvidence.POINT_WITHOUT_NORMAL
        }

    /**
     * Wall observation from an ARCore vertical plane: center pose, its +Y axis as the normal, and the
     * polygon transformed to world space for the observed extent. Follows `subsumedBy` to the plane that
     * replaced a merged one. Null when the plane is no longer tracking or is not vertical enough.
     */
    fun wallFromPlane(plane: Plane, viewer: Vec3): WallObservation? {
        var current = plane
        var hops = 0
        while (hops++ < MAX_SUBSUMPTION_CHAIN) {
            current = current.subsumedBy ?: break
        }
        if (current.trackingState != TrackingState.TRACKING || current.type != Plane.Type.VERTICAL) return null
        val pose = current.centerPose
        val center = pose.position()
        val normal = Vec3.of(pose.yAxis).normalized()
        if (!center.isFinite() || !normal.isFinite() || normal == Vec3.ZERO) return null

        val polygon = current.polygon
        val base = polygon.position()
        val count = (polygon.limit() - base) / 2
        val local = FloatArray(3)
        val world = FloatArray(3)
        val boundary = ArrayList<Vec3>(count)
        for (i in 0 until count) {
            local[0] = polygon.get(base + 2 * i)
            local[1] = 0f
            local[2] = polygon.get(base + 2 * i + 1)
            pose.transformPoint(local, 0, world, 0)
            val p = Vec3.of(world)
            if (p.isFinite()) boundary += p
        }
        return WallEstimator.fromPlane(center, normal, boundary, viewer)
    }

    /** Plane hit inside the polygon, on a tracking plane, seen from the side its normal points to. */
    private fun isUsablePlaneHit(plane: Plane, hitPose: Pose, camera: Vec3): Boolean {
        if (plane.trackingState != TrackingState.TRACKING || !plane.isPoseInPolygon(hitPose)) return false
        val n = hitPose.yAxis
        val toCamera = camera - hitPose.position()
        return toCamera.x * n[0] + toCamera.y * n[1] + toCamera.z * n[2] > 0.0
    }

    /** Subsumption chains are short in practice; the bound only guards against a malformed cycle. */
    private const val MAX_SUBSUMPTION_CHAIN = 8

    /** Hits closer than this to the camera (m) have no meaningful direction. */
    private const val MIN_RAY_LENGTH = 1e-3
}

/** One floor-lock fallback reading: [world] is null when the hit was rejected for [rejection]. */
internal class FloorFallbackSample(val world: Vec3?, val rejection: FloorLockFallback.Rejection)

/** Translation of an ARCore pose as a world-space [Vec3]. */
internal fun Pose.position(): Vec3 = Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

/** Yaw + horizontal translation of an ARCore pose as a plan-space rigid transform. */
internal fun Pose.toPlanTransform(): RigidTransform2 = RigidTransform2.fromArPose(
    qx().toDouble(),
    qy().toDouble(),
    qz().toDouble(),
    qw().toDouble(),
    tx().toDouble(),
    tz().toDouble(),
)
