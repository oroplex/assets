package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.WallObservation

data class SolverOptions(
    val squareAngles: Boolean = true,
    /** Walls within this many degrees of the dominant axis (mod 90deg) are snapped onto it. */
    val squareToleranceDeg: Double = 5.0,
    /** Consecutive walls closer than this angle are treated as parallel (same wall or a jog). */
    val parallelDeg: Double = 12.0,
    /** Parallel consecutive walls whose faces are within this distance are merged into one wall. */
    val sameWallOffset: Double = 0.10,
)

data class SolveResult(
    /** Interior outline, counter-clockwise. */
    val corners: List<Vec2>,
    /** For each edge i (corners[i] -> corners[i+1]): index of the source observation, or -1 if synthesized. */
    val edgeSources: List<Int>,
    /** Dominant wall direction in radians, in [0, PI/2). */
    val dominantAngle: Double,
    /** Human-readable issues worth showing the user (e.g. "Wall 3 corner is far from what was measured"). */
    val warnings: List<String>,
)

object RoomSolver {
    /**
     * Build a closed room outline from walls captured IN ORDER around the room (either direction).
     * Steps: (1) optional squaring — rotate each near-axis wall about its observed midpoint onto the
     * dominant axis; (2) merge/jog handling for consecutive near-parallel walls; (3) corner i = intersection
     * of walls i and i+1 (wrapping); (4) orientation normalized to CCW; (5) sanity checks -> warnings.
     * If the result is self-intersecting, retries with walls sorted by angle around the viewer centroid.
     * Returns null when fewer than 3 usable walls remain or no valid polygon can be formed.
     */
    fun solveWalls(observations: List<WallObservation>, options: SolverOptions = SolverOptions()): SolveResult? = TODO("implement")

    /** Clean up a corner-tapped outline: drop near-duplicate/collinear points, optional squaring, CCW. */
    fun solveCorners(taps: List<Vec2>, options: SolverOptions = SolverOptions()): SolveResult? = TODO("implement")

    /**
     * Weighted dominant direction mod 90deg, in [0, PI/2). Uses the 4θ circular-mean trick so that
     * perpendicular walls reinforce each other. [weights] default to 1.
     */
    fun dominantAngle(angles: List<Double>, weights: List<Double>? = null): Double = TODO("implement")
}
