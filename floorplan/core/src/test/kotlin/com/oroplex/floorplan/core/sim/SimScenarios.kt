package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.RobustStats
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.FloorOps
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.core.solve.SolverOptions
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/** Results of the whole-floor calibration scenario (c) for one seed set. */
internal class CalibrationOutcome(
    val seedSet: Int,
    /** The +2 % scans, pole station, one per room (in [SimScenarios.truthRooms] order). */
    val raw: List<RoomScan>,
    /** Control: identical seeds without the scale error. */
    val control: List<RoomScan>,
    val calibratedFloor: Floor,
    val calibrationTrueEdge: Int,
    val calibrationSolvedEdge: Int,
    val trueLength: Double,
    val factor: Double,
    /** Whole-floor translation removed before comparing (calibration scales about the floor center). */
    val translation: Vec2,
    val calibratedErrors: List<OutlineError>,
    val resolvedErrors: List<OutlineError>,
    /** Max |calibrated corner − (floor center + (raw corner − center) · factor)| over the floor. */
    val scaleMathDeviation: Double,
    /** Per room: max distance between calibrated (translation removed) and control corners, NaN on mismatch. */
    val calibratedVsControl: List<Double>,
)

/** One row of the distance sweep (a single flat wall seen head-on from [distance]). */
internal class SweepRow(
    val distance: Double,
    val trials: Int,
    /** Failures of the production estimator (with aim point). */
    val failures: Map<WallFitFailure, Int>,
    /** |offset| of the fitted line at the aim point (m) and |angle| error (deg), accepted captures. */
    val offsets: List<Double>,
    val angles: List<Double>,
) {
    val successRate: Double get() = 1.0 - failures.values.sum().toDouble() / trials
}

/** Outcome counts of the grazing-incidence experiment for one aim position. */
internal class GrazingRow(val aimFraction: Double, val config: String, val onTarget: Int, val wrongWall: Int, val failed: Int)

/**
 * The scan scenarios over the sample house (SampleProjects.demo). Every scenario is run for [SEED_SETS]
 * independent seed sets (and two home stations per room), computed once, lazily, and written to
 * `core/build/sim-report.txt` (and stdout) so every test reads the same deterministic numbers.
 */
internal object SimScenarios {
    private const val NOW = 1_700_000_000_000L

    val SEED_SETS = 1..5

    val project = SampleProjects.demo(NOW)
    val truthRooms: List<SimRoom> = project.floors.first().rooms.map { SimRoom(it.name, it.corners) }

    val NARROW = setOf("Hallway", "Linen Closet")

    /** Home stations: the pole of inaccessibility and a seeded, jittered second position. */
    fun stations(room: SimRoom, roomIndex: Int, seedSet: Int): List<Pair<String, Vec2>> {
        val pole = room.pole
        val r = room.clearance(pole)
        val rng = Random(seedOf(77L, roomIndex.toLong(), seedSet.toLong()))
        var jitter = pole
        for (k in 0 until 100) {
            val c = pole + Vec2.fromAngle(rng.nextDouble() * 2 * PI, r * (0.3 + 0.4 * rng.nextDouble()))
            if (room.clearance(c) >= max(0.12, 0.45 * r)) {
                jitter = c
                break
            }
        }
        return listOf("pole" to pole, "jitter" to jitter)
    }

    /** Furniture (0.6 m deep, [height] tall) against every other wall where the room is deep enough. */
    fun furnished(room: SimRoom, roomIndex: Int, seedSet: Int, height: ClosedFloatingPointRange<Double> = 0.5..0.9): SimRoom {
        val rng = Random(seedOf(55L, roomIndex.toLong(), seedSet.toLong()))
        val boxes = (0 until room.size).filter { it % 2 == 0 }.mapNotNull { e ->
            val len = room.length(e)
            val h = height.start + (height.endInclusive - height.start) * rng.nextDouble()
            if (len < 1.0 || room.depthInFront(e, room.pointOn(e, 0.5)) < 2.0) return@mapNotNull null
            FurnitureBox(e, room.start(e), room.direction(e), room.inwardNormal(e), 0.1 * len, 0.9 * len, 0.6, h)
        }
        return room.withFurniture(boxes)
    }

    private fun scanAll(
        base: Long,
        roomsOf: (Int) -> List<IndexedValue<SimRoom>> = { truthRooms.withIndex().toList() },
        stationsOf: (SimRoom, Int, Int) -> List<Pair<String, Vec2?>> = { r, i, k -> stations(r, i, k) },
        sequenceOf: (SimRoom) -> List<Int> = { r -> (0 until r.size).toList() },
        userOf: (SimRoom) -> SimUser = { SimUser(it) },
        nearDistance: (String) -> Double? = { null },
    ): List<RoomScan> = SEED_SETS.flatMap { k ->
        roomsOf(k).flatMap { (i, room) ->
            val user = userOf(room)
            stationsOf(room, i, k).mapIndexed { si, (label, home) ->
                scanRoom(user, i, si, "s$k/$label", home, sequenceOf(room), base * 100 + k, nearDistance(label))
            }
        }
    }

    /** (a) Clean walls, every room, two stations each. */
    val clean: List<RoomScan> by lazy { scanAll(base = 1) }

    private fun furnishedRooms(k: Int, height: ClosedFloatingPointRange<Double> = 0.5..0.9) =
        truthRooms.withIndex().map { (i, r) -> IndexedValue(i, furnished(r, i, k, height)) }
            .filter { it.value.furniture.isNotEmpty() }

    /** (b) Furniture in front of every other wall (rooms deep enough for it). */
    val furniture: List<RoomScan> by lazy { scanAll(base = 2, roomsOf = { k -> furnishedRooms(k) }) }

    /**
     * (b2) Stress variant: 0.85–0.9 m furniture and the user aiming just above it (0.95–1.15 m), so the
     * lower half of the 9 × 9 grid lands on the furniture and "prefer farther" has to pick the wall.
     */
    val furnitureLowAim: List<RoomScan> by lazy {
        scanAll(
            base = 25,
            roomsOf = { k -> furnishedRooms(k, 0.85..0.9) },
            userOf = { SimUser(it, aimHeight = 0.95..1.15) },
        )
    }

    /** (d) Walls 2 and 3 (indices 1, 2) captured in swapped order; same observations as (a). */
    val swapped: List<RoomScan> by lazy {
        scanAll(base = 1, sequenceOf = { r -> (0 until r.size).toMutableList().also { it[1] = 2; it[2] = 1 } })
    }

    /** (e1) Wall 2 (index 1) captured twice in a row (a retake). */
    val retake: List<RoomScan> by lazy {
        scanAll(base = 1, sequenceOf = { r -> listOf(0, 1, 1) + (2 until r.size) })
    }

    /** (e2) The first wall captured again at the end "to close the room". */
    val closingRecapture: List<RoomScan> by lazy {
        scanAll(base = 1, sequenceOf = { r -> (0 until r.size).toList() + 0 })
    }

    /** (e3) Wall 2 (index 1) captured again later, after wall 4 (index 3), i.e. not consecutively. */
    val lateRecapture: List<RoomScan> by lazy {
        scanAll(base = 1, sequenceOf = { r -> (0 until r.size).toList().let { it.take(4) + 1 + it.drop(4) } })
    }

    /** (f) Narrow rooms captured from close range (< 1 m from every wall). */
    val narrow: List<RoomScan> by lazy {
        scanAll(
            base = 6,
            roomsOf = { truthRooms.withIndex().filter { it.value.name in NARROW } },
            stationsOf = { _, _, _ -> listOf("near90" to null, "near60" to null) },
            nearDistance = { label -> if (label.endsWith("near90")) 0.9 else 0.6 },
        )
    }

    /** (c) +2 % AR scale on every hit and pose, then FloorOps.calibrate from one tape-measured wall. */
    val calibration: List<CalibrationOutcome> by lazy { SEED_SETS.map { runCalibration(it) } }

    private fun floorScan(seedSet: Int, scale: Double): List<RoomScan> {
        val distortion = ArDistortion(scale = scale, origin = Vec3.ZERO)
        return truthRooms.mapIndexed { i, room ->
            val home = stations(room, i, seedSet).first()
            val user = SimUser(room, distortion = distortion)
            scanRoom(user, i, 0, "s$seedSet/${home.first}", home.second, (0 until room.size).toList(), 300L + seedSet)
        }
    }

    private fun runCalibration(seedSet: Int): CalibrationOutcome {
        val raw = floorScan(seedSet, 1.02)
        val control = floorScan(seedSet, 1.0)
        val rooms = raw.mapIndexed { i, s ->
            Room(
                id = "sim-room-$i",
                name = s.truth.name,
                corners = s.result?.corners ?: s.truth.corners.map { it * 1.02 },
                ceilingHeight = 2.44 * 1.02,
                captureMethod = CaptureMethod.WALLS,
                observations = s.observations,
            )
        }
        val floor = Floor(id = "sim-floor", name = "Main Floor", rooms = rooms)
        // Tape-measure the longest wall of the first room (Living / Kitchen, the 8.0 m west wall).
        val truth = truthRooms[0]
        val trueEdge = (0 until truth.size).maxBy { truth.length(it) }
        val solvedEdge = OutlineError.of(truth.corners.map { it * 1.02 }, rooms[0].corners).solvedEdge(trueEdge)
        val calibrated = FloorOps.calibrate(floor, rooms[0].id, solvedEdge, truth.length(trueEdge), NOW)
        val factor = calibrated.calibration?.factor ?: Double.NaN

        // FloorOps.calibrate must be exactly a uniform scale about the floor's bounds center.
        val center = Bounds.of(rooms.flatMap { it.corners }).center
        var mathDev = 0.0
        rooms.forEachIndexed { i, r ->
            r.corners.forEachIndexed { k, c ->
                mathDev = max(mathDev, calibrated.rooms[i].corners[k].distanceTo(center + (c - center) * factor))
            }
        }

        // The calibrated floor is the true floor shifted rigidly (the AR scale grew from the AR origin, the
        // calibration shrinks about the floor center). Component-wise median over all matched corners, so
        // one badly solved room cannot skew it.
        val dx = ArrayList<Double>()
        val dy = ArrayList<Double>()
        calibrated.rooms.forEachIndexed { i, room ->
            val e = OutlineError.of(truthRooms[i].corners, room.corners)
            if (!e.topologyOk) return@forEachIndexed
            for (k in 0 until e.trueCount) {
                val d = room.corners[(k + e.shift) % e.solvedCount] - truthRooms[i].corners[k]
                dx += d.x
                dy += d.y
            }
        }
        val translation = if (dx.isEmpty()) Vec2.ZERO else Vec2(RobustStats.median(dx), RobustStats.median(dy))
        val calErrors = calibrated.rooms.mapIndexed { i, room -> OutlineError.of(truthRooms[i].corners, room.corners, translation) }
        val resolvedErrors = calibrated.rooms.mapIndexed { i, room ->
            OutlineError.of(truthRooms[i].corners, RoomOps.resolve(room, SolverOptions()).corners, translation)
        }
        val vsControl = calibrated.rooms.mapIndexed { i, room ->
            val c = control[i].result?.corners ?: return@mapIndexed Double.NaN
            if (c.size != room.corners.size) Double.NaN else OutlineError.of(c, room.corners, translation).maxCornerError
        }
        return CalibrationOutcome(
            seedSet = seedSet,
            raw = raw,
            control = control,
            calibratedFloor = calibrated,
            calibrationTrueEdge = trueEdge,
            calibrationSolvedEdge = solvedEdge,
            trueLength = truth.length(trueEdge),
            factor = factor,
            translation = translation,
            calibratedErrors = calErrors,
            resolvedErrors = resolvedErrors,
            scaleMathDeviation = mathDev,
            calibratedVsControl = vsControl,
        )
    }

    private val sweepRoom = SimRoom("Sweep 8x8", listOf(Vec2(0.0, 0.0), Vec2(8.0, 0.0), Vec2(8.0, 8.0), Vec2(0.0, 8.0)))

    /** Distances of the sweep (m). */
    val SWEEP_DISTANCES = listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5)

    /**
     * Distance sweep: one flat wall (an 8 x 8 m room) seen head-on from 0.5-4.5 m, 30 captures each, with
     * the production estimator (range-adaptive band and noise gate, aim point).
     */
    val sweep: List<SweepRow> by lazy {
        val truth = Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        SWEEP_DISTANCES.map { d ->
            val failures = HashMap<WallFitFailure, Int>()
            val offsets = ArrayList<Double>()
            val angles = ArrayList<Double>()
            val trials = 30
            for (k in 0 until trials) {
                val rng = Random(seedOf(91L, k.toLong()))
                val aimX = 3.0 + 2.0 * rng.nextDouble()
                val aim = Vec3.fromPlan(Vec2(aimX, 0.0), 1.0 + 0.8 * rng.nextDouble())
                val station = Vec3.fromPlan(Vec2(aimX, d), CAMERA_HEIGHT)
                val seed = seedOf(93L, (d * 1000).toLong(), k.toLong())
                val fit = ScanSimulator.capture(sweepRoom, station, aim, Random(seed)).fit
                fit.failure?.let { failures.merge(it, 1, Int::plus) }
                fit.observation?.let { o ->
                    offsets += abs(o.line.signedDistance(Vec2(aimX, 0.0)))
                    angles += Math.toDegrees(Math.asin(o.line.sinAngleTo(truth).coerceIn(0.0, 1.0)))
                }
            }
            SweepRow(d, trials, failures, offsets, angles)
        }
    }

    /**
     * Grazing-incidence experiment: from the middle of the hallway (its pole of inaccessibility, 2.7 m from
     * the end walls) aim at the west end wall (wall 4) at several positions across it, 1.4 m high, 20
     * captures each; does the fit land on the end wall or on a side wall seen at grazing incidence? Run with
     * the aim point (production) and without it (the estimator's fallback heuristics alone).
     */
    val grazing: List<GrazingRow> by lazy {
        val hall = truthRooms.first { it.name == "Hallway" }
        val edge = 3
        val station = Vec3.fromPlan(hall.pole, CAMERA_HEIGHT)
        val user = SimUser(hall)
        listOf(0.1, 0.2, 0.35, 0.5, 0.65, 0.8, 0.9).flatMap { s ->
            listOf("aim" to true, "no aim" to false).map { (name, useAim) ->
                var on = 0
                var wrong = 0
                var failed = 0
                for (k in 0 until 20) {
                    val aim = Vec3.fromPlan(hall.pointOn(edge, s), 1.4)
                    val fit = ScanSimulator.capture(
                        hall, station, aim, Random(seedOf(97L, (s * 100).toLong(), k.toLong())), useAim = useAim,
                    ).fit
                    val o = fit.observation
                    when {
                        o == null -> failed++
                        user.matchEdge(o) == edge -> on++
                        else -> wrong++
                    }
                }
                GrazingRow(s, name, on, wrong, failed)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Report

    val report: String by lazy { buildReport() }

    /** `core/build/sim-report.txt` (Gradle runs tests with the module directory as working directory). */
    val reportFile: File by lazy {
        val cwd = File(System.getProperty("user.dir"))
        val build = if (File(cwd, "core/build.gradle.kts").exists()) File(cwd, "core/build") else File(cwd, "build")
        build.mkdirs()
        File(build, "sim-report.txt")
    }

    private val published: String by lazy {
        reportFile.writeText(report)
        println(report)
        report
    }

    /** Builds (once), prints and writes the report; returns its text. */
    fun publish(): String = published

    private fun buildReport(): String = buildString {
        appendLine("FloorPlan Scan - scan simulation report (WallEstimator.fromPoints -> RoomSolver.solveWalls)")
        appendLine(
            "Ground truth: SampleProjects.demo (8 rooms). Camera 1.4 m high; per wall 15 frames x 81 rays (9x9 grid, " +
                "+-12 deg) aimed at a random point of the middle 60 % of the edge, 1.0-1.8 m high; first surface hit; " +
                "depth noise sigma = 5 mm + 1.2 % of range along the ray; 10 % outliers; floorY 0, ceilingY 2.44; the " +
                "AR layer passes the aim point (median of the center-ray hits) like the app; production WallEstimatorConfig " +
                "and default SolverOptions. ${SEED_SETS.count()} seed sets x 2 stations per room unless noted. On TOO_NOISY/TOO_FAR " +
                "the simulated user steps closer (to 60 %), on NO_DOMINANT_PLANE/TOO_FEW_POINTS re-aims (max 4 tries).",
        )
        appendLine(
            "Columns: scans; topo = scans with the true corner count; offT = scans with >= 1 capture that fitted a " +
                "different surface than the aimed-at wall; 1stfail = captures whose first try failed / captures; dist = " +
                "mean camera-to-aim distance (m). Accuracy columns are over scans that are on target with correct " +
                "topology: maxdL / p95dL = wall length error (mm), corner = max corner position error (mm), area% = max " +
                "|area error|, sq = max interior-angle error, rot = max |rotation| of the outline (deg). '!' lines list " +
                "every other scan.",
        )
        appendLine()
        append(SimReport.section("(a) clean walls", clean))
        appendLine()
        append(SimReport.section("(b) furniture 0.6 m deep, 0.5-0.9 m tall, in front of every other wall", furniture, furnitureStats(furniture)))
        appendLine()
        append(
            SimReport.section(
                "(b2) stress: 0.85-0.9 m furniture, user aims just above it (0.95-1.15 m)",
                furnitureLowAim, furnitureStats(furnitureLowAim),
            ),
        )
        appendLine()
        append(calibrationSection("(c)", calibration))
        appendLine()
        append(SimReport.section("(d) walls 2 and 3 captured in swapped order (same captures as (a))", swapped))
        appendLine()
        append(SimReport.section("(e1) wall 2 captured twice in a row", retake))
        appendLine()
        append(SimReport.section("(e2) first wall captured again at the end", closingRecapture))
        appendLine()
        append(SimReport.section("(e3) wall 2 captured again after wall 4 (non-consecutive)", lateRecapture))
        appendLine()
        append(SimReport.section("(f) narrow rooms, camera < 1 m from the walls", narrow))
        appendLine()
        append(sweepSection())
        appendLine()
        append(grazingSection())
    }

    private fun furnitureStats(scans: List<RoomScan>): List<String> {
        val furnishedCaptures = scans.flatMap { scan ->
            scan.captures.filter { c -> scan.truth.furniture.any { it.edge == c.edge } }
        }
        val fractions = furnishedCaptures.map { it.fraction(RayHit.FURNITURE) }.sorted()
        val heavy = fractions.count { it >= 0.3 }
        return listOf(
            String.format(
                Locale.ROOT,
                "furnished-wall captures: %d; rays on furniture: median %.0f %%, p90 %.0f %%, max %.0f %%; %d captures with >= 30 %% on furniture",
                furnishedCaptures.size, 100 * ErrorSummary.percentile(fractions, 0.5), 100 * ErrorSummary.percentile(fractions, 0.9),
                100 * (fractions.lastOrNull() ?: 0.0), heavy,
            ),
        )
    }

    private fun calibrationSection(id: String, calibration: List<CalibrationOutcome>): String = buildString {
        val raws = calibration.flatMap { it.raw }
        append(SimReport.section("$id AR scale +2 % on all hits and poses: raw scans before calibration (pole station)", raws))
        val controls = calibration.flatMap { it.control }
        appendLine("  control (identical seeds, no scale error): " + SimReport.row("ALL", ErrorSummary(controls)).trim())
        for (c in calibration) {
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  s%d: FloorOps.calibrate from Living / Kitchen true edge %d (solved edge %d) = %.4f m -> factor %.5f " +
                        "(ideal %.5f); scale-about-center deviation %.1e m; translation removed (%.0f, %.0f) mm",
                    c.seedSet, c.calibrationTrueEdge + 1, c.calibrationSolvedEdge + 1, c.trueLength, c.factor, 1 / 1.02,
                    c.scaleMathDeviation, mm(c.translation.x), mm(c.translation.y),
                ),
            )
        }
        appendLine("  after calibration, per room over all seed sets (rooms whose raw scan was on target with correct topology):")
        appendLine("    room              n | cal maxdL  corner  area% | resolved maxdL corner | control maxdL corner | cal-vs-control corner")
        for ((i, room) in truthRooms.withIndex()) {
            val ok = calibration.filter { it.raw[i].allOnTarget && it.raw[i].error?.topologyOk == true }
            fun mx(f: (CalibrationOutcome) -> Double) = ok.maxOfOrNull(f)?.let { String.format(Locale.ROOT, "%.1f", mm(it)) } ?: "-"
            appendLine(
                String.format(
                    Locale.ROOT, "    %-17s %d | %9s %7s %6s | %14s %6s | %13s %6s | %s",
                    room.name.take(17), ok.size,
                    mx { it.calibratedErrors[i].maxLengthError }, mx { it.calibratedErrors[i].maxCornerError },
                    ok.maxOfOrNull { abs(it.calibratedErrors[i].areaErrorPct) }?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-",
                    mx { it.resolvedErrors[i].maxLengthError }, mx { it.resolvedErrors[i].maxCornerError },
                    mx { it.control[i].error?.maxLengthError ?: Double.NaN }, mx { it.control[i].error?.maxCornerError ?: Double.NaN },
                    mx { it.calibratedVsControl[i] },
                ),
            )
        }
    }

    private fun sweepSection(): String = buildString {
        appendLine("== distance sweep: flat wall head-on, 30 captures per distance (|off| = fitted line offset at the aim point)")
        appendLine("  dist  ok%  failures         | |off| med / max mm | |ang| p95 / max deg")
        for (row in sweep) {
            val fails = row.failures.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}x${it.value}" }
            appendLine(
                String.format(
                    Locale.ROOT,
                    "  %4.1f  %3.0f  %-16s | %6s / %6s     | %5s / %5s",
                    row.distance, 100 * row.successRate, fails.ifEmpty { "-" },
                    q(row.offsets, 0.5, true), q(row.offsets, 1.0, true),
                    q(row.angles, 0.95, false), q(row.angles, 1.0, false),
                ),
            )
        }
    }

    private fun grazingSection(): String = buildString {
        appendLine("== grazing incidence: hallway center (2.7 m from the end wall), aim at the west end wall (wall 4), 20 captures each")
        appendLine("  aim across wall | config   | on end wall | on a side wall | failed")
        for (g in grazing) {
            appendLine(String.format(Locale.ROOT, "  %4.0f %%          | %-8s | %11d | %14d | %6d", 100 * g.aimFraction, g.config, g.onTarget, g.wrongWall, g.failed))
        }
    }

    private fun q(v: List<Double>, p: Double, asMm: Boolean): String {
        if (v.isEmpty()) return "-"
        val x = ErrorSummary.percentile(v.sorted(), p)
        return String.format(Locale.ROOT, if (asMm) "%.1f" else "%.2f", if (asMm) mm(x) else x)
    }
}
