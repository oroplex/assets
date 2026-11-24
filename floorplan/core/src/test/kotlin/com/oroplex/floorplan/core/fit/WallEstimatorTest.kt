package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import java.util.Random
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WallEstimatorTest {
    private val floorY = -1.42
    private val eyeHeight = 1.45

    /**
     * A synthetic capture: a wall whose interior face passes through [center] with direction [dir];
     * the viewer stands [viewerDistance] in front of it on the LEFT of [dir] (so [dir] is the expected
     * orientation of the result).
     */
    private class Scene(val center: Vec2, val dir: Vec2, val viewerPlan: Vec2, val viewerWorld: Vec3) {
        val normal: Vec2 get() = dir.perp()
        val truth: Line2 get() = Line2(center, dir)
    }

    private fun scene(angleDeg: Double = 20.0, viewerPlan: Vec2 = Vec2(0.3, -0.2), viewerDistance: Double = 2.6): Scene {
        val dir = Vec2.fromAngle(Math.toRadians(angleDeg))
        val center = viewerPlan - dir.perp() * viewerDistance
        return Scene(center, dir, viewerPlan, Vec3.fromPlan(viewerPlan, floorY + eyeHeight))
    }

    private fun world(p: Vec2, height: Double) = Vec3.fromPlan(p, floorY + height)

    /** Points on the wall face: along-wall s in [sMin, sMax], heights in [hMin, hMax], Gaussian depth noise. */
    private fun wallPoints(
        rnd: Random,
        s: Scene,
        count: Int,
        sigma: Double,
        offset: Double = 0.0,
        sRange: ClosedFloatingPointRange<Double> = -1.5..1.5,
        hRange: ClosedFloatingPointRange<Double> = 0.0..2.4,
        occluded: (Double, Double) -> Boolean = { _, _ -> false },
    ): List<Vec3> {
        val out = ArrayList<Vec3>(count)
        while (out.size < count) {
            val along = sRange.start + rnd.nextDouble() * (sRange.endInclusive - sRange.start)
            val h = hRange.start + rnd.nextDouble() * (hRange.endInclusive - hRange.start)
            if (occluded(along, h)) continue
            val p = s.center + s.dir * along + s.normal * (offset + rnd.nextGaussian() * sigma)
            out += world(p, h)
        }
        return out
    }

    /** Depth outliers: random points along the viewing rays, anywhere from 10 % to 115 % of the way to the wall. */
    private fun rayOutliers(rnd: Random, s: Scene, count: Int): List<Vec3> = List(count) {
        val onWall = s.center + s.dir * (rnd.nextDouble() * 3.0 - 1.5)
        val f = 0.1 + rnd.nextDouble() * 1.05
        world(Vec2.lerp(s.viewerPlan, onWall, f), rnd.nextDouble() * 2.4)
    }

    private fun angleDeg(a: Vec2, b: Vec2): Double = Math.toDegrees(asin(abs(a cross b).coerceAtMost(1.0)))

    private fun assertOrientedViewerLeft(o: WallObservation, viewerPlan: Vec2) {
        assertTrue(o.line.signedDistance(viewerPlan) > 0, "viewer must be on the LEFT of the wall line")
        assertTrue((o.line.normal dot (viewerPlan - o.line.point)) > 0, "normal must point into the room")
        assertTrue(o.line.param(o.extentStart) < o.line.param(o.extentEnd), "extent must run along +direction")
        assertEquals(0.0, o.line.distance(o.extentStart), 1e-9)
        assertEquals(0.0, o.line.distance(o.extentEnd), 1e-9)
        assertEquals(1.0, o.line.direction.length, 1e-9)
        // approxEquals: Vec3(.., z = 0.0).toPlan() yields y = -0.0, which data-class equality treats as != 0.0.
        assertTrue(o.viewer.approxEquals(viewerPlan, 1e-12), "viewer ${o.viewer} != $viewerPlan")
    }

    /** Fitted line within [offsetTol] m of truth over the whole observed span and within [angleTolDeg]. */
    private fun assertMatchesTruth(o: WallObservation, s: Scene, offsetTol: Double = 0.01, angleTolDeg: Double = 0.5) {
        assertTrue((o.line.direction dot s.dir) > 0, "direction should match the expected orientation")
        val angle = angleDeg(o.line.direction, s.dir)
        assertTrue(angle < angleTolDeg, "angle error $angle deg")
        for (t in listOf(-1.5, 0.0, 1.5)) {
            val d = o.line.distance(s.center + s.dir * t)
            assertTrue(d < offsetTol, "offset error $d m at s=$t")
        }
    }

    // ---------------------------------------------------------------- fromPoints: realistic scenes

    @Test fun wallBehindCouchIsPreferredOverTheCouch() {
        val rnd = Random(2024)
        val s = scene()
        // The couch (0.8 m in front of the wall) hides the lower wall behind it.
        val couchS = -0.9..0.6
        val wall = wallPoints(rnd, s, 1500, sigma = 0.015) { along, h -> along in -1.1..0.8 && h < 0.66 }
        val couch = wallPoints(rnd, s, 1600, sigma = 0.01, offset = 0.8, sRange = couchS, hRange = 0.2..0.9)
        val outliers = rayOutliers(rnd, s, (wall.size + couch.size) / 4) // 20 % of all points
        val pts = (wall + couch + outliers).shuffled(rnd)

        // Sanity of the scene: the couch alone IS the dominant line.
        val planInBand = pts.filter { it.y - floorY in 0.2..2.25 }.map { it.toPlan() }
        val dominant = assertNotNull(LineFit.ransac(planInBand, 0.025, 300, 25, 1L))
        assertTrue(abs(s.truth.signedDistance(dominant.line.point) - 0.8) < 0.05, "scene: couch should dominate")

        val fit = WallEstimator.fromPoints(pts, floorY, null, s.viewerWorld)
        val o = assertNotNull(fit.observation, "failure: ${fit.failure}")
        assertNull(fit.failure)
        assertMatchesTruth(o, s)
        assertOrientedViewerLeft(o, s.viewerPlan)
        assertEquals(WallSource.DEPTH_FIT, o.source)
        assertEquals(o.pointCount, fit.usedPoints)
        assertEquals(pts.size, fit.totalPoints)
        assertTrue(o.rmsResidual in 0.005..0.02, "rms ${o.rmsResidual}")
        assertTrue(o.confidence in 0.5..1.0, "confidence ${o.confidence}")
        // Extents cover the visible wall (s in [-1.5, 1.5]).
        assertEquals(-1.5, s.truth.param(o.extentStart), 0.05)
        assertEquals(1.5, s.truth.param(o.extentEnd), 0.05)
    }

    @Test fun plainWallWithNoiseAndOutliers() {
        val rnd = Random(7)
        val s = scene(angleDeg = -63.0, viewerPlan = Vec2(-1.0, 2.0), viewerDistance = 2.0)
        val wall = wallPoints(rnd, s, 1200, sigma = 0.015)
        val pts = (wall + rayOutliers(rnd, s, 300)).shuffled(rnd)
        val fit = WallEstimator.fromPoints(pts, floorY, floorY + 2.5, s.viewerWorld)
        val o = assertNotNull(fit.observation, "failure: ${fit.failure}")
        assertMatchesTruth(o, s)
        assertOrientedViewerLeft(o, s.viewerPlan)
        assertEquals(3.0, o.extentStart.distanceTo(o.extentEnd), 0.08)
        assertTrue(o.confidence > 0.6, "confidence ${o.confidence}")
    }

    @Test fun orientationFollowsTheViewerSide() {
        val rnd = Random(11)
        val s = scene(angleDeg = 90.0)
        val pts = wallPoints(rnd, s, 600, sigma = 0.01)
        val front = assertNotNull(WallEstimator.fromPoints(pts, floorY, null, s.viewerWorld).observation)
        assertOrientedViewerLeft(front, s.viewerPlan)
        // Same points seen from the other side of the (infinite) wall line.
        val otherViewerPlan = s.center + s.normal * -2.0
        val back = assertNotNull(WallEstimator.fromPoints(pts, floorY, null, Vec3.fromPlan(otherViewerPlan, 0.0)).observation)
        assertOrientedViewerLeft(back, otherViewerPlan)
        assertTrue((front.line.direction dot back.line.direction) < -0.999)
        // Same observed span either way, just reversed.
        assertTrue(front.extentStart.approxEquals(back.extentEnd, 1e-6))
        assertTrue(front.extentEnd.approxEquals(back.extentStart, 1e-6))
    }

    @Test fun nearWallIsKeptWhenFartherLineIsWeak() {
        // A doorway shows a little of a parallel wall 1.5 m beyond: too few points to win.
        val rnd = Random(12)
        val s = scene()
        val wall = wallPoints(rnd, s, 900, sigma = 0.01) { along, h -> along in 0.0..0.9 && h < 2.03 }
        val beyond = wallPoints(rnd, s, 250, sigma = 0.01, offset = -1.5, sRange = 0.1..0.9, hRange = 0.0..2.0)
        val o = assertNotNull(WallEstimator.fromPoints(wall + beyond, floorY, null, s.viewerWorld).observation)
        assertMatchesTruth(o, s)
    }

    @Test fun parallelWallBehindTheViewerIsNotPreferred() {
        // Hallway: the viewer sees both walls; the farther one is on the OTHER side, so it never wins
        // through the "prefer farther" rule even with plenty of points.
        val rnd = Random(13)
        val s = scene(viewerDistance = 1.0)
        val front = wallPoints(rnd, s, 900, sigma = 0.01)
        val behind = wallPoints(rnd, s, 800, sigma = 0.01, offset = 2.6)
        val o = assertNotNull(WallEstimator.fromPoints(front + behind, floorY, null, s.viewerWorld).observation)
        assertMatchesTruth(o, s)
        assertOrientedViewerLeft(o, s.viewerPlan)
    }

    @Test fun rangeFilterDropsDistantStructure() {
        // A big, farther parallel surface 7 m away would win "prefer farther" if it were not out of range.
        val rnd = Random(14)
        val s = scene(viewerDistance = 1.5)
        val near = wallPoints(rnd, s, 500, sigma = 0.01)
        val far = wallPoints(rnd, s, 1500, sigma = 0.01, offset = -5.5, sRange = -4.0..4.0)
        val fit = WallEstimator.fromPoints(near + far, floorY, null, s.viewerWorld)
        val o = assertNotNull(fit.observation)
        assertMatchesTruth(o, s)
        assertTrue(fit.usedPoints <= 500)
    }

    // ---------------------------------------------------------------- fromPoints: height band

    @Test fun pointsBelowTheBandAreIgnored() {
        val rnd = Random(21)
        val s = scene()
        val wall = wallPoints(rnd, s, 400, sigma = 0.01, hRange = 0.3..2.2)
        // Dense low clutter (floor bleed / low table edge) 0.5 m in front of the wall, below 0.2 m.
        val low = wallPoints(rnd, s, 1500, sigma = 0.005, offset = 0.5, hRange = 0.0..0.19)
        val fit = WallEstimator.fromPoints(wall + low, floorY, null, s.viewerWorld)
        val o = assertNotNull(fit.observation)
        assertMatchesTruth(o, s)
        assertTrue(fit.usedPoints <= 400)
    }

    @Test fun measuredCeilingExcludesSoffitPoints() {
        val rnd = Random(22)
        val s = scene()
        val ceilingHeight = 2.0
        val wall = wallPoints(rnd, s, 500, sigma = 0.01, hRange = 0.3..1.8)
        // A bulkhead face 0.4 m in front of the wall, just under the measured ceiling (above 2.0 - 0.15).
        val soffit = wallPoints(rnd, s, 1500, sigma = 0.005, offset = 0.4, hRange = 1.87..1.99)
        val pts = wall + soffit

        val withCeiling = assertNotNull(WallEstimator.fromPoints(pts, floorY, floorY + ceilingHeight, s.viewerWorld).observation)
        assertMatchesTruth(withCeiling, s)

        // Without a measured ceiling the assumed 2.4 m keeps the soffit in the band and it dominates.
        val assumed = assertNotNull(WallEstimator.fromPoints(pts, floorY, null, s.viewerWorld).observation)
        assertEquals(0.4, s.truth.signedDistance(assumed.line.point), 0.01)

        // A nonsensical ceiling (below the floor) falls back to the assumed height instead of failing.
        val bogus = WallEstimator.fromPoints(pts, floorY, floorY - 1.0, s.viewerWorld)
        assertNotNull(bogus.observation)
        val nan = WallEstimator.fromPoints(pts, floorY, Double.NaN, s.viewerWorld)
        assertNotNull(nan.observation)
    }

    // ---------------------------------------------------------------- fromPoints: failures

    @Test fun tooFewPoints() {
        val rnd = Random(31)
        val s = scene()
        val few = WallEstimator.fromPoints(wallPoints(rnd, s, 10, sigma = 0.01, hRange = 0.5..2.0), floorY, null, s.viewerWorld)
        assertEquals(WallFitFailure.TOO_FEW_POINTS, few.failure)
        assertNull(few.observation)
        assertEquals(10, few.totalPoints)

        val empty = WallEstimator.fromPoints(emptyList(), floorY, null, s.viewerWorld)
        assertEquals(WallFitFailure.TOO_FEW_POINTS, empty.failure)
        assertEquals(0, empty.totalPoints)

        // Plenty of points, but all on the floor (outside the height band): not a range problem.
        val onFloor = WallEstimator.fromPoints(wallPoints(rnd, s, 500, sigma = 0.01, hRange = 0.0..0.1), floorY, null, s.viewerWorld)
        assertEquals(WallFitFailure.TOO_FEW_POINTS, onFloor.failure)
        assertEquals(0, onFloor.usedPoints)

        // Non-finite samples and a broken viewer never crash.
        val nanPts = List(100) { Vec3(Double.NaN, 0.0, 1.0) }
        assertEquals(WallFitFailure.TOO_FEW_POINTS, WallEstimator.fromPoints(nanPts, floorY, null, s.viewerWorld).failure)
        val badViewer = Vec3(Double.NaN, 0.0, 0.0)
        assertEquals(WallFitFailure.TOO_FEW_POINTS, WallEstimator.fromPoints(wallPoints(rnd, s, 300, 0.01), floorY, null, badViewer).failure)
    }

    @Test fun tooFar() {
        val rnd = Random(32)
        val s = scene(viewerDistance = 8.0)
        val fit = WallEstimator.fromPoints(wallPoints(rnd, s, 500, sigma = 0.01), floorY, null, s.viewerWorld)
        assertEquals(WallFitFailure.TOO_FAR, fit.failure)
        assertEquals(0, fit.usedPoints)
        assertEquals(500, fit.totalPoints)
        // A smaller maxRange applies too.
        val near = scene(viewerDistance = 3.0)
        val tight = WallEstimator.fromPoints(wallPoints(rnd, near, 500, 0.01), floorY, null, near.viewerWorld, WallEstimatorConfig(maxRange = 2.0))
        assertEquals(WallFitFailure.TOO_FAR, tight.failure)
    }

    @Test fun noDominantPlane() {
        val rnd = Random(33)
        val viewer = Vec3(0.0, floorY + eyeHeight, 0.0)
        fun scatter(n: Int) = List(n) { Vec3(rnd.nextDouble() * 4 - 2, floorY + 0.3 + rnd.nextDouble() * 1.9, -(0.5 + rnd.nextDouble() * 4)) }
        // Sparse cloud: no strip reaches minInliers.
        assertEquals(WallFitFailure.NO_DOMINANT_PLANE, WallEstimator.fromPoints(scatter(400), floorY, null, viewer).failure)
        // Dense cloud: some strip always reaches minInliers, but it explains only a tiny fraction.
        assertEquals(WallFitFailure.NO_DOMINANT_PLANE, WallEstimator.fromPoints(scatter(3000), floorY, null, viewer).failure)
        // A tiny patch (a few cm wide) cannot define a wall direction.
        val s = scene()
        val patch = wallPoints(rnd, s, 300, sigma = 0.002, sRange = -0.03..0.03)
        assertEquals(WallFitFailure.NO_DOMINANT_PLANE, WallEstimator.fromPoints(patch, floorY, null, s.viewerWorld).failure)
    }

    @Test fun tooNoisy() {
        val rnd = Random(34)
        val s = scene()
        val fit = WallEstimator.fromPoints(wallPoints(rnd, s, 1000, sigma = 0.06), floorY, null, s.viewerWorld)
        assertEquals(WallFitFailure.TOO_NOISY, fit.failure)
        assertNull(fit.observation)
        assertTrue(fit.usedPoints > 0)
        // The same scene with moderate noise is accepted.
        assertNotNull(WallEstimator.fromPoints(wallPoints(rnd, s, 1000, sigma = 0.012), floorY, null, s.viewerWorld).observation)
    }

    @Test fun fromPointsIsDeterministic() {
        val rnd = Random(35)
        val s = scene()
        val pts = wallPoints(rnd, s, 800, sigma = 0.015) + rayOutliers(rnd, s, 200)
        assertEquals(
            WallEstimator.fromPoints(pts, floorY, null, s.viewerWorld),
            WallEstimator.fromPoints(pts, floorY, null, s.viewerWorld),
        )
    }

    @Test fun confidenceReflectsQuality() {
        fun c(ratio: Double = 0.8, noise: Double = 0.01, n: Int = 600, len: Double = 2.0, dist: Double = 2.0) =
            WallEstimator.depthFitConfidence(ratio, noise, 0.03, n, len, dist, 6.0)
        val good = c()
        assertTrue(good in 0.85..1.0, "good $good")
        assertTrue(c(ratio = 0.2) < good)
        assertTrue(c(noise = 0.025) < good)
        assertTrue(c(n = 30) < good)
        assertTrue(c(len = 0.3) < good)
        assertTrue(c(dist = 5.5) < good)
        val awful = c(ratio = 0.1, noise = 0.029, n = 25, len = 0.12, dist = 5.9)
        assertTrue(awful in 0.0..0.25, "awful $awful")
        assertTrue(c(noise = Double.NaN) in 0.0..1.0)
    }

    // ---------------------------------------------------------------- fromPlane

    @Test fun fromPlaneOrientationIsIndependentOfNormalSign() {
        // Wall at world z = -3 (plan y = 3) in front of a viewer at the origin.
        val center = Vec3(0.5, 0.0, -3.0)
        val boundary = listOf(Vec3(-1.0, -1.4, -3.0), Vec3(2.0, -1.4, -3.0), Vec3(2.0, 1.0, -3.0), Vec3(-1.0, 1.0, -3.0))
        val viewer = Vec3(0.0, 0.0, 0.0)
        for (normal in listOf(Vec3(0.0, 0.0, 1.0), Vec3(0.0, 0.0, -1.0))) {
            val o = assertNotNull(WallEstimator.fromPlane(center, normal, boundary, viewer))
            assertOrientedViewerLeft(o, Vec2.ZERO)
            assertTrue(o.line.direction.approxEquals(Vec2(-1.0, 0.0), 1e-12))
            assertEquals(3.0, o.line.signedDistance(Vec2.ZERO), 1e-12)
            assertTrue(o.extentStart.approxEquals(Vec2(2.0, 3.0), 1e-12))
            assertTrue(o.extentEnd.approxEquals(Vec2(-1.0, 3.0), 1e-12))
            assertEquals(WallSource.DETECTED_PLANE, o.source)
            assertEquals(0.0, o.rmsResidual)
            assertEquals(4, o.pointCount)
            assertEquals(0.8, o.confidence, 1e-12)
        }
    }

    @Test fun fromPlaneRotatedAndSlightlyTilted() {
        val theta = Math.toRadians(30.0)
        // Horizontal normal facing the viewer at the origin, plus a small vertical component.
        val center = Vec3(-2.0 * sin(theta), 0.2, -2.0 * cos(theta))
        val normal = Vec3(sin(theta), 0.15, cos(theta))
        val o = assertNotNull(WallEstimator.fromPlane(center, normal, emptyList(), Vec3(0.0, 1.0, 0.0)))
        assertOrientedViewerLeft(o, Vec2.ZERO)
        // Plan normal of the line = (n.x, -n.z) normalized, pointing to the viewer.
        assertTrue(o.line.normal.approxEquals(Vec2(sin(theta), -cos(theta)), 1e-12))
        assertEquals(2.0, o.line.signedDistance(Vec2.ZERO), 1e-12)
        // No boundary: +-0.5 m around the center.
        assertEquals(1.0, o.extentStart.distanceTo(o.extentEnd), 1e-12)
        assertTrue(Vec2.lerp(o.extentStart, o.extentEnd, 0.5).approxEquals(center.toPlan(), 1e-12))
        assertEquals(0, o.pointCount)
    }

    @Test fun fromPlaneRejectsNonVerticalAndDegeneratePlanes() {
        val center = Vec3(0.0, 0.0, -2.0)
        val viewer = Vec3.ZERO
        assertNull(WallEstimator.fromPlane(center, Vec3(0.0, 1.0, 0.0), emptyList(), viewer))
        assertNull(WallEstimator.fromPlane(center, Vec3(0.0, 0.3, 0.95), emptyList(), viewer))
        assertNull(WallEstimator.fromPlane(center, Vec3(0.0, -0.5, 0.8), emptyList(), viewer))
        assertNull(WallEstimator.fromPlane(center, Vec3.ZERO, emptyList(), viewer))
        assertNull(WallEstimator.fromPlane(center, Vec3(Double.NaN, 0.0, 1.0), emptyList(), viewer))
        // The viewer standing in the plane leaves the interior side undefined.
        assertNull(WallEstimator.fromPlane(center, Vec3(0.0, 0.0, 1.0), emptyList(), Vec3(3.0, 0.0, -2.0)))
        // Non-normalized normals are fine.
        assertNotNull(WallEstimator.fromPlane(center, Vec3(0.0, 0.5, 4.0), emptyList(), viewer))
    }

    @Test fun fromPlaneTinyExtentLowersConfidence() {
        val center = Vec3(0.0, 0.0, -2.0)
        val boundary = listOf(Vec3(-0.075, 0.0, -2.0), Vec3(0.075, 0.0, -2.0), Vec3(Double.NaN, 0.0, 0.0))
        val o = assertNotNull(WallEstimator.fromPlane(center, Vec3(0.0, 0.0, 1.0), boundary, Vec3.ZERO))
        assertEquals(0.15, o.extentStart.distanceTo(o.extentEnd), 1e-12)
        assertTrue(o.confidence < 0.8 && o.confidence > 0.0, "confidence ${o.confidence}")
        val big = assertNotNull(
            WallEstimator.fromPlane(center, Vec3(0.0, 0.0, 1.0), listOf(Vec3(-1.0, 0.0, -2.0), Vec3(1.0, 0.0, -2.0)), Vec3.ZERO),
        )
        assertTrue(o.confidence < big.confidence)
    }

    // ---------------------------------------------------------------- fromBaseline

    @Test fun fromBaselineOrientsAndOrdersExtents() {
        val a = Vec3(0.0, floorY, -2.0) // plan (0, 2)
        val b = Vec3(3.0, floorY + 0.01, -2.0) // plan (3, 2); small height difference is irrelevant
        val below = assertNotNull(WallEstimator.fromBaseline(a, b, Vec3(1.0, 0.0, 0.0))) // viewer plan (1, 0)
        assertOrientedViewerLeft(below, Vec2(1.0, 0.0))
        assertTrue(below.line.direction.approxEquals(Vec2(-1.0, 0.0), 1e-12))
        assertEquals(Vec2(3.0, 2.0), below.extentStart)
        assertEquals(Vec2(0.0, 2.0), below.extentEnd)
        assertEquals(WallSource.BASELINE_TAPS, below.source)
        assertEquals(0.9, below.confidence, 1e-12)
        assertEquals(0.0, below.rmsResidual)
        assertEquals(2, below.pointCount)

        val above = assertNotNull(WallEstimator.fromBaseline(a, b, Vec3(1.0, 0.0, -4.0))) // viewer plan (1, 4)
        assertOrientedViewerLeft(above, Vec2(1.0, 4.0))
        assertTrue(above.line.direction.approxEquals(Vec2(1.0, 0.0), 1e-12))
        assertEquals(Vec2(0.0, 2.0), above.extentStart)
        assertEquals(Vec2(3.0, 2.0), above.extentEnd)

        // Tap order does not matter.
        val swapped = assertNotNull(WallEstimator.fromBaseline(b, a, Vec3(1.0, 0.0, 0.0)))
        assertTrue(swapped.line.direction.approxEquals(below.line.direction, 1e-12))
        assertEquals(below.extentStart, swapped.extentStart)
        assertEquals(below.extentEnd, swapped.extentEnd)
    }

    @Test fun fromBaselineRejectsDegenerateTaps() {
        val a = Vec3(0.0, floorY, -2.0)
        assertNull(WallEstimator.fromBaseline(a, Vec3(0.1, floorY, -2.05), Vec3.ZERO)) // 11 cm apart
        assertNull(WallEstimator.fromBaseline(a, a, Vec3.ZERO))
        // Vertical separation does not count: plan distance is what matters.
        assertNull(WallEstimator.fromBaseline(a, Vec3(0.0, floorY + 1.0, -2.0), Vec3.ZERO))
        assertNull(WallEstimator.fromBaseline(a, Vec3(Double.NaN, floorY, -2.0), Vec3.ZERO))
        // Viewer collinear with the taps.
        assertNull(WallEstimator.fromBaseline(a, Vec3(2.0, floorY, -2.0), Vec3(5.0, 0.0, -2.0)))
        assertNotNull(WallEstimator.fromBaseline(a, Vec3(0.16, floorY, -2.0), Vec3.ZERO))
    }
}
