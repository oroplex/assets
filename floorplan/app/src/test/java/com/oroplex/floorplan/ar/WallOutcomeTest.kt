package com.oroplex.floorplan.ar

import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which evidence a wall capture may return: see [WallOutcome]. */
class WallOutcomeTest {
    private val viewer = Vec3(0.0, 1.4, 0.0)
    private val band = 0.3..2.3

    /** A wall at plan y = [y] (world z = -y) facing the viewer at the origin. */
    private fun wall(y: Double, source: WallSource, confidence: Double = 0.8) = WallObservation(
        line = Line2.of(Vec2(0.0, y), Vec2(-1.0, 0.0)),
        extentStart = Vec2(1.0, y),
        extentEnd = Vec2(-1.0, y),
        source = source,
        rmsResidual = 0.01,
        pointCount = 200,
        confidence = confidence,
        viewer = Vec2.ZERO,
    )

    private fun fitOk(y: Double) = WallFit(wall(y, WallSource.DEPTH_FIT), null, 200, 220)
    private fun fitFailed(hint: String? = null) = WallFit(null, WallFitFailure.NO_DOMINANT_PLANE, 40, 220, hint = hint)

    /** Crosshair samples on the wall at plan y = [y], at height [height]. */
    private fun crosshair(y: Double, height: Double = 1.5, count: Int = 12) = List(count) { Vec3(0.01 * it, height, -y) }

    private fun decide(fit: WallFit, aim: Vec3?, crosshair: List<Vec3>, plane: WallObservation?) =
        WallOutcome.decide(fit, aim, crosshair, band, plane, viewer, totalPoints = 220)

    @Test
    fun `an aimed depth fit is returned as it is`() {
        val fit = fitOk(2.0)
        val d = decide(fit, Vec3(0.0, 1.5, -2.0), crosshair(2.0), wall(2.0, WallSource.DETECTED_PLANE))
        assertSame(fit, d.fit)
        assertEquals(WallOutcome.Source.DEPTH_FIT, d.source)
    }

    @Test
    fun `a fit without an aim point is never returned`() {
        // The crosshair is on the ceiling; the samples fitted the wardrobe front at 1.4 m.
        val d = decide(fitOk(1.4), aim = null, crosshair = crosshair(2.0, height = 2.6), plane = null)
        assertNull(d.fit.observation)
        assertEquals(WallFitFailure.NO_DOMINANT_PLANE, d.fit.failure)
        assertEquals(WallOutcome.HINT_AIM_OFF_WALL, d.fit.message)
        assertEquals(WallOutcome.Source.NO_AIM, d.source)
    }

    @Test
    fun `without an aim point the hint says what to change`() {
        assertEquals(WallOutcome.HINT_NO_DEPTH_AT_AIM, decide(fitOk(2.0), null, emptyList(), null).fit.message)
        assertEquals(WallOutcome.HINT_AIM_OFF_WALL, decide(fitOk(2.0), null, crosshair(2.0, height = 0.05), null).fit.message)
        // Two samples on the wall are too few for an aim point: the hand moved off it.
        val few = crosshair(2.0, count = 2) + crosshair(2.0, height = 2.7, count = 8)
        assertEquals(WallOutcome.HINT_AIM_UNSTEADY, decide(fitOk(2.0), null, few, null).fit.message)
    }

    @Test
    fun `a plane at the crosshair stands in for a failed fit`() {
        val plane = wall(2.0, WallSource.DETECTED_PLANE)
        val d = decide(fitFailed(), Vec3(0.0, 1.5, -2.02), crosshair(2.02), plane)
        assertEquals(WallOutcome.Source.PLANE, d.source)
        assertNull(d.fit.failure)
        assertEquals(0.8, d.fit.observation!!.confidence, 1e-12)
    }

    @Test
    fun `a plane behind the surface at the crosshair is not used`() {
        // Crosshair depth says 2 m (a column, a door frame); the first wall plane along the ray is 4.5 m away.
        val farPlane = wall(4.5, WallSource.DETECTED_PLANE)
        val d = decide(fitFailed(WallEstimator.HINT_SHORT_WALL), Vec3(0.0, 1.5, -2.0), crosshair(2.0), farPlane)
        assertNull(d.fit.observation)
        assertEquals(WallOutcome.Source.FIT_FAILED, d.source)
        assertEquals(WallEstimator.HINT_SHORT_WALL, d.fit.message)
        assertFalse(WallOutcome.planeIsAtCrosshair(farPlane, crosshair(2.0), band, viewer))
    }

    @Test
    fun `a plane replacing a refused fit is flagged as unverified`() {
        val plane = wall(2.0, WallSource.DETECTED_PLANE, confidence = 0.9)
        val d = decide(fitFailed(WallEstimator.HINT_SHORT_WALL), Vec3(0.0, 1.5, -2.0), crosshair(2.0), plane)
        assertEquals(WallOutcome.Source.PLANE_AFTER_REFUSAL, d.source)
        assertEquals(WallOutcome.UNVERIFIED_PLANE_CONFIDENCE, d.fit.observation!!.confidence, 1e-12)
        // An already doubtful plane is not made to look better.
        val weak = decide(fitFailed(WallEstimator.HINT_SHORT_WALL), Vec3(0.0, 1.5, -2.0), crosshair(2.0), wall(2.0, WallSource.DETECTED_PLANE, 0.2))
        assertEquals(0.2, weak.fit.observation!!.confidence, 1e-12)
    }

    @Test
    fun `with no depth at the crosshair a plane is the only evidence and is used`() {
        val plane = wall(2.0, WallSource.DETECTED_PLANE)
        val d = decide(fitOk(1.4), aim = null, crosshair = emptyList(), plane = plane)
        assertNotNull(d.fit.observation)
        assertEquals(WallSource.DETECTED_PLANE, d.fit.observation!!.source)
        assertEquals(WallOutcome.Source.PLANE, d.source)
    }

    @Test
    fun `a plane is not used when the crosshair depth is only on the floor or ceiling`() {
        val plane = wall(2.0, WallSource.DETECTED_PLANE)
        val d = decide(fitOk(2.0), aim = null, crosshair = crosshair(2.0, height = 0.02), plane = plane)
        assertNull(d.fit.observation)
        assertEquals(WallOutcome.HINT_AIM_OFF_WALL, d.fit.message)
    }

    @Test
    fun `the plane tolerance grows with range`() {
        val near = wall(1.0, WallSource.DETECTED_PLANE)
        assertTrue(WallOutcome.planeIsAtCrosshair(near, crosshair(1.04), band, viewer))
        assertFalse(WallOutcome.planeIsAtCrosshair(near, crosshair(1.08), band, viewer))
        // At 4 m the expected depth noise is about 5 cm, so 9 cm is still "on the plane".
        val far = wall(4.0, WallSource.DETECTED_PLANE)
        assertTrue(WallOutcome.planeIsAtCrosshair(far, crosshair(4.09), band, viewer))
        assertFalse(WallOutcome.planeIsAtCrosshair(far, crosshair(4.4), band, viewer))
    }

    @Test
    fun `a failed fit with no plane is returned unchanged`() {
        val fit = fitFailed(WallEstimator.HINT_IMPRECISE)
        val d = decide(fit, Vec3(0.0, 1.5, -2.0), crosshair(2.0), plane = null)
        assertSame(fit, d.fit)
        assertEquals(WallOutcome.Source.FIT_FAILED, d.source)
    }
}
