package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SamplingPolicyTest {

    // ---------------------------------------------------------------------------------------------
    // Ceiling (AR layer AR-2)
    // ---------------------------------------------------------------------------------------------

    private val floorY = 0.0
    private val cameraY = 1.4

    /** y of the unit direction of a ray [deg] degrees above the horizon. */
    private fun up(deg: Double) = sin(Math.toRadians(deg))

    @Test
    fun ceiling_acceptsDownwardPlaneAboveTheCamera() {
        assertTrue(CeilingSampling.isCeilingSample(SurfaceEvidence.DOWNWARD_PLANE, 2.45, cameraY, floorY, up(20.0), null))
        // Below the 1.8 m minimum (underside of wall cabinets), or below the camera: rejected.
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.DOWNWARD_PLANE, 1.75, cameraY, floorY, up(80.0), null))
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.DOWNWARD_PLANE, 1.9, 2.0, floorY, up(80.0), null))
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.DOWNWARD_PLANE, 7.5, cameraY, floorY, up(80.0), null))
    }

    @Test
    fun ceiling_depthHitsNeedASteepRayAndAHorizontalSurface() {
        val steep = up(70.0)
        assertTrue(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITH_NORMAL, 2.45, cameraY, floorY, steep, -0.98))
        // The normal's sign does not matter.
        assertTrue(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITH_NORMAL, 2.45, cameraY, floorY, steep, 0.9))
        // A cabinet front or wall face (horizontal normal) above 1.8 m: rejected.
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITH_NORMAL, 2.1, cameraY, floorY, steep, 0.1))
        // Aiming only 30° up: rejected even on a horizontal surface.
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITH_NORMAL, 2.45, cameraY, floorY, up(30.0), -1.0))
        // Unknown / broken normal.
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITH_NORMAL, 2.45, cameraY, floorY, steep, null))
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITH_NORMAL, 2.45, cameraY, floorY, steep, Double.NaN))
    }

    @Test
    fun ceiling_pointsWithoutNormalNeedANearVerticalRay() {
        assertTrue(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITHOUT_NORMAL, 2.45, cameraY, floorY, up(75.0), null))
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITHOUT_NORMAL, 2.45, cameraY, floorY, up(55.0), null))
    }

    @Test
    fun ceiling_rejectsNonFiniteInput() {
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.DOWNWARD_PLANE, Double.NaN, cameraY, floorY, 1.0, null))
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.DOWNWARD_PLANE, 2.4, Double.NaN, floorY, 1.0, null))
        assertFalse(CeilingSampling.isCeilingSample(SurfaceEvidence.POINT_WITHOUT_NORMAL, 2.4, cameraY, floorY, Double.NaN, null))
    }

    @Test
    fun upperCluster_prefersTheCeilingOverCabinetTops() {
        // 9 frames on the ceiling, 6 on a cabinet top at 2.10 m.
        val heights = List(9) { 2.44 + 0.002 * it } + List(6) { 2.09 + 0.004 * it }
        val cluster = CeilingSampling.upperCluster(heights)
        assertEquals(9, cluster.size)
        assertTrue(cluster.all { it > 2.4 })
    }

    @Test
    fun upperCluster_skipsALoneSpikeAboveTheCeiling() {
        val heights = List(13) { 2.40 + 0.003 * (it % 5) } + listOf(3.6, 4.2)
        val cluster = CeilingSampling.upperCluster(heights)
        assertEquals(13, cluster.size)
        assertTrue(cluster.max() < 2.42)
    }

    @Test
    fun upperCluster_aMinorityOfCeilingFramesStillWins() {
        // Most frames hit a pendant lamp at 2.0 m, three reached the ceiling behind it.
        val heights = List(12) { 2.0 + 0.003 * it } + listOf(2.46, 2.47, 2.45)
        assertEquals(listOf(2.45, 2.46, 2.47), CeilingSampling.upperCluster(heights))
    }

    @Test
    fun upperCluster_chainsNoisySamplesIntoOneCluster() {
        val noisy = listOf(2.41, 2.45, 2.43, 2.47, 2.39, 2.44, 2.42, 2.46, 2.40, 2.48)
        assertEquals(noisy.size, CeilingSampling.upperCluster(noisy).size)
    }

    @Test
    fun upperCluster_emptyWhenNothingIsSupported() {
        assertTrue(CeilingSampling.upperCluster(emptyList()).isEmpty())
        assertTrue(CeilingSampling.upperCluster(listOf(Double.NaN, Double.NaN)).isEmpty())
        // Two samples can never form a supported cluster.
        assertTrue(CeilingSampling.upperCluster(listOf(2.4, 2.41)).isEmpty())
        // Widely scattered readings.
        assertTrue(CeilingSampling.upperCluster(listOf(1.9, 2.1, 2.3, 2.5, 2.7, 2.9, 3.1, 3.3, 3.5, 3.7)).isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Floor-lock fallback (Capture flow AR-4)
    // ---------------------------------------------------------------------------------------------

    /** y of the unit direction of a ray [deg] degrees below the horizon. */
    private fun down(deg: Double) = -sin(Math.toRadians(deg))

    @Test
    fun floorFallback_acceptsAHorizontalSurfaceWellBelowThePhone() {
        assertEquals(FloorLockFallback.Rejection.NONE, FloorLockFallback.classify(0.0, 1.4, down(45.0), -0.99))
        assertEquals(FloorLockFallback.Rejection.NONE, FloorLockFallback.classify(0.0, 1.4, down(35.0), null))
        // Crouching user: 0.6 m above the floor is still fine.
        assertEquals(FloorLockFallback.Rejection.NONE, FloorLockFallback.classify(0.0, 0.6, down(60.0), 1.0))
    }

    @Test
    fun floorFallback_requiresAimingDown() {
        assertEquals(FloorLockFallback.Rejection.NOT_POINTING_DOWN, FloorLockFallback.classify(0.0, 1.4, down(20.0), -1.0))
        assertEquals(FloorLockFallback.Rejection.NOT_POINTING_DOWN, FloorLockFallback.classify(0.0, 1.4, 0.3, -1.0))
        assertEquals(FloorLockFallback.Rejection.NOT_POINTING_DOWN, FloorLockFallback.classify(0.0, 1.4, Double.NaN, -1.0))
    }

    @Test
    fun floorFallback_rejectsWallsAndRaisedOrImplausibleSurfaces() {
        // Wall face (horizontal normal).
        assertEquals(FloorLockFallback.Rejection.NOT_FLOOR_LIKE, FloorLockFallback.classify(0.4, 1.4, down(40.0), 0.2))
        // Counter 0.45 m below the phone.
        assertEquals(FloorLockFallback.Rejection.NOT_FLOOR_LIKE, FloorLockFallback.classify(0.95, 1.4, down(50.0), -1.0))
        // A reflection far below the floor.
        assertEquals(FloorLockFallback.Rejection.NOT_FLOOR_LIKE, FloorLockFallback.classify(-1.2, 1.4, down(50.0), null))
        assertEquals(FloorLockFallback.Rejection.NOT_FLOOR_LIKE, FloorLockFallback.classify(Double.NaN, 1.4, down(50.0), null))
    }

    @Test
    fun floorFallback_crosshairOnFloorOverridesAnAmbiguousTrackedPlane() {
        // Phone at 1.65 m, tracked plane = bed top at 0.55 m, crosshair hit on the floor.
        assertTrue(FloorLockFallback.contradictsTrackedFloor(trackedY = 0.55, cameraY = 1.65, hitY = 0.01))
        // Crosshair on the bed itself, or on a slightly lower surface: keep the tracked plane.
        assertFalse(FloorLockFallback.contradictsTrackedFloor(trackedY = 0.55, cameraY = 1.65, hitY = 0.54))
        assertFalse(FloorLockFallback.contradictsTrackedFloor(trackedY = 0.55, cameraY = 1.65, hitY = 0.40))
        // A floor tracked from a normal height is trusted over a (possibly reflected) depth hit.
        assertFalse(FloorLockFallback.contradictsTrackedFloor(trackedY = 0.0, cameraY = 1.40, hitY = -0.6))
        assertFalse(FloorLockFallback.contradictsTrackedFloor(trackedY = 0.55, cameraY = 1.65, hitY = Double.NaN))
    }

    @Test
    fun floorFallback_locksTheMedianOfAgreeingReadings() {
        val samples = listOf(0.012, -0.004, 0.003, 0.008, -0.01, 0.0, 0.005, 0.25, -0.002, 0.004)
        val evaluation = FloorLockFallback.evaluate(samples)
        assertNotNull(evaluation.floorY)
        assertEquals(0.0035, evaluation.floorY!!, 1e-9)
    }

    @Test
    fun floorFallback_refusesInconsistentReadings() {
        val scattered = listOf(0.0, 0.08, -0.07, 0.12, -0.1, 0.05, -0.04, 0.15, 0.02, -0.12)
        val evaluation = FloorLockFallback.evaluate(scattered)
        assertNull(evaluation.floorY)
        assertTrue(evaluation.mad > FloorLockFallback.MAX_MAD)
        assertNull(FloorLockFallback.evaluate(emptyList()).floorY)
        assertNull(FloorLockFallback.evaluate(listOf(Double.NaN)).floorY)
    }

    // ---------------------------------------------------------------------------------------------
    // Wall sampling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun wall_farWallsGetMoreFrames() {
        assertEquals(15, WallSampling.targetFrames(15, 1.8))
        assertEquals(15, WallSampling.targetFrames(15, 2.5))
        assertEquals(25, WallSampling.targetFrames(15, 2.6))
        assertEquals(15, WallSampling.targetFrames(15, null))
        assertEquals(15, WallSampling.targetFrames(15, Double.NaN))
        // A caller asking for more than the long-range count keeps its own.
        assertEquals(40, WallSampling.targetFrames(40, 3.5))
    }

    @Test
    fun wall_completionHonoursRangeAndTimeBudget() {
        val ms = 1_000_000L
        assertFalse(WallSampling.isComplete(14, 15, 1.5, 400 * ms))
        assertTrue(WallSampling.isComplete(15, 15, 1.5, 500 * ms))
        assertFalse(WallSampling.isComplete(15, 15, 3.2, 500 * ms))
        assertFalse(WallSampling.isComplete(24, 15, 3.2, 900 * ms))
        assertTrue(WallSampling.isComplete(25, 15, 3.2, 900 * ms))
        // Slow frame rate: stop extending once the budget is used up, so the 4 s deadline is never hit.
        assertTrue(WallSampling.isComplete(18, 15, 3.2, WallSampling.LONG_RANGE_BUDGET_NS))
        // ...but never before the requested base frames.
        assertFalse(WallSampling.isComplete(10, 15, 3.2, 3_500 * ms))
    }

    @Test
    fun wall_isDone_farWallsReallyGetTheExtraFrames() {
        val ms = 1_000_000L
        // ARX-3: from 3.5 m the capture must not end at the base 15 frames.
        assertFalse(WallSampling.isDone(15, 15, 3.5, 500 * ms))
        assertFalse(WallSampling.isDone(24, 15, 3.5, 800 * ms))
        assertTrue(WallSampling.isDone(25, 15, 3.5, 830 * ms))
        // A near wall is done at the base count, and never before it.
        assertFalse(WallSampling.isDone(14, 15, 2.0, 470 * ms))
        assertTrue(WallSampling.isDone(15, 15, 2.0, 500 * ms))
        // Unknown distance (no crosshair hit yet): the base count.
        assertTrue(WallSampling.isDone(15, 15, null, 500 * ms))
        // Slow frames: once the time budget is used up the capture ends with what it has (>= base frames).
        assertFalse(WallSampling.isDone(16, 15, 3.5, WallSampling.LONG_RANGE_BUDGET_NS - 1))
        assertTrue(WallSampling.isDone(16, 15, 3.5, WallSampling.LONG_RANGE_BUDGET_NS))
        assertFalse(WallSampling.isDone(12, 15, 3.5, 3_000 * ms))
    }

    @Test
    fun wall_isDone_hasAHardCap() {
        assertEquals(25, WallSampling.maxFrames(15))
        assertEquals(40, WallSampling.maxFrames(40))
        assertEquals(25, WallSampling.maxFrames(1))
        // The cap ends the capture whatever the distance estimate says, even a nonsensical one.
        assertTrue(WallSampling.isDone(25, 15, Double.POSITIVE_INFINITY, 0))
        assertTrue(WallSampling.isDone(25, 15, Double.NaN, 0))
        assertTrue(WallSampling.isDone(40, 40, 3.5, 0))
        assertFalse(WallSampling.isDone(39, 40, 3.5, 0))
    }

    /** A flat wall patch at plan y = 3 (world z = -3) seen from the origin, [heights] sampled 15 x 15. */
    private fun wallPatch(heights: ClosedFloatingPointRange<Double>): List<Vec3> = buildList {
        for (i in 0 until 15) {
            for (j in 0 until 15) {
                val x = -0.6 + 1.2 * i / 14.0
                val y = heights.start + (heights.endInclusive - heights.start) * j / 14.0
                // A few millimetres of deterministic "depth noise".
                val noise = 0.003 * sin(12.9898 * i + 78.233 * j)
                add(Vec3(x, y, -3.0 + noise))
            }
        }
    }

    @Test
    fun wall_bandIsTheBandTheEstimatorFiltersWith() {
        // CAPX-8: the aim filter (wallBand) and the fit (WallEstimator) must agree for the same request.
        val viewer = Vec3(0.0, 1.4, 0.0)
        val floorY = 0.0

        // A measured 2.5 m ceiling: points between 1.0 and 2.2 m are on the wall for both.
        val measured = WallSampling.wallBand(floorY, 2.5)
        val inBand = wallPatch(1.0..2.2)
        assertTrue(inBand.all { it.y in measured })
        assertNotNull(WallEstimator.fromPoints(inBand, floorY, 2.5, viewer).observation)

        // The strip above a 2.2 m wardrobe (2.40-2.80 m) is out of that band for both...
        val aboveWardrobe = wallPatch(2.40..2.80)
        assertTrue(aboveWardrobe.none { it.y in measured })
        assertEquals(WallFitFailure.TOO_FEW_POINTS, WallEstimator.fromPoints(aboveWardrobe, floorY, 2.5, viewer).failure)
        assertNull(WallSampling.aimPoint(aboveWardrobe.take(15), frames = 15, band = measured))

        // ... and inside the band top capture passes for an unmeasured 2.7 m default ceiling
        // (floor + max(default, 2.4) + 0.3 = 3.0 m), again for both.
        val assumedTop = floorY + 2.7 + 0.3
        val generous = WallSampling.wallBand(floorY, assumedTop)
        assertTrue(aboveWardrobe.all { it.y in generous })
        val fit = WallEstimator.fromPoints(aboveWardrobe, floorY, assumedTop, viewer)
        assertNotNull(fit.failure?.name, fit.observation)
        assertNotNull(WallSampling.aimPoint(aboveWardrobe.take(15), frames = 15, band = generous))

        // Without any band top both fall back to the same assumed 2.4 m ceiling.
        val fallback = WallSampling.wallBand(floorY, null)
        assertTrue(aboveWardrobe.none { it.y in fallback })
        assertEquals(WallFitFailure.TOO_FEW_POINTS, WallEstimator.fromPoints(aboveWardrobe, floorY, null, viewer).failure)
    }

    @Test
    fun wall_bandMatchesTheEstimatorDefaults() {
        val config = WallEstimatorConfig()
        val measured = WallSampling.wallBand(floorY = 0.0, ceilingY = 2.6)
        assertEquals(config.minHeightAboveFloor, measured.start, 1e-12)
        assertEquals(2.6 - config.minDistanceBelowCeiling, measured.endInclusive, 1e-12)
        val assumed = WallSampling.wallBand(floorY = -1.4, ceilingY = null)
        assertEquals(-1.4 + config.assumedCeilingHeight - config.minDistanceBelowCeiling, assumed.endInclusive, 1e-12)
        // A ceiling below the band start falls back to the assumed height.
        val broken = WallSampling.wallBand(floorY = 0.0, ceilingY = 0.1)
        assertEquals(config.assumedCeilingHeight - config.minDistanceBelowCeiling, broken.endInclusive, 1e-12)
    }

    @Test
    fun wall_aimPointIsTheMedianCrosshairHitInsideTheBand() {
        val band = WallSampling.wallBand(floorY = 0.0, ceilingY = 2.5)
        val onWall = List(12) { i ->
            val a = 0.01 * i
            Vec3(2.0 + 0.005 * cos(a * 50), 1.3 + 0.01 * (i % 3), -3.0 + 0.004 * (i % 4))
        }
        val outliers = listOf(Vec3(0.5, 1.3, -1.0), Vec3(2.0, 0.05, -3.0) /* floor, below the band */)
        val aim = WallSampling.aimPoint(onWall + outliers, frames = 15, band = band)
        assertNotNull(aim)
        assertEquals(2.0, aim!!.x, 0.01)
        assertEquals(1.31, aim.y, 0.01)
        assertEquals(-3.0, aim.z, 0.01)
    }

    @Test
    fun wall_noAimPointWithoutEnoughCrosshairHits() {
        val band = WallSampling.wallBand(floorY = 0.0, ceilingY = 2.5)
        val few = List(4) { Vec3(2.0, 1.3, -3.0) }
        // 25 frames need at least 9 crosshair hits.
        assertNull(WallSampling.aimPoint(few, frames = 25, band = band))
        assertNotNull(WallSampling.aimPoint(few, frames = 10, band = band))
        assertNull(WallSampling.aimPoint(emptyList(), frames = 15, band = band))
        assertNull(WallSampling.aimPoint(List(10) { Vec3(Double.NaN, 1.3, -3.0) }, frames = 10, band = band))
    }

    @Test
    fun grid_centerIndexIsTheMiddleSample() {
        assertEquals(WallSampleGrid.COUNT / 2, WallSampleGrid.CENTER_INDEX)
        val out = FloatArray(WallSampleGrid.COUNT * 2)
        WallSampleGrid.positions(1440, 3120, out)
        assertEquals(720f, out[2 * WallSampleGrid.CENTER_INDEX], 1e-3f)
        assertEquals(1560f, out[2 * WallSampleGrid.CENTER_INDEX + 1], 1e-3f)
    }

    @Test
    fun grid_smallerGridsCoverTheSameSquareAroundTheCrosshair() {
        val out = FloatArray(WallSampleGrid.COUNT * 2)
        assertEquals(WallSampleGrid.SIZE, WallSampleGrid.SIZES.first())
        for (size in WallSampleGrid.SIZES) {
            assertEquals("grid sizes must be odd so the middle sample is the crosshair", 1, size % 2)
            WallSampleGrid.positions(1440, 3120, out, size)
            val center = WallSampleGrid.centerIndex(size)
            assertEquals(720f, out[2 * center], 1e-3f)
            assertEquals(1560f, out[2 * center + 1], 1e-3f)
            val side = 1440 * WallSampleGrid.SIDE_FRACTION
            val last = size * size - 1
            assertEquals(720f - side / 2, out[0], 1e-3f)
            assertEquals(1560f - side / 2, out[1], 1e-3f)
            assertEquals(720f + side / 2, out[2 * last], 1e-3f)
            assertEquals(1560f + side / 2, out[2 * last + 1], 1e-3f)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Which hit along a ray is the wall sample (MATH-5)
    // ---------------------------------------------------------------------------------------------

    private val depthHit = Vec3(0.0, 1.3, -2.52)
    private val planeHit = Vec3(0.0, 1.3, -2.50)
    private val featureHit = Vec3(0.0, 1.3, -2.47)

    @Test
    fun wallSample_depthWinsEvenWhenThePlaneHitIsNearer() {
        // The depth reading errs 2 cm AWAY from the viewer; "first hit" would have replaced it by the plane.
        val sample = WallSampleRule.choose(depthHit, planeHit, featureHit, depthEnabled = true)!!
        assertEquals(WallSampleKind.DEPTH, sample.kind)
        assertEquals(depthHit, sample.world)
        // ... and equally when it errs toward the viewer: the choice never depends on which is nearer.
        val near = Vec3(0.0, 1.3, -2.48)
        assertEquals(near, WallSampleRule.choose(near, planeHit, null, depthEnabled = true)!!.world)
    }

    @Test
    fun wallSample_fallsBackToThePlaneAndSkipsFeaturePointsWhileDepthIsOn() {
        val plane = WallSampleRule.choose(null, planeHit, featureHit, depthEnabled = true)!!
        assertEquals(WallSampleKind.PLANE, plane.kind)
        assertEquals(planeHit, plane.world)
        assertNull(WallSampleRule.choose(null, null, featureHit, depthEnabled = true))
        assertNull(WallSampleRule.choose(null, null, null, depthEnabled = true))
    }

    @Test
    fun wallSample_withoutDepthUsesPlanesThenFeaturePoints() {
        assertEquals(WallSampleKind.PLANE, WallSampleRule.choose(null, planeHit, featureHit, depthEnabled = false)!!.kind)
        val feature = WallSampleRule.choose(null, null, featureHit, depthEnabled = false)!!
        assertEquals(WallSampleKind.FEATURE, feature.kind)
        assertEquals(featureHit, feature.world)
        assertNull(WallSampleRule.choose(null, null, null, depthEnabled = false))
    }

    // ---------------------------------------------------------------------------------------------
    // Frame budget of wall sampling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun gridGovernor_keepsTheFullGridOnAFastPhone() {
        val governor = WallGridGovernor(budgetNs = 12_000_000L)
        assertEquals(9, governor.gridSize())
        repeat(25) {
            governor.onFrameSampled(hitTests = 80, elapsedNs = 80 * 60_000L) // 4.8 ms per frame
            assertEquals(9, governor.gridSize())
        }
        assertEquals(60_000.0, governor.costPerHitNs, 1.0)
    }

    @Test
    fun gridGovernor_shrinksTheGridUntilAFrameFitsTheBudget() {
        val governor = WallGridGovernor(budgetNs = 12_000_000L)
        // 250 µs per hit test: 80 hits = 20 ms (too slow), 48 hits = 12 ms (just fits).
        governor.onFrameSampled(80, 80 * 250_000L)
        assertEquals(7, governor.gridSize())
        governor.onFrameSampled(48, 48 * 250_000L)
        assertEquals(7, governor.gridSize())
        // 600 µs per hit test: even the smallest grid (24 hits = 14.4 ms) is over, but it never goes lower.
        val slow = WallGridGovernor(budgetNs = 12_000_000L)
        slow.onFrameSampled(80, 80 * 600_000L)
        assertEquals(5, slow.gridSize())
        repeat(10) { slow.onFrameSampled(24, 24 * 600_000L) }
        assertEquals(5, slow.gridSize())
    }

    @Test
    fun gridGovernor_growsBackOnlyWithHeadroom() {
        val governor = WallGridGovernor(budgetNs = 12_000_000L)
        governor.onFrameSampled(80, 80 * 250_000L)
        assertEquals(7, governor.gridSize())
        // One slow first frame (cold start) must not cost the rest of the session: the smoothed cost recovers.
        repeat(30) { governor.onFrameSampled(48, 48 * 60_000L) }
        assertEquals(9, governor.gridSize())
        // Just under the budget for 9 x 9 (80 x 140 µs = 11.2 ms) is not enough headroom to grow from 7 x 7.
        val marginal = WallGridGovernor(budgetNs = 12_000_000L)
        marginal.onFrameSampled(80, 80 * 250_000L)
        assertEquals(7, marginal.gridSize())
        repeat(60) { marginal.onFrameSampled(48, 48 * 140_000L) }
        assertEquals(7, marginal.gridSize())
    }

    @Test
    fun gridGovernor_ignoresNonsenseMeasurements() {
        val governor = WallGridGovernor()
        governor.onFrameSampled(0, 5_000_000L)
        governor.onFrameSampled(80, -1L)
        assertEquals(0.0, governor.costPerHitNs, 0.0)
        assertEquals(9, governor.gridSize())
    }
}
