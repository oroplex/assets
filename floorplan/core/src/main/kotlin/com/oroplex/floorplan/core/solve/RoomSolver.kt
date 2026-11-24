package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.model.WallObservation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

    /** A corner farther than this beyond the observed extents of BOTH adjacent walls gets a warning. */
    internal const val FAR_CORNER_DISTANCE = 1.5

    /** Interior angles below this, or above 360deg minus this, are reported as spikes. */
    internal const val SPIKE_ANGLE_DEG = 30.0

    /** Rooms with a smaller area (m²) get a warning. */
    internal const val MIN_ROOM_AREA = 0.5

    internal const val REORDERED_WARNING = "Walls were re-ordered"

    internal const val UNSQUARED_WARNING = "Corners were not squared because that made the outline cross itself"

    /** Floor for observation confidence so a zero-confidence wall still has a usable (tiny) weight. */
    private const val MIN_CONFIDENCE = 0.01

    /** Observed extents shorter than this still weigh as if they were this long (meters). */
    private const val MIN_WEIGHT_LENGTH = 0.1

    /**
     * Snapped corner-tap edges meeting at |sin| below this (~14.5deg) are too close to parallel for a stable
     * intersection; the corner then keeps its tapped position.
     */
    private const val MIN_CORNER_SIN = 0.25

    /** When collapsing a short edge, the neighbours' intersection is used only if it is this close (m). */
    private const val MAX_COLLAPSE_SHIFT = 0.25

    /**
     * Build a closed room outline from walls captured IN ORDER around the room (either direction).
     * Steps: (1) optional squaring — rotate each near-axis wall about its observed midpoint onto the
     * dominant axis; (2) merge/jog handling for consecutive near-parallel walls; (3) corner i = intersection
     * of walls i and i+1 (wrapping); (4) orientation normalized to CCW; (5) sanity checks -> warnings.
     * If the result is self-intersecting, retries with walls sorted by angle around the mean of their
     * observed extent midpoints.
     * Returns null when fewer than 3 usable walls remain or no valid polygon can be formed.
     *
     * Details:
     * * Each observation's line is re-oriented so its viewer is on the left (interior-left convention)
     *   even if the stored direction is inconsistent. Observations with non-finite or degenerate data
     *   are ignored (their index never appears in [SolveResult.edgeSources]).
     * * Wall weight = confidence * max(observed extent length, 0.1 m).
     * * Consecutive near-parallel walls: same facing and faces within [SolverOptions.sameWallOffset] are
     *   merged (weighted line, union of extents; the heavier wall's index is the edge source); same facing
     *   but farther apart is a jog, bridged by a perpendicular connector halfway between the observed
     *   extents; opposite facing means a wall between them was skipped, so a connector is added at the
     *   far end of their extents (with a warning).
     * * Corners are normalized to CCW (a clockwise capture is reversed with [reverseOutline]) and the
     *   outline is rotated to start with the earliest captured wall, so edge 0 is normally observation 0.
     * * If the capture order cannot produce a simple polygon, or the result has problems (lost walls,
     *   "missing wall" connectors, corners far beyond what was measured) that ordering the walls by angle
     *   around their mean midpoint avoids, the angular order is used and [REORDERED_WARNING] is reported.
     * * Edges shorter than 5 cm are collapsed and collinear vertices removed before validation.
     */
    fun solveWalls(observations: List<WallObservation>, options: SolverOptions = SolverOptions()): SolveResult? {
        if (observations.size < 3) return null
        val raw = observations.mapIndexedNotNull { i, o -> wallFromObservation(o, i) }
        if (raw.size < 3) return null

        val cfg = Config.of(options)
        val dominant = dominantAngle(raw.map { it.line.angle() }, raw.map { it.weight })
        val snapper = if (options.squareAngles) Snapper(dominant, cfg.toleranceRad) else null
        val walls = if (snapper != null) raw.map { snapper.snapWall(it) } else raw

        val inOrder = attemptWalls(walls, cfg, snapper)
        var reordered = false
        val chosen = if (inOrder != null && inOrder.penalty == 0) {
            inOrder
        } else {
            val sorted = attemptWalls(sortAroundCenter(walls), cfg, snapper)
            when {
                sorted != null && (inOrder == null || sorted.penalty < inOrder.penalty) -> {
                    reordered = true
                    sorted
                }
                inOrder != null -> inOrder
                else -> return null
            }
        }

        val warnings = buildList {
            if (reordered) add(REORDERED_WARNING)
            addAll(chosen.warnings)
            addAll(chosen.farCornerWarnings)
            addAll(shapeWarnings(chosen.edges, wallsMode = true))
        }.distinct()
        return SolveResult(
            corners = chosen.edges.map { it.start },
            edgeSources = chosen.edges.map { it.source },
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
     * * Squaring: the dominant direction comes from the edge directions weighted by length; every edge
     *   within tolerance becomes a line through its midpoint at the exact axis angle (others keep the
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
        val dominant = dominantAngle(edgeAngles(edges), edgeLengths(edges))
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
        var r = atan2(s, c) / 4
        if (r < 0) r += PI / 2
        // Values a hair below 90deg (or above 0) are the same axis as 0; keep the result canonical.
        if (r >= PI / 2 - 1e-12 || r < 1e-12) r = 0.0
        return r
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
     * (start has the smaller parameter). [source] is the observation index, -1 for synthesized walls.
     */
    private data class Wall(val line: Line2, val start: Vec2, val end: Vec2, val weight: Double, val source: Int) {
        val midpoint: Vec2 get() = Vec2.lerp(start, end, 0.5)

        /** How far [p] lies outside the observed extent, measured along the wall (0 when within it). */
        fun beyondExtent(p: Vec2): Double {
            val t = line.param(p)
            return max(0.0, max(line.param(start) - t, t - line.param(end)))
        }

        /** Same wall on a different line: the extent is re-projected onto [newLine]. */
        fun withLine(newLine: Line2, points: List<Vec2> = listOf(start, end)): Wall {
            val sorted = points.map { newLine.project(it) }.sortedBy { newLine.param(it) }
            return copy(line = newLine, start = sorted.first(), end = sorted.last())
        }
    }

    /**
     * Edge of a closed outline: it starts at [start] and ends at the next edge's start.
     * [wall] is the wall it lies on (walls mode only); [weight] decides which identity survives merges.
     */
    private data class OutlineEdge(val start: Vec2, val source: Int, val wall: Wall?, val weight: Double)

    /**
     * One way of ordering the walls. [penalty] scores how suspicious the result is:
     * 2 per lost wall or "missing wall" connector, 1 per corner far beyond the observed extents.
     */
    private class WallAttempt(
        val edges: List<OutlineEdge>,
        val warnings: List<String>,
        val farCornerWarnings: List<String>,
        val penalty: Int,
    )

    private class Connected(val walls: List<Wall>, val warnings: List<String>, val skipped: Int)

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

        /** Rotate [w] about the midpoint of its observed extent onto the nearest axis, if within tolerance. */
        fun snapWall(w: Wall): Wall {
            val s = snap(w.line.angle()) ?: return w
            return w.withLine(Line2(w.midpoint, s.direction))
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
        val weight = confidence * max(s.distanceTo(e), MIN_WEIGHT_LENGTH)
        return Wall(line, s, e, weight, index)
    }

    private fun attemptWalls(walls: List<Wall>, cfg: Config, snapper: Snapper?): WallAttempt? {
        val merged = mergeSameWalls(walls, cfg, snapper)
        if (merged.size < 3) return null
        val connected = insertConnectors(merged, cfg)
        val outline = buildOutline(connected.walls) ?: return null
        val ccw = normalizeCcw(outline) ?: return null
        val cleaned = cleanUp(ccw.toMutableList(), useWallLines = true)
        if (!isValidOutline(cleaned)) return null
        val edges = startAtEarliestSource(cleaned)
        val kept = edges.mapTo(HashSet()) { it.source }
        val lost = merged.count { it.source !in kept }
        val far = farCornerWarnings(edges)
        return WallAttempt(edges, connected.warnings, far, 2 * (connected.skipped + lost) + far.size)
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
        val point = (a.midpoint * a.weight + b.midpoint * b.weight) / total
        val heavier = when {
            a.weight > b.weight -> a
            b.weight > a.weight -> b
            a.source <= b.source -> a
            else -> b
        }
        return heavier.copy(weight = total).withLine(Line2(point, dir), listOf(a.start, a.end, b.start, b.end))
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
        for (i in 0 until n) {
            val a = walls[i]
            val b = walls[(i + 1) % n]
            out += a
            if (!isParallel(a, b, cfg)) continue
            if (sameFacing(a, b)) {
                out += jogConnector(a, b)
            } else {
                out += skippedWallConnector(a, b, sense)
                skipped++
                warnings += "A wall seems to be missing between walls ${a.source + 1} and ${b.source + 1}; " +
                    "it was added where they end"
            }
        }
        return Connected(out, warnings, skipped)
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
        val line = Line2(Vec2.lerp(p1, p2, 0.5), dir)
        return Wall(line, p1, p2, 0.0, -1).withLine(line)
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
        val line = Line2(Vec2.lerp(p1, p2, 0.5), c)
        return Wall(line, p1, p2, 0.0, -1).withLine(line)
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

    /** Sort walls counter-clockwise by the angle of their extent midpoint around the mean midpoint. */
    private fun sortAroundCenter(walls: List<Wall>): List<Wall> {
        val center = walls.fold(Vec2.ZERO) { acc, w -> acc + w.midpoint } / walls.size.toDouble()
        return walls.sortedWith(compareBy<Wall>({ (it.midpoint - center).angle() }, { it.source }))
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
        return if (isValidOutline(cleaned)) startAtEarliestSource(cleaned) else null
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

    /** Rotate the outline (corners and edges together) so it starts with the lowest non-synthesized source. */
    private fun startAtEarliestSource(edges: List<OutlineEdge>): List<OutlineEdge> {
        val first = edges.indices.filter { edges[it].source >= 0 }.minByOrNull { edges[it].source } ?: return edges
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

    /** Corners lying more than [FAR_CORNER_DISTANCE] beyond the observed extents of both adjacent walls. */
    private fun farCornerWarnings(edges: List<OutlineEdge>): List<String> {
        val m = edges.size
        val out = mutableListOf<String>()
        for (k in 0 until m) {
            val before = edges[(k - 1 + m) % m].wall
            val after = edges[k].wall
            if (before == null || after == null || before.source < 0 || after.source < 0) continue
            val corner = edges[k].start
            val beyond = min(before.beyondExtent(corner), after.beyondExtent(corner))
            if (beyond > FAR_CORNER_DISTANCE) {
                out += "Corner ${k + 1} is ${distanceText(beyond)} beyond what was measured; " +
                    "check walls ${before.source + 1} and ${after.source + 1}"
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
