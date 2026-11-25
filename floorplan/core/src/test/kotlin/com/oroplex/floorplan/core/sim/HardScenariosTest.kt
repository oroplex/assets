package com.oroplex.floorplan.core.sim

import org.junit.BeforeClass
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scenarios review round 2 found missing from the simulation (MATH-1, MATH-4, MATH-5, MATH-7, CAPX-8), see
 * [HardScenarios]: short walls seen at an angle, a sensor noisier than the estimator's model, errors that
 * samples share, ARCore plane hits, a wardrobe under an unmeasured ceiling.
 *
 * One rule is strict everywhere: a refused capture is acceptable (the user is told what to do), a WRONG
 * observation (accepted, but not the wall aimed at, or more than 5° / 5 cm off its face) is not. Accuracy
 * bounds have about 25 % headroom over what `core/build/sim-report.txt` shows; the simulation is deterministic.
 */
class HardScenariosTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun publishReport() {
            SimScenarios.publish()
        }

        private const val CM = 0.01
        private const val MM = 0.001
    }

    private fun label(scan: RoomScan) = "${scan.truth.name} ${scan.stationLabel}"

    /** Bounds on the scans that captured every wall (all of them on target, by [noCaptureIsEverAcceptedOnTheWrongSurface]). */
    private fun assertAccuracy(what: String, scans: List<RoomScan>, maxLength: Double, p95Length: Double, maxCorner: Double, maxAreaPct: Double, maxRotationDeg: Double) {
        val summary = ErrorSummary(scans)
        assertTrue(summary.accurate.isNotEmpty(), "$what: no complete scan")
        for (scan in summary.accurate) {
            val e = requireNotNull(scan.error)
            assertTrue(e.maxLengthError <= maxLength, "$what ${label(scan)}: |dL| ${mm(e.maxLengthError)} mm > ${mm(maxLength)} mm")
            assertTrue(e.maxCornerError <= maxCorner, "$what ${label(scan)}: corner error ${mm(e.maxCornerError)} mm")
            assertTrue(abs(e.areaErrorPct) <= maxAreaPct, "$what ${label(scan)}: area error ${e.areaErrorPct} %")
            assertTrue(e.squarenessDeg <= 0.01, "$what ${label(scan)}: not square (${e.squarenessDeg} deg)")
            val rotation = rotationDeg(requireNotNull(scan.result).dominantAngle)
            assertTrue(abs(rotation) <= maxRotationDeg, "$what ${label(scan)}: outline rotated by $rotation deg")
        }
        assertTrue(summary.p95Length <= p95Length, "$what: p95 |dL| ${mm(summary.p95Length)} mm > ${mm(p95Length)} mm")
    }

    // ---------------------------------------------------------------------------------------------
    // The strict rule

    /** MATH-1 / MATH-7: across every hard scenario, nominal and stressed, not one wrong observation. */
    @Test
    fun noCaptureIsEverAcceptedOnTheWrongSurface() {
        val scans = listOf(
            "(h)" to HardScenarios.scans, "(h2)" to HardScenarios.scansStress,
            "(a-stress)" to HardScenarios.cleanStress, "(a-field)" to HardScenarios.cleanField, "(a-narrow)" to HardScenarios.cleanNarrowGrid,
        )
        for ((what, list) in scans) {
            for (scan in list) assertEquals(0, scan.wrongWall, "$what ${label(scan)}: ${SimReport.anomaly(scan)}")
        }
        for ((what, rows) in listOf("short faces" to HardScenarios.faces, "short faces, stress" to HardScenarios.facesStress)) {
            for (r in rows) assertEquals(0, r.wrong, "$what: ${r.room} ${r.face} at ${r.incidenceDeg} deg from ${r.range} m: ${r.wrongDetails}")
        }
        for (r in HardScenarios.squareOn) assertEquals(0, r.wrong, "square-on ${r.room} from ${r.distance} m (stress = ${r.stress})")
        for (w in HardScenarios.wardrobe.filter { it.ceilingY != null }) assertEquals(0, w.wrong, "wardrobe, ${w.config}")
        // The scenarios really do put the estimator in front of the cases round 2 got wrong.
        assertTrue(HardScenarios.faces.sumOf { it.trials } >= 400, "too few short-face trials")
        assertTrue(HardScenarios.faces.count { it.incidenceDeg >= 50.0 } >= 20, "too few oblique short-face cells")
        val shortWallCaptures = HardScenarios.scans.sumOf { s -> s.captures.count { s.truth.length(it.edge) < 0.7 } }
        assertTrue(shortWallCaptures >= 50, "only $shortWallCaptures short walls in the hard rooms")
    }

    // ---------------------------------------------------------------------------------------------
    // Hard rooms

    /**
     * Nominal noise: every room comes out with all its walls, short returns included, and accurately. The short
     * walls are refused from the room's centre (43 of 310 captures fail first) and measured once the simulated
     * user stands square to them. Measured: max |dL| 7.7 mm, p95 4.8 mm, corners <= 11.7 mm.
     */
    @Test
    fun hardRooms_areSolvedWithTheirShortWalls() {
        val scans = HardScenarios.scans
        assertEquals(50, scans.size)
        for (scan in scans) {
            assertEquals(0, scan.missingWalls, "${label(scan)}: ${SimReport.anomaly(scan)}")
            assertEquals(true, scan.error?.topologyOk, "${label(scan)}: ${SimReport.anomaly(scan)}")
            assertTrue(requireNotNull(scan.result).warnings.isEmpty(), "${label(scan)}: ${scan.result?.warnings}")
        }
        assertAccuracy("(h)", scans, maxLength = 1 * CM, p95Length = 0.65 * CM, maxCorner = 1.5 * CM, maxAreaPct = 0.3, maxRotationDeg = 0.3)
        // The short walls themselves: within 1 cm in every scan.
        for (scan in scans) {
            val e = requireNotNull(scan.error)
            for (edge in 0 until scan.truth.size) {
                if (scan.truth.length(edge) < 0.7) assertTrue(abs(e.edgeLengthErrors[edge]) <= 1 * CM, "${label(scan)} wall ${edge + 1}: ${mm(e.edgeLengthErrors[edge])} mm")
            }
        }
    }

    /**
     * Pessimistic noise (1.5 x the model, 0.3 % per-frame range error, plane hits mixed in): still no wrong wall.
     * A 0.3 m return then cannot always be measured to the precision the estimator demands, even from 0.65 m:
     * 5 of 310 walls are refused four times (the app offers tapping the wall's base), and the solver says that a
     * step was assumed. Complete scans measured: max |dL| 20.1 mm, p95 11.4 mm, corners <= 31.5 mm.
     */
    @Test
    fun hardRooms_pessimisticNoise_refusesRatherThanGuesses() {
        val scans = HardScenarios.scansStress
        val captures = scans.sumOf { it.captures.size }
        val missing = scans.sumOf { it.missingWalls }
        assertTrue(missing <= 0.03 * captures, "$missing of $captures walls were never captured")
        for (scan in scans) {
            if (scan.missingWalls == 0) {
                assertEquals(true, scan.error?.topologyOk, "${label(scan)}: ${SimReport.anomaly(scan)}")
            } else {
                // A wall the user gave up on is never silent: the solver reports the step it had to assume.
                val warnings = scan.result?.warnings.orEmpty()
                assertTrue(scan.result == null || warnings.isNotEmpty(), "${label(scan)}: a wall is missing and nothing says so")
            }
        }
        assertAccuracy("(h2)", scans, maxLength = 2.6 * CM, p95Length = 1.5 * CM, maxCorner = 4 * CM, maxAreaPct = 0.6, maxRotationDeg = 0.5)
    }

    // ---------------------------------------------------------------------------------------------
    // Short walls, capture by capture

    /** What the refusal's message asks for works: square to a short wall from within twice its length. */
    @Test
    fun shortWalls_squareOnAndClose_areMeasured() {
        val near = HardScenarios.squareOn.filter { it.distance <= 2.0 * it.length + 1e-9 }
        assertTrue(near.count { !it.stress } >= 8 && near.count { it.stress } >= 8, "too few square-on cells")
        for (r in near.filter { !it.stress }) {
            assertTrue(r.onTarget >= 0.9 * r.trials, "${r.room} from ${r.distance} m: only ${r.onTarget} of ${r.trials}")
        }
        // With pessimistic noise the 0.3 m return is refused half the time even from 0.6 m; longer ones are measured.
        for (r in near.filter { it.stress && it.length >= 0.4 }) {
            assertTrue(r.onTarget >= 0.7 * r.trials, "stress, ${r.room} from ${r.distance} m: only ${r.onTarget} of ${r.trials}")
        }
        // Accepted captures are good ones. Measured: 1.44 deg / 2.5 mm nominal, 3.48 deg / 6.7 mm stressed.
        for (r in HardScenarios.squareOn) {
            assertTrue(r.maxAngle <= if (r.stress) 4.4 else 1.9, "${r.room} from ${r.distance} m (stress = ${r.stress}): ${r.maxAngle} deg")
            assertTrue(r.maxOffset <= if (r.stress) 9 * MM else 3.5 * MM, "${r.room} from ${r.distance} m (stress = ${r.stress}): ${mm(r.maxOffset)} mm")
        }
    }

    /** From across the room at 40-65 degrees the few short faces that are accepted are accurate ones. */
    @Test
    fun shortWalls_fromAcrossTheRoom_acceptedOnesAreAccurate() {
        for ((rows, maxOffset, maxAngle) in listOf(Triple(HardScenarios.faces, 6 * MM, 3.0), Triple(HardScenarios.facesStress, 12 * MM, 4.5))) {
            for (r in rows) {
                assertTrue((r.offsets.maxOrNull() ?: 0.0) <= maxOffset, "${r.room} ${r.face} ${r.incidenceDeg} deg ${r.range} m: ${r.offsets.maxOrNull()?.let(::mm)} mm")
                assertTrue((r.angles.maxOrNull() ?: 0.0) <= maxAngle, "${r.room} ${r.face} ${r.incidenceDeg} deg ${r.range} m: ${r.angles.maxOrNull()} deg")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Noise the model does not expect (MATH-4)

    /**
     * A flat wall head-on is captured at every distance up to 4.5 m with 1.5 x and 2 x the model's noise, with a
     * per-frame range error and with a smooth error across the image: round 2 failed every capture beyond 1 m
     * at 1.7 x. Bounds per variant: line offset at 4.5 m (scaled down linearly to 40 % of it at 0 m) and angle.
     */
    @Test
    fun noisierSensor_headOnWallsAreStillCapturedAndPrecise() {
        // Measured worst offset / angle: x1.5 13.2 mm / 1.30 deg; x2.0 21.3 mm / 2.13 deg; frame 17.5 mm / 1.12 deg;
        // field 5.1 mm / 2.43 deg (a smooth error tilts the wall: nothing in one capture can tell).
        val bounds = mapOf(
            "x1.5" to (17 * MM to 1.7), "x2.0" to (27 * MM to 2.7), "x1.5+frame0.3%" to (22 * MM to 1.5), "x1+field0.3%" to (7 * MM to 3.0),
        )
        assertEquals(bounds.keys, HardScenarios.sweepStress.map { it.first }.toSet())
        for ((name, rows) in HardScenarios.sweepStress) {
            val (offsetAtMax, angle) = bounds.getValue(name)
            for (row in rows) {
                assertEquals(1.0, row.successRate, "$name at ${row.distance} m: ${row.failures}")
                val maxOffset = offsetAtMax * (0.4 + 0.6 * row.distance / 4.5)
                assertTrue(row.offsets.max() <= maxOffset, "$name at ${row.distance} m: offset ${mm(row.offsets.max())} mm > ${mm(maxOffset)} mm")
                assertTrue(row.angles.max() <= angle, "$name at ${row.distance} m: angle ${row.angles.max()} deg")
            }
        }
    }

    /**
     * The sample house with pessimistic noise and plane hits: every wall on target at the first try, the right
     * topology, and accuracy about twice the nominal. Measured: max |dL| 17.9 mm, p95 8.6 mm, corners <= 23.0 mm.
     */
    @Test
    fun sampleHouse_pessimisticNoise() {
        val scans = HardScenarios.cleanStress
        for (scan in scans) {
            assertTrue(scan.allOnTarget, "${label(scan)}: ${SimReport.anomaly(scan)}")
            assertEquals(true, scan.error?.topologyOk, "${label(scan)}: ${SimReport.anomaly(scan)}")
        }
        val summary = ErrorSummary(scans)
        assertTrue(summary.firstTryFailures <= 0.01 * summary.captures, "${summary.firstTryFailures} of ${summary.captures} captures failed first")
        assertAccuracy("(a-stress)", scans, maxLength = 2.3 * CM, p95Length = 1.1 * CM, maxCorner = 3 * CM, maxAreaPct = 0.8, maxRotationDeg = 0.7)
    }

    /**
     * The sample house with a smooth 0.3 % range error across the image, fixed per capture: the kind of error real
     * depth-from-motion has and no number of samples averages away. Walls are still captured at the first try
     * (the flatness tests allow for it), but they tilt, and the room is as wrong as its walls: measured max |dL|
     * 39.6 mm, p95 16.5 mm, corners <= 52.3 mm. This bound is a statement of sensitivity, not of accuracy.
     */
    @Test
    fun sampleHouse_smoothFieldError_isCapturedButLessAccurate() {
        val scans = HardScenarios.cleanField
        for (scan in scans) {
            assertTrue(scan.allOnTarget, "${label(scan)}: ${SimReport.anomaly(scan)}")
            assertEquals(true, scan.error?.topologyOk, "${label(scan)}: ${SimReport.anomaly(scan)}")
        }
        val summary = ErrorSummary(scans)
        assertTrue(summary.firstTryFailures <= 0.01 * summary.captures, "${summary.firstTryFailures} of ${summary.captures} captures failed first")
        assertAccuracy("(a-field)", scans, maxLength = 5 * CM, p95Length = 2.1 * CM, maxCorner = 6.6 * CM, maxAreaPct = 0.9, maxRotationDeg = 1.0)
        // It IS worse than the nominal case: the scenario must keep measuring something.
        assertTrue(summary.p95Length > 2 * ErrorSummary(SimScenarios.clean).p95Length, "the field error no longer shows")
    }

    /**
     * The sample house sampled over +-8 degrees instead of +-12 (the app's grid is 25 % of the short side of the
     * view, somewhere between the two): every wall is still captured at the first try; with less of each wall
     * in view its direction is known less well. Measured: max |dL| 16.5 mm, p95 4.2 mm, corners <= 25.8 mm.
     */
    @Test
    fun sampleHouse_narrowerSamplingGrid() {
        val scans = HardScenarios.cleanNarrowGrid
        for (scan in scans) {
            assertTrue(scan.allOnTarget, "${label(scan)}: ${SimReport.anomaly(scan)}")
            assertEquals(true, scan.error?.topologyOk, "${label(scan)}: ${SimReport.anomaly(scan)}")
        }
        val summary = ErrorSummary(scans)
        assertTrue(summary.firstTryFailures <= 0.01 * summary.captures, "${summary.firstTryFailures} of ${summary.captures} captures failed first")
        assertAccuracy("(a-narrow)", scans, maxLength = 2.1 * CM, p95Length = 0.55 * CM, maxCorner = 3.3 * CM, maxAreaPct = 0.65, maxRotationDeg = 0.55)
    }

    // ---------------------------------------------------------------------------------------------
    // Plane hits (MATH-5)

    /**
     * The AR layer now takes the depth hit of a ray whenever there is one: the fitted wall is unbiased whether or
     * not ARCore planes are hit as well. Taking the nearest hit (round 2) censors depth errors on one side and
     * pulls every wall 8-17 mm towards the viewer; the row stays in the report for the record.
     */
    @Test
    fun planeHits_depthFirstIsUnbiased_nearestFirstIsNot() {
        val rows = HardScenarios.planeBias
        val depthFirst = rows.filter { "NEAREST" !in it.label }
        val nearest = rows.filter { "NEAREST" in it.label }
        assertEquals(9, depthFirst.size)
        assertEquals(3, nearest.size)
        for (b in depthFirst) {
            assertEquals(b.trials, b.accepted, "${b.label} at ${b.range} m")
            assertTrue(abs(b.meanOffset) <= 1 * MM, "${b.label} at ${b.range} m: bias ${mm(b.meanOffset)} mm")
        }
        for (b in nearest) assertTrue(b.meanOffset >= 5 * MM, "${b.label} at ${b.range} m: bias ${mm(b.meanOffset)} mm")
    }

    // ---------------------------------------------------------------------------------------------
    // Tall wardrobe (CAPX-8)

    /**
     * 2.7 m ceiling, 2.2 m wardrobe, crosshair on the strip of wall above it. With the band top the capture
     * flow passes for an unmeasured ceiling (floor + max(default, 2.4) + 0.3, for an 8 ft and a 9 ft default)
     * and with the measured ceiling, the wall behind the wardrobe is captured every time. (With no ceiling at
     * all, the round-2 call, the strip is above the band, the AR layer has no aim point to pass, and the
     * estimator fits the wardrobe front: that row stays in the report for the record.)
     */
    @Test
    fun tallWardrobe_wallAboveItIsCaptured() {
        val rows = HardScenarios.wardrobe.filter { it.ceilingY != null }
        assertEquals(3, rows.size)
        assertEquals(2.74, HardScenarios.assumedBandCeiling(2.44), 1e-12)
        assertEquals(2.7, HardScenarios.assumedBandCeiling(2.0), 1e-12)
        for (w in rows) {
            assertEquals(0, w.wrong, w.config)
            assertTrue(w.onTarget >= 28, "${w.config}: only ${w.onTarget} of 30 on the wall (${w.failures})")
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    fun reportHasTheHardSections() {
        val text = SimScenarios.publish()
        for (section in listOf("(h)", "(h2)", "short faces from the room", "short walls square-on", "noise stress", "plane hits", "(a-stress)", "(a-field)", "(a-narrow)", "tall wardrobe", "what this simulation does NOT tell us")) {
            assertTrue("== $section" in text, "report lacks section $section")
        }
    }
}
