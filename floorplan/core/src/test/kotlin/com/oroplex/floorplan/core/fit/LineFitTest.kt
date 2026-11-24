package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineFitTest {

    /** Angle between two undirected lines, degrees. */
    private fun angleDeg(a: Line2, b: Line2): Double = Math.toDegrees(asin(a.sinAngleTo(b).coerceAtMost(1.0)))

    private fun noisyLine(
        rnd: Random,
        truth: Line2,
        count: Int,
        tRange: ClosedFloatingPointRange<Double>,
        sigma: Double,
    ): List<Vec2> = List(count) {
        val t = tRange.start + rnd.nextDouble() * (tRange.endInclusive - tRange.start)
        truth.at(t) + truth.normal * (rnd.nextGaussian() * sigma)
    }

    // ---------------------------------------------------------------- totalLeastSquares

    @Test fun tlsExactLineThroughPoints() {
        val pts = (0..10).map { Vec2(it * 0.3, 0.5 * it * 0.3 + 1.0) }
        val line = assertNotNull(LineFit.totalLeastSquares(pts))
        pts.forEach { assertEquals(0.0, line.distance(it), 1e-12) }
        assertEquals(1.0, line.direction.length, 1e-12)
        assertEquals(0.0, abs(line.direction cross Vec2(2.0, 1.0).normalized()), 1e-12)
        // Anchor is the centroid.
        assertTrue(line.point.approxEquals(Vec2(1.5, 1.75), 1e-12))
    }

    @Test fun tlsHandlesVerticalAndHorizontalLines() {
        val vertical = assertNotNull(LineFit.totalLeastSquares(listOf(Vec2(2.0, -1.0), Vec2(2.0, 0.0), Vec2(2.0, 3.0))))
        assertEquals(1.0, abs(vertical.direction.y), 1e-12)
        assertEquals(0.0, vertical.distance(Vec2(2.0, 100.0)), 1e-9)
        val horizontal = assertNotNull(LineFit.totalLeastSquares(listOf(Vec2(-5.0, 1.0), Vec2(7.0, 1.0))))
        assertEquals(1.0, abs(horizontal.direction.x), 1e-12)
    }

    @Test fun tlsNoisyLineIsAccurate() {
        val rnd = Random(42)
        val truth = Line2.fromAngle(Vec2(1.0, -2.0), Math.toRadians(37.0))
        val pts = noisyLine(rnd, truth, 800, -2.0..2.0, 0.01)
        val fit = assertNotNull(LineFit.totalLeastSquares(pts))
        assertTrue(angleDeg(fit, truth) < 0.2, "angle error ${angleDeg(fit, truth)}")
        assertTrue(abs(truth.signedDistance(fit.point)) < 0.002)
        // TLS minimizes orthogonal distances: it beats the true line on its own data.
        val fitSse = pts.sumOf { fit.distance(it).let { d -> d * d } }
        val truthSse = pts.sumOf { truth.distance(it).let { d -> d * d } }
        assertTrue(fitSse <= truthSse + 1e-12)
    }

    @Test fun tlsIsOrthogonalNotOrdinaryRegression() {
        // A steep noisy line: y-on-x regression would flatten it noticeably; TLS must not.
        val rnd = Random(3)
        val truth = Line2.fromAngle(Vec2.ZERO, Math.toRadians(80.0))
        val pts = noisyLine(rnd, truth, 2000, -1.0..1.0, 0.05)
        val fit = assertNotNull(LineFit.totalLeastSquares(pts))
        assertTrue(angleDeg(fit, truth) < 1.0, "angle error ${angleDeg(fit, truth)}")
    }

    @Test fun tlsDegenerateInputs() {
        assertNull(LineFit.totalLeastSquares(emptyList()))
        assertNull(LineFit.totalLeastSquares(listOf(Vec2(1.0, 2.0))))
        assertNull(LineFit.totalLeastSquares(List(5) { Vec2(0.1, 0.7) }))
        // Non-finite points are ignored; one finite point left -> null.
        assertNull(LineFit.totalLeastSquares(listOf(Vec2(1.0, 1.0), Vec2(Double.NaN, 0.0))))
        val withNan = LineFit.totalLeastSquares(listOf(Vec2(0.0, 0.0), Vec2(Double.POSITIVE_INFINITY, 1.0), Vec2(1.0, 0.0)))
        assertNotNull(withNan)
        assertEquals(1.0, abs(withNan.direction.x), 1e-12)
    }

    // ---------------------------------------------------------------- ransac

    /** 60 % inliers on a 4 m line with 5 mm noise, 40 % uniform outliers in a 6 x 6 m box. */
    private fun outlierScene(seed: Long, truth: Line2, inliers: Int = 600, outliers: Int = 400): List<Vec2> {
        val rnd = Random(seed)
        val pts = ArrayList<Vec2>()
        pts += noisyLine(rnd, truth, inliers, -2.0..2.0, 0.005)
        repeat(outliers) { pts += Vec2(rnd.nextDouble() * 6 - 3, rnd.nextDouble() * 6 - 3) + truth.point }
        java.util.Collections.shuffle(pts, rnd)
        return pts
    }

    @Test fun ransacRecoversLineWithFortyPercentOutliers() {
        val truth = Line2.fromAngle(Vec2(0.4, 1.1), Math.toRadians(-23.0))
        for (seed in 1L..5L) {
            val pts = outlierScene(100 + seed, truth)
            val fit = assertNotNull(LineFit.ransac(pts, inlierThreshold = 0.025, iterations = 300, minInliers = 12, seed = seed))
            // Offset: the true line's points at both ends of the observed span lie within 5 mm of the fit.
            assertTrue(fit.line.distance(truth.at(-2.0)) < 0.005, "seed $seed end A off by ${fit.line.distance(truth.at(-2.0))}")
            assertTrue(fit.line.distance(truth.at(2.0)) < 0.005, "seed $seed end B off by ${fit.line.distance(truth.at(2.0))}")
            assertTrue(angleDeg(fit.line, truth) < 0.3, "seed $seed angle ${angleDeg(fit.line, truth)}")
            assertTrue(fit.inlierCount >= 600, "inliers ${fit.inlierCount}") // all true inliers + a few outliers
            assertTrue(fit.inlierCount < 660)
            assertEquals(1000, fit.totalCount)
            assertEquals(fit.inlierCount / 1000.0, fit.inlierRatio, 1e-12)
            assertTrue(fit.rmsResidual < 0.008)
        }
    }

    @Test fun ransacResultIsInternallyConsistent() {
        val truth = Line2.fromAngle(Vec2(-1.0, 0.0), 1.2)
        val pts = outlierScene(7, truth)
        val fit = assertNotNull(LineFit.ransac(pts))
        // Every inlier is within the threshold of the returned line.
        fit.inliers.forEach { assertTrue(fit.line.distance(it) <= 0.025 + 1e-12) }
        // ...and every point within the threshold is an inlier (stable selection).
        assertEquals(pts.count { fit.line.distance(it) <= 0.025 }, fit.inlierCount)
        val rms = sqrt(fit.inliers.sumOf { fit.line.distance(it).let { d -> d * d } } / fit.inlierCount)
        assertEquals(rms, fit.rmsResidual, 1e-12)
        assertEquals(fit.inliers.minOf { fit.line.param(it) }, fit.tMin, 1e-12)
        assertEquals(fit.inliers.maxOf { fit.line.param(it) }, fit.tMax, 1e-12)
        // At least the 4 m of true inliers (stray outliers on the line inside the 6 m box may extend it).
        assertTrue(fit.tMax - fit.tMin > 3.9 && fit.tMax - fit.tMin < 6.0 * sqrt(2.0))
        assertEquals(1.0, fit.line.direction.length, 1e-12)
    }

    @Test fun ransacIsDeterministic() {
        val truth = Line2.fromAngle(Vec2(2.0, 2.0), 0.3)
        val pts = outlierScene(11, truth)
        val a = LineFit.ransac(pts, seed = 99L)
        val b = LineFit.ransac(pts, seed = 99L)
        assertNotNull(a)
        assertEquals(a, b)
        // A different seed explores different hypotheses but converges to (nearly) the same line.
        val c = assertNotNull(LineFit.ransac(pts, seed = 12345L))
        assertTrue(angleDeg(a.line, c.line) < 0.05)
        assertTrue(a.line.distance(c.line.point) < 0.002)
    }

    @Test fun ransacPicksTheLargerOfTwoLines() {
        val rnd = Random(5)
        val big = Line2.fromAngle(Vec2.ZERO, 0.0)
        val small = Line2.fromAngle(Vec2(0.0, 1.0), PI / 2)
        val pts = noisyLine(rnd, big, 300, -2.0..2.0, 0.004) + noisyLine(rnd, small, 150, -1.0..1.0, 0.004)
        val fit = assertNotNull(LineFit.ransac(pts))
        assertTrue(angleDeg(fit.line, big) < 0.3)
        assertTrue(fit.inlierCount in 300..310)
    }

    @Test fun ransacReturnsNullWhenNoLineHasEnoughSupport() {
        val rnd = Random(8)
        assertNull(LineFit.ransac(List(5) { Vec2(it.toDouble(), 0.0) }, minInliers = 12)) // too few points
        assertNull(LineFit.ransac(emptyList()))
        val scatter = List(200) { Vec2(rnd.nextDouble() * 4, rnd.nextDouble() * 4) }
        assertNull(LineFit.ransac(scatter, minInliers = 40))
        // All points inside a 2*threshold blob: no well-separated sample pair exists.
        val blob = List(100) { Vec2(rnd.nextDouble() * 0.02, rnd.nextDouble() * 0.02) }
        assertNull(LineFit.ransac(blob))
    }

    @Test fun ransacIgnoresNonFinitePointsAndBadParameters() {
        val rnd = Random(9)
        val truth = Line2.fromAngle(Vec2.ZERO, 0.5)
        val pts = noisyLine(rnd, truth, 100, -1.0..1.0, 0.003) +
            listOf(Vec2(Double.NaN, 0.0), Vec2(0.0, Double.POSITIVE_INFINITY))
        val fit = assertNotNull(LineFit.ransac(pts))
        assertEquals(100, fit.inlierCount)
        assertEquals(102, fit.totalCount)
        assertTrue(fit.rmsResidual.isFinite())
        assertNull(LineFit.ransac(pts, inlierThreshold = 0.0))
        assertNull(LineFit.ransac(pts, inlierThreshold = Double.NaN))
        assertNull(LineFit.ransac(pts, iterations = 0))
    }

    @Test fun ransacExactlyCollinearPoints() {
        val pts = (0 until 30).map { Vec2(it * 0.1, -it * 0.2) }
        val fit = assertNotNull(LineFit.ransac(pts))
        assertEquals(30, fit.inlierCount)
        assertEquals(0.0, fit.rmsResidual, 1e-12)
        assertEquals(sqrt(2.9 * 2.9 + 5.8 * 5.8), fit.tMax - fit.tMin, 1e-9)
    }

    @Test fun ransacIsFastOnLargeInputs() {
        val truth = Line2.fromAngle(Vec2(0.0, 0.0), 0.7)
        val pts = outlierScene(21, truth, inliers = 3500, outliers = 1500)
        repeat(5) { LineFit.ransac(pts, iterations = 300) } // JIT warm-up
        // Minimum of several runs is robust to other processes loading the machine.
        val best = (0 until 7).minOf {
            val t0 = System.nanoTime()
            LineFit.ransac(pts, iterations = 300)
            System.nanoTime() - t0
        }
        assertTrue(best < 50_000_000L, "5000 points x 300 iterations took ${best / 1e6} ms")
    }
}
