package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation

enum class WallFitFailure(val message: String) {
    TOO_FEW_POINTS("Not enough depth points on the wall. Move closer or aim at a textured area."),
    NO_DOMINANT_PLANE("Couldn't find a flat wall. Aim at a clear section of wall above furniture."),
    TOO_NOISY("Wall measurement too noisy. Hold steady, add light, or move closer."),
    TOO_FAR("Too far from the wall for an accurate reading. Move within about 4 m (13 ft)."),
}

data class WallFit(
    val observation: WallObservation?,
    val failure: WallFitFailure?,
    val usedPoints: Int,
    val totalPoints: Int,
)

data class WallEstimatorConfig(
    /** Ignore points lower than this above the floor (baseboards, floor bleed). */
    val minHeightAboveFloor: Double = 0.20,
    /** Ignore points closer than this to the ceiling. */
    val minDistanceBelowCeiling: Double = 0.15,
    /** Assumed ceiling height for filtering when none was measured. */
    val assumedCeilingHeight: Double = 2.4,
    /** Points farther than this from the viewer (horizontal) are discarded. */
    val maxRange: Double = 6.0,
    val inlierThreshold: Double = 0.025,
    val minInliers: Int = 25,
    /** RMS residual above which the fit is rejected as TOO_NOISY. */
    val maxRms: Double = 0.03,
    /**
     * When a second roughly-parallel line lies FARTHER from the viewer than the best line and has at
     * least this fraction of the best line's inliers, prefer it (the near one is usually furniture).
     */
    val preferFartherRatio: Double = 0.5,
)

/**
 * Turns raw AR measurements into [WallObservation]s. Walls are modeled as vertical planes, i.e. lines
 * in the plan after projecting points with Vec3.toPlan().
 */
object WallEstimator {
    /** Fit a vertical wall to world-space sample points (depth hits) captured around the reticle. */
    fun fromPoints(
        worldPoints: List<Vec3>,
        floorY: Double,
        ceilingY: Double?,
        viewer: Vec3,
        config: WallEstimatorConfig = WallEstimatorConfig(),
    ): WallFit = TODO("implement")

    /**
     * Wall from an ARCore vertical plane: [center] = plane center, [normal] = plane normal (world),
     * [boundary] = plane polygon in world space (used for the observed extent).
     * Returns null if the plane is not close to vertical (|normal.y| > 0.2).
     */
    fun fromPlane(center: Vec3, normal: Vec3, boundary: List<Vec3>, viewer: Vec3): WallObservation? = TODO("implement")

    /** Wall from two floor points tapped along the base of the wall (fallback mode). */
    fun fromBaseline(a: Vec3, b: Vec3, viewer: Vec3): WallObservation? = TODO("implement")
}
