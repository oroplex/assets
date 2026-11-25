package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.solve.RoomSolver
import org.junit.BeforeClass
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end scan simulation of the core measurement pipeline (WallEstimator.fromPoints with the aim point
 * -> RoomSolver.solveWalls) against the sample house, with the production defaults. See [SimScenarios] for
 * the scenarios and `core/build/sim-report.txt` for the measured distributions the bounds below are based on
 * (each bound has ~20 % headroom over what the pipeline achieved when it was set; the simulation is
 * deterministic). The scenarios here are the NOMINAL ones: the estimator's own noise model on a house without
 * short walls. What that leaves out is in [HardScenarios] / [HardScenariosTest].
 *
 * Accuracy assertions use the "accurate" scans (every capture on the wall that was aimed at, correct corner
 * count); that every scan IS accurate is asserted separately ([noScenarioHasWrongWallsOrTopologyFailures]).
 * The tests named after review-round-1 findings (SIM-1 … SIM-5, previously `@Ignore`d) guard those fixes.
 */
class ScanSimulationTest {
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

    /** Asserts per-scan bounds on every accurate scan, a p95 bound and a minimum share of accurate scans. */
    private fun assertAccuracy(
        what: String,
        scans: List<RoomScan>,
        maxLength: Double,
        p95Length: Double,
        maxCorner: Double,
        maxAreaPct: Double = 0.6,
        maxRotationDeg: Double = 0.5,
        minAccurateShare: Double = 1.0,
    ) {
        val summary = ErrorSummary(scans)
        assertTrue(scans.isNotEmpty(), "$what: no scans")
        assertTrue(
            summary.accurate.size >= minAccurateShare * scans.size,
            "$what: only ${summary.accurate.size} of ${scans.size} scans are on target with the right topology",
        )
        for (scan in summary.accurate) {
            val e = requireNotNull(scan.error)
            e.edgeLengthErrors.forEachIndexed { i, d ->
                assertTrue(abs(d) <= maxLength, "$what ${label(scan)} wall ${i + 1}: |dL| ${mm(d)} mm > ${mm(maxLength)} mm")
            }
            assertTrue(e.maxCornerError <= maxCorner, "$what ${label(scan)}: corner error ${mm(e.maxCornerError)} mm")
            assertTrue(abs(e.areaErrorPct) <= maxAreaPct, "$what ${label(scan)}: area error ${e.areaErrorPct} %")
            assertTrue(e.squarenessDeg <= 0.01, "$what ${label(scan)}: not square (${e.squarenessDeg} deg)")
            val rot = rotationDeg(requireNotNull(scan.result).dominantAngle)
            assertTrue(abs(rot) <= maxRotationDeg, "$what ${label(scan)}: outline rotated by $rot deg")
        }
        assertTrue(summary.p95Length <= p95Length, "$what: p95 |dL| ${mm(summary.p95Length)} mm > ${mm(p95Length)} mm")
    }

    /** Every scenario that captures whole rooms, by report section. */
    private val allScenarios: List<Pair<String, List<RoomScan>>>
        get() = listOf(
            "(a)" to SimScenarios.clean,
            "(b)" to SimScenarios.furniture,
            "(b2)" to SimScenarios.furnitureLowAim,
            "(c) raw" to SimScenarios.calibration.flatMap { it.raw },
            "(c) control" to SimScenarios.calibration.flatMap { it.control },
            "(d)" to SimScenarios.swapped,
            "(e1)" to SimScenarios.retake,
            "(e2)" to SimScenarios.closingRecapture,
            "(e3)" to SimScenarios.lateRecapture,
            "(f)" to SimScenarios.narrow,
        )

    /** Longest true wall of the room: the lever of the room's residual heading error. */
    private fun longestWall(room: SimRoom) = (0 until room.size).maxOf { room.length(it) }

    // ---------------------------------------------------------------------------------------------
    // Harness self-checks

    @Test
    fun harness_noiselessCaptureReproducesTheTrueWall() {
        for (room in SimScenarios.truthRooms) {
            for (edge in 0 until room.size) {
                val aim = room.pointOn(edge, 0.5)
                val station = aim + room.inwardNormal(edge) * min(1.5, 0.5 * room.depthInFront(edge, aim))
                val cap = ScanSimulator.capture(
                    room, Vec3.fromPlan(station, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.4), Random(7L),
                    noise = DepthNoise.NONE,
                )
                val o = assertNotNull(cap.fit.observation, "${room.name} wall ${edge + 1}: ${cap.fit.failure}")
                val truth = Line2.through(room.start(edge), room.end(edge))
                assertTrue(truth.distance(o.extentStart) < 1e-3 && truth.distance(o.extentEnd) < 1e-3, "${room.name} wall ${edge + 1} offset")
                assertTrue((o.line.direction dot truth.direction) > 0.99999, "${room.name} wall ${edge + 1} orientation")
                // The simulated AR layer hands the estimator the reticle hit, like the app.
                val aimPoint = assertNotNull(cap.aimPoint, "${room.name} wall ${edge + 1}: no aim point")
                assertTrue(truth.distance(aimPoint.toPlan()) < 1e-6, "${room.name} wall ${edge + 1}: aim point off the wall")
            }
        }
    }

    @Test
    fun harness_isDeterministic() {
        val room = SimScenarios.truthRooms[0]
        val home = SimScenarios.stations(room, 0, 1)[0]
        val again = scanRoom(SimUser(room), 0, 0, "s1/pole", home.second, (0 until room.size).toList(), 101L)
        val first = SimScenarios.clean.first { it.truth.name == room.name && it.stationLabel == "s1/pole" }
        assertEquals(first.result?.corners, again.result?.corners)
    }

    // ---------------------------------------------------------------------------------------------
    // Robustness across every scenario

    /** No capture fits another surface and every room gets its true corner count, in every scenario. */
    @Test
    fun noScenarioHasWrongWallsOrTopologyFailures() {
        for ((what, scans) in allScenarios) {
            for (scan in scans) {
                assertTrue(scan.allOnTarget, "$what ${label(scan)}: ${SimReport.anomaly(scan)}")
                assertEquals(true, scan.error?.topologyOk, "$what ${label(scan)}: ${SimReport.anomaly(scan)}")
            }
        }
    }

    /**
     * Captures of ordinary walls succeed on the first try (production defaults, users standing where the scenario
     * puts them). Since round 3 the estimator refuses walls it cannot verify (too short or too oblique to be seen
     * flat at three places, see HardScenariosTest); none of the sample house's walls is one of those from where
     * the scenarios stand.
     */
    @Test
    fun firstTryFailuresAreRare() {
        for ((what, scans) in allScenarios) {
            val s = ErrorSummary(scans)
            assertTrue(s.firstTryFailures <= 0.01 * s.captures, "$what: ${s.firstTryFailures} of ${s.captures} captures failed first")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (a) clean walls

    /** Measured: max |dL| 11.4 mm (8 m L-shaped living room), p95 3.7 mm, corners <= 19.9 mm, rotation <= 0.30 deg. */
    @Test
    fun cleanWalls_accurateScansStayWithinMeasuredBounds() {
        val scans = SimScenarios.clean
        assertAccuracy("(a)", scans, maxLength = 2.6 * CM, p95Length = 1 * CM, maxCorner = 3 * CM)
        assertTrue(ErrorSummary(scans).medianLength <= 2 * MM, "median |dL| ${mm(ErrorSummary(scans).medianLength)} mm")
        // A clean, in-order scan raises no warning at all.
        val warned = scans.filter { it.result?.warnings.orEmpty().isNotEmpty() }
        assertTrue(warned.isEmpty(), warned.joinToString { "${label(it)}: ${it.result?.warnings}" })
    }

    /**
     * FINDING SIM-5 (fixed: range-adaptive band, along-ray fit, corner-cluster trim, precision-weighted robust
     * axis). 99 % of walls are within 2 cm, and every wall within 1 cm + 0.2 % of its room's longest wall:
     * what is left is the room's heading error (~0.1 deg rms, from one 9 x 9 capture per wall) times the
     * lever from each capture to the far corners, so the tolerance scales with the room like an instrument's
     * (±(1 cm + 0.2 %)); rooms up to 5 m stay within 2 cm, the 8 m L-shaped living room within 2.6 cm.
     */
    @Test
    fun cleanWalls_everyWallWithin2cm() {
        val summary = ErrorSummary(SimScenarios.clean)
        val all = summary.accurate.flatMap { s -> requireNotNull(s.error).edgeLengthErrors.map { abs(it) } }.sorted()
        val p99 = ErrorSummary.percentile(all, 0.99)
        assertTrue(p99 <= 2 * CM, "p99 |dL| ${mm(p99)} mm")
        for (scan in summary.accurate) {
            val e = requireNotNull(scan.error)
            val tolerance = 1 * CM + 0.002 * longestWall(scan.truth)
            assertTrue(e.maxLengthError <= tolerance, "${label(scan)}: |dL| ${mm(e.maxLengthError)} mm > ${mm(tolerance)} mm")
            assertTrue(e.maxCornerError <= 1.5 * tolerance, "${label(scan)}: corner ${mm(e.maxCornerError)} mm")
            if (longestWall(scan.truth) <= 5.0) assertTrue(e.maxLengthError <= 2 * CM, "${label(scan)}: ${mm(e.maxLengthError)} mm")
        }
    }

    /** FINDING SIM-1 (fixed: aim point + range-adaptive band): every capture lands on the wall aimed at. */
    @Test
    fun cleanWalls_everyCaptureLandsOnTheAimedWall() {
        val off = SimScenarios.clean.filter { !it.allOnTarget }
        assertTrue(off.isEmpty(), "off-target scans: " + off.joinToString { label(it) })
    }

    /** FINDING SIM-1: from the hallway center the end-wall capture used to return a side wall (15/20 at 20 %). */
    @Test
    fun grazing_hallwayEndWallIsNeverConfusedWithASideWall() {
        val rows = SimScenarios.grazing.filter { it.config == "aim" }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.wrongWall == 0 && it.onTarget >= 19 }, rows.joinToString { "${it.aimFraction}: ${it.onTarget}/${it.wrongWall}/${it.failed}" })
    }

    /**
     * Without an aim point (legacy callers) the range-adaptive band alone keeps the end wall unless the aim
     * is within ~10 cm of a corner, where only the aim point can tell the walls apart.
     */
    @Test
    fun grazing_withoutAimPointTheAdaptiveBandKeepsTheEndWallAwayFromTheCorners() {
        val rows = SimScenarios.grazing.filter { it.config == "no aim" && it.aimFraction in 0.15..0.85 }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.wrongWall == 0 && it.onTarget >= 18 }, rows.joinToString { "${it.aimFraction}: ${it.onTarget}/${it.wrongWall}" })
    }

    /** FINDING SIM-4 (fixed: the far-corner warning is gone): accurate scans raise no far-corner warnings. */
    @Test
    fun warnings_accurateScansHaveNoFarCornerWarnings() {
        for ((what, scans) in allScenarios) {
            val noisy = ErrorSummary(scans).accurate.filter { s ->
                s.result?.warnings.orEmpty().any { SimReport.shortWarning(it) == "far-corner" }
            }
            assertTrue(noisy.isEmpty(), "$what: ${noisy.size} accurate scans carry far-corner warnings")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (b) furniture

    /** Measured: max |dL| 17.3 mm, p95 5.5 mm, corners <= 21.3 mm; no capture picked the furniture face. */
    @Test
    fun furniture_wallsWithin2Point5cm() {
        val scans = SimScenarios.furniture
        assertTrue(scans.all { it.allOnTarget }, "a capture fitted the furniture instead of the wall")
        assertAccuracy("(b)", scans, maxLength = 2.5 * CM, p95Length = 1 * CM, maxCorner = 3 * CM)
    }

    /** Stress variant: up to 44 % of the rays hit furniture; the aim point above it keeps the wall every time. */
    @Test
    fun furniture_lowAimStillMeasuresTheWallBehindTheFurniture() {
        val scans = SimScenarios.furnitureLowAim
        val heavy = scans.sumOf { s -> s.captures.count { it.fraction(RayHit.FURNITURE) >= 0.3 } }
        assertTrue(heavy >= 30, "the stress scenario must put >= 30 % of the rays on furniture often (was $heavy)")
        assertTrue(scans.all { it.allOnTarget }, "a capture fitted the furniture instead of the wall")
        assertAccuracy("(b2)", scans, maxLength = 2.5 * CM, p95Length = 1 * CM, maxCorner = 3 * CM, maxRotationDeg = 0.6)
    }

    // ---------------------------------------------------------------------------------------------
    // (c) AR scale error + calibration

    @Test
    fun scaleError_calibrationReturnsFloorToTruth() {
        for (c in SimScenarios.calibration) {
            // The +2 % is really there before calibration.
            val rel = c.raw.filter { it.allOnTarget }.flatMap { s ->
                val e = requireNotNull(s.error)
                e.edgeLengthErrors.mapIndexed { i, d -> d / s.truth.length(i) }
            }.sorted()
            val median = ErrorSummary.percentile(rel, 0.5)
            assertTrue(median in 0.018..0.022, "s${c.seedSet}: median raw length error ${100 * median} %")
            // FloorOps.calibrate is exactly a uniform scale about the floor's bounds center.
            assertTrue(c.scaleMathDeviation < 1e-9, "s${c.seedSet}: calibrate is not a uniform scale (${c.scaleMathDeviation})")
            assertTrue(abs(c.factor * 1.02 - 1) <= 0.001, "s${c.seedSet}: factor ${c.factor}")
            for ((i, e) in c.calibratedErrors.withIndex()) {
                val raw = c.raw[i]
                assertTrue(raw.allOnTarget && raw.error?.topologyOk == true, "s${c.seedSet} ${raw.truth.name}: ${SimReport.anomaly(raw)}")
                val name = "s${c.seedSet} ${raw.truth.name}"
                assertTrue(e.maxLengthError <= 1.3 * CM, "$name: calibrated |dL| ${mm(e.maxLengthError)} mm")
                assertTrue(e.maxCornerError <= 2 * CM, "$name: calibrated corner ${mm(e.maxCornerError)} mm")
                assertTrue(c.calibratedVsControl[i] <= 0.7 * CM, "$name: calibrated vs unscaled control ${mm(c.calibratedVsControl[i])} mm")
                // Re-solving a calibrated room from its (scaled) observations keeps the calibrated outline.
                val r = c.resolvedErrors[i]
                assertTrue(abs(r.maxCornerError - e.maxCornerError) < 1e-4, "$name: RoomOps.resolve moved the calibrated outline")
            }
        }
        val all = SimScenarios.calibration.flatMap { c -> c.calibratedErrors.flatMap { it.edgeLengthErrors } }.map { abs(it) }.sorted()
        assertTrue(ErrorSummary.percentile(all, 0.5) <= 2.5 * MM, "median calibrated |dL| ${mm(ErrorSummary.percentile(all, 0.5))} mm")
    }

    /**
     * FINDING SIM-5 (fixed): after one tape calibration 95 % of all walls of the floor are within 8 mm and
     * every wall within 5 mm + 0.1 % of its room's longest wall (the 8 m living room 1.3 cm, every other room
     * within 1 cm). The rest is the measurement error the unscaled control scans also have.
     */
    @Test
    fun scaleError_calibratedFloorWithin1cm() {
        val all = ArrayList<Double>()
        for (c in SimScenarios.calibration) {
            for ((i, e) in c.calibratedErrors.withIndex()) {
                val room = c.raw[i].truth
                all += e.edgeLengthErrors.map { abs(it) }
                val tolerance = 5 * MM + 0.001 * longestWall(room)
                assertTrue(e.maxLengthError <= tolerance, "s${c.seedSet} ${room.name}: ${mm(e.maxLengthError)} mm > ${mm(tolerance)} mm")
                if (longestWall(room) <= 5.0) assertTrue(e.maxLengthError <= 1 * CM, "s${c.seedSet} ${room.name}: ${mm(e.maxLengthError)} mm")
            }
        }
        all.sort()
        assertTrue(ErrorSummary.percentile(all, 0.95) <= 8 * MM, "p95 calibrated |dL| ${mm(ErrorSummary.percentile(all, 0.95))} mm")
    }

    // ---------------------------------------------------------------------------------------------
    // (d) / (e3) out of order

    @Test
    fun outOfOrder_rectangularRoomsAreReorderedToTheSameOutline() {
        val clean = SimScenarios.clean.associateBy { label(it) }
        for (scan in SimScenarios.swapped.filter { it.truth.size == 4 }) {
            val reference = clean.getValue(label(scan))
            val result = assertNotNull(scan.result, label(scan))
            assertTrue(RoomSolver.REORDERED_WARNING in result.warnings, "${label(scan)}: no re-ordered warning")
            // Same observations as the in-order capture: the re-ordered solve must be identical.
            val d = SimReport.outlineDistance(requireNotNull(reference.result).corners, result.corners)
            assertTrue(d < 1e-6, "${label(scan)}: differs from the in-order solve by $d m")
        }
        // A wall captured again later is merged into it: same corners, no warning needed.
        for (scan in SimScenarios.lateRecapture.filter { it.truth.size == 4 }) {
            val result = assertNotNull(scan.result, label(scan))
            assertEquals(4, result.corners.size, label(scan))
            assertTrue(result.edgeSources.none { it < 0 }, "${label(scan)}: synthesized wall in ${result.edgeSources}")
        }
        assertAccuracy("(e3) rectangles", SimScenarios.lateRecapture.filter { it.truth.size == 4 }, 2 * CM, 1 * CM, 3 * CM)
    }

    /** FINDING SIM-2 (fixed: global duplicate merge + consistency-scored order search). */
    @Test
    fun outOfOrder_lShapedRoomIsRecovered() {
        for (variant in listOf(SimScenarios.swapped, SimScenarios.lateRecapture)) {
            val scans = variant.filter { it.truth.size == 6 }
            assertTrue(scans.isNotEmpty())
            for (scan in scans) {
                val e = assertNotNull(scan.error, label(scan))
                assertTrue(scan.allOnTarget, label(scan))
                assertTrue(e.topologyOk, "${label(scan)}: ${e.solvedCount} corners instead of ${e.trueCount}")
                assertTrue(e.maxLengthError <= 2.6 * CM, "${label(scan)}: |dL| ${mm(e.maxLengthError)} mm")
                val warnings = requireNotNull(scan.result).warnings
                if (variant === SimScenarios.swapped) assertTrue(RoomSolver.REORDERED_WARNING in warnings, "${label(scan)}: $warnings")
                assertTrue(requireNotNull(scan.result).edgeSources.none { it < 0 }, "${label(scan)}: invented a wall")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (e) captured twice

    @Test
    fun capturedTwice_retakeAndClosingRecaptureAreMerged() {
        for ((what, scans) in listOf("(e1)" to SimScenarios.retake, "(e2)" to SimScenarios.closingRecapture)) {
            assertEquals(scans.size, scans.count { it.error?.topologyOk == true }, "$what: duplicate wall not merged")
            assertAccuracy(what, scans, maxLength = 2.5 * CM, p95Length = 1 * CM, maxCorner = 3 * CM)
            for (scan in ErrorSummary(scans).accurate) {
                val result = requireNotNull(scan.result)
                assertTrue(result.edgeSources.none { it < 0 }, "$what ${label(scan)}: synthesized wall in ${result.edgeSources}")
                assertTrue(result.warnings.isEmpty(), "$what ${label(scan)}: ${result.warnings}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (f) narrow rooms

    /** Measured: |dL| <= 2.1 mm, corners <= 17.5 mm (0.3 deg heading over the 5.4 m hallway). */
    @Test
    fun narrowRooms_closeRangeCaptures() {
        val scans = SimScenarios.narrow
        assertTrue(scans.all { it.allOnTarget })
        assertTrue(scans.all { s -> s.captures.all { it.distance < 1.0 } }, "camera must stay within 1 m of the walls")
        assertAccuracy("(f)", scans, maxLength = 0.5 * CM, p95Length = 0.3 * CM, maxCorner = 2.2 * CM)
    }

    // ---------------------------------------------------------------------------------------------
    // Distance sweep

    @Test
    fun distanceSweep_capturesAreAccurateAtEveryDistance() {
        for (row in SimScenarios.sweep) {
            val maxOffset = if (row.distance <= 1.5) 0.003 else 0.008
            assertTrue(row.offsets.max() <= maxOffset, "${row.distance} m: offset ${mm(row.offsets.max())} mm")
            assertTrue(row.angles.max() <= 1.0, "${row.distance} m: angle ${row.angles.max()} deg")
        }
    }

    /** FINDING SIM-3 (fixed: range-adaptive noise gate): head-on captures from 0.5 to 4.5 m all succeed. */
    @Test
    fun distanceSweep_wallsWithin4mAreAccepted() {
        for (row in SimScenarios.sweep) {
            assertEquals(1.0, row.successRate, "${row.distance} m: ${row.failures}")
        }
        assertTrue(WallEstimatorConfig().maxRange >= 4.5)
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    fun writesReport() {
        val text = SimScenarios.publish()
        assertTrue(SimScenarios.reportFile.isFile, "${SimScenarios.reportFile} missing")
        assertEquals(text, SimScenarios.reportFile.readText())
        for (section in listOf("(a)", "(b)", "(b2)", "(c)", "(d)", "(e1)", "(e2)", "(e3)", "(f)", "distance sweep", "grazing")) {
            assertTrue("== $section" in text, "report lacks section $section")
        }
    }
}
