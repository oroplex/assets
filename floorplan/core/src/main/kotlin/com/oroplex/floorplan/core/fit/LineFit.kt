package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2

/**
 * Result of fitting a line to 2D points.
 * [tMin]/[tMax] are the extreme parameters of the inliers along [line] (line.at(t)).
 */
data class LineFitResult(
    val line: Line2,
    val inliers: List<Vec2>,
    val rmsResidual: Double,
    val totalCount: Int,
    val tMin: Double,
    val tMax: Double,
) {
    val inlierCount: Int get() = inliers.size
    val inlierRatio: Double get() = if (totalCount == 0) 0.0 else inlierCount.toDouble() / totalCount
}

object LineFit {
    /**
     * Orthogonal (total least squares) line fit through the centroid along the principal axis.
     * Returns null for fewer than 2 points or a degenerate (all-coincident) set.
     */
    fun totalLeastSquares(points: List<Vec2>): Line2? = TODO("implement")

    /**
     * Deterministic RANSAC (seeded RNG) followed by TLS refinement on the inliers
     * (re-selecting inliers against the refined line, up to 3 refinement passes).
     * Returns null when no line reaches [minInliers].
     */
    fun ransac(
        points: List<Vec2>,
        inlierThreshold: Double = 0.025,
        iterations: Int = 300,
        minInliers: Int = 12,
        seed: Long = 1L,
    ): LineFitResult? = TODO("implement")
}
