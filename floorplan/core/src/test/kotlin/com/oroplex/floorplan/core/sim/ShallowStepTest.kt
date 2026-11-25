package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.fit.WallEstimator
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.solve.RoomSolver
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Something flat under or beside the crosshair that stands a few centimetres proud of the wall or is set into
 * it: a closed door seen from its recessed side, a wall-mounted TV, a panel, a framed picture. When the step is
 * smaller than the estimator's inlier band both faces fit one line, and before the shallow-step rule
 * ([WallEstimator]) the result was a diagonal through the wall on one side and the object on the other: 5–9°
 * off, kept as measured by the solver, corners up to half a metre out (regression check of round 3, RA-1: a
 * door 8 cm back seen from 4 m skewed 44 of 60 captures; a 6 cm panel from 3 m, 44 of 60).
 *
 * Each row captures the south wall of a 5 × 5 m room 60 times from one distance, then the three plain walls, and
 * solves the room. The table goes to `build/step-report.txt`.
 */
class ShallowStepTest {
    private val truth = listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 5.0), Vec2(0.0, 5.0))

    private class Case(val name: String, val x0: Double, val x1: Double, val depth: Double, val aimX: Double, val distances: List<Double>)

    private class Row(val case: Case, val distance: Double, val trials: Int) {
        var refused = 0
        var rooms = 0
        var notRectangles = 0
        var warned = 0
        val hints = HashMap<String, Int>()
        val tilts = ArrayList<Double>()
        val offsets = ArrayList<Double>()
        val corners = ArrayList<Double>()

        /** Accepted captures more than 5° off the wall: beyond what squaring absorbs. */
        val skewed: Int get() = tilts.count { it > SKEW_DEG }
    }

    private val cases = listOf(
        Case("plain wall", 0.0, 0.0, 0.0, 2.5, listOf(1.5, 2.5, 4.0)),
        Case("door 0.8 m, 8 cm back, aim at its centre", 2.1, 2.9, -0.08, 2.5, listOf(2.0, 2.5, 3.0, 4.0)),
        Case("door 0.8 m, 8 cm back, aim 10 cm inside its edge", 2.1, 2.9, -0.08, 2.8, listOf(2.0, 2.5, 3.0, 4.0)),
        Case("door 0.8 m, 4 cm back, aim at its centre", 2.1, 2.9, -0.04, 2.5, listOf(1.5, 2.0, 3.0, 4.0)),
        Case("panel 0.6 m, 6 cm proud, aim at its centre", 2.2, 2.8, 0.06, 2.5, listOf(1.5, 2.0, 3.0, 4.0)),
        Case("TV 1.2 m, 8 cm proud, aim at its centre", 1.9, 3.1, 0.08, 2.5, listOf(2.0, 3.0, 4.0)),
        Case("picture 0.6 m, 3 cm proud, aim at its centre", 2.2, 2.8, 0.03, 2.5, listOf(1.0, 1.5, 2.0, 3.0)),
        Case("picture 0.6 m, 3 cm proud, aim 8 cm beside it", 2.2, 2.8, 0.03, 2.88, listOf(1.0, 1.5, 2.0, 3.0)),
        Case("bare wall 20 cm beside the door (8 cm back)", 2.1, 2.9, -0.08, 3.1, listOf(2.0, 3.0, 4.0)),
    )

    /** The room; its south wall (y = 0) has a flat object from x0 to x1, [Case.depth] m proud (> 0) or set back (< 0). */
    private fun room(c: Case): SimRoom =
        if (c.depth == 0.0) {
            SimRoom("Plain", truth)
        } else {
            SimRoom(
                "Step",
                listOf(Vec2(0.0, 0.0), Vec2(c.x0, 0.0), Vec2(c.x0, c.depth), Vec2(c.x1, c.depth), Vec2(c.x1, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 5.0), Vec2(0.0, 5.0)),
            )
        }

    private val rows: List<Row> by lazy {
        cases.flatMap { c ->
            val room = room(c)
            c.distances.map { d ->
                val row = Row(c, d, TRIALS)
                for (k in 0 until TRIALS) {
                    fun capture(station: Vec2, aim: Vec2, salt: Long): WallFit {
                        // Like the app: 25 frames beyond 2.5 m, 15 nearer.
                        val rig = CaptureRig(frames = if (station.distanceTo(aim) > 2.5) 25 else 15)
                        val rng = Random(seedOf(733L, salt, (d * 1000).toLong(), (c.aimX * 1000).toLong(), (c.depth * 1000).toLong(), k.toLong()))
                        return ScanSimulator.capture(room, Vec3.fromPlan(station, CAMERA_HEIGHT), Vec3.fromPlan(aim, 1.5), rng, rig = rig).fit
                    }
                    val fits = listOf(
                        capture(Vec2(c.aimX, d), Vec2(c.aimX, 0.0), 1),
                        capture(Vec2(3.0, 2.5), Vec2(5.0, 2.5), 2),
                        capture(Vec2(2.5, 3.0), Vec2(2.5, 5.0), 3),
                        capture(Vec2(2.0, 2.5), Vec2(0.0, 2.5), 4),
                    )
                    val first = fits[0].observation
                    if (first == null) {
                        row.refused++
                        row.hints.merge(fits[0].hint ?: fits[0].failure?.name ?: "?", 1, Int::plus)
                        continue
                    }
                    row.tilts += Math.toDegrees(asin(first.line.sinAngleTo(Line2(Vec2(0.0, 0.0), Vec2(1.0, 0.0))).coerceIn(0.0, 1.0)))
                    // Where the measured line puts the wall at the crosshair (the true wall face is y = 0).
                    row.offsets += abs(first.line.signedDistance(Vec2(c.aimX, 0.0)))
                    val observations = fits.mapNotNull { it.observation }
                    if (observations.size < 4) continue
                    val solved = RoomSolver.solveWalls(observations) ?: continue
                    row.rooms++
                    if (solved.warnings.any { it.contains("off square") }) row.warned++
                    if (solved.corners.size == 4) {
                        row.corners += OutlineError.of(truth, solved.corners).maxCornerError
                        if (Polygons.interiorAngles(solved.corners).any { abs(Math.toDegrees(it) - 90.0) > 0.5 }) row.notRectangles++
                    } else {
                        row.notRectangles++
                    }
                }
                row
            }
        }
    }

    private fun quantile(values: List<Double>, q: Double): Double = values.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * q).toInt()] }

    private fun report(): String {
        val out = StringBuilder()
        out.append("Shallow steps under or beside the crosshair ($TRIALS captures per row; tilt = angle to the true wall;\n")
        out.append("offset = distance of the measured line from the true wall face at the crosshair)\n")
        var name = ""
        for (row in rows) {
            if (row.case.name != name) {
                name = row.case.name
                out.append(name).append('\n')
            }
            out.append(
                String.format(
                    Locale.ROOT,
                    "  %.1f m | refused %2d | accepted %2d: tilt p50 %4.1f max %4.1f deg, over 5 deg %2d, offset p50 %3.0f max %3.0f mm | rooms %2d: not rectangles %2d (warned %2d), corner error p50 %3.0f max %3.0f mm%s\n",
                    row.distance, row.refused, row.tilts.size, quantile(row.tilts, 0.5), quantile(row.tilts, 1.0), row.skewed,
                    1000 * quantile(row.offsets, 0.5), 1000 * quantile(row.offsets, 1.0),
                    row.rooms, row.notRectangles, row.warned, 1000 * quantile(row.corners, 0.5), 1000 * quantile(row.corners, 1.0),
                    if (row.hints.isEmpty()) "" else " | " + row.hints.entries.sortedByDescending { it.value }.joinToString { "${it.value}x \"${it.key.take(24)}…\"" },
                ),
            )
        }
        return out.toString()
    }

    @Test
    fun aDoorPictureOrTvUnderTheCrosshairDoesNotSkewTheWall() {
        File("build").mkdirs()
        File("build/step-report.txt").writeText(report())

        val stepped = rows.filter { it.case.depth != 0.0 }
        val trials = stepped.sumOf { it.trials }
        val skewed = stepped.sumOf { it.skewed }
        // Measured: 8 of 1 860 (0.4 %), at most 3 of 60 in a row; before the shallow-step rule more than 250.
        assertTrue(skewed <= 0.01 * trials, "$skewed of $trials captures more than $SKEW_DEG deg off the wall\n${report()}")
        for (row in stepped) {
            assertTrue(row.skewed <= 0.1 * row.trials, "${row.case.name} at ${row.distance} m: ${row.skewed} of ${row.trials} skewed\n${report()}")
        }
        // Measured: 3 rooms that are not rectangles; every one of them carries the solver's off-square warning.
        val notRectangles = stepped.sumOf { it.notRectangles }
        assertTrue(notRectangles <= 0.005 * trials, "$notRectangles rooms came out as non-rectangles\n${report()}")
        for (row in stepped) {
            assertTrue(row.warned >= row.notRectangles, "${row.case.name} at ${row.distance} m: a skewed room without a warning\n${report()}")
        }
        // An accepted capture measures the surface under the crosshair: never further from the wall than the
        // object is deep (plus noise), whatever its direction.
        for (row in stepped) {
            val limit = abs(row.case.depth) + 0.02
            assertTrue(row.offsets.all { it <= limit }, "${row.case.name} at ${row.distance} m: offset ${row.offsets.maxOrNull()} m\n${report()}")
        }
    }

    @Test
    fun plainWallsAndBareWallBesideADoorAreMeasuredAsBefore() {
        for (row in rows.filter { it.case.depth == 0.0 }) {
            assertEquals(0, row.refused, "plain wall at ${row.distance} m refused")
            assertTrue(row.tilts.max() < 1.0 && row.offsets.max() < 0.006, "plain wall at ${row.distance} m: ${row.tilts.max()} deg, ${row.offsets.max()} m")
            assertEquals(0, row.notRectangles)
        }
        // Aiming at bare wall beside the door is what the hint asks for: it has to work.
        for (row in rows.filter { it.case.name.startsWith("bare wall") }) {
            assertTrue(row.tilts.size >= 0.8 * row.trials, "bare wall beside the door at ${row.distance} m: only ${row.tilts.size} of ${row.trials} accepted")
            assertTrue(row.tilts.max() < 2.0 && row.offsets.max() < 0.015, "bare wall beside the door at ${row.distance} m: ${row.tilts.max()} deg, ${row.offsets.max()} m")
        }
    }

    @Test
    fun aRefusalAtAStepSaysWhatIsInTheWay() {
        // Where the step leaves too little on the crosshair's side, the user is told about the object, not a corner.
        val far = rows.first { it.case.name.startsWith("door 0.8 m, 8 cm back, aim at its centre") && it.distance == 4.0 }
        assertTrue((far.hints[WallEstimator.HINT_STEP] ?: 0) >= 0.8 * far.trials, "hints: ${far.hints}")
    }

    private companion object {
        const val TRIALS = 60
        const val SKEW_DEG = 5.0
    }
}
