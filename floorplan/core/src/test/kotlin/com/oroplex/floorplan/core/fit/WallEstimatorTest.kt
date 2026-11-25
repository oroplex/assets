package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.core.sim.CAMERA_HEIGHT
import com.oroplex.floorplan.core.sim.DepthNoise
import com.oroplex.floorplan.core.sim.FurnitureBox
import com.oroplex.floorplan.core.sim.HardScenarios
import com.oroplex.floorplan.core.sim.RayHit
import com.oroplex.floorplan.core.sim.SIM_CEILING_Y
import com.oroplex.floorplan.core.sim.SIM_FLOOR_Y
import com.oroplex.floorplan.core.sim.ScanSimulator
import com.oroplex.floorplan.core.sim.SimFace
import com.oroplex.floorplan.core.sim.SimRoom
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
        // Same observed span either way, just reversed. The extent is a robust span of where the samples' rays
        // meet the line (review round 2, MATH-6), so it depends a little on where the viewer is: the two differ by
        // about the noise of a few samples (2.2 cm here), not by the outliers that used to stretch extents.
        assertTrue(front.extentStart.distanceTo(back.extentEnd) < 0.04, "${front.extentStart} vs ${back.extentEnd}")
        assertTrue(front.extentEnd.distanceTo(back.extentStart) < 0.04, "${front.extentEnd} vs ${back.extentStart}")
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

    @Test fun tooNoisyOnlyWhenTheNoiseIsGrosslyOffTheModel() {
        // Review round 2, MATH-4: the model expects 3.6 cm at 2.6 m. Only noise several times that fails; the
        // message for it ("hold steady, add light, or move closer") then makes sense.
        val rnd = Random(34)
        val s = scene()
        val gross = WallEstimator.fromPoints(wallPoints(rnd, s, 1000, sigma = 0.20), floorY, null, s.viewerWorld)
        assertEquals(WallFitFailure.TOO_NOISY, gross.failure)
        assertNull(gross.observation)
        assertNull(gross.hint)
        assertEquals(WallFitFailure.TOO_NOISY.message, gross.message)
        assertTrue(gross.usedPoints > 0)
        assertTrue(assertNotNull(gross.noiseRatio) > WallEstimatorConfig().grossNoiseFactor, "ratio ${gross.noiseRatio}")

        // 1.7 x the model (6 cm) used to fail; the line is still good to millimetres, so it is accepted with a
        // lower confidence than a quiet capture, and the measured ratio is reported for tuning on device.
        val noisy = WallEstimator.fromPoints(wallPoints(rnd, s, 1000, sigma = 0.06), floorY, null, s.viewerWorld)
        val quiet = WallEstimator.fromPoints(wallPoints(rnd, s, 1000, sigma = 0.012), floorY, null, s.viewerWorld)
        val noisyWall = assertNotNull(noisy.observation, "failure: ${noisy.failure}")
        val quietWall = assertNotNull(quiet.observation, "failure: ${quiet.failure}")
        assertNull(noisy.message)
        assertMatchesTruth(noisyWall, s, offsetTol = 0.03, angleTolDeg = 1.0)
        assertTrue(noisyWall.confidence < quietWall.confidence, "${noisyWall.confidence} vs ${quietWall.confidence}")
        assertEquals(0.06 / 0.0362, assertNotNull(noisy.noiseRatio), 0.35)
        assertTrue(assertNotNull(quiet.noiseRatio) < 0.6, "ratio ${quiet.noiseRatio}")
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

    // ---------------------------------------------------------------- range-adaptive noise model

    @Test fun noiseModelDefaultsAndBands() {
        val c = WallEstimatorConfig()
        assertEquals(0.005, c.noiseSigmaBase)
        assertEquals(0.012, c.noiseSigmaPerMeter)
        assertEquals(0.005 + 0.012 * 3.0, c.expectedSigma(3.0), 1e-12)
        // Close range: the fixed minimum band applies; far: the 2σ band. The noise limit is a GROSS cap
        // (review round 2, MATH-4): 3.5σ, never below maxRms.
        assertEquals(0.025, c.inlierThresholdAt(0.5), 1e-12)
        assertEquals(2 * (0.005 + 0.012 * 4.0), c.inlierThresholdAt(4.0), 1e-12)
        assertEquals(3.5, c.grossNoiseFactor)
        assertEquals(3.5 * (0.005 + 0.012 * 0.5), c.noiseLimitAt(0.5), 1e-12)
        assertEquals(3.5 * (0.005 + 0.012 * 4.0), c.noiseLimitAt(4.0), 1e-12)
        assertEquals(0.03, c.noiseLimitAt(0.0), 1e-12)
        assertEquals(0.03, WallEstimatorConfig(grossNoiseFactor = 1.0).noiseLimitAt(1.0), 1e-12)
        // Bad input never yields NaN bands.
        assertEquals(0.025, c.inlierThresholdAt(Double.NaN), 1e-12)
        assertEquals(0.025, c.inlierThresholdAt(-3.0), 1e-12)
        assertEquals(0.0, WallEstimatorConfig(noiseSigmaBase = Double.NaN).expectedSigma(2.0))
        assertTrue(c.maxRange >= 4.0, "TOO_FAR must not fire before the ~4 m the message asks for")
    }

    @Test fun lineErrorsFollowNoiseCountAndExtent() {
        val c = WallEstimatorConfig()
        // 100 samples of 2 cm noise over 1 m: offset 2 mm, direction sqrt(12) x 2 mm / 1 m.
        val (offset, direction) = c.lineErrors(0.02, 100, 1.0)
        assertEquals(0.002, offset, 1e-12)
        assertEquals(kotlin.math.sqrt(12.0) * 0.002, direction, 1e-12)
        // Samples share errors: beyond maxEffectivePoints more of them do not make the line more precise.
        assertEquals(c.lineErrors(0.02, c.maxEffectivePoints, 1.0), c.lineErrors(0.02, 100_000, 1.0))
        // Half the extent, twice the direction error; the offset error stays.
        assertEquals(2 * direction, c.lineErrors(0.02, 100, 0.5).second, 1e-12)
        assertEquals(offset, c.lineErrors(0.02, 100, 0.5).first, 1e-12)
        // Nothing to go by: infinitely uncertain, never NaN.
        for (bad in listOf(c.lineErrors(Double.NaN, 100, 1.0), c.lineErrors(0.02, 0, 1.0), c.lineErrors(0.02, 100, 0.0), c.lineErrors(-1.0, 100, 1.0))) {
            assertEquals(Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY, bad)
        }
    }

    /** A synthetic capture from the scan simulation (15 frames x 9x9 rays, range noise 5 mm + 1.2 %, 10 % outliers). */
    private fun capture(room: SimRoom, station: Vec2, aim: Vec2, aimHeight: Double, seed: Long, useAim: Boolean = true) =
        ScanSimulator.capture(room, Vec3.fromPlan(station, CAMERA_HEIGHT), Vec3.fromPlan(aim, aimHeight), Random(seed), useAim = useAim)

    private val bigRoom = SimRoom("8x8", listOf(Vec2(0.0, 0.0), Vec2(8.0, 0.0), Vec2(8.0, 8.0), Vec2(0.0, 8.0)))

    @Test fun headOnWallsUpTo4mAreAcceptedAndAccurate() {
        // Review round 1 (simulation SIM-3): with a fixed 3 cm noise gate every capture beyond ~2.2 m failed.
        val truth = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        for (d in listOf(1.0, 2.5, 3.5, 4.0)) {
            for (k in 0 until 5) {
                val fit = capture(bigRoom, Vec2(4.0, d), Vec2(4.0 + 0.3 * k - 0.6, 0.0), 1.4, 1000L + k).fit
                val o = assertNotNull(fit.observation, "$d m, trial $k: ${fit.failure}")
                assertTrue(truth.distance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5)) < 0.01, "$d m offset")
                assertTrue(angleDeg(o.line.direction, truth.direction) < 1.0, "$d m angle")
                assertOrientedViewerLeft(o, Vec2(4.0, d).let { v -> o.viewer.takeIf { it.distanceTo(v) < 0.05 } ?: v })
            }
        }
    }

    /** The hallway of the sample house: 5.37 x 1.07 m. */
    private val hallway = SimRoom("Hall", listOf(Vec2(0.0, 0.0), Vec2(5.37, 0.0), Vec2(5.37, 1.07), Vec2(0.0, 1.07)))

    @Test fun aimPointKeepsTheEndWallAgainstAGrazingSideWall() {
        // Review round 1 (simulation SIM-1): aiming down a hallway at its end wall fitted a side wall.
        val station = Vec2(2.685, 0.535)
        for (across in listOf(0.1, 0.5, 0.9)) {
            for (k in 0 until 5) {
                val aim = hallway.pointOn(3, across)
                val cap = capture(hallway, station, aim, 1.4, 2000L + k)
                val o = assertNotNull(cap.fit.observation, "$across/$k: ${cap.fit.failure}")
                val endWall = Line2.through(hallway.start(3), hallway.end(3))
                assertTrue(angleDeg(o.line.direction, endWall.direction) < 2.0, "$across/$k fitted a side wall")
                assertTrue(endWall.distance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5)) < 0.02, "$across/$k offset")
            }
        }
    }

    @Test fun cornerPointsOfTheSideWallsDoNotTiltTheEndWall() {
        // Head-on at 2.7 m with both corners in view: the side walls' points near the corners lie inside the
        // band on the room side only. Unbiased: mean signed error ~0, spread at the statistical level.
        val station = Vec2(2.685, 0.535)
        val endWall = Line2.through(hallway.start(3), hallway.end(3))
        val angles = ArrayList<Double>()
        val offsets = ArrayList<Double>()
        for (k in 0 until 24) {
            val o = assertNotNull(capture(hallway, station, hallway.pointOn(3, 0.3 + 0.017 * k), 1.4, 3000L + k).fit.observation)
            val d = if ((o.line.direction dot endWall.direction) < 0) -o.line.direction else o.line.direction
            angles += Math.toDegrees(Math.asin((endWall.direction cross d).coerceIn(-1.0, 1.0)))
            offsets += endWall.signedDistance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5))
        }
        val rms = kotlin.math.sqrt(angles.map { it * it }.average())
        assertTrue(rms < 0.5, "direction rms $rms deg")
        assertTrue(abs(offsets.average()) < 0.002, "offset bias ${offsets.average()} m")
    }

    /** Main wall y = 0 with a 0.9 m doorway at x 3.0..3.9 into a 1.2 m deep space (its back wall parallel). */
    private val doorwayRoom = SimRoom(
        "Doorway",
        listOf(
            Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, -1.2), Vec2(3.9, -1.2), Vec2(3.9, 0.0),
            Vec2(8.0, 0.0), Vec2(8.0, 6.0), Vec2(0.0, 6.0),
        ),
    )

    @Test fun wallBesideADoorwayIsNotReplacedByTheWallBehindIt() {
        // Review round 1 (Core math AR-2): with the doorway covering ~40 % of the patch, "prefer farther" took
        // the wall 1.2 m behind the opening. The overlap / height test keeps the near wall, with or without aim.
        val station = Vec2(2.9, 3.0)
        val aim = Vec2(2.9, 0.0)
        val mainWall = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        for (useAim in listOf(true, false)) {
            for (k in 0 until 6) {
                val cap = capture(doorwayRoom, station, aim, 1.3, 4000L + k, useAim)
                assertTrue(cap.fraction(2) > 0.25, "scene: the opening must cover a large part of the patch")
                val o = assertNotNull(cap.fit.observation, "aim=$useAim/$k: ${cap.fit.failure}")
                assertTrue(mainWall.distance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5)) < 0.02, "aim=$useAim/$k took the wall behind")
            }
        }
    }

    private val furnishedRoom = SimRoom("Sofa", listOf(Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 6.0), Vec2(0.0, 6.0))).withFurniture(
        listOf(FurnitureBox(0, Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0), 1.5, 4.5, 0.8, 0.85)),
    )

    @Test fun wallBehindFurnitureWithAndWithoutAimPoint() {
        val wall = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        val station = Vec2(3.0, 2.6)
        for (k in 0 until 4) {
            // Aimed just above the sofa back: the wall. Without an aim point "prefer farther" finds it too.
            for (useAim in listOf(true, false)) {
                val cap = capture(furnishedRoom, station, Vec2(3.0, 0.0), 1.0, 5000L + k, useAim)
                assertTrue(cap.fraction(RayHit.FURNITURE) > 0.2, "scene: the sofa must fill a good part of the patch")
                val o = assertNotNull(cap.fit.observation, "aim=$useAim/$k: ${cap.fit.failure}")
                assertTrue(wall.distance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5)) < 0.02, "aim=$useAim/$k fitted the sofa")
            }
            // Aimed at the sofa itself, from where the wall above it is in view too: never return the sofa as
            // a wall; ask to aim above it.
            val onSofa = capture(furnishedRoom, Vec2(3.0, 3.6), Vec2(3.0, 0.8), 0.7, 5100L + k).fit
            assertNull(onSofa.observation)
            assertEquals(WallFitFailure.NO_DOMINANT_PLANE, onSofa.failure)
            assertTrue("above furniture" in onSofa.failure!!.message)
        }
    }

    @Test fun aimPointOnNothingFailsAndBadAimPointsAreIgnored() {
        val cap = capture(bigRoom, Vec2(4.0, 2.0), Vec2(4.0, 0.0), 1.4, 6000L)
        val viewer = cap.viewer
        // An aim point 1 m in front of the wall (a lamp, a person): the crosshair is not on this wall.
        val lamp = WallEstimator.fromPoints(cap.points, SIM_FLOOR_Y, SIM_CEILING_Y, viewer, aimPoint = Vec3.fromPlan(Vec2(4.0, 1.0), 1.4))
        assertEquals(WallFitFailure.NO_DOMINANT_PLANE, lamp.failure)
        // A non-finite aim point is ignored (same as none).
        val nan = WallEstimator.fromPoints(cap.points, SIM_FLOOR_Y, SIM_CEILING_Y, viewer, aimPoint = Vec3(Double.NaN, 0.0, 0.0))
        val none = WallEstimator.fromPoints(cap.points, SIM_FLOOR_Y, SIM_CEILING_Y, viewer)
        assertEquals(none, nan)
        assertNotNull(none.observation)
        // A slightly noisy aim point (2 cm off the wall) still matches it.
        val near = WallEstimator.fromPoints(cap.points, SIM_FLOOR_Y, SIM_CEILING_Y, viewer, aimPoint = Vec3.fromPlan(Vec2(4.0, 0.02), 1.4))
        assertNotNull(near.observation)
    }

    @Test fun lineAnchorIsInsideTheObservedExtent() {
        // RoomSolver rotates squared walls about the anchor: it must be the samples' centroid, inside the extent.
        val o = assertNotNull(capture(bigRoom, Vec2(2.0, 2.5), Vec2(4.0, 0.0), 1.4, 7000L).fit.observation)
        val t = o.line.param(o.line.point)
        assertTrue(t > o.line.param(o.extentStart) && t < o.line.param(o.extentEnd), "anchor outside the extent")
    }

    // ---------------------------------------------------------------- short walls (review round 2, MATH-1)

    /** 5 x 4 m room with a 0.4 m closet return (wall 4: x = 1.4, y 3.6..4.0) between walls 3 (y = 4) and 5 (y = 3.6). */
    private val closetRoom = HardScenarios.closet40
    private val closetReturn = SimFace("return", closetRoom.start(3), closetRoom.end(3))

    @Test fun shortWallSeenAtAnAngleIsRefusedNotFittedToAChordOrANeighbour() {
        // From the room's pole the return is 2.3 m away at about 50 degrees. Round 2 accepted 97 % of such
        // captures on a diagonal chord through the corner or on wall 3 / wall 5, with normal confidence.
        val station = closetRoom.pole
        assertEquals(50.0, Math.toDegrees(Math.acos(((station - closetReturn.pointOn(0.5)).normalized() dot closetReturn.normal))), 8.0)
        var refused = 0
        var hinted = 0
        for (k in 0 until 40) {
            val rnd = Random(8000L + k)
            val aim = closetReturn.pointOn(0.2 + 0.6 * rnd.nextDouble())
            val fit = capture(closetRoom, station, aim, 1.0 + 0.8 * rnd.nextDouble(), 8100L + k).fit
            val o = fit.observation
            if (o != null) {
                // A failed capture is fine; a wrong observation is not.
                assertTrue(closetReturn.matches(o), "trial $k: accepted a line ${angleDeg(o.line.direction, closetReturn.direction)} deg off the return")
                continue
            }
            refused++
            assertNotNull(fit.failure)
            val message = assertNotNull(fit.message)
            if (fit.hint != null) {
                hinted++
                // The message says what to do, and it is the hint, not the generic text.
                assertEquals(fit.hint, message)
                assertTrue("Stand square" in message && "tap its base" in message, message)
            } else {
                assertEquals(fit.failure!!.message, message)
            }
        }
        assertTrue(refused >= 36, "only $refused of 40 oblique captures of the 0.4 m return were refused")
        assertTrue(hinted >= 30, "only $hinted of $refused refusals say what to do")
    }

    @Test fun shortWallIsMeasuredFromSquareInFrontOfIt() {
        // What the hint asks for: square to the return from about a metre.
        var accepted = 0
        for (k in 0 until 30) {
            val rnd = Random(8200L + k)
            val aim = closetReturn.pointOn(0.2 + 0.6 * rnd.nextDouble())
            val fit = capture(closetRoom, aim + closetReturn.normal * 0.9, aim, 1.0 + 0.8 * rnd.nextDouble(), 8300L + k).fit
            val o = fit.observation ?: continue
            accepted++
            assertNull(fit.message)
            assertTrue(closetReturn.matches(o), "trial $k: wrong wall")
            assertTrue(angleDeg(o.line.direction, closetReturn.direction) < 3.0, "trial $k: ${angleDeg(o.line.direction, closetReturn.direction)} deg")
            assertTrue(closetReturn.line.distance(o.line.project(closetReturn.pointOn(0.5))) < 0.005, "trial $k: offset")
            // The observed extent is the return, not the walls around the corner (MATH-6).
            for (end in listOf(o.extentStart, o.extentEnd)) {
                assertTrue(closetReturn.line.param(end) in -0.05..closetReturn.length + 0.05, "trial $k: extent end $end")
            }
        }
        assertTrue(accepted >= 27, "only $accepted of 30 square-on captures of the 0.4 m return succeeded")
    }

    @Test fun wallsNextToTheReturnAreStillMeasuredFromThePole() {
        // The long walls on either side of the return (walls 3 and 5) are ordinary captures and must stay so.
        for (edge in listOf(2, 4)) {
            val face = SimFace("wall ${edge + 1}", closetRoom.start(edge), closetRoom.end(edge))
            var accepted = 0
            for (k in 0 until 20) {
                val rnd = Random(8400L + 100 * edge + k)
                val fit = capture(closetRoom, closetRoom.pole, face.pointOn(0.3 + 0.4 * rnd.nextDouble()), 1.0 + 0.8 * rnd.nextDouble(), 8500L + 100 * edge + k).fit
                val o = fit.observation ?: continue
                accepted++
                assertTrue(face.matches(o), "wall ${edge + 1} trial $k: wrong wall")
                assertTrue(angleDeg(o.line.direction, face.direction) < 1.5, "wall ${edge + 1} trial $k: angle")
            }
            assertTrue(accepted >= 19, "wall ${edge + 1}: only $accepted of 20 accepted")
        }
    }

    @Test fun observedExtentStopsAtTheWallsEnd() {
        // Review round 2 (MATH-6): extents were the extreme inliers, and range outliers on rays that pass the
        // corner stretched them by up to 1.7 m beyond the wall (wall 5 "seen" at x = -1.3 when it ends at x = 0).
        // That overlap then made RoomSolver delete a correct wall.
        val wall5 = SimFace("wall 5", closetRoom.start(4), closetRoom.end(4))
        var accepted = 0
        for (k in 0 until 30) {
            val rnd = Random(8600L + k)
            val fit = capture(closetRoom, closetRoom.pole, wall5.pointOn(0.25 + 0.5 * rnd.nextDouble()), 1.0 + 0.8 * rnd.nextDouble(), 8700L + k).fit
            val o = fit.observation ?: continue
            accepted++
            assertTrue(wall5.matches(o), "trial $k: wrong wall")
            for (end in listOf(o.extentStart, o.extentEnd)) {
                val along = wall5.line.param(end)
                assertTrue(along in -0.10..wall5.length + 0.10, "trial $k: extent reaches ${along} m along a ${wall5.length} m wall")
            }
        }
        assertTrue(accepted >= 25, "only $accepted of 30 accepted")
        // Seen obliquely next to an ordinary corner: the same bound.
        val box = SimRoom("5x4", listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 4.0), Vec2(0.0, 4.0)))
        val top = SimFace("top", box.start(2), box.end(2))
        for (k in 0 until 20) {
            val o = capture(box, Vec2(3.5, 1.2), Vec2(1.0 + 0.1 * k, 4.0), 1.4, 8800L + k).fit.observation ?: continue
            for (end in listOf(o.extentStart, o.extentEnd)) {
                assertTrue(top.line.param(end) in -0.10..top.length + 0.10, "trial $k: extent end $end")
            }
        }
    }

    @Test fun wallSeenThroughTwoSamplingColumnsIsNotAWall() {
        // Two columns of samples always fit a line: nothing shows whether it is a wall or a chord across a corner.
        // A 0.25 m pier seen square-on from 2.4 m spans 6 degrees (two of the nine columns): refused; from 0.5 m
        // it fills most of the view: measured.
        val pier = SimRoom(
            "pier",
            listOf(Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 4.0), Vec2(3.25, 4.0), Vec2(3.25, 3.7), Vec2(3.0, 3.7), Vec2(3.0, 4.0), Vec2(0.0, 4.0)),
        )
        val face = SimFace("pier front", pier.start(4), pier.end(4))
        assertEquals(0.25, face.length, 1e-12)
        for (k in 0 until 12) {
            val far = capture(pier, face.pointOn(0.5) + face.normal * 2.4, face.pointOn(0.5), 1.4, 8900L + k).fit
            assertNull(far.observation, "trial $k: a 0.25 m face 2.4 m away was accepted")
            assertTrue(far.hint == WallEstimator.HINT_SHORT_WALL || far.hint == WallEstimator.HINT_IMPRECISE, "trial $k: ${far.failure} / ${far.hint}")
            val near = capture(pier, face.pointOn(0.5) + face.normal * 0.5, face.pointOn(0.5), 1.4, 9000L + k).fit
            val o = assertNotNull(near.observation, "trial $k from 0.5 m: ${near.message}")
            assertTrue(face.matches(o), "trial $k: wrong wall")
        }
    }

    // ---------------------------------------------------------------- noise (review round 2, MATH-4)

    @Test fun sensorTwiceAsNoisyAsTheModelStillMeasuresWalls() {
        // The 1.5 x gate failed every capture beyond 1 m at 1.7 x the model's noise. The line is still precise.
        val truth = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        for (d in listOf(1.0, 2.5, 4.0)) {
            for (k in 0 until 5) {
                val aim = Vec2(4.0 + 0.3 * k - 0.6, 0.0)
                val nominal = ScanSimulator.capture(bigRoom, Vec3.fromPlan(Vec2(4.0, d), CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.4), Random(9100L + k)).fit
                val noisy = ScanSimulator.capture(
                    bigRoom, Vec3.fromPlan(Vec2(4.0, d), CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.4), Random(9100L + k), noise = DepthNoise(scale = 2.0),
                ).fit
                val o = assertNotNull(noisy.observation, "$d m, trial $k: ${noisy.message}")
                assertTrue(truth.distance(Vec2.lerp(o.extentStart, o.extentEnd, 0.5)) < 0.005 + 0.005 * d, "$d m offset")
                assertTrue(angleDeg(o.line.direction, truth.direction) < 2.5, "$d m angle ${angleDeg(o.line.direction, truth.direction)}")
                // The measured-to-expected noise ratio is reported and lowers the confidence.
                assertEquals(2.0, assertNotNull(noisy.noiseRatio), 0.35)
                assertEquals(1.0, assertNotNull(nominal.noiseRatio), 0.2)
                assertTrue(o.confidence < assertNotNull(nominal.observation).confidence, "$d m: confidence not lowered")
            }
        }
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
