package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
     * Point sets whose mean squared distance from their centroid is below this (i.e. an RMS spread
     * under ~1 nm) are treated as coincident: no direction can be estimated from them.
     */
    private const val MIN_MEAN_SQUARED_SPREAD = 1e-18

    /** Redraws allowed per RANSAC iteration when the sampled pair is too close together. */
    private const val MAX_SAMPLE_ATTEMPTS = 10

    /** Maximum TLS refine / re-select passes after the RANSAC hypothesis search. */
    private const val REFINE_PASSES = 3

    /**
     * Orthogonal (total least squares) line fit through the centroid along the principal axis.
     * Returns null for fewer than 2 points or a degenerate (all-coincident) set.
     *
     * Non-finite points are ignored. The direction is the principal eigenvector of the 2x2 scatter
     * matrix, obtained in closed form as the angle θ = ½·atan2(2·Sxy, Sxx − Syy); the line's anchor
     * point is the centroid. For an isotropic cloud (Sxx == Syy, Sxy == 0) every direction is
     * equally good and θ = 0 is returned.
     */
    fun totalLeastSquares(points: List<Vec2>): Line2? {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        for (p in points) {
            if (!p.isFinite()) continue
            sx += p.x
            sy += p.y
            n++
        }
        if (n < 2) return null
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (p in points) {
            if (!p.isFinite()) continue
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        return lineFromMoments(n, mx, my, sxx, sxy, syy)
    }

    /**
     * Weighted orthogonal fit: the principal axis of the weighted scatter matrix, through the weighted
     * centroid. With equal weights this is exactly [totalLeastSquares].
     *
     * Pairs are taken index by index up to the shorter of the two lists; pairs with a non-finite point or a
     * non-finite / non-positive weight are ignored. Returns null for fewer than 2 usable pairs or a
     * degenerate (all-coincident) set.
     */
    fun weightedTotalLeastSquares(points: List<Vec2>, weights: List<Double>): Line2? {
        val n = minOf(points.size, weights.size)
        var count = 0
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            val p = points[i]
            val w = weights[i]
            if (!p.isFinite() || !(w > 0.0) || w.isInfinite()) continue
            sw += w
            sx += w * p.x
            sy += w * p.y
            count++
        }
        if (count < 2 || !(sw > 0.0)) return null
        val mx = sx / sw
        val my = sy / sw
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val p = points[i]
            val w = weights[i]
            if (!p.isFinite() || !(w > 0.0) || w.isInfinite()) continue
            val dx = p.x - mx
            val dy = p.y - my
            sxx += w * dx * dx
            sxy += w * dx * dy
            syy += w * dy * dy
        }
        // lineFromMoments expects sums over n points; pass the weighted sums normalized to a mean.
        return lineFromMoments(1, mx, my, sxx / sw, sxy / sw, syy / sw)
    }

    /**
     * Maximum-likelihood line for depth samples whose error lies ALONG the viewing ray from [viewer]
     * (depth sensors and depth-from-motion measure range, not position).
     *
     * Orthogonal regression (TLS) assumes isotropic noise. With range noise and a wall seen at incidence
     * angle α, every sample is displaced along its ray, which has a component along the wall: the noise
     * covariance then tilts the principal axis towards the viewing direction by roughly
     * σ²·sin α·cos α / var(t) radians (≈ 0.1–0.2° for a 45° view at 2–4 m), which squaring turns into
     * centimetres at the far corners. This fit instead minimizes Σ wᵢ·eᵢ² where
     * eᵢ = (n·pᵢ − c) / (n·r̂ᵢ) is the distance from pᵢ to the line measured along its own ray r̂ᵢ
     * (unit vector from [viewer] to pᵢ), i.e. the actual depth error. Gauss–Newton on (normal angle,
     * offset) from [initial], with step halving so the cost never increases.
     *
     * * |n·r̂ᵢ| is floored at [minCos] (rays within ~acos(minCos) of grazing), so near-grazing samples cannot
     *   blow up; such samples are down-weighted by 1/minCos² instead.
     * * Pairs with a non-finite point, a point at the viewer, or a non-finite / non-positive weight are
     *   ignored (see [weightedTotalLeastSquares] for the pairing rule).
     * * Returns null for fewer than 2 usable samples, non-finite input, or a numerically singular system;
     *   callers then keep [initial]. The returned line's point is on the fitted line near the samples.
     */
    fun fitAlongRays(
        points: List<Vec2>,
        weights: List<Double>,
        viewer: Vec2,
        initial: Line2,
        minCos: Double = 0.2,
        maxIterations: Int = 10,
    ): Line2? {
        if (!viewer.isFinite() || !initial.point.isFinite() || !initial.direction.isFinite()) return null
        val floor = minCos.takeIf { it.isFinite() }?.coerceIn(1e-3, 1.0) ?: 0.2
        val count = minOf(points.size, weights.size)
        val qx = DoubleArray(count)
        val qy = DoubleArray(count)
        val rx = DoubleArray(count)
        val ry = DoubleArray(count)
        val ws = DoubleArray(count)
        var m = 0
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until count) {
            val p = points[i]
            val w = weights[i]
            if (!p.isFinite() || !(w > 0.0) || w.isInfinite()) continue
            val dx = p.x - viewer.x
            val dy = p.y - viewer.y
            val len = sqrt(dx * dx + dy * dy)
            if (!(len > 1e-9)) continue
            qx[m] = p.x
            qy[m] = p.y
            rx[m] = dx / len
            ry[m] = dy / len
            ws[m] = w
            sw += w
            sx += w * p.x
            sy += w * p.y
            m++
        }
        if (m < 2 || !(sw > 0.0)) return null
        // Center on the weighted centroid for conditioning.
        val mx = sx / sw
        val my = sy / sw
        for (i in 0 until m) {
            qx[i] -= mx
            qy[i] -= my
        }
        val n0 = initial.normal
        var phi = atan2(n0.y, n0.x)
        var c = n0.x * (initial.point.x - mx) + n0.y * (initial.point.y - my)

        fun cost(phiV: Double, cV: Double): Double {
            val nx = cos(phiV)
            val ny = sin(phiV)
            var s = 0.0
            for (i in 0 until m) {
                var g = nx * rx[i] + ny * ry[i]
                if (abs(g) < floor) g = if (g < 0) -floor else floor
                val e = (nx * qx[i] + ny * qy[i] - cV) / g
                s += ws[i] * e * e
            }
            return s
        }

        var current = cost(phi, c)
        if (!current.isFinite()) return null
        for (iter in 0 until maxIterations.coerceAtLeast(0)) {
            val nx = cos(phi)
            val ny = sin(phi)
            var aPP = 0.0
            var aPC = 0.0
            var aCC = 0.0
            var bP = 0.0
            var bC = 0.0
            for (i in 0 until m) {
                val gRaw = nx * rx[i] + ny * ry[i]
                val clamped = abs(gRaw) < floor
                val g = if (!clamped) gRaw else if (gRaw < 0) -floor else floor
                // dn/dphi = (-ny, nx)
                val dg = if (clamped) 0.0 else -ny * rx[i] + nx * ry[i]
                val u = nx * qx[i] + ny * qy[i] - c
                val du = -ny * qx[i] + nx * qy[i]
                val e = u / g
                val jP = (du * g - u * dg) / (g * g)
                val jC = -1.0 / g
                val w = ws[i]
                aPP += w * jP * jP
                aPC += w * jP * jC
                aCC += w * jC * jC
                bP -= w * jP * e
                bC -= w * jC * e
            }
            val det = aPP * aCC - aPC * aPC
            if (!(abs(det) > 1e-300) || !det.isFinite()) return null
            var dPhi = (bP * aCC - bC * aPC) / det
            var dC = (aPP * bC - aPC * bP) / det
            if (!dPhi.isFinite() || !dC.isFinite()) return null
            var accepted = false
            for (halving in 0 until 8) {
                val next = cost(phi + dPhi, c + dC)
                if (next.isFinite() && next <= current) {
                    phi += dPhi
                    c += dC
                    current = next
                    accepted = true
                    break
                }
                dPhi *= 0.5
                dC *= 0.5
            }
            if (!accepted || (abs(dPhi) < 1e-12 && abs(dC) < 1e-12)) break
        }
        val nx = cos(phi)
        val ny = sin(phi)
        val point = Vec2(mx + nx * c, my + ny * c)
        // Direction d with d.perp() == n.
        return Line2(point, Vec2(ny, -nx))
    }

    /**
     * Deterministic RANSAC (seeded RNG) followed by TLS refinement on the inliers
     * (re-selecting inliers against the refined line, up to 3 refinement passes).
     * Returns null when no line reaches [minInliers].
     *
     * Details:
     * * Non-finite points are ignored (they still count towards [LineFitResult.totalCount]).
     * * Each iteration draws two distinct points with `java.util.Random(seed)`; pairs closer than
     *   2·[inlierThreshold] give a poorly-conditioned direction and are redrawn (a bounded number of
     *   times, after which the iteration is skipped).
     * * A point is an inlier when its perpendicular distance is ≤ [inlierThreshold]. The hypothesis
     *   with the most inliers wins; ties go to the smaller sum of absolute residuals.
     * * Refinement: TLS on the inliers, then inliers are re-selected against the refined line; this
     *   repeats until the inlier set is stable (max 3 passes). A refinement that would drop below
     *   [minInliers] is discarded. Every returned inlier is within [inlierThreshold] of the returned
     *   line, and [LineFitResult.rmsResidual], [LineFitResult.tMin] and [LineFitResult.tMax] are
     *   computed against that same line.
     * * Invalid parameters (non-positive / non-finite threshold, no iterations) yield null.
     *
     * Cost is O(iterations · n); 5000 points × 300 iterations runs in a few milliseconds.
     */
    fun ransac(
        points: List<Vec2>,
        inlierThreshold: Double = 0.025,
        iterations: Int = 300,
        minInliers: Int = 12,
        seed: Long = 1L,
    ): LineFitResult? {
        if (!(inlierThreshold > 0.0) || inlierThreshold.isInfinite() || iterations <= 0) return null
        val required = minInliers.coerceAtLeast(2)

        val finite = ArrayList<Vec2>(points.size)
        for (p in points) if (p.isFinite()) finite.add(p)
        val n = finite.size
        if (n < required) return null
        val xs = DoubleArray(n) { finite[it].x }
        val ys = DoubleArray(n) { finite[it].y }

        val minPairDistSq = (2.0 * inlierThreshold) * (2.0 * inlierThreshold)
        val random = java.util.Random(seed)

        var bestCount = 0
        var bestResidual = Double.POSITIVE_INFINITY
        var bestNx = 0.0
        var bestNy = 0.0
        var bestC = 0.0

        for (iter in 0 until iterations) {
            // Draw a well-separated pair of distinct indices.
            var i = -1
            var j = -1
            for (attempt in 0 until MAX_SAMPLE_ATTEMPTS) {
                val a = random.nextInt(n)
                var b = random.nextInt(n - 1)
                if (b >= a) b++
                val dx = xs[b] - xs[a]
                val dy = ys[b] - ys[a]
                if (dx * dx + dy * dy >= minPairDistSq) {
                    i = a
                    j = b
                    break
                }
            }
            if (i < 0) continue

            val dx = xs[j] - xs[i]
            val dy = ys[j] - ys[i]
            val len = sqrt(dx * dx + dy * dy)
            // Unit normal of the hypothesis line; distance(p) = nx*x + ny*y - c.
            val nx = -dy / len
            val ny = dx / len
            val c = nx * xs[i] + ny * ys[i]

            var count = 0
            var residual = 0.0
            for (k in 0 until n) {
                val d = abs(nx * xs[k] + ny * ys[k] - c)
                if (d <= inlierThreshold) {
                    count++
                    residual += d
                }
            }
            if (count > bestCount || (count == bestCount && count > 0 && residual < bestResidual)) {
                bestCount = count
                bestResidual = residual
                bestNx = nx
                bestNy = ny
                bestC = c
            }
        }
        if (bestCount < required) return null

        // Hypothesis line in Line2 form: direction = normal rotated -90deg so that Line2.normal == (nx, ny).
        var line = Line2(Vec2(bestNx * bestC, bestNy * bestC), Vec2(bestNy, -bestNx))
        var inliers = selectInliers(xs, ys, line, inlierThreshold)

        for (pass in 0 until REFINE_PASSES) {
            val refined = fitIndices(xs, ys, inliers) ?: break
            val reselected = selectInliers(xs, ys, refined, inlierThreshold)
            if (reselected.size < required) break
            val stable = reselected.contentEquals(inliers)
            line = refined
            inliers = reselected
            if (stable) break
        }

        var sumSq = 0.0
        var tMin = Double.POSITIVE_INFINITY
        var tMax = Double.NEGATIVE_INFINITY
        val nrm = line.normal
        val dir = line.direction
        for (k in inliers) {
            val rx = xs[k] - line.point.x
            val ry = ys[k] - line.point.y
            val d = rx * nrm.x + ry * nrm.y
            sumSq += d * d
            val t = rx * dir.x + ry * dir.y
            if (t < tMin) tMin = t
            if (t > tMax) tMax = t
        }
        return LineFitResult(
            line = line,
            inliers = inliers.map { finite[it] },
            rmsResidual = sqrt(sumSq / inliers.size),
            totalCount = points.size,
            tMin = tMin,
            tMax = tMax,
        )
    }

    /** Indices (ascending) of the points within [threshold] of [line]. */
    private fun selectInliers(xs: DoubleArray, ys: DoubleArray, line: Line2, threshold: Double): IntArray {
        val nx = line.normal.x
        val ny = line.normal.y
        val c = nx * line.point.x + ny * line.point.y
        var count = 0
        val buf = IntArray(xs.size)
        for (k in xs.indices) {
            if (abs(nx * xs[k] + ny * ys[k] - c) <= threshold) buf[count++] = k
        }
        return buf.copyOf(count)
    }

    /** TLS over the subset [indices] of the coordinate arrays. */
    private fun fitIndices(xs: DoubleArray, ys: DoubleArray, indices: IntArray): Line2? {
        val n = indices.size
        if (n < 2) return null
        var sx = 0.0
        var sy = 0.0
        for (k in indices) {
            sx += xs[k]
            sy += ys[k]
        }
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (k in indices) {
            val dx = xs[k] - mx
            val dy = ys[k] - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        return lineFromMoments(n, mx, my, sxx, sxy, syy)
    }

    /** Principal-axis line from centered second moments (sums, not means). */
    private fun lineFromMoments(n: Int, mx: Double, my: Double, sxx: Double, sxy: Double, syy: Double): Line2? {
        if (!((sxx + syy) / n >= MIN_MEAN_SQUARED_SPREAD)) return null // also rejects NaN
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        return Line2(Vec2(mx, my), Vec2(cos(theta), sin(theta)))
    }
}
