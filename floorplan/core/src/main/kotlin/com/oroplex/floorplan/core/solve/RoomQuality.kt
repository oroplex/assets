package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** MANUAL = drawn/typed, no AR evidence to grade. */
enum class QualityLevel { GOOD, FAIR, POOR, MANUAL }

/**
 * How [RoomQuality.assess] numbers walls and corners in its reasons.
 * * [OUTLINE]: 1-based edge / corner numbers of the room's current outline, as the editor shows them.
 * * [CAPTURE]: 1-based capture numbers (the index of the wall observation / corner tap the user took, as the
 *   capture screen's wall chips and AR badges show them), so "Wall 4" is the capture to redo. Edges no capture
 *   supports are described by their neighbours ("the wall between walls 3 and 4").
 */
enum class WallNumbering { OUTLINE, CAPTURE }

data class RoomQualityReport(
    val level: QualityLevel,
    /** 0..1, higher is better (1.0 for MANUAL). */
    val score: Double,
    /** Short user-facing reasons for anything below GOOD, e.g. "Wall 3 was noisy (±2.4 cm) — consider recapturing". */
    val reasons: List<String>,
)

object RoomQuality {
    /** Scores at or above this are [QualityLevel.GOOD]. */
    const val GOOD_SCORE = 0.8

    /** Scores at or above this (and below [GOOD_SCORE]) are [QualityLevel.FAIR]; below it [QualityLevel.POOR]. */
    const val FAIR_SCORE = 0.55

    /** A depth fit with at least this confidence counts as a fully trustworthy wall. */
    private const val CONFIDENT_FIT = 0.6

    /** Depth fits whose inlier RMS is above this (m) are called out as noisy. */
    internal const val NOISY_RMS = 0.025

    /** Highest score a noisy depth fit can get (enough to pull an otherwise clean room down to FAIR). */
    private const val NOISY_WALL = 0.6

    /** Walls scoring below this get a "weak capture" reason. */
    private const val WEAK_WALL = 0.6

    /** Walls shorter than this (m) neither get reasons nor count toward the weakest-wall term. */
    private const val MIN_GRADED_WALL = 0.3

    /** An observation supports a wall when it is within this angle and this distance of it. */
    private val SUPPORT_COS = cos(Math.toRadians(15.0))
    private const val SUPPORT_DISTANCE = 0.15

    /** Score of a wall that no capture supports (filled in by the solver, or edited away from the scan). */
    private const val UNSUPPORTED_WALL = 0.35

    /** Score of a short unsupported wall (a jog connector): little room for error. */
    private const val UNSUPPORTED_SHORT_WALL = 0.75

    /** Corner taps: a corner within the first distance of a tap is exact, beyond the second it is called out. */
    private const val TAP_EXACT = 0.03
    private const val TAP_FAR = 0.15

    private const val WARNING_PENALTY = 0.1
    private const val MAX_WARNING_PENALTY = 0.3
    private const val ESTIMATED_CEILING_PENALTY = 0.05

    /** At most this many reasons are returned (most important first). */
    private const val MAX_REASONS = 4

    /**
     * Grade how trustworthy a captured room's measurements are, from its raw evidence: per-wall fit residuals,
     * confidences and sources (depth fit / detected plane / baseline taps), synthesized connector walls, current
     * solver warnings, corner taps vs final corners, estimated ceiling. Deterministic and cheap (called from UI).
     *
     * How the score is built (higher is better):
     * * WALLS rooms: every captured wall is matched to the outline edge it lies on (same direction within 15°,
     *   within 15 cm). An edge's score is its best capture's: depth fits by confidence (>= 0.6 counts as
     *   fully trusted; RMS above 2.5 cm is "noisy" and capped at 0.6), detected planes likewise but capped at
     *   0.9, baseline taps 0.7..0.95 by tapped length. Edges no capture supports (connectors the solver
     *   filled in, or walls edited away from the scan) score 0.35 (0.75 when shorter than 30 cm). The room's
     *   wall score is 0.6 x length-weighted mean + 0.4 x weakest wall of at least 30 cm.
     * * CORNERS rooms: each final corner is compared with the nearest tap: within 3 cm scores 1, then
     *   linearly down to 0.5 at 15 cm, and 0.35 beyond; combined like walls and capped at 0.95.
     * * Then minus 0.1 per solver warning (at most 0.3) and 0.05 for an estimated ceiling.
     * * Level: >= [GOOD_SCORE] GOOD, >= [FAIR_SCORE] FAIR, else POOR. MANUAL rooms are always
     *   [QualityLevel.MANUAL] with score 1 and no reasons. A scanned room with a broken outline is POOR.
     *
     * Reasons use 1-based wall / corner numbers of the room's current outline (as the editor shows them),
     * most important first, at most four.
     */
    fun assess(
        room: Room,
        numbering: WallNumbering = WallNumbering.OUTLINE,
        /** Units for lengths in reasons (e.g. noise "±1.2 in"); null = both systems, as before. */
        unitSystem: UnitSystem? = null,
    ): RoomQualityReport {
        // Round-3 contract: [numbering] and [unitSystem] are not honoured yet (OUTLINE numbering, both units).
        if (room.captureMethod == CaptureMethod.MANUAL) return RoomQualityReport(QualityLevel.MANUAL, 1.0, emptyList())
        val outline = WallPairs.ccwOutline(room.corners)
            ?: return RoomQualityReport(QualityLevel.POOR, 0.0, listOf("The outline is broken — fix its corners or scan the room again"))
        val reasons = ArrayList<String>()
        val base = when (room.captureMethod) {
            CaptureMethod.WALLS -> wallsScore(room, outline, reasons)
            else -> cornersScore(room, outline, reasons)
        }
        var penalty = 0.0
        val warnings = room.warnings.count { it.isNotBlank() }
        if (warnings > 0) {
            penalty += min(MAX_WARNING_PENALTY, WARNING_PENALTY * warnings)
            reasons += if (warnings == 1) "1 scan warning to review" else "$warnings scan warnings to review"
        }
        if (room.ceilingEstimated) {
            penalty += ESTIMATED_CEILING_PENALTY
            reasons += "Ceiling height is estimated — measure it for accurate volumes"
        }
        val score = (base - penalty).coerceIn(0.0, 1.0)
        val level = when {
            score >= GOOD_SCORE -> QualityLevel.GOOD
            score >= FAIR_SCORE -> QualityLevel.FAIR
            else -> QualityLevel.POOR
        }
        return RoomQualityReport(level, score, reasons.take(MAX_REASONS))
    }

    // ---------------------------------------------------------------------------------------------

    private class EdgeGrade(val length: Double, var score: Double? = null, var noisyRms: Double? = null)

    private fun wallsScore(room: Room, outline: List<Vec2>, reasons: MutableList<String>): Double {
        val observations = room.observations.filter(::isUsable)
        if (observations.isEmpty()) {
            reasons += "No scan data was kept for this room — scan it again to check its accuracy"
            return 0.6
        }
        // Wall numbers follow the stored outline; a clockwise outline was reversed for the geometry.
        val reversed = outline !== room.corners
        val n = outline.size
        val edges = List(n) { EdgeGrade(outline[it].distanceTo(outline[(it + 1) % n])) }
        for (o in observations) {
            val k = supportedEdge(o, outline) ?: continue
            val (q, noisy) = observationScore(o)
            val edge = edges[k]
            val current = edge.score
            if (current == null || q > current) {
                edge.score = q
                edge.noisyRms = noisy
            }
        }
        // Edge k of the reversed list is stored edge n - 2 - k, traversed backwards.
        fun wallNumber(k: Int) = (if (reversed) (2 * n - 2 - k) % n else k) + 1

        val unsupported = ArrayList<Int>()
        val weak = ArrayList<Int>()
        val noisy = ArrayList<Pair<Int, Double>>()
        val scores = DoubleArray(n)
        for (k in 0 until n) {
            val e = edges[k]
            val graded = e.length >= MIN_GRADED_WALL
            val s = e.score
            scores[k] = when {
                s != null -> s
                graded -> UNSUPPORTED_WALL
                else -> UNSUPPORTED_SHORT_WALL
            }
            if (!graded) continue
            val noisyRms = e.noisyRms
            when {
                s == null -> unsupported += wallNumber(k)
                noisyRms != null -> noisy += wallNumber(k) to noisyRms
                s < WEAK_WALL -> weak += wallNumber(k)
            }
        }
        if (unsupported.isNotEmpty()) {
            reasons += "${walls(unsupported.sorted())} ${if (unsupported.size == 1) "wasn't" else "weren't"} measured directly — " +
                "check ${if (unsupported.size == 1) "it" else "them"} with a tape"
        }
        if (noisy.isNotEmpty()) {
            val sorted = noisy.sortedBy { it.first }
            val worst = sorted.maxOf { it.second }
            reasons += if (sorted.size == 1) "${walls(sorted.map { it.first })} was noisy (±${cm(worst)}) — consider recapturing"
            else "${walls(sorted.map { it.first })} were noisy (up to ±${cm(worst)}) — consider recapturing"
        }
        if (weak.isNotEmpty()) {
            reasons += "${walls(weak.sorted())} ${if (weak.size == 1) "was a weak capture — recapture it" else "were weak captures — recapture them"} " +
                "from closer"
        }
        return combine(scores, edges.map { it.length })
    }

    private fun cornersScore(room: Room, outline: List<Vec2>, reasons: MutableList<String>): Double {
        val taps = room.cornerTaps.filter { it.isFinite() }
        if (taps.isEmpty()) {
            reasons += "No corner taps were kept for this room — scan it again to check its accuracy"
            return 0.6
        }
        val reversed = outline !== room.corners
        val n = outline.size
        val scores = DoubleArray(n)
        var worstDistance = 0.0
        val far = ArrayList<Int>()
        for (k in 0 until n) {
            val d = taps.minOf { it.distanceTo(outline[k]) }
            scores[k] = when {
                d <= TAP_EXACT -> 1.0
                d <= TAP_FAR -> 1.0 - 0.5 * (d - TAP_EXACT) / (TAP_FAR - TAP_EXACT)
                else -> UNSUPPORTED_WALL
            }
            if (d > TAP_FAR) {
                val number = (if (reversed) n - 1 - k else k) + 1
                far += number
                worstDistance = max(worstDistance, d)
            }
        }
        if (far.isNotEmpty()) {
            val d = worstDistance
            val distance = "${Units.formatLength(d, UnitSystem.METRIC)} (${Units.formatLength(d, UnitSystem.IMPERIAL, 1)})"
            reasons += if (far.size == 1) "Corner ${far[0]} is $distance from where you tapped — check it"
            else "Corners ${numberList(far.sorted())} are up to $distance from where you tapped — check them"
        }
        return 0.95 * combine(scores, List(n) { 1.0 })
    }

    /** 0.6 x weighted mean + 0.4 x weakest of the walls long enough to matter. */
    private fun combine(scores: DoubleArray, lengths: List<Double>): Double {
        var sum = 0.0
        var weight = 0.0
        var weakest = Double.POSITIVE_INFINITY
        for (k in scores.indices) {
            val w = lengths[k].takeIf { it.isFinite() && it > 0.0 } ?: continue
            sum += w * scores[k]
            weight += w
            if (w >= MIN_GRADED_WALL || lengths.all { it < MIN_GRADED_WALL }) weakest = min(weakest, scores[k])
        }
        if (weight <= 0.0) return 0.0
        val mean = sum / weight
        return 0.6 * mean + 0.4 * (if (weakest.isFinite()) weakest else mean)
    }

    /** Score of one capture and, for a noisy depth fit, its RMS. */
    private fun observationScore(o: WallObservation): Pair<Double, Double?> {
        val confidence = o.confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        return when (o.source) {
            WallSource.DEPTH_FIT -> {
                val q = min(1.0, confidence / CONFIDENT_FIT)
                val rms = o.rmsResidual.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                if (rms > NOISY_RMS) min(q, NOISY_WALL) to rms else q to null
            }
            WallSource.DETECTED_PLANE -> min(0.9, confidence / CONFIDENT_FIT) to null
            WallSource.BASELINE_TAPS -> {
                val tapped = o.extentStart.distanceTo(o.extentEnd)
                (0.7 + 0.25 * ((tapped - 0.15) / 0.85).coerceIn(0.0, 1.0)) to null
            }
            WallSource.MANUAL -> 0.8 to null
        }
    }

    /** The outline edge [o] lies on, or null when it supports none (direction within 15°, within 15 cm). */
    private fun supportedEdge(o: WallObservation, outline: List<Vec2>): Int? {
        val mid = Vec2.lerp(o.extentStart, o.extentEnd, 0.5)
        val dir = o.line.direction.normalized()
        if (dir == Vec2.ZERO) return null
        var best: Int? = null
        var bestCost = Double.POSITIVE_INFINITY
        for (k in outline.indices) {
            val a = outline[k]
            val b = outline[(k + 1) % outline.size]
            val length = a.distanceTo(b)
            if (length < 1e-6) continue
            val e = (b - a) / length
            if (abs(e dot dir) < SUPPORT_COS) continue
            val offset = abs((mid - a) cross e)
            val t = (mid - a) dot e
            val outside = max(0.0, max(-t, t - length))
            if (offset > SUPPORT_DISTANCE || outside > 0.3) continue
            val cost = offset + 0.5 * outside
            if (cost < bestCost) {
                bestCost = cost
                best = k
            }
        }
        return best
    }

    private fun isUsable(o: WallObservation): Boolean =
        o.line.point.isFinite() && o.line.direction.isFinite() && o.extentStart.isFinite() && o.extentEnd.isFinite()

    private fun cm(meters: Double): String = String.format(Locale.ROOT, "%.1f cm", meters * 100)

    private fun walls(numbers: List<Int>): String =
        if (numbers.size == 1) "Wall ${numbers[0]}" else "Walls ${numberList(numbers)}"

    /** "2", "2 and 5", "2, 3 and 5". */
    internal fun numberList(numbers: List<Int>): String = when (numbers.size) {
        0 -> ""
        1 -> numbers[0].toString()
        else -> numbers.dropLast(1).joinToString(", ") + " and " + numbers.last()
    }
}
