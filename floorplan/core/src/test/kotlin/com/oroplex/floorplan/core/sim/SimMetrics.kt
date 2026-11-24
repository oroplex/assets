package com.oroplex.floorplan.core.sim

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Solved outline vs. ground truth. Both are CCW; when the corner counts agree the corners are matched by
 * the cyclic shift that minimizes the summed squared corner distance ("optimal edge correspondence"):
 * solved corner (i + [shift]) ↔ true corner i, solved edge (i + [shift]) ↔ true edge i.
 * [translation] is subtracted from the solved corners first (used after a whole-floor calibration).
 */
internal class OutlineError private constructor(
    val trueCount: Int,
    val solvedCount: Int,
    val shift: Int,
    /** solved − true length per TRUE edge (empty on a topology mismatch). */
    val edgeLengthErrors: List<Double>,
    /** Distance of each true corner to its matched solved corner (nearest solved corner on a mismatch). */
    val cornerErrors: List<Double>,
    val trueArea: Double,
    val solvedArea: Double,
    /** Max deviation of a solved interior angle from the true one (from k·90° on a mismatch), degrees. */
    val squarenessDeg: Double,
) {
    val topologyOk: Boolean get() = trueCount == solvedCount
    val maxLengthError: Double get() = edgeLengthErrors.maxOfOrNull { abs(it) } ?: Double.NaN
    val rmsLengthError: Double
        get() = if (edgeLengthErrors.isEmpty()) Double.NaN else sqrt(edgeLengthErrors.sumOf { it * it } / edgeLengthErrors.size)
    val maxCornerError: Double get() = cornerErrors.maxOrNull() ?: Double.NaN
    val areaError: Double get() = solvedArea - trueArea
    val areaErrorPct: Double get() = 100.0 * areaError / trueArea

    /** Solved edge index that corresponds to true edge [trueEdge]. */
    fun solvedEdge(trueEdge: Int): Int = Math.floorMod(trueEdge + shift, solvedCount)

    companion object {
        fun of(truth: List<Vec2>, solvedRaw: List<Vec2>, translation: Vec2 = Vec2.ZERO): OutlineError {
            val solved = solvedRaw.map { it - translation }
            val n = truth.size
            val m = solved.size
            val trueArea = Polygons.area(truth)
            val solvedArea = Polygons.area(solved)
            if (n != m || m < 3) {
                val corners = truth.map { t -> solved.minOfOrNull { it.distanceTo(t) } ?: Double.POSITIVE_INFINITY }
                val sq = if (m >= 3) {
                    Polygons.interiorAngles(solved).maxOf { a ->
                        val q = a / (PI / 2)
                        abs(q - round(q)) * 90.0
                    }
                } else {
                    Double.NaN
                }
                return OutlineError(n, m, 0, emptyList(), corners, trueArea, solvedArea, sq)
            }
            var bestShift = 0
            var bestCost = Double.POSITIVE_INFINITY
            for (k in 0 until n) {
                var cost = 0.0
                for (i in 0 until n) {
                    val d = solved[(i + k) % n].distanceTo(truth[i])
                    cost += d * d
                }
                if (cost < bestCost) {
                    bestCost = cost
                    bestShift = k
                }
            }
            val lenErr = (0 until n).map { i ->
                val s = solved[(i + bestShift) % n].distanceTo(solved[(i + 1 + bestShift) % n])
                s - truth[i].distanceTo(truth[(i + 1) % n])
            }
            val corners = (0 until n).map { i -> solved[(i + bestShift) % n].distanceTo(truth[i]) }
            val ta = Polygons.interiorAngles(truth)
            val sa = Polygons.interiorAngles(solved)
            val sq = (0 until n).maxOf { i -> abs(sa[(i + bestShift) % n] - ta[i]) } * 180.0 / PI
            return OutlineError(n, m, bestShift, lenErr, corners, trueArea, solvedArea, sq)
        }
    }
}

/**
 * Error statistics over many room scans. Accuracy figures use only [accurate] scans: solved, same corner
 * count as the truth and every capture on the wall that was aimed at (off-target captures are a separate
 * finding and are listed as anomalies instead of polluting the accuracy distribution).
 */
internal class ErrorSummary(val scans: List<RoomScan>) {
    val accurate: List<RoomScan> = scans.filter { it.allOnTarget && it.error?.topologyOk == true }
    val solved = scans.count { it.result != null }
    val topologyOk = scans.count { it.error?.topologyOk == true }
    val offTargetScans = scans.count { !it.allOnTarget }
    private val lengthErrors = accurate.flatMap { it.error!!.edgeLengthErrors }.map { abs(it) }.sorted()
    val maxLength: Double = lengthErrors.lastOrNull() ?: Double.NaN
    val p95Length: Double = percentile(lengthErrors, 0.95)
    val medianLength: Double = percentile(lengthErrors, 0.5)
    val maxCorner: Double = accurate.maxOfOrNull { it.error!!.maxCornerError } ?: Double.NaN
    val p95Corner: Double = percentile(accurate.map { it.error!!.maxCornerError }.sorted(), 0.95)
    val maxAreaPct: Double = accurate.maxOfOrNull { abs(it.error!!.areaErrorPct) } ?: Double.NaN
    val maxSquareness: Double = accurate.maxOfOrNull { it.error!!.squarenessDeg } ?: Double.NaN
    val maxRotation: Double = accurate.maxOfOrNull { abs(rotationDeg(it.result!!.dominantAngle)) } ?: Double.NaN
    val captures = scans.sumOf { s -> s.captures.size }
    val firstTryFailures = scans.sumOf { s -> s.captures.count { it.failures.isNotEmpty() } }
    val missingWalls = scans.sumOf { s -> s.captures.count { it.observation == null } }
    val meanDistance: Double = scans.flatMap { s -> s.captures.map { it.distance } }.average()

    fun warningCount(kind: String) = scans.count { s -> s.result?.warnings.orEmpty().any { SimReport.shortWarning(it) == kind } }

    companion object {
        fun percentile(sorted: List<Double>, q: Double): Double {
            if (sorted.isEmpty()) return Double.NaN
            val idx = ceil((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[idx]
        }
    }
}

internal fun mm(meters: Double) = meters * 1000.0

/**
 * Rotation of a solved outline relative to the axis-aligned sample house: the solver's dominant angle
 * ([0°, 90°)) mapped to (−45°, 45°].
 */
internal fun rotationDeg(dominantAngle: Double): Double {
    val d = Math.toDegrees(dominantAngle)
    return if (d > 45.0) d - 90.0 else d
}

/** Compact fixed-width tables for the report. */
internal object SimReport {
    private const val ROW = "  %-17s %5s %5s %5s %8s %5s | %6s %6s %7s %6s %5s %5s | %s"

    val HEADER: String = String.format(
        Locale.ROOT, ROW,
        "room", "scans", "topo", "offT", "1stfail", "dist", "maxdL", "p95dL", "corner", "area%", "sq°", "rot°", "warnings",
    )

    private fun f1(v: Double) = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.1f", v)
    private fun f2(v: Double) = if (v.isNaN()) "-" else String.format(Locale.ROOT, "%.2f", v)

    /** One aggregate row (accuracy columns over the accurate scans, in mm / % / degrees). */
    fun row(label: String, s: ErrorSummary): String {
        val warnings = listOf("REORDERED", "far-corner", "missing-wall", "spike", "small-room")
            .mapNotNull { k -> s.warningCount(k).takeIf { it > 0 }?.let { "$k x$it" } }
            .joinToString(", ")
        return String.format(
            Locale.ROOT, ROW,
            label.take(17), "${s.scans.size}", "${s.topologyOk}", "${s.offTargetScans}",
            "${s.firstTryFailures}/${s.captures}", f2(s.meanDistance),
            f1(mm(s.maxLength)), f1(mm(s.p95Length)), f1(mm(s.maxCorner)), f2(s.maxAreaPct), f2(s.maxSquareness),
            f2(s.maxRotation), warnings,
        )
    }

    fun shortWarning(w: String): String = when {
        w.startsWith("Walls were re-ordered") -> "REORDERED"
        w.contains("beyond what was measured") -> "far-corner"
        w.contains("seems to be missing") -> "missing-wall"
        w.contains("sharp spike") -> "spike"
        w.contains("very small") -> "small-room"
        else -> w.take(40)
    }

    /** One line per scan that is not an "accurate" scan, explaining what went wrong. */
    fun anomaly(scan: RoomScan): String {
        val e = scan.error
        val what = buildList {
            if (scan.result == null) add("UNSOLVED")
            if (e != null && !e.topologyOk) add("TOPOLOGY ${e.solvedCount}/${e.trueCount} corners")
            scan.captures.filter { it.observation != null && !it.onTarget }.forEach { c ->
                val on = if (c.matchedEdge >= 0) "wall ${c.matchedEdge + 1}" else "no wall (furniture/clutter)"
                add(
                    String.format(
                        Locale.ROOT, "WRONG-WALL aimed at wall %d from %.2f m, fit lies on %s",
                        c.edge + 1, c.distance, on,
                    ),
                )
            }
            scan.captures.filter { it.observation == null }.forEach { add("wall ${it.edge + 1} never captured ${it.failures}") }
        }
        val err = if (e == null) {
            "-"
        } else {
            String.format(
                Locale.ROOT, "maxdL %s mm, corner %.1f mm, area %+.2f %%",
                f1(mm(e.maxLengthError)), mm(e.maxCornerError), e.areaErrorPct,
            )
        }
        val warnings = scan.result?.warnings.orEmpty().map(::shortWarning).distinct().joinToString(",")
        return String.format(
            Locale.ROOT, "  ! %-17s %-10s %s | %s | warnings: %s",
            scan.truth.name.take(17), scan.stationLabel, what.joinToString("; "), err, warnings.ifEmpty { "-" },
        )
    }

    /** Distance between two outlines with equal topology: max corner distance under the optimal shift. */
    fun outlineDistance(a: List<Vec2>, b: List<Vec2>): Double =
        if (a.size != b.size) Double.POSITIVE_INFINITY else OutlineError.of(a, b).maxCornerError

    /** Per-room aggregate table, summary line, anomaly list and optional extra lines. */
    fun section(title: String, scans: List<RoomScan>, extra: List<String> = emptyList()): String = buildString {
        appendLine("== $title")
        appendLine(HEADER)
        scans.groupBy { it.truth.name }.forEach { (name, list) -> appendLine(row(name, ErrorSummary(list))) }
        appendLine(row("ALL", ErrorSummary(scans)))
        scans.filter { !it.allOnTarget || it.error?.topologyOk != true }.forEach { appendLine(anomaly(it)) }
        extra.forEach { appendLine("  $it") }
    }
}
