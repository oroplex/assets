package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.WallEstimatorConfig
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.solve.RoomSolver
import org.junit.BeforeClass
import org.junit.Ignore
import java.util.Random
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end scan simulation of the core measurement pipeline (WallEstimator.fromPoints ->
 * RoomSolver.solveWalls) against the sample house. See [SimScenarios] for the scenarios and
 * `core/build/sim-report.txt` for the measured distributions the thresholds below are based on.
 *
 * Accuracy assertions use the "accurate" scans (every capture on the wall that was aimed at, correct corner
 * count); captures that land on a different surface are a separate finding (SIM-1) with its own test.
 * Tests marked `@Ignore("FINDING …")` fail today because of a production-code defect and pass once fixed.
 */
class ScanSimulationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun publishReport() {
            SimScenarios.publish()
        }

        private const val CM = 0.01
    }

    private fun label(scan: RoomScan) = "${scan.truth.name} ${scan.stationLabel}"

    /** Asserts per-scan bounds on every accurate scan, a p95 bound and a minimum share of accurate scans. */
    private fun assertAccuracy(
        what: String,
        scans: List<RoomScan>,
        maxLength: Double,
        p95Length: Double,
        maxCorner: Double,
        maxAreaPct: Double = 1.0,
        maxRotationDeg: Double = 0.5,
        minAccurateShare: Double = 0.9,
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
    // (a) clean walls

    /** Measured (5 seed sets × 2 stations × 8 rooms): max |dL| 24 mm, p95 8 mm, corners ≤ 23 mm, rotation ≤ 0.35°. */
    @Test
    fun cleanWalls_accurateScansStayWithinMeasuredBounds() {
        val scans = SimScenarios.clean
        assertEquals(scans.size, scans.count { it.error?.topologyOk == true }, "every clean scan has the true corner count")
        assertAccuracy("(a)", scans, maxLength = 3 * CM, p95Length = 1.2 * CM, maxCorner = 3.5 * CM)
        assertTrue(ErrorSummary(scans).medianLength <= 0.004)
    }

    @Ignore(
        "FINDING SIM-5: room rotation from the dominant-angle estimate (per-wall TLS direction error x " +
            "patch-to-corner lever arm) puts a 2.25 m wall of the L-shaped living room 24 mm off",
    )
    @Test
    fun cleanWalls_everyWallWithin2cm() {
        assertAccuracy("(a) strict", SimScenarios.clean, maxLength = 2 * CM, p95Length = 1 * CM, maxCorner = 2 * CM)
    }

    @Ignore(
        "FINDING SIM-1: WallEstimator.fromPoints fits a side wall seen at grazing incidence instead of the " +
            "aimed-at hallway end wall (fixed 2.5 cm RANSAC band + per-point TOO_NOISY gate)",
    )
    @Test
    fun cleanWalls_everyCaptureLandsOnTheAimedWall() {
        val off = SimScenarios.clean.filter { !it.allOnTarget }
        assertTrue(off.isEmpty(), "off-target scans: " + off.joinToString { label(it) })
    }

    @Ignore("FINDING SIM-1: from the hallway center the end-wall capture returns a side wall (15/20 at 20 %, 11/20 at 80 %)")
    @Test
    fun grazing_hallwayEndWallIsNeverConfusedWithASideWall() {
        val rows = SimScenarios.grazing.filter { it.config == "default" }
        assertTrue(rows.all { it.wrongWall == 0 }, rows.joinToString { "${it.aimFraction}: ${it.wrongWall} wrong" })
    }

    /** The harness's candidate fix for SIM-1 (range-adaptive band) never confuses the walls. */
    @Test
    fun grazing_rangeAdaptiveBandKeepsTheEndWall() {
        val rows = SimScenarios.grazing.filter { it.config == "adaptive" }
        assertTrue(rows.all { it.wrongWall == 0 && it.onTarget >= 18 }, rows.joinToString { "${it.aimFraction}: ${it.onTarget}/${it.wrongWall}" })
    }

    @Ignore(
        "FINDING SIM-4: RoomSolver far-corner warnings fire on a third of accurate scans (any wall > ~4 m: a " +
            "single capture observes only ~0.4 x range of wall) and feed the reorder penalty",
    )
    @Test
    fun warnings_accurateScansHaveNoFarCornerWarnings() {
        val noisy = ErrorSummary(SimScenarios.clean).accurate.filter { s ->
            s.result?.warnings.orEmpty().any { SimReport.shortWarning(it) == "far-corner" }
        }
        assertTrue(noisy.isEmpty(), "${noisy.size} accurate scans carry far-corner warnings")
    }

    // ---------------------------------------------------------------------------------------------
    // (b) furniture

    /** Measured: max |dL| 23 mm, p95 9 mm, corners ≤ 31 mm; no capture picked the furniture face. */
    @Test
    fun furniture_wallsWithin3cm() {
        val scans = SimScenarios.furniture
        assertTrue(scans.all { it.allOnTarget }, "a capture fitted the furniture instead of the wall")
        assertAccuracy("(b)", scans, maxLength = 3 * CM, p95Length = 1.2 * CM, maxCorner = 4 * CM)
    }

    /** Stress variant: up to 44 % of the rays hit furniture; "prefer farther" still picks the wall every time. */
    @Test
    fun furniture_lowAimStillMeasuresTheWallBehindTheFurniture() {
        val scans = SimScenarios.furnitureLowAim
        val heavy = scans.sumOf { s -> s.captures.count { it.fraction(RayHit.FURNITURE) >= 0.3 } }
        assertTrue(heavy >= 30, "the stress scenario must put >= 30 % of the rays on furniture often (was $heavy)")
        assertTrue(scans.all { it.allOnTarget }, "a capture fitted the furniture instead of the wall")
        assertAccuracy("(b2)", scans, maxLength = 3 * CM, p95Length = 1.5 * CM, maxCorner = 4 * CM, maxRotationDeg = 0.8)
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
            assertTrue(abs(c.factor * 1.02 - 1) <= 0.003, "s${c.seedSet}: factor ${c.factor}")
            for ((i, e) in c.calibratedErrors.withIndex()) {
                val raw = c.raw[i]
                if (!raw.allOnTarget || raw.error?.topologyOk != true) continue
                val name = "s${c.seedSet} ${raw.truth.name}"
                assertTrue(e.maxLengthError <= 3.5 * CM, "$name: calibrated |dL| ${mm(e.maxLengthError)} mm")
                assertTrue(e.maxCornerError <= 4.5 * CM, "$name: calibrated corner ${mm(e.maxCornerError)} mm")
                assertTrue(c.calibratedVsControl[i] <= 3 * CM, "$name: calibrated vs unscaled control ${mm(c.calibratedVsControl[i])} mm")
                // Re-solving a calibrated room from its (scaled) observations keeps the calibrated outline.
                val r = c.resolvedErrors[i]
                assertTrue(abs(r.maxCornerError - e.maxCornerError) < 1e-4, "$name: RoomOps.resolve moved the calibrated outline")
            }
        }
        val all = SimScenarios.calibration.flatMap { c ->
            c.calibratedErrors.filterIndexed { i, _ -> c.raw[i].allOnTarget }.flatMap { it.edgeLengthErrors }
        }.map { abs(it) }.sorted()
        assertTrue(ErrorSummary.percentile(all, 0.5) <= 0.005, "median calibrated |dL| ${mm(ErrorSummary.percentile(all, 0.5))} mm")
    }

    @Ignore(
        "FINDING SIM-5: the calibration itself is exact, but per-capture measurement error (control scans without " +
            "any scale error are already 21 mm off in the living room) keeps calibrated walls from 1 cm",
    )
    @Test
    fun scaleError_calibratedFloorWithin1cm() {
        for (c in SimScenarios.calibration) {
            for ((i, e) in c.calibratedErrors.withIndex()) {
                if (!c.raw[i].allOnTarget) continue
                assertTrue(e.maxLengthError <= 1 * CM, "s${c.seedSet} ${c.raw[i].truth.name}: ${mm(e.maxLengthError)} mm")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (d) / (e3) out of order

    @Test
    fun outOfOrder_rectangularRoomsAreReorderedToTheSameOutline() {
        val clean = SimScenarios.clean.associateBy { label(it) }
        for (variant in listOf(SimScenarios.swapped, SimScenarios.lateRecapture)) {
            for (scan in variant.filter { it.truth.size == 4 }) {
                val reference = clean.getValue(label(scan))
                if (!reference.allOnTarget || !scan.allOnTarget) continue
                val result = assertNotNull(scan.result, label(scan))
                assertTrue(RoomSolver.REORDERED_WARNING in result.warnings, "${label(scan)}: no re-ordered warning")
                assertEquals(4, result.corners.size, label(scan))
                if (variant === SimScenarios.swapped) {
                    // Same observations as the in-order capture: the re-ordered solve must be identical.
                    val d = SimReport.outlineDistance(requireNotNull(reference.result).corners, result.corners)
                    assertTrue(d < 1e-6, "${label(scan)}: differs from the in-order solve by $d m")
                }
            }
        }
        assertAccuracy("(e3) rectangles", SimScenarios.lateRecapture.filter { it.truth.size == 4 }, 3 * CM, 1.2 * CM, 3.5 * CM)
    }

    @Ignore(
        "FINDING SIM-2: RoomSolver.solveWalls bridges mis-ordered same-facing walls of an L-shaped room with " +
            "penalty-free jog connectors and keeps the wrong 8-corner outline (corner error up to 2.26 m, area -31 %)",
    )
    @Test
    fun outOfOrder_lShapedRoomIsRecovered() {
        for (variant in listOf(SimScenarios.swapped, SimScenarios.lateRecapture)) {
            val scans = variant.filter { it.truth.size == 6 && it.allOnTarget }
            assertTrue(scans.isNotEmpty())
            for (scan in scans) {
                val e = assertNotNull(scan.error, label(scan))
                assertTrue(e.topologyOk, "${label(scan)}: ${e.solvedCount} corners instead of ${e.trueCount}")
                assertTrue(e.maxLengthError <= 3 * CM, "${label(scan)}: |dL| ${mm(e.maxLengthError)} mm")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (e) captured twice

    @Test
    fun capturedTwice_retakeAndClosingRecaptureAreMerged() {
        for ((what, scans) in listOf("(e1)" to SimScenarios.retake, "(e2)" to SimScenarios.closingRecapture)) {
            assertEquals(scans.size, scans.count { it.error?.topologyOk == true }, "$what: duplicate wall not merged")
            assertAccuracy(what, scans, maxLength = 3 * CM, p95Length = 1.2 * CM, maxCorner = 3.5 * CM)
            for (scan in ErrorSummary(scans).accurate) {
                val sources = requireNotNull(scan.result).edgeSources
                assertTrue(sources.none { it < 0 }, "$what ${label(scan)}: synthesized wall in $sources")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // (f) narrow rooms

    /** Measured: |dL| ≤ 3 mm, corners ≤ 19 mm (0.34° rotation over the 5.4 m hallway). */
    @Test
    fun narrowRooms_closeRangeCaptures() {
        val scans = SimScenarios.narrow
        assertTrue(scans.all { it.allOnTarget })
        assertTrue(scans.all { s -> s.captures.all { it.distance < 1.0 } }, "camera must stay within 1 m of the walls")
        assertAccuracy("(f)", scans, maxLength = 1 * CM, p95Length = 0.6 * CM, maxCorner = 2.5 * CM, minAccurateShare = 1.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Distance sweep

    @Test
    fun distanceSweep_closeRangeCapturesSucceedAndAreAccurate() {
        for (row in SimScenarios.sweep.filter { it.distance <= 1.5 }) {
            assertEquals(1.0, row.successRate, "${row.distance} m: ${row.failures}")
            assertTrue(row.defaultOffsets.max() <= 0.005, "${row.distance} m: offset ${mm(row.defaultOffsets.max())} mm")
        }
        // With the gate relaxed the line is still good to ~1 cm at 4 m: the data supports longer range.
        for (row in SimScenarios.sweep) assertTrue(row.relaxedOffsets.max() <= 0.015, "${row.distance} m relaxed")
    }

    @Ignore(
        "FINDING SIM-3: WallEstimatorConfig.maxRms = 3 cm gates per-point depth noise, so every head-on capture " +
            "beyond ~2.2 m fails TOO_NOISY (100 % at 2.5-4 m) although the fitted line is within ~1 cm",
    )
    @Test
    fun distanceSweep_wallsWithin4mAreAccepted() {
        for (row in SimScenarios.sweep) {
            assertTrue(row.successRate >= 0.9, "${row.distance} m: ${row.failures}")
        }
        assertTrue(WallEstimatorConfig().maxRange >= 4.0)
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
