package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class SolverOptions(
    val squareAngles: Boolean = true,
    /** Walls within this many degrees of the dominant axis (mod 90deg) are snapped onto it. */
    val squareToleranceDeg: Double = 5.0,
    /** Consecutive walls closer than this angle are treated as parallel (same wall or a jog). */
    val parallelDeg: Double = 12.0,
    /** Parallel consecutive walls whose faces are within this distance are merged into one wall. */
    val sameWallOffset: Double = 0.10,
    /**
     * Preferred squaring axis (radians, mod 90deg), e.g. the dominant axis of rooms already captured in the
     * same AR session, so neighbouring rooms come out exactly parallel. Used when the room's own dominant
     * axis is within squareToleranceDeg of it; ignored otherwise.
     */
    val axisHint: Double? = null,
)

data class SolveResult(
    /** Interior outline, counter-clockwise. */
    val corners: List<Vec2>,
    /** For each edge i (corners[i] -> corners[i+1]): index of the source observation, or -1 if synthesized. */
    val edgeSources: List<Int>,
    /** Dominant wall direction in radians, in [0, PI/2). */
    val dominantAngle: Double,
    /** Human-readable issues worth showing the user (e.g. "Wall 3 corner is far from what was measured"). */
    val warnings: List<String>,
)

object RoomSolver {
    /** Edges shorter than this (meters) are collapsed when cleaning up a solved outline. */
    internal const val MIN_EDGE_LENGTH = 0.05

    /** A corner tap closer than this to the previous tap is treated as an accidental double tap. */
    internal const val DUPLICATE_TAP_DISTANCE = 0.05

    /** A vertex closer than this to the chord between its two neighbours is dropped as collinear. */
    internal const val COLLINEAR_TOLERANCE = 0.01

    /** Interior angles below this, or above 360deg minus this, are reported as spikes. */
    internal const val SPIKE_ANGLE_DEG = 30.0

    /**
     * Adjacent measured walls meeting at less than this angle (a bend of at most this much) get a warning:
     * their intersection moves by an offset error / sin(angle), i.e. 2.4× or more.
     */
    internal const val SHALLOW_CORNER_DEG = 25.0

    /** Rooms with a smaller area (m²) get a warning. */
    internal const val MIN_ROOM_AREA = 0.5

    internal const val REORDERED_WARNING = "Walls were re-ordered to close the room; check the outline"

    internal const val UNSQUARED_WARNING = "Corners were not squared because that made the outline cross itself"

    /** Floor for observation confidence so a zero-confidence wall still has a usable (tiny) weight. */
    private const val MIN_CONFIDENCE = 0.01

    /** Observed extents shorter than this still weigh as if they were this long (meters). */
    private const val MIN_WEIGHT_LENGTH = 0.1

    /**
     * Axis consensus: a wall's vote is confidence × min(observed extent, this) (m), so one long ARCore plane
     * or one long oblique capture cannot outvote the room's other walls.
     */
    private const val MAX_SUPPORT_LENGTH = 1.0

    /**
     * Direction variance floor (rad²) added to every wall's estimated direction variance: unmodeled error
     * (depth bias, slightly bowed walls) that more points cannot average away. (0.15°)².
     */
    private val SYSTEMATIC_DIRECTION_VARIANCE = Math.toRadians(0.15).let { it * it }

    /** Direction variance (rad²) of an ARCore vertical plane: its orientation is good to about a degree. */
    private val PLANE_DIRECTION_VARIANCE = Math.toRadians(1.0).let { it * it }

    /** Position noise (m) of a wall-base tap (baseline mode) / of a corner tap, and of a manual wall. */
    private const val TAP_SIGMA = 0.01
    private const val MANUAL_SIGMA = 0.005

    /** A depth fit's line anchor is used as its pivot only when at least this fraction of the extent from either end. */
    private const val PIVOT_END_MARGIN = 0.01

    /** Depth-fit direction variance: residual RMS floor (m) and effective point-count cap. */
    private const val MIN_RMS = 0.002
    private const val MAX_EFFECTIVE_POINTS = 2000

    /** Axis refinement iterations (re-selecting the supporting walls each time). */
    private const val AXIS_REFINE_ITERATIONS = 5

    /**
     * Snapped corner-tap edges meeting at |sin| below this (~14.5deg) are too close to parallel for a stable
     * intersection; the corner then keeps its tapped position.
     */
    private const val MIN_CORNER_SIN = 0.25

    /** When collapsing a short edge, the neighbours' intersection is used only if it is this close (m). */
    private const val MAX_COLLAPSE_SHIFT = 0.25

    /** Duplicate captures of one wall: their observed extents overlap, or the gap between them is at most this (m). */
    private const val DUPLICATE_GAP = 0.05

    /**
     * Same-facing parallel captures whose extents overlap but whose faces are between sameWallOffset and this
     * far apart (m) are the same wall measured at two depths (one capture hit furniture): a room cannot have
     * two walls facing the same way one behind the other this close.
     */
    private const val MAX_DUPLICATE_DEPTH = 0.75

    /** Overlap (fraction of the shorter extent, and at least [MIN_DUPLICATE_OVERLAP] m) for such a duplicate. */
    private const val DUPLICATE_OVERLAP_FRACTION = 0.3
    private const val MIN_DUPLICATE_OVERLAP = 0.10

    // Ordering cost (meter-equivalents): what each inconsistency of a candidate wall order costs.
    /** A "missing wall" connector between two facing walls. */
    private const val COST_MISSING_WALL = 1.5

    /** A captured wall that does not survive into the outline. */
    private const val COST_LOST_WALL = 1.5

    /** A synthesized step between same-facing parallel walls, plus [COST_JOG_PER_METER] beyond [FREE_JOG_LENGTH]. */
    private const val COST_JOG = 0.5
    private const val COST_JOG_PER_METER = 1.0
    private const val FREE_JOG_LENGTH = 0.5
    private const val MAX_JOG_COST = 1.5

    /** An edge that runs against its wall's direction (the room would be on the wrong side of it). */
    private const val COST_REVERSED_EDGE = 3.0

    /** Observed extent sticking out beyond its edge's corners is free up to this much (m): band, noise. */
    private const val EXTENT_TOLERANCE = 0.15

    /** A re-ordered capture must beat the capture order by at least this much to be used. */
    private const val REORDER_MARGIN = 0.05

    /** Local search: rounds of "best single-wall move" from each seed order. */
    private const val SEARCH_ROUNDS = 4

    private const val COST_EPS = 1e-9

    /**
     * Build a closed room outline from walls captured around the room (either direction, ideally in order).
     *
     * Steps:
     * 1. Each observation's line is re-oriented so its viewer is on the left (interior-left convention)
     *    even if the stored direction is inconsistent. Observations with non-finite or degenerate data
     *    are ignored (their index never appears in [SolveResult.edgeSources]).
     * 2. Squaring (optional): a robust dominant axis (see [robustAxis]; snapped to
     *    [SolverOptions.axisHint] when within tolerance of it) and every wall within tolerance of it
     *    (mod 90°) rotated onto it about its pivot (see [pivotOf]: where its position is known best), so its
     *    measured position is kept; angled walls stay as measured.
     * 3. Duplicates, wherever they are in the capture order: same-facing near-parallel captures whose faces
     *    are within [SolverOptions.sameWallOffset] and whose observed extents overlap (or nearly touch) are
     *    merged (weighted line, union of extents; the heavier capture names the edge). Overlapping ones
     *    10–75 cm apart are one wall measured twice, once on furniture: the outer one is kept (warning).
     * 4. Wall order: the capture order is solved first; if anything is inconsistent, single-wall moves
     *    (any wall to any position, which covers swaps, late re-captures and U-shaped rooms) are searched
     *    from the capture order and from the order by angle around the walls' centre, scoring each order by
     *    [orderCost]. The cheapest wins, the capture order on ties (it must be beaten by a margin);
     *    [REORDERED_WARNING] is reported when the final order of walls differs from the capture order.
     * 5. For one order: consecutive same-facing near-parallel walls within sameWallOffset merge; same facing
     *    but farther apart is a step, bridged by a perpendicular connector halfway between the observed
     *    extents (warning); opposite facing means a wall between them was skipped, so a connector is added at
     *    the far end of their extents (warning). Corner i = intersection of walls i-1 and i; the outline is
     *    normalized to CCW and rotated to start with the earliest capture; edges shorter than 5 cm are
     *    collapsed and collinear vertices removed.
     * 6. If squaring makes every order cross itself, the walls are solved unsquared ([UNSQUARED_WARNING]).
     *
     * Warnings (1-based wall numbers = capture numbers): re-ordered; same wall at two depths; missing wall /
     * assumed step; a wall left out; two walls meeting at a shallow angle (uncertain corner); spikes; very
     * small room. Plain extrapolation from a small observed patch to a far corner is NOT a warning: every
     * centre-aimed capture does it, and with squared walls it is accurate.
     *
     * Returns null when fewer than 3 usable walls remain or no valid polygon can be formed.
     */
    fun solveWalls(observations: List<WallObservation>, options: SolverOptions = SolverOptions()): SolveResult? {
        if (observations.size < 3) return null
        val raw = observations.mapIndexedNotNull { i, o -> wallFromObservation(o, i) }
        if (raw.size < 3) return null

        val cfg = Config.of(options)
        val dominant = withHint(
            robustAxis(raw.map { it.line.angle() }, raw.map { it.support }, raw.map { it.precision }, cfg.toleranceRad),
            options.axisHint,
            cfg.toleranceRad,
        )
        val snapper = if (options.squareAngles) Snapper(dominant, cfg.toleranceRad) else null
        var solved = solveOrdered(raw, cfg, snapper)
        var unsquared = false
        if (solved == null && snapper != null) {
            solved = solveOrdered(raw, cfg, null)
            unsquared = solved != null
        }
        val chosen = solved ?: return null

        val warnings = buildList {
            if (unsquared) add(UNSQUARED_WARNING)
            if (!followsCaptureOrder(chosen.attempt.edges)) add(REORDERED_WARNING)
            addAll(chosen.duplicateWarnings)
            addAll(chosen.attempt.warnings)
            chosen.attempt.lost.forEach { add("Wall ${it.source + 1} didn't fit the outline and was left out; check it") }
            addAll(shallowCornerWarnings(chosen.attempt.edges))
            addAll(shapeWarnings(chosen.attempt.edges, wallsMode = true))
        }.distinct()
        return SolveResult(
            corners = chosen.attempt.edges.map { it.start },
            edgeSources = chosen.attempt.edges.map { it.source },
            dominantAngle = dominant,
            warnings = warnings,
        )
    }

    /**
     * Clean up a corner-tapped outline: drop near-duplicate/collinear points, optional squaring, CCW.
     *
     * * Taps closer than 5 cm to the previous kept tap are dropped (a final tap back onto the first
     *   corner closes the room and is dropped too); vertices within 1 cm of their neighbours' chord are
     *   dropped as collinear.
     * * Squaring: the dominant direction is the robust consensus axis of the edges ([robustAxis]; an
     *   angled edge does not pull it; snapped to [SolverOptions.axisHint] when within tolerance); every
     *   edge within tolerance becomes a line through its midpoint at the exact axis angle (others keep the
     *   line through their two taps), consecutive edges snapped onto the same axis are merged, and each
     *   corner is the intersection of its two edge lines. If squaring would make the outline cross
     *   itself, the unsquared outline is returned with [UNSQUARED_WARNING].
     * * edgeSources[i] is the index (into [taps]) of the tap edge taps[j] -> taps[j+1] that the output
     *   edge comes from; when tap edges were merged, the longest one wins.
     * * Returns null for fewer than 3 usable corners, a degenerate outline or one that crosses itself.
     */
    fun solveCorners(taps: List<Vec2>, options: SolverOptions = SolverOptions()): SolveResult? {
        val valid = taps.withIndex().filter { it.value.isFinite() }
        if (valid.size < 3) return null
        val edges = ArrayList<OutlineEdge>(valid.size)
        for (k in valid.indices) {
            val a = valid[k].value
            val b = valid[(k + 1) % valid.size].value
            edges += OutlineEdge(a, valid[k].index, null, a.distanceTo(b))
        }
        dropDuplicateTaps(edges)
        if (edges.size < 3) return null
        removeCollinear(edges)
        if (edges.size < 3 || abs(Polygons.signedArea(edges.map { it.start })) < 1e-9) return null

        val cfg = Config.of(options)
        val lengths = edgeLengths(edges)
        // Two taps σ apart give a direction variance of 2σ²/L².
        val precision = lengths.map { 1.0 / (2 * TAP_SIGMA * TAP_SIGMA / max(it, MIN_WEIGHT_LENGTH).let { l -> l * l } + SYSTEMATIC_DIRECTION_VARIANCE) }
        val dominant = withHint(
            robustAxis(edgeAngles(edges), lengths.map { min(it, MAX_SUPPORT_LENGTH) }, precision, cfg.toleranceRad),
            options.axisHint,
            cfg.toleranceRad,
        )
        val unsquared = finishCornerOutline(edges)
        if (options.squareAngles) {
            val squared = squareCornerOutline(edges, Snapper(dominant, cfg.toleranceRad))?.let(::finishCornerOutline)
            if (squared != null) return cornerResult(squared, dominant, emptyList())
            if (unsquared != null) return cornerResult(unsquared, dominant, listOf(UNSQUARED_WARNING))
            return null
        }
        return unsquared?.let { cornerResult(it, dominant, emptyList()) }
    }

    /**
     * Robust shared squaring axis (mod 90°, in [0, PI/2)) of several closed room [outlines], for
     * [SolverOptions.axisHint]: angled walls (bays, chamfers, a 30° wall) must not pull it, so it is a
     * consensus over the edges (support = edges within the squaring tolerance, refined with supporters only),
     * not the plain 4θ mean of [dominantAngle]. Returns null when there is no usable edge or when the
     * supporting length is less than half of the total (no coherent axis).
     * Round-3 contract: currently the FloorAxis refinement (mean, then 10° and 5° windows).
     */
    fun sharedAxis(outlines: List<List<Vec2>>): Double? = FloorAxis.ofOutlines(outlines)

    /**
     * Weighted dominant direction mod 90deg, in [0, PI/2). Uses the 4θ circular-mean trick so that
     * perpendicular walls reinforce each other. [weights] default to 1.
     *
     * Non-finite angles and non-positive / non-finite weights are ignored; a missing weight counts as 1.
     * Returns 0 when there is no usable input or the directions cancel out exactly.
     */
    fun dominantAngle(angles: List<Double>, weights: List<Double>? = null): Double {
        var c = 0.0
        var s = 0.0
        for ((i, a) in angles.withIndex()) {
            val w = weights?.getOrNull(i) ?: 1.0
            if (!a.isFinite() || !w.isFinite() || w <= 0.0) continue
            c += w * cos(4 * a)
            s += w * sin(4 * a)
        }
        if (hypot(c, s) < 1e-12) return 0.0
        return canonicalAxis(atan2(s, c) / 4)
    }

    /**
     * Robust squaring axis, in [0, PI/2): the axis most walls agree on, refined with those walls only.
     *
     * A plain weighted mean ([dominantAngle]) over all walls is pulled by any genuinely angled wall (a 30°
     * wall moves it by ~5°, which then rotates every square wall), and a heavily weighted angled wall can
     * even take the axis over. Instead:
     * 1. Candidates: the plain mean (weighted by [support]) and every wall's own direction mod 90°.
     * 2. Each candidate's score is the total [support] of the walls within [toleranceRad] of it (mod 90°);
     *    the best wins (ties: the earlier candidate, i.e. the mean, then capture order).
     * 3. Refinement: the 4θ mean of only those supporting walls, weighted by [precision] (the walls'
     *    direction precision, 1 / variance), re-selecting the supporters around the new axis until stable.
     *
     * [support] should be bounded per wall (one wall must not outvote several), [precision] reflects how
     * well each wall's direction is actually known. Walls outside the tolerance keep their own direction
     * when squaring. Invalid entries (non-finite angle, non-positive / non-finite weights) are ignored;
     * returns 0 when nothing is usable.
     */
    internal fun robustAxis(angles: List<Double>, support: List<Double>, precision: List<Double>, toleranceRad: Double): Double {
        val idx = angles.indices.filter { i ->
            val s = support.getOrNull(i) ?: 0.0
            val p = precision.getOrNull(i) ?: 0.0
            angles[i].isFinite() && s.isFinite() && s > 0.0 && p.isFinite() && p > 0.0
        }
        if (idx.isEmpty()) return 0.0
        val tol = if (toleranceRad.isFinite()) toleranceRad.coerceIn(0.0, PI / 4) else Math.toRadians(5.0)
        fun supporters(axis: Double) = idx.filter { axisDistance(angles[it], axis) <= tol + 1e-12 }
        val candidates = ArrayList<Double>(idx.size + 1)
        candidates += dominantAngle(idx.map { angles[it] }, idx.map { support[it] })
        idx.forEach { candidates += canonicalAxis(angles[it]) }
        var best = candidates[0]
        var bestScore = -1.0
        for (c in candidates) {
            val score = supporters(c).sumOf { support[it] }
            if (score > bestScore + 1e-12) {
                bestScore = score
                best = c
            }
        }
        var axis = best
        var members = supporters(axis)
        for (iter in 0 until AXIS_REFINE_ITERATIONS) {
            if (members.isEmpty()) break
            val refined = dominantAngle(members.map { angles[it] }, members.map { precision[it] })
            val next = supporters(refined)
            axis = refined
            if (next == members) break
            members = next
        }
        return axis
    }

    /** [axis], or the hint (mod 90°) when the hint is finite and within [toleranceRad] of it. */
    private fun withHint(axis: Double, hint: Double?, toleranceRad: Double): Double {
        if (hint == null || !hint.isFinite()) return axis
        val h = canonicalAxis(hint)
        return if (axisDistance(axis, h) <= toleranceRad + 1e-12) h else axis
    }

    /** Angle mod 90°, in [0, PI/2) (values a hair below 90° or above 0 are the same axis as 0). */
    private fun canonicalAxis(angle: Double): Double {
        var r = angle % (PI / 2)
        if (r < 0) r += PI / 2
        if (r >= PI / 2 - 1e-12 || r < 1e-12) r = 0.0
        return r
    }

    /** Distance between two directions modulo 90°, in [0, PI/4]. */
    private fun axisDistance(a: Double, b: Double): Double {
        val d = ((a - b) % (PI / 2) + PI / 2) % (PI / 2)
        return min(d, PI / 2 - d)
    }

    /**
     * Reverse a closed outline while keeping per-edge data attached to the right edge.
     * Edge i of the input runs corners[i] -> corners[(i+1) % n] and carries edgeData[i].
     *
     * Mapping (n = corners.size): new corner k = corners[(n + 1 - k) % n] and new edge k carries
     * edgeData[(n - k) % n]. New edge k runs from old corner (n+1-k) to old corner (n-k), i.e. it is
     * old edge (n - k) traversed backwards. Corner 0 becomes old corner 1, so old edge 0 stays edge 0.
     */
    internal fun <T> reverseOutline(corners: List<Vec2>, edgeData: List<T>): Pair<List<Vec2>, List<T>> {
        require(corners.size == edgeData.size) { "corners and edgeData must have the same size" }
        val n = corners.size
        if (n == 0) return corners to edgeData
        return List(n) { k -> corners[(n + 1 - k) % n] } to List(n) { k -> edgeData[(n - k) % n] }
    }

    // ---------------------------------------------------------------------------------------------
    // Internal model

    /** Sanitized solver options. */
    private class Config(val toleranceRad: Double, val parallelSin: Double, val sameWallOffset: Double) {
        companion object {
            fun of(o: SolverOptions): Config {
                val tolDeg = o.squareToleranceDeg.takeIf { it.isFinite() }?.coerceIn(0.0, 45.0) ?: 5.0
                val parDeg = o.parallelDeg.takeIf { it.isFinite() }?.coerceIn(0.0, 45.0) ?: 12.0
                val same = o.sameWallOffset.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.10
                return Config(
                    toleranceRad = Math.toRadians(tolDeg),
                    parallelSin = max(sin(Math.toRadians(parDeg)), 1e-6),
                    sameWallOffset = same,
                )
            }
        }
    }

    /**
     * A wall line (interior on its LEFT) with its observed extent [start] -> [end] projected onto it
     * (start has the smaller parameter). [source] is the observation index naming the wall (the heaviest
     * capture), -1 for synthesized walls; [members] are all captures merged into it (ascending).
     * [weight] decides merges (confidence × extent), [support] / [precision] the squaring axis, and
     * [pivot] (on the line) is where its position is best known: squaring rotates the wall about it.
     */
    private data class Wall(
        val line: Line2,
        val start: Vec2,
        val end: Vec2,
        val weight: Double,
        val source: Int,
        val members: List<Int>,
        val support: Double,
        val precision: Double,
        val pivot: Vec2,
    ) {
        val midpoint: Vec2 get() = Vec2.lerp(start, end, 0.5)

        /** Earliest capture in this wall (capture-order identity). */
        val first: Int get() = members.firstOrNull() ?: -1

        /** Same wall on a different line: extent and pivot are re-projected onto [newLine]. */
        fun withLine(newLine: Line2, points: List<Vec2> = listOf(start, end), newPivot: Vec2 = pivot): Wall {
            val sorted = points.map { newLine.project(it) }.sortedBy { newLine.param(it) }
            return copy(line = newLine, start = sorted.first(), end = sorted.last(), pivot = newLine.project(newPivot))
        }
    }

    /**
     * Edge of a closed outline: it starts at [start] and ends at the next edge's start.
     * [wall] is the wall it lies on (walls mode only); [weight] decides which identity survives merges.
     */
    private data class OutlineEdge(val start: Vec2, val source: Int, val wall: Wall?, val weight: Double)

    /**
     * One wall order, solved. [cost] scores how inconsistent the result is with what was observed (see
     * [orderCost]); [lost] are walls that did not survive into the outline.
     */
    private class WallAttempt(val edges: List<OutlineEdge>, val warnings: List<String>, val lost: List<Wall>, val cost: Double)

    private class Connected(val walls: List<Wall>, val warnings: List<String>, val skipped: Int, val jogCost: Double)

    private class Ordered(val attempt: WallAttempt, val duplicateWarnings: List<String>)

    /** Snaps directions onto the four axis directions dominant + k*90deg. */
    private class Snapper(private val dominant: Double, private val toleranceRad: Double) {
        /**
         * Axis directions built with exact negation / perpendicular operations, so walls snapped onto
         * different axes are exactly perpendicular or anti-parallel.
         */
        private val axes: List<Vec2> = Vec2.fromAngle(dominant).let { b -> listOf(b, b.perp(), -b, -b.perp()) }

        class Snap(val axis: Int, val direction: Vec2)

        fun snap(angle: Double): Snap? {
            if (!angle.isFinite()) return null
            val k = Math.round((angle - dominant) / (PI / 2))
            val target = dominant + k * (PI / 2)
            if (abs(angle - target) > toleranceRad + 1e-12) return null
            val axis = Math.floorMod(k, 4L).toInt()
            return Snap(axis, axes[axis])
        }

        /** Rotate [w] about its pivot onto the nearest axis, if within tolerance. */
        fun snapWall(w: Wall): Wall {
            val s = snap(w.line.angle()) ?: return w
            return w.withLine(Line2(w.pivot, s.direction))
        }

        fun snapDirection(d: Vec2): Vec2 = snap(d.angle())?.direction ?: d
    }

    // ---------------------------------------------------------------------------------------------
    // Walls mode

    private fun wallFromObservation(o: WallObservation, index: Int): Wall? {
        if (!o.line.point.isFinite() || !o.line.direction.isFinite()) return null
        if (!o.extentStart.isFinite() || !o.extentEnd.isFinite()) return null
        val dir = o.line.direction.normalized()
        if (dir == Vec2.ZERO) return null
        var line = Line2(o.line.point, dir)
        if (o.viewer.isFinite() && line.signedDistance(o.viewer) < 0) line = line.flipped()
        val confidence = if (o.confidence.isFinite()) o.confidence.coerceIn(MIN_CONFIDENCE, 1.0) else 0.5
        val a = line.project(o.extentStart)
        val b = line.project(o.extentEnd)
        val (s, e) = if (line.param(a) <= line.param(b)) a to b else b to a
        val length = s.distanceTo(e)
        val weight = confidence * max(length, MIN_WEIGHT_LENGTH)
        val pivot = pivotOf(o, line, s, e)
        val support = confidence * min(max(length, MIN_WEIGHT_LENGTH), MAX_SUPPORT_LENGTH)
        val precision = confidence / (directionVariance(o, max(length, MIN_WEIGHT_LENGTH)) + SYSTEMATIC_DIRECTION_VARIANCE)
        return Wall(line, s, e, weight, index, listOf(index), support, precision, pivot)
    }

    /**
     * Where a wall's measured position is known best, so squaring rotates it about that point: for a depth
     * fit the line's anchor, which [com.oroplex.floorplan.core.fit.WallEstimator.fromPoints] puts at the
     * weighted centroid of its samples (offset and direction errors are uncorrelated there; for an oblique
     * capture that is towards the near end, not the middle of the extent). It is trusted only strictly
     * inside the observed extent (an anchor sitting on an extent end is a line built through two points);
     * every other case uses the middle of the observed extent [s]..[e].
     */
    private fun pivotOf(o: WallObservation, line: Line2, s: Vec2, e: Vec2): Vec2 {
        val mid = Vec2.lerp(s, e, 0.5)
        if (o.source != WallSource.DEPTH_FIT) return mid
        val anchor = line.project(o.line.point)
        if (!anchor.isFinite()) return mid
        val t = line.param(anchor)
        val t0 = line.param(s)
        val t1 = line.param(e)
        val margin = PIVOT_END_MARGIN * (t1 - t0)
        return if (t1 - t0 > 0 && t > t0 + margin && t < t1 - margin) anchor else mid
    }

    /**
     * Estimated variance (rad²) of an observation's direction from how it was measured: a line fitted
     * to n samples with residual RMS r spread over an extent L has var(θ) ≈ 12 r² / (n L²) (n capped,
     * r floored); two taps σ apart give 2σ² / L²; an ARCore plane about (1°)².
     */
    private fun directionVariance(o: WallObservation, length: Double): Double = when (o.source) {
        WallSource.DEPTH_FIT -> {
            val r = if (o.rmsResidual.isFinite()) max(o.rmsResidual, MIN_RMS) else 0.02
            val n = o.pointCount.coerceIn(2, MAX_EFFECTIVE_POINTS)
            12 * r * r / (n * length * length)
        }
        WallSource.DETECTED_PLANE -> PLANE_DIRECTION_VARIANCE
        WallSource.BASELINE_TAPS -> 2 * TAP_SIGMA * TAP_SIGMA / (length * length)
        WallSource.MANUAL -> 2 * MANUAL_SIGMA * MANUAL_SIGMA / (length * length)
    }

    /**
     * Steps 3–5 of [solveWalls] for already squared (or raw, with [snapper] null) walls. Null when no
     * order gives a valid outline.
     */
    private fun solveOrdered(raw: List<Wall>, cfg: Config, snapper: Snapper?): Ordered? {
        val snapped = if (snapper != null) raw.map { snapper.snapWall(it) } else raw
        val (walls, duplicateWarnings) = mergeDuplicates(snapped, cfg, snapper)
        if (walls.size < 3) return null
        val attempt = searchOrder(walls, cfg, snapper) ?: return null
        return Ordered(attempt, duplicateWarnings)
    }

    /**
     * Step 3 of [solveWalls]: duplicate captures of one wall anywhere in the capture order. Each merged
     * wall keeps the capture-order position of its earliest member.
     */
    private fun mergeDuplicates(input: List<Wall>, cfg: Config, snapper: Snapper?): Pair<List<Wall>, List<String>> {
        val walls = input.toMutableList()
        val warnings = mutableListOf<String>()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in walls.indices) {
                for (j in i + 1 until walls.size) {
                    val a = walls[i]
                    val b = walls[j]
                    if (!isParallel(a, b, cfg) || !sameFacing(a, b)) continue
                    val offset = faceOffset(a, b)
                    val overlap = extentOverlap(a, b)
                    if (offset <= cfg.sameWallOffset && overlap >= -DUPLICATE_GAP) {
                        walls[i] = merge(a, b, snapper)
                    } else if (offset <= MAX_DUPLICATE_DEPTH &&
                        overlap >= max(MIN_DUPLICATE_OVERLAP, DUPLICATE_OVERLAP_FRACTION * min(a.length(), b.length()))
                    ) {
                        // The same stretch of wall measured at two depths: the inner one stood in front of it.
                        val outerIsA = a.line.signedDistance(b.midpoint) > 0
                        val outer = if (outerIsA) a else b
                        val inner = if (outerIsA) b else a
                        warnings += "Walls ${a.first + 1} and ${b.first + 1} are the same wall measured " +
                            "${distanceText(offset)} apart; the outer one (wall ${outer.first + 1}) was used"
                        walls[i] = outer.copy(members = (outer.members + inner.members).sorted())
                    } else {
                        continue
                    }
                    walls.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return walls to warnings
    }

    private fun Wall.length() = start.distanceTo(end)

    /** Overlap (m) of two near-parallel walls' observed extents along [a]'s direction; negative = gap. */
    private fun extentOverlap(a: Wall, b: Wall): Double {
        val a0 = a.line.param(a.start)
        val a1 = a.line.param(a.end)
        val b0 = min(a.line.param(b.start), a.line.param(b.end))
        val b1 = max(a.line.param(b.start), a.line.param(b.end))
        return min(a1, b1) - max(a0, b0)
    }

    /**
     * Step 4 of [solveWalls]: the capture order unless an order with a clearly lower [orderCost] exists.
     * Candidates: best-single-move local search (any wall moved to any position, [SEARCH_ROUNDS] rounds)
     * from the capture order and from the angular order around the walls' centre. O(rounds · n²) solves,
     * a few milliseconds for 16 walls.
     */
    private fun searchOrder(walls: List<Wall>, cfg: Config, snapper: Snapper?): WallAttempt? {
        val cache = HashMap<List<Int>, WallAttempt?>()
        fun solve(order: List<Int>): WallAttempt? = cache.getOrPut(order) { attemptWalls(order.map { walls[it] }, cfg, snapper) }
        fun cost(order: List<Int>): Double = solve(order)?.cost ?: Double.POSITIVE_INFINITY

        val captureOrder = walls.indices.toList()
        val captureCost = cost(captureOrder)
        if (captureCost <= COST_EPS) return solve(captureOrder)

        var bestOrder: List<Int>? = null
        var bestCost = Double.POSITIVE_INFINITY
        val angular = sortAroundCenter(walls)
        for (seed in listOf(captureOrder, angular)) {
            var current = seed
            var currentCost = cost(seed)
            for (round in 0 until SEARCH_ROUNDS) {
                if (currentCost <= COST_EPS) break
                var roundBest: List<Int>? = null
                var roundCost = currentCost
                for (from in current.indices) {
                    for (to in current.indices) {
                        if (to == from) continue
                        val moved = current.toMutableList().also { it.add(to, it.removeAt(from)) }
                        val c = cost(moved)
                        if (c < roundCost - COST_EPS) {
                            roundCost = c
                            roundBest = moved
                        }
                    }
                }
                current = roundBest ?: break
                currentCost = roundCost
            }
            if (currentCost < bestCost - COST_EPS) {
                bestCost = currentCost
                bestOrder = current
            }
        }
        val reordered = bestOrder
        return if (reordered != null && bestCost < captureCost - REORDER_MARGIN) solve(reordered) else solve(captureOrder)
            ?: reordered?.let { solve(it) }
    }

    private fun attemptWalls(walls: List<Wall>, cfg: Config, snapper: Snapper?): WallAttempt? {
        val merged = mergeSameWalls(walls, cfg, snapper)
        if (merged.size < 3) return null
        val connected = insertConnectors(merged, cfg)
        val outline = buildOutline(connected.walls) ?: return null
        val ccw = normalizeCcw(outline) ?: return null
        val cleaned = cleanUp(ccw.toMutableList(), useWallLines = true)
        if (!isValidOutline(cleaned)) return null
        val edges = startAtEarliestCapture(cleaned)
        val kept = edges.mapTo(HashSet()) { it.source }
        val lost = merged.filter { it.source !in kept }
        val cost = orderCost(edges, connected, lost.size)
        return WallAttempt(edges, connected.warnings, lost, cost)
    }

    /**
     * How inconsistent a solved outline is with what was observed, in meter-equivalents (0 = consistent):
     * * each observed wall's extent must lie on its edge: what sticks out beyond the edge's corners
     *   (more than 15 cm, the band and noise) is a corner cutting into a measured stretch of wall — a
     *   mis-ordered capture puts walls where they were not seen;
     * * an edge running against its wall's direction (the room on the wrong side of it): 3 + its length;
     * * a "missing wall" connector or a lost wall: 1.5 each; a synthesized step: 0.5, up to 1.5 when it is
     *   longer than 0.5 m (a real uncaptured step is a column or chimney breast, not a 2 m jog).
     */
    private fun orderCost(edges: List<OutlineEdge>, connected: Connected, lost: Int): Double {
        var cost = COST_MISSING_WALL * connected.skipped + COST_LOST_WALL * lost + connected.jogCost
        val m = edges.size
        for (k in 0 until m) {
            val wall = edges[k].wall ?: continue
            val a = edges[k].start
            val b = edges[(k + 1) % m].start
            val dir = wall.line.direction
            if (((b - a) dot dir) < 0) {
                cost += COST_REVERSED_EDGE + a.distanceTo(b)
                continue
            }
            if (wall.source < 0) continue
            val t0 = wall.line.param(a)
            val t1 = wall.line.param(b)
            val s0 = wall.line.param(wall.start)
            val s1 = wall.line.param(wall.end)
            val excess = max(0.0, t0 - s0) + max(0.0, s1 - t1)
            if (excess > EXTENT_TOLERANCE) cost += excess - EXTENT_TOLERANCE
        }
        return cost
    }

    /** Merge consecutive (wrapping) same-facing near-parallel walls whose faces are close; repeat until stable. */
    private fun mergeSameWalls(walls: List<Wall>, cfg: Config, snapper: Snapper?): List<Wall> {
        val list = walls.toMutableList()
        var changed = true
        while (changed && list.size >= 2) {
            changed = false
            for (i in list.indices) {
                val j = (i + 1) % list.size
                if (i == j) break
                val a = list[i]
                val b = list[j]
                if (!isParallel(a, b, cfg) || !sameFacing(a, b) || faceOffset(a, b) > cfg.sameWallOffset) continue
                val m = merge(a, b, snapper)
                if (j == 0) {
                    list[0] = m
                    list.removeAt(i)
                } else {
                    list[i] = m
                    list.removeAt(j)
                }
                changed = true
                break
            }
        }
        return list
    }

    private fun isParallel(a: Wall, b: Wall, cfg: Config) = a.line.sinAngleTo(b.line) < cfg.parallelSin

    private fun sameFacing(a: Wall, b: Wall) = (a.line.direction dot b.line.direction) > 0

    /** Mean perpendicular distance between two near-parallel walls, measured at their observed midpoints. */
    private fun faceOffset(a: Wall, b: Wall) = (a.line.distance(b.midpoint) + b.line.distance(a.midpoint)) / 2

    /** Weight-averaged direction of two same-facing walls (exact when they already agree). */
    private fun averageDirection(a: Wall, b: Wall): Vec2 {
        if (a.line.direction == b.line.direction) return a.line.direction
        val d = (a.line.direction * a.weight + b.line.direction * b.weight).normalized()
        return if (d == Vec2.ZERO) a.line.direction else d
    }

    private fun merge(a: Wall, b: Wall, snapper: Snapper?): Wall {
        val total = a.weight + b.weight
        val dir = averageDirection(a, b).let { snapper?.snapDirection(it) ?: it }
        val point = (a.pivot * a.weight + b.pivot * b.weight) / total
        val heavier = when {
            a.weight > b.weight -> a
            b.weight > a.weight -> b
            a.source <= b.source -> a
            else -> b
        }
        return heavier.copy(
            weight = total,
            members = (a.members + b.members).sorted(),
            support = a.support + b.support,
            precision = a.precision + b.precision,
        ).withLine(Line2(point, dir), listOf(a.start, a.end, b.start, b.end), point)
    }

    /**
     * +1 when the walls were captured counter-clockwise, -1 for clockwise: the sign of the area of the
     * polygon through the observed midpoints, falling back to the summed turning of the directions.
     */
    private fun traversalSense(walls: List<Wall>): Double {
        val area = Polygons.signedArea(walls.map { it.midpoint })
        if (abs(area) > 1e-9) return if (area > 0) 1.0 else -1.0
        var turn = 0.0
        for (i in walls.indices) turn += walls[i].line.direction cross walls[(i + 1) % walls.size].line.direction
        return if (turn < 0) -1.0 else 1.0
    }

    /** Bridge every remaining consecutive near-parallel pair with a synthesized perpendicular wall. */
    private fun insertConnectors(walls: List<Wall>, cfg: Config): Connected {
        val n = walls.size
        val sense = traversalSense(walls)
        val out = ArrayList<Wall>(n + 2)
        val warnings = mutableListOf<String>()
        var skipped = 0
        var jogCost = 0.0
        for (i in 0 until n) {
            val a = walls[i]
            val b = walls[(i + 1) % n]
            out += a
            if (!isParallel(a, b, cfg)) continue
            if (sameFacing(a, b)) {
                val jog = jogConnector(a, b)
                out += jog
                val length = jog.length()
                jogCost += min(MAX_JOG_COST, COST_JOG + COST_JOG_PER_METER * max(0.0, length - FREE_JOG_LENGTH))
                warnings += "A step was assumed between walls ${a.source + 1} and ${b.source + 1}; " +
                    "capture the short wall there for an exact position"
            } else {
                out += skippedWallConnector(a, b, sense)
                skipped++
                warnings += "A wall seems to be missing between walls ${a.source + 1} and ${b.source + 1}; " +
                    "it was added where they end"
            }
        }
        return Connected(out, warnings, skipped, jogCost)
    }

    /**
     * Connector for a jog (two same-facing parallel walls at different depths): perpendicular to them,
     * halfway between the end of the earlier extent and the start of the later one (along their shared
     * direction). It runs from the earlier wall's face to the later one's, which keeps the interior on its
     * left: walking along +direction, a step towards the interior (+normal) turns left, so the interior is
     * behind the step (-direction), which is exactly the left side of +normal.
     */
    private fun jogConnector(a: Wall, b: Wall): Wall {
        val d = averageDirection(a, b)
        val n = d.perp()
        val a0 = min(a.start dot d, a.end dot d)
        val a1 = max(a.start dot d, a.end dot d)
        val b0 = min(b.start dot d, b.end dot d)
        val b1 = max(b.start dot d, b.end dot d)
        val aFirst = a0 + a1 <= b0 + b1
        val boundary = if (aFirst) (a1 + b0) / 2 else (b1 + a0) / 2
        val first = if (aFirst) a else b
        val second = if (aFirst) b else a
        val oFirst = first.midpoint dot n
        val oSecond = second.midpoint dot n
        val dir = if (oSecond > oFirst) n else -n
        val p1 = d * boundary + n * oFirst
        val p2 = d * boundary + n * oSecond
        return synthesizedWall(p1, p2, dir)
    }

    /**
     * Connector for two consecutive walls facing each other (the wall between them was skipped). The
     * missing wall is assumed at the far end of both observed extents in the direction of travel
     * ([sense] * wall a's direction), so the room is not cut short. Its direction t.perp() puts the room
     * (which lies behind that far end, at -t) on its left.
     */
    private fun skippedWallConnector(a: Wall, b: Wall, sense: Double): Wall {
        val t = a.line.direction * sense
        val far = listOf(a.start, a.end, b.start, b.end).maxOf { it dot t }
        val c = t.perp()
        val p1 = t * far + c * (a.midpoint dot c)
        val p2 = t * far + c * (b.midpoint dot c)
        return synthesizedWall(p1, p2, c)
    }

    private fun synthesizedWall(p1: Vec2, p2: Vec2, dir: Vec2): Wall {
        val mid = Vec2.lerp(p1, p2, 0.5)
        val line = Line2(mid, dir)
        return Wall(line, p1, p2, 0.0, -1, emptyList(), 0.0, 0.0, mid).withLine(line)
    }

    /** Edge i starts at the intersection of walls i-1 and i and lies on wall i. */
    private fun buildOutline(walls: List<Wall>): List<OutlineEdge>? {
        val m = walls.size
        if (m < 3) return null
        val edges = ArrayList<OutlineEdge>(m)
        for (i in 0 until m) {
            val prev = walls[(i - 1 + m) % m]
            val cur = walls[i]
            val p = prev.line.intersect(cur.line) ?: return null
            if (!p.isFinite()) return null
            edges += OutlineEdge(p, cur.source, cur, cur.weight)
        }
        return edges
    }

    /** Wall indices sorted counter-clockwise by the angle of their extent midpoint around the mean midpoint. */
    private fun sortAroundCenter(walls: List<Wall>): List<Int> {
        val center = walls.fold(Vec2.ZERO) { acc, w -> acc + w.midpoint } / walls.size.toDouble()
        return walls.indices.sortedWith(compareBy<Int>({ (walls[it].midpoint - center).angle() }, { it }))
    }

    /**
     * True when the outline visits the walls in capture order (or exactly reversed: a clockwise capture),
     * cyclically, judged by each wall's earliest capture; synthesized edges are ignored.
     */
    private fun followsCaptureOrder(edges: List<OutlineEdge>): Boolean {
        val seq = edges.mapNotNull { e -> e.wall?.takeIf { it.source >= 0 }?.first }
        if (seq.size <= 2) return true
        var descents = 0
        var ascents = 0
        for (k in seq.indices) {
            val a = seq[k]
            val b = seq[(k + 1) % seq.size]
            if (b < a) descents++ else if (b > a) ascents++
        }
        return descents <= 1 || ascents <= 1
    }

    // ---------------------------------------------------------------------------------------------
    // Corners mode

    private fun dropDuplicateTaps(edges: MutableList<OutlineEdge>) {
        var k = 1
        while (k < edges.size) {
            if (edges[k].start.distanceTo(edges[k - 1].start) < DUPLICATE_TAP_DISTANCE) absorbVertex(edges, k) else k++
        }
        // A last tap back onto the first corner just closes the room.
        while (edges.size > 1 && edges.last().start.distanceTo(edges.first().start) < DUPLICATE_TAP_DISTANCE) {
            absorbVertex(edges, edges.lastIndex)
        }
    }

    private fun edgeAngles(edges: List<OutlineEdge>): List<Double> =
        edges.indices.map { (edges[(it + 1) % edges.size].start - edges[it].start).angle() }

    private fun edgeLengths(edges: List<OutlineEdge>): List<Double> =
        edges.indices.map { edges[(it + 1) % edges.size].start.distanceTo(edges[it].start) }

    /**
     * Snap near-axis edges and move each corner to the intersection of its two edge lines. Consecutive
     * edges snapped onto the same axis are merged first (their shared vertex was only a slight bend).
     */
    private fun squareCornerOutline(input: List<OutlineEdge>, snapper: Snapper): List<OutlineEdge>? {
        val edges = input.toMutableList()
        while (true) {
            val m = edges.size
            if (m < 3) return null
            val snaps = edgeAngles(edges).map { snapper.snap(it) }
            val k = (0 until m).firstOrNull { i ->
                val before = snaps[(i - 1 + m) % m]
                val after = snaps[i]
                before != null && after != null && before.axis == after.axis
            } ?: break
            absorbVertex(edges, k)
        }
        val m = edges.size
        val snaps = edgeAngles(edges).map { snapper.snap(it) }
        val lines = (0 until m).map { i ->
            val a = edges[i].start
            val b = edges[(i + 1) % m].start
            val s = snaps[i]
            if (s != null) Line2(Vec2.lerp(a, b, 0.5), s.direction) else Line2.through(a, b)
        }
        return (0 until m).map { k ->
            val before = lines[(k - 1 + m) % m]
            val after = lines[k]
            val hit = if (before.sinAngleTo(after) >= MIN_CORNER_SIN) before.intersect(after) else null
            edges[k].copy(start = hit?.takeIf { it.isFinite() } ?: edges[k].start)
        }
    }

    private fun finishCornerOutline(edges: List<OutlineEdge>): List<OutlineEdge>? {
        val ccw = normalizeCcw(edges) ?: return null
        val cleaned = cleanUp(ccw.toMutableList(), useWallLines = false)
        return if (isValidOutline(cleaned)) startAtEarliestCapture(cleaned) else null
    }

    private fun cornerResult(edges: List<OutlineEdge>, dominant: Double, extra: List<String>) = SolveResult(
        corners = edges.map { it.start },
        edgeSources = edges.map { it.source },
        dominantAngle = dominant,
        warnings = (extra + shapeWarnings(edges, wallsMode = false)).distinct(),
    )

    // ---------------------------------------------------------------------------------------------
    // Shared outline clean-up and checks

    private fun normalizeCcw(edges: List<OutlineEdge>): List<OutlineEdge>? {
        val corners = edges.map { it.start }
        val area = Polygons.signedArea(corners)
        if (!area.isFinite() || abs(area) < 1e-12) return null
        if (area > 0) return edges
        val (rc, re) = reverseOutline(corners, edges)
        return re.mapIndexed { k, e -> e.copy(start = rc[k]) }
    }

    /**
     * Rotate the outline (corners and edges together) so it starts with the edge holding the earliest
     * capture (walls mode: earliest member of a merged wall; corners mode: lowest tap index).
     */
    private fun startAtEarliestCapture(edges: List<OutlineEdge>): List<OutlineEdge> {
        fun key(e: OutlineEdge): Int = e.wall?.first ?: e.source
        val first = edges.indices.filter { edges[it].source >= 0 }.minByOrNull { key(edges[it]) } ?: return edges
        return if (first == 0) edges else edges.drop(first) + edges.take(first)
    }

    private fun isValidOutline(edges: List<OutlineEdge>): Boolean {
        if (edges.size < 3) return false
        val corners = edges.map { it.start }
        return Polygons.isSimple(corners) && Polygons.signedArea(corners) > 0
    }

    private fun cleanUp(edges: MutableList<OutlineEdge>, useWallLines: Boolean): List<OutlineEdge> {
        removeShortEdges(edges, useWallLines)
        removeCollinear(edges)
        return edges
    }

    /**
     * Collapse edges shorter than [MIN_EDGE_LENGTH], shortest first. The two end vertices become one:
     * the intersection of the neighbouring wall lines when it is well defined and close, else the midpoint.
     */
    private fun removeShortEdges(edges: MutableList<OutlineEdge>, useWallLines: Boolean) {
        while (edges.size > 3) {
            val m = edges.size
            var idx = -1
            var shortest = MIN_EDGE_LENGTH
            for (i in 0 until m) {
                val len = edges[i].start.distanceTo(edges[(i + 1) % m].start)
                if (len < shortest) {
                    shortest = len
                    idx = i
                }
            }
            if (idx < 0) return
            val prev = edges[(idx - 1 + m) % m]
            val nextIdx = (idx + 1) % m
            val next = edges[nextIdx]
            val mid = Vec2.lerp(edges[idx].start, next.start, 0.5)
            var v = mid
            val prevLine = prev.wall?.line
            val nextLine = next.wall?.line
            if (useWallLines && prevLine != null && nextLine != null) {
                val hit = prevLine.intersect(nextLine, minSin = 0.05)
                if (hit != null && hit.isFinite() && hit.distanceTo(mid) <= MAX_COLLAPSE_SHIFT) v = hit
            }
            edges[nextIdx] = next.copy(start = v)
            edges.removeAt(idx)
        }
    }

    /** Drop vertices within [COLLINEAR_TOLERANCE] of the chord between their neighbours (not spikes). */
    private fun removeCollinear(edges: MutableList<OutlineEdge>) {
        var changed = true
        while (changed && edges.size > 3) {
            changed = false
            val m = edges.size
            for (k in 0 until m) {
                val prev = edges[(k - 1 + m) % m].start
                val next = edges[(k + 1) % m].start
                if (Segment2(prev, next).distanceTo(edges[k].start) < COLLINEAR_TOLERANCE) {
                    absorbVertex(edges, k)
                    changed = true
                    break
                }
            }
        }
    }

    /** Remove vertex [k]: edge k-1 absorbs edge k and keeps the identity of the heavier of the two. */
    private fun absorbVertex(edges: MutableList<OutlineEdge>, k: Int) {
        val m = edges.size
        val p = (k - 1 + m) % m
        val prev = edges[p]
        val cur = edges[k]
        val keep = if (cur.weight > prev.weight) cur else prev
        edges[p] = keep.copy(start = prev.start)
        edges.removeAt(k)
    }

    /**
     * Corners between two measured walls that meet at a shallow angle (a bend of at most
     * [SHALLOW_CORNER_DEG]): an offset error of either wall moves the corner by that error / sin(angle).
     */
    private fun shallowCornerWarnings(edges: List<OutlineEdge>): List<String> {
        val m = edges.size
        val limit = sin(Math.toRadians(SHALLOW_CORNER_DEG))
        val out = mutableListOf<String>()
        for (k in 0 until m) {
            val before = edges[(k - 1 + m) % m].wall ?: continue
            val after = edges[k].wall ?: continue
            if (before.source < 0 || after.source < 0) continue
            val sinAngle = before.line.sinAngleTo(after.line)
            val sameWay = (before.line.direction dot after.line.direction) > 0
            if (sameWay && sinAngle <= limit) {
                out += "Walls ${before.source + 1} and ${after.source + 1} meet at a shallow angle; " +
                    "the corner position is uncertain"
            }
        }
        return out
    }

    /** Spikes (very sharp interior angles) and implausibly small rooms. */
    private fun shapeWarnings(edges: List<OutlineEdge>, wallsMode: Boolean): List<String> {
        val corners = edges.map { it.start }
        val m = corners.size
        val out = mutableListOf<String>()
        val angles = Polygons.interiorAngles(corners)
        val low = Math.toRadians(SPIKE_ANGLE_DEG)
        val high = 2 * PI - low
        for (k in angles.indices) {
            if (angles[k] < low || angles[k] > high) {
                val hint = if (wallsMode) wallsHint(edges[(k - 1 + m) % m].source, edges[k].source) else "; check the corner taps"
                out += "Corner ${k + 1} forms a very sharp spike$hint"
            }
        }
        val area = Polygons.area(corners)
        if (area < MIN_ROOM_AREA) {
            out += "The room is very small (${areaText(area)}); check that every ${if (wallsMode) "wall" else "corner"} was captured"
        }
        return out
    }

    private fun wallsHint(a: Int, b: Int): String {
        val numbers = listOf(a, b).filter { it >= 0 }.distinct().map { it + 1 }
        return when (numbers.size) {
            2 -> "; check walls ${numbers[0]} and ${numbers[1]}"
            1 -> "; check wall ${numbers[0]}"
            else -> ""
        }
    }

    private fun distanceText(meters: Double) =
        "${Units.formatLength(meters, UnitSystem.METRIC)} (${Units.formatLength(meters, UnitSystem.IMPERIAL, 1)})"

    private fun areaText(squareMeters: Double) =
        "${Units.formatArea(squareMeters, UnitSystem.METRIC)} / ${Units.formatArea(squareMeters, UnitSystem.IMPERIAL)}"
}
