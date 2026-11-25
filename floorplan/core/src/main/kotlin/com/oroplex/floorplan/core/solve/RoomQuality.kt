package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.fit.WallEstimatorConfig
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

    /** [noisyLimit]: multiple of the range-adaptive expected per-point sigma a depth fit may reach. */
    const val NOISY_SIGMA_FACTOR = 1.3

    /**
     * Per-point RMS (m) above which a depth-fit observation counts as noisy: max(2.5 cm, 1.3 x the estimator's
     * expected sigma at the capture range), range = horizontal distance from [WallObservation.viewer] to the
     * middle of the observed extent. Per-point noise grows with range (about 5 mm + 1.2 % of the distance), so a
     * fixed limit would flag every accurate capture from 2 m or more. Shared by RoomQuality and the capture
     * screen's wall chips so the two always agree. Non-finite input gives the 2.5 cm floor.
     */
    fun noisyLimit(o: WallObservation): Double {
        val mid = (o.extentStart + o.extentEnd) * 0.5
        val range = o.viewer.distanceTo(mid)
        if (!range.isFinite()) return NOISY_RMS
        return max(NOISY_RMS, NOISY_SIGMA_FACTOR * WallEstimatorConfig().expectedSigma(range))
    }

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

    /** Lowest score of a wall the user set by hand in the editor (dragged, inserted or deleted corner)... */
    private const val HAND_SET_WALL = 0.8

    /** ...and of a wall whose length the user typed (usually a tape measurement). */
    private const val TYPED_WALL = 0.95

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
     * confidences and sources (depth fit / detected plane / baseline taps), synthesized connector walls, walls set
     * by hand, current solver warnings, corner taps vs final corners, estimated ceiling. Deterministic and cheap
     * (called from UI).
     *
     * How the score is built (higher is better):
     * * WALLS rooms: every captured wall is matched to the outline edge it lies on (same direction within 15°,
     *   within 15 cm). An edge's score is its best capture's: depth fits by confidence (>= 0.6 counts as
     *   fully trusted; an inlier RMS above [noisyLimit] at the capture's range is "noisy" and capped at 0.6),
     *   detected planes likewise but capped at 0.9, baseline taps 0.7..0.95 by tapped length. Edges no capture
     *   supports (connectors the solver filled in) score 0.35 (0.75 when shorter than 30 cm). The room's wall
     *   score is 0.6 x length-weighted mean + 0.4 x weakest wall of at least 30 cm.
     * * Walls set by hand in the editor ([Room.handSetWalls]) score at least 0.8, or 0.95 when their length was
     *   typed, and never get a capture reason: the user deliberately put them there.
     * * CORNERS rooms: each final corner is compared with the nearest tap: within 3 cm scores 1, then
     *   linearly down to 0.5 at 15 cm, and 0.35 beyond; a corner of a hand-set wall scores at least 0.8 (0.95
     *   typed) without a reason; combined like walls and capped at 0.95.
     * * Then minus 0.1 per solver warning (at most 0.3) and 0.05 for an estimated ceiling.
     * * Level: >= [GOOD_SCORE] GOOD, >= [FAIR_SCORE] FAIR, else POOR. MANUAL rooms are always
     *   [QualityLevel.MANUAL] with score 1 and no reasons. A scanned room with a broken outline is POOR.
     *
     * Reasons are most important first, at most four. Walls and corners are numbered per [numbering]: OUTLINE
     * uses the room's current outline (as the editor shows it); CAPTURE uses capture numbers (observation /
     * tap index + 1, as the capture screen's chips show them), so a clockwise walk still names the wall to
     * recapture, and a wall no capture supports is named by its neighbours ("the wall between walls 3 and 4").
     * Lengths are in [unitSystem]; null keeps the earlier style (noise in cm, corner distances in both systems).
     */
    fun assess(
        room: Room,
        numbering: WallNumbering = WallNumbering.OUTLINE,
        /** Units for lengths in reasons (e.g. noise "±1.2 in"); null = both systems, as before. */
        unitSystem: UnitSystem? = null,
    ): RoomQualityReport {
        if (room.captureMethod == CaptureMethod.MANUAL) return RoomQualityReport(QualityLevel.MANUAL, 1.0, emptyList())
        val outline = WallPairs.ccwOutline(room.corners)
            ?: return RoomQualityReport(QualityLevel.POOR, 0.0, listOf("The outline is broken — fix its corners or scan the room again"))
        val reasons = ArrayList<String>()
        val base = when (room.captureMethod) {
            CaptureMethod.WALLS -> wallsScore(room, outline, numbering, unitSystem, reasons)
            else -> cornersScore(room, outline, numbering, unitSystem, reasons)
        }
        var penalty = 0.0
        val warnings = room.warnings.count { it.isNotBlank() }
        if (warnings > 0) {
            penalty += min(MAX_WARNING_PENALTY, WARNING_PENALTY * warnings)
            reasons += if (warnings == 1) "1 scan warning to review" else "$warnings scan warnings to review"
        }
        if (room.ceilingEstimated) {
            penalty += ESTIMATED_CEILING_PENALTY
            reasons += "Ceiling height is estimated — measure it so wall areas and volumes are right"
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

    /**
     * Grade of one outline edge: its best capture's score, that capture's index in room.observations
     * ([capture]) and RMS when it was noisy, and the floor score of a hand-set wall ([handSet]).
     */
    private class EdgeGrade(
        val length: Double,
        var score: Double? = null,
        var noisyRms: Double? = null,
        var capture: Int? = null,
        var handSet: Double? = null,
    )

    private fun wallsScore(
        room: Room,
        outline: List<Vec2>,
        numbering: WallNumbering,
        unitSystem: UnitSystem?,
        reasons: MutableList<String>,
    ): Double {
        val observations = room.observations.withIndex().filter { isUsable(it.value) }
        if (observations.isEmpty()) {
            reasons += "No scan data was kept for this room — scan it again to check its accuracy"
            return 0.6
        }
        // Outline numbers follow the stored outline; a clockwise outline was reversed for the geometry.
        val reversed = outline !== room.corners
        val n = outline.size
        // Edge k of the reversed list is stored edge n - 2 - k, traversed backwards.
        fun storedEdge(k: Int) = if (reversed) (2 * n - 2 - k) % n else k
        val edges = List(n) { EdgeGrade(outline[it].distanceTo(outline[(it + 1) % n])) }
        for ((index, o) in observations) {
            val k = supportedEdge(o, outline) ?: continue
            val (q, noisy) = observationScore(o)
            val edge = edges[k]
            val current = edge.score
            if (current == null || q > current) {
                edge.score = q
                edge.noisyRms = noisy
                edge.capture = index
            }
        }
        for (k in 0 until n) {
            val h = RoomOps.handSetWallFor(room, storedEdge(k)) ?: continue
            edges[k].handSet = if (h.typedLength != null) TYPED_WALL else HAND_SET_WALL
        }

        val unsupported = ArrayList<Int>()
        val weak = ArrayList<Int>()
        val noisy = ArrayList<Pair<Int, Double>>()
        val scores = DoubleArray(n)
        for (k in 0 until n) {
            val e = edges[k]
            val graded = e.length >= MIN_GRADED_WALL
            val s = e.score
            val handSet = e.handSet
            scores[k] = when {
                handSet != null -> max(s ?: 0.0, handSet)
                s != null -> s
                graded -> UNSUPPORTED_WALL
                else -> UNSUPPORTED_SHORT_WALL
            }
            if (!graded || handSet != null) continue
            val noisyRms = e.noisyRms
            when {
                s == null -> unsupported += k
                noisyRms != null -> noisy += k to noisyRms
                s < WEAK_WALL -> weak += k
            }
        }
        // Names: outline edge numbers, or the capture to redo (its observation index + 1).
        fun number(k: Int): Int = when (numbering) {
            WallNumbering.OUTLINE -> storedEdge(k) + 1
            WallNumbering.CAPTURE -> (edges[k].capture ?: storedEdge(k)) + 1
        }
        if (unsupported.isNotEmpty()) {
            reasons += when (numbering) {
                WallNumbering.OUTLINE -> {
                    val numbers = unsupported.map(::number).sorted()
                    "${walls(numbers)} ${if (numbers.size == 1) "wasn't" else "weren't"} measured directly — " +
                        "check ${if (numbers.size == 1) "it" else "them"} with a tape"
                }
                WallNumbering.CAPTURE -> unsupportedByNeighbours(unsupported, edges)
            }
        }
        if (noisy.isNotEmpty()) {
            val sorted = noisy.map { (k, rms) -> number(k) to rms }.sortedBy { it.first }
            val worst = sorted.maxOf { it.second }
            reasons += if (sorted.size == 1) "${walls(sorted.map { it.first })} was noisy (±${noise(worst, unitSystem)}) — consider recapturing"
            else "${walls(sorted.map { it.first })} were noisy (up to ±${noise(worst, unitSystem)}) — consider recapturing"
        }
        if (weak.isNotEmpty()) {
            val numbers = weak.map(::number).sorted()
            reasons += "${walls(numbers)} ${if (numbers.size == 1) "was a weak capture — recapture it" else "were weak captures — recapture them"} " +
                "from closer"
        }
        return combine(scores, edges.map { it.length })
    }

    /**
     * CAPTURE-numbered reason for walls no capture supports: each is named by the captures of the nearest
     * supported walls before and after it around the outline ("the wall between walls 3 and 4"); consecutive
     * unsupported walls between the same captures are one gap.
     */
    private fun unsupportedByNeighbours(unsupported: List<Int>, edges: List<EdgeGrade>): String {
        val n = edges.size
        fun neighbour(k: Int, step: Int): Int? {
            var i = k
            repeat(n - 1) {
                i = (i + step + n) % n
                edges[i].capture?.let { return it + 1 }
            }
            return null
        }
        val gaps = LinkedHashMap<Pair<Int, Int>, Int>()
        var unnamed = 0
        for (k in unsupported) {
            val a = neighbour(k, -1)
            val b = neighbour(k, +1)
            if (a == null || b == null) {
                unnamed++
                continue
            }
            val key = min(a, b) to max(a, b)
            gaps[key] = (gaps[key] ?: 0) + 1
        }
        if (gaps.isEmpty()) {
            return if (unnamed == 1) "A wall wasn't measured directly — check it with a tape"
            else "$unnamed walls weren't measured directly — check them with a tape"
        }
        val phrases = gaps.keys.mapIndexed { i, (a, b) ->
            when {
                a == b -> "next to wall $a"
                i == 0 -> "between walls $a and $b"
                else -> "between $a and $b"
            }
        }
        val count = gaps.values.sum() + unnamed
        val where = if (phrases.size == 1) phrases[0] else phrases.dropLast(1).joinToString(", ") + " and " + phrases.last()
        return if (count == 1) "The wall $where wasn't measured directly — check it with a tape"
        else "The walls $where weren't measured directly — check them with a tape"
    }

    private fun cornersScore(
        room: Room,
        outline: List<Vec2>,
        numbering: WallNumbering,
        unitSystem: UnitSystem?,
        reasons: MutableList<String>,
    ): Double {
        val taps = room.cornerTaps.withIndex().filter { it.value.isFinite() }
        if (taps.isEmpty()) {
            reasons += "No corner taps were kept for this room — scan it again to check its accuracy"
            return 0.6
        }
        val reversed = outline !== room.corners
        val n = outline.size
        fun storedCorner(k: Int) = if (reversed) n - 1 - k else k
        // A corner is set by hand when a wall meeting there is (stored edges storedCorner - 1 and storedCorner).
        fun handSet(k: Int): Double? {
            val c = storedCorner(k)
            val walls = listOfNotNull(RoomOps.handSetWallFor(room, (c - 1 + n) % n), RoomOps.handSetWallFor(room, c))
            if (walls.isEmpty()) return null
            return if (walls.any { it.typedLength != null }) TYPED_WALL else HAND_SET_WALL
        }
        val scores = DoubleArray(n)
        var worstDistance = 0.0
        val far = ArrayList<Int>()
        for (k in 0 until n) {
            val nearest = taps.minBy { it.value.distanceTo(outline[k]) }
            val d = nearest.value.distanceTo(outline[k])
            val tapScore = when {
                d <= TAP_EXACT -> 1.0
                d <= TAP_FAR -> 1.0 - 0.5 * (d - TAP_EXACT) / (TAP_FAR - TAP_EXACT)
                else -> UNSUPPORTED_WALL
            }
            val hand = handSet(k)
            scores[k] = if (hand != null) max(tapScore, hand) else tapScore
            if (d > TAP_FAR && hand == null) {
                far += when (numbering) {
                    WallNumbering.OUTLINE -> storedCorner(k) + 1
                    WallNumbering.CAPTURE -> nearest.index + 1
                }
                worstDistance = max(worstDistance, d)
            }
        }
        if (far.isNotEmpty()) {
            val distance = distance(worstDistance, unitSystem)
            val numbers = far.distinct().sorted()
            reasons += if (numbers.size == 1) "Corner ${numbers[0]} is $distance from where you tapped — check it"
            else "Corners ${numberList(numbers)} are up to $distance from where you tapped — check them"
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

    /** Score of one capture and, for a noisy depth fit (RMS above [noisyLimit] at its range), its RMS. */
    private fun observationScore(o: WallObservation): Pair<Double, Double?> {
        val confidence = o.confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        return when (o.source) {
            WallSource.DEPTH_FIT -> {
                val q = min(1.0, confidence / CONFIDENT_FIT)
                val rms = o.rmsResidual.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                if (rms > noisyLimit(o)) min(q, NOISY_WALL) to rms else q to null
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

    /** Per-point noise for a reason: "3.1 cm", or "1.2 in" in imperial projects (cm when no unit system is given). */
    private fun noise(meters: Double, unitSystem: UnitSystem?): String =
        if (unitSystem == UnitSystem.IMPERIAL) String.format(Locale.ROOT, "%.1f in", meters / Units.METERS_PER_INCH)
        else String.format(Locale.ROOT, "%.1f cm", meters * 100)

    /** A corner distance: in [unitSystem], or "20 cm (8\")" when none is given. */
    private fun distance(meters: Double, unitSystem: UnitSystem?): String = when (unitSystem) {
        UnitSystem.METRIC -> Units.formatLength(meters, UnitSystem.METRIC)
        UnitSystem.IMPERIAL -> Units.formatLength(meters, UnitSystem.IMPERIAL, 1)
        null -> "${Units.formatLength(meters, UnitSystem.METRIC)} (${Units.formatLength(meters, UnitSystem.IMPERIAL, 1)})"
    }

    private fun walls(numbers: List<Int>): String =
        if (numbers.size == 1) "Wall ${numbers[0]}" else "Walls ${numberList(numbers)}"

    /** "2", "2 and 5", "2, 3 and 5". */
    internal fun numberList(numbers: List<Int>): String = when (numbers.size) {
        0 -> ""
        1 -> numbers[0].toString()
        else -> numbers.dropLast(1).joinToString(", ") + " and " + numbers.last()
    }
}
