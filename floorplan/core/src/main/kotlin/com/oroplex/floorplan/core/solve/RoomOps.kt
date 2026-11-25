package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.PolygonClipping
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure editing operations on rooms. All return new objects; inputs are never mutated.
 *
 * Invariants kept by every operation: openings reference an existing edge and lie inside it
 * (0 <= offset, offset + width <= edge length). Operations that would turn a valid outline (simple and
 * counter-clockwise) into an invalid one are rejected by returning the room unchanged (same instance),
 * so callers can detect a no-op with `===`.
 */
object RoomOps {
    /** Edits never create walls shorter than this (meters). */
    const val MIN_WALL_LENGTH = 0.05

    /** [placeOpening]: a marked point may be at most this far from the wall it is placed on (meters). */
    const val MAX_OPENING_DISTANCE = 0.5

    /** [placeOpening]: minimum opening width (meters). */
    const val MIN_OPENING_WIDTH = 0.2

    /**
     * [setWallLength]: how much a wall turning by 1 radian (per meter of wall) costs compared with moving a
     * corner by 1 m. High, so keeping every wall's direction always wins over moving fewer corners.
     */
    private const val DIRECTION_WEIGHT = 10.0

    /** [setWallLength]: corner projections closer than this (m) count as the same position along the wall. */
    private const val PROJECTION_TOLERANCE = 1e-6

    /** [setWallLength]: neighbouring walls meeting the edited one at |sin| below this are treated as parallel. */
    private const val MIN_INTERSECT_SIN = 1e-3

    /** Re-mapping openings: extra cost (meters) for an edge perpendicular to the opening's old wall. */
    private const val DIRECTION_PENALTY = 0.25

    /**
     * Move one corner. Openings on the two adjacent edges are clamped to stay inside their edge.
     * Each such opening stays anchored to the edge's OTHER (unmoved) corner, so doors and windows do not
     * slide along the wall while a corner is dragged. Rejected when [index] or [position] is invalid or
     * the move would make a valid outline self-intersecting or inside-out.
     */
    fun moveCorner(room: Room, index: Int, position: Vec2): Room {
        if (index !in room.corners.indices || !position.isFinite()) return room
        val corners = room.corners.toMutableList().also { it[index] = position }
        if (!acceptable(room.corners, corners)) return room
        return room.copy(corners = corners, openings = reanchorOpenings(room.corners, corners, room.openings))
    }

    /**
     * Set the length of edge [wallIndex] (a = corners[wallIndex] -> b = corners[wallIndex+1]) while keeping the
     * rest of the room's shape: walls keep their directions wherever that is geometrically possible, and as
     * few corners as possible move.
     *
     * Candidate edits, of which the one that turns walls least (then moves corners least) wins:
     * 1. **Stretch** along the edge: pick a cut position between a and b along the edge direction and move
     *    every corner beyond it by the length change. Walls entirely on one side keep their shape; the walls
     *    crossing the cut (for rectangles and L-shapes: the opposite wall) only change length when they run
     *    parallel to the edge. Every cut between consecutive corner positions is tried, so a far wall that is
     *    not exactly perpendicular (after a corner drag, an 88.5° capture, a trapezoid) keeps its shape and a
     *    rectangle stays exactly rectangular.
     * 2. **Slide** the edge sideways (along its normal) with both ends running along their neighbouring walls,
     *    e.g. a 45° chamfer or bay wall between two square walls grows or shrinks in place.
     * 3. As a last resort, slide b (or a) along its other wall, turning only the edited wall.
     *
     * Rejected (room returned unchanged, same instance): lengths <= 5 cm or non-finite, invalid indices,
     * degenerate edges, rooms with non-finite corners, and edits that would make a valid outline
     * self-intersecting or create a wall shorter than [MIN_WALL_LENGTH]. Openings stay anchored to an
     * unmoved corner of their edge (see [moveCorner]).
     */
    fun setWallLength(room: Room, wallIndex: Int, newLength: Double): Room {
        val c = room.corners
        val n = c.size
        if (n < 3 || wallIndex !in c.indices || !newLength.isFinite() || newLength <= MIN_WALL_LENGTH) return room
        if (c.any { !it.isFinite() }) return room
        val a = c[wallIndex]
        val b = c[(wallIndex + 1) % n]
        val oldLength = a.distanceTo(b)
        if (!oldLength.isFinite() || oldLength < 0.01) return room
        if (abs(newLength - oldLength) < 1e-12) return room
        val candidates = stretchCandidates(c, wallIndex, newLength) +
            listOfNotNull(
                offsetCandidate(c, wallIndex, newLength),
                slideCandidate(c, wallIndex, newLength, moveEnd = true),
                slideCandidate(c, wallIndex, newLength, moveEnd = false),
            )
        var best: List<Vec2>? = null
        var bestCost = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            if (!isAcceptableLengthEdit(c, candidate, wallIndex, newLength)) continue
            val cost = editCost(c, candidate)
            if (cost < bestCost - 1e-9) {
                best = candidate
                bestCost = cost
            }
        }
        val corners = best ?: return room
        return room.copy(corners = corners, openings = reanchorOpenings(c, corners, room.openings))
    }

    /**
     * Split edge [wallIndex] at the projection of [at]; openings re-assigned to the correct sub-edge.
     * The new corner becomes corners[wallIndex + 1]; later edges shift up by one. An opening on the split
     * edge goes to the sub-edge containing its midpoint and is clamped (shrunk only if wider than it).
     * Rejected when either sub-edge would be shorter than [MIN_WALL_LENGTH].
     */
    fun insertCorner(room: Room, wallIndex: Int, at: Vec2): Room {
        val c = room.corners
        val n = c.size
        if (n < 3 || wallIndex !in c.indices || !at.isFinite()) return room
        val a = c[wallIndex]
        val b = c[(wallIndex + 1) % n]
        val length = a.distanceTo(b)
        if (length < 2 * MIN_WALL_LENGTH) return room
        val dir = (b - a) / length
        val t = ((at - a) dot dir).coerceIn(0.0, length)
        if (t < MIN_WALL_LENGTH || length - t < MIN_WALL_LENGTH) return room
        val corners = c.toMutableList().also { it.add(wallIndex + 1, a + dir * t) }
        val openings = room.openings.mapNotNull { o ->
            when {
                o.wallIndex !in c.indices -> null
                o.wallIndex < wallIndex -> o
                o.wallIndex > wallIndex -> o.copy(wallIndex = o.wallIndex + 1)
                o.offset + o.width / 2 < t -> clampToEdge(o, t)
                else -> clampToEdge(o.copy(wallIndex = wallIndex + 1, offset = o.offset - t), length - t)
            }
        }
        return room.copy(corners = corners, openings = openings)
    }

    /**
     * Remove a corner (min 3 remain). Openings on removed edges are re-projected onto the merged edge.
     * The merged edge runs corners[index-1] -> corners[index+1]; edges after [index] shift down by one.
     * Each affected opening keeps its absolute midpoint (projected onto the merged edge), then is clamped.
     * Rejected when the result would make a valid outline self-intersecting or inside-out.
     */
    fun deleteCorner(room: Room, index: Int): Room {
        val c = room.corners
        val n = c.size
        if (n <= 3 || index !in c.indices) return room
        val corners = c.toMutableList().also { it.removeAt(index) }
        if (!acceptable(c, corners)) return room
        val merged = if (index == 0) n - 2 else index - 1
        val before = (index - 1 + n) % n
        val openings = room.openings.mapNotNull { o ->
            when (o.wallIndex) {
                !in c.indices -> null
                before, index -> projectOntoEdge(c, o, corners, merged)
                else -> o.copy(wallIndex = if (o.wallIndex > index) o.wallIndex - 1 else o.wallIndex)
            }
        }
        return room.copy(corners = corners, openings = openings)
    }

    /** Rigidly move/rotate the room: corners, observations (lines, extents, viewer), taps and hand-set walls. */
    fun transform(room: Room, t: RigidTransform2): Room {
        if (!t.angle.isFinite() || !t.translation.isFinite()) return room
        return room.copy(
            corners = room.corners.map { t.apply(it) },
            cornerTaps = room.cornerTaps.map { t.apply(it) },
            handSetWalls = room.handSetWalls.map { it.copy(segment = Segment2(t.apply(it.segment.a), t.apply(it.segment.b))) },
            observations = room.observations.map { o ->
                o.copy(
                    line = t.apply(o.line),
                    extentStart = t.apply(o.extentStart),
                    extentEnd = t.apply(o.extentEnd),
                    viewer = t.apply(o.viewer),
                )
            },
        )
    }

    /**
     * Uniform scale about [origin]: corners, observations, taps, opening offsets/widths/heights, ceiling.
     * Observation residuals are scaled too; line directions are unchanged. Returns the room unchanged for
     * a non-positive or non-finite [factor] (a negative factor would mirror the outline).
     */
    fun scale(room: Room, factor: Double, origin: Vec2): Room {
        if (!factor.isFinite() || factor <= 0.0 || !origin.isFinite()) return room
        fun s(p: Vec2) = origin + (p - origin) * factor
        return room.copy(
            corners = room.corners.map(::s),
            cornerTaps = room.cornerTaps.map(::s),
            handSetWalls = room.handSetWalls.map {
                it.copy(segment = Segment2(s(it.segment.a), s(it.segment.b)), typedLength = it.typedLength?.let { l -> l * factor })
            },
            ceilingHeight = room.ceilingHeight?.let { it * factor },
            openings = room.openings.map {
                it.copy(
                    offset = it.offset * factor,
                    width = it.width * factor,
                    height = it.height * factor,
                    sillHeight = it.sillHeight * factor,
                )
            },
            observations = room.observations.map { o ->
                o.copy(
                    line = Line2(s(o.line.point), o.line.direction),
                    extentStart = s(o.extentStart),
                    extentEnd = s(o.extentEnd),
                    viewer = s(o.viewer),
                    rmsResidual = o.rmsResidual * factor,
                )
            },
        )
    }

    /**
     * Re-run the solver from the raw observations (WALLS) or taps (CORNERS) with [options].
     * Openings are re-mapped to the nearest new edge by their world position. MANUAL rooms only get squared.
     *
     * A WALLS/CORNERS room without enough raw data is treated like a MANUAL room. Openings keep their
     * absolute midpoint; the nearest edge wins, with a small penalty for edges at an angle to the
     * opening's old wall so a door near a corner does not jump onto the perpendicular wall. The room's
     * warnings are replaced by the solver's. When nothing can be solved (or a MANUAL room is resolved
     * without squaring) the room is returned unchanged (same instance).
     */
    fun resolve(room: Room, options: SolverOptions): Room {
        val squareManual = { if (options.squareAngles) RoomSolver.solveCorners(room.corners, options) else null }
        val result = when (room.captureMethod) {
            CaptureMethod.WALLS ->
                if (room.observations.size >= 3) RoomSolver.solveWalls(room.observations, options) else squareManual()
            CaptureMethod.CORNERS ->
                if (room.cornerTaps.size >= 3) RoomSolver.solveCorners(room.cornerTaps, options) else squareManual()
            CaptureMethod.MANUAL -> squareManual()
        } ?: return room
        val openings = room.openings.mapNotNull { remapToNearestEdge(room.corners, it, result.corners) }
        return room.copy(corners = result.corners, openings = openings, warnings = result.warnings)
    }

    /**
     * Create an opening from two plan points marking its two side edges (e.g. door jambs at the floor).
     * Both points are projected onto the nearest edge of the room; returns null if they are > 0.5 m from any
     * edge or closer than 0.2 m to each other. The opening is clamped within the edge.
     *
     * "Nearest edge" minimizes the summed distance of both points to the edge segment. The width is the
     * distance between the two projections (after clamping onto the edge), so it is also null when that
     * is below [MIN_OPENING_WIDTH]. Also null for invalid input (non-finite points, height <= 0, a room
     * with fewer than 3 corners). Negative sill heights are treated as 0.
     */
    fun placeOpening(
        room: Room,
        a: Vec2,
        b: Vec2,
        type: OpeningType,
        height: Double,
        sillHeight: Double,
        id: String,
    ): Opening? {
        val c = room.corners
        if (c.size < 3 || !a.isFinite() || !b.isFinite() || !height.isFinite() || height <= 0.0) return null
        if (a.distanceTo(b) < MIN_OPENING_WIDTH) return null
        val edges = Polygons.edges(c)
        val wallIndex = edges.indices
            .filter { edges[it].length > 1e-9 }
            .minByOrNull { edges[it].distanceTo(a) + edges[it].distanceTo(b) } ?: return null
        val edge = edges[wallIndex]
        if (edge.distanceTo(a) > MAX_OPENING_DISTANCE || edge.distanceTo(b) > MAX_OPENING_DISTANCE) return null
        val length = edge.length
        val dir = edge.direction
        val ta = ((a - edge.a) dot dir).coerceIn(0.0, length)
        val tb = ((b - edge.a) dot dir).coerceIn(0.0, length)
        val width = abs(ta - tb)
        if (width < MIN_OPENING_WIDTH) return null
        val sill = if (sillHeight.isFinite()) sillHeight.coerceAtLeast(0.0) else 0.0
        val opening = Opening(
            id = id,
            type = type,
            wallIndex = wallIndex,
            offset = minOf(ta, tb),
            width = width,
            height = height,
            sillHeight = sill,
        )
        return clampToEdge(opening, length)
    }

    /**
     * Plan-space segment (start, end) of an opening along its wall edge:
     * start = c[i] + dir * offset, end = start + dir * width, with dir the unit direction of edge i.
     * Defensive for bad data: the segment is clamped onto the edge, an unknown edge index yields a
     * zero-length segment at the first corner, and a room without corners yields (0,0)-(0,0).
     */
    fun openingSegment(room: Room, opening: Opening): Pair<Vec2, Vec2> {
        val c = room.corners
        if (c.isEmpty()) return Vec2.ZERO to Vec2.ZERO
        if (opening.wallIndex !in c.indices || c.size < 2) return c[0] to c[0]
        return segmentOnEdge(c, opening)
    }

    // ---------------------------------------------------------------------------------------------

    private fun edgeLength(c: List<Vec2>, i: Int): Double = c[i].distanceTo(c[(i + 1) % c.size])

    /**
     * [setWallLength] strategy 1: one candidate per cut position between a (projection 0) and b (projection
     * = old length) along the edge direction; corners projecting beyond the cut move by the length change.
     */
    private fun stretchCandidates(c: List<Vec2>, i: Int, newLength: Double): List<List<Vec2>> {
        val a = c[i]
        val b = c[(i + 1) % c.size]
        val oldLength = a.distanceTo(b)
        val dir = (b - a) / oldLength
        val shift = dir * (newLength - oldLength)
        val proj = c.map { (it - a) dot dir }
        val stops = ArrayList<Double>()
        stops += 0.0
        for (p in proj.filter { it > PROJECTION_TOLERANCE && it < oldLength - PROJECTION_TOLERANCE }.sorted()) {
            if (p - stops.last() > PROJECTION_TOLERANCE) stops += p
        }
        if (oldLength - stops.last() > PROJECTION_TOLERANCE) stops += oldLength
        return (0 until stops.size - 1).map { k ->
            val cut = (stops[k] + stops[k + 1]) / 2
            c.mapIndexed { m, p -> if (proj[m] > cut) p + shift else p }
        }
    }

    /**
     * [setWallLength] strategy 2: move the edge along its normal, its two ends sliding along the neighbouring
     * walls' lines, by the offset that gives [newLength] (the length changes linearly with the offset).
     * Null when a neighbour is (nearly) parallel or perpendicular to the edge, i.e. sliding cannot change
     * the length in a stable way.
     */
    private fun offsetCandidate(c: List<Vec2>, i: Int, newLength: Double): List<Vec2>? {
        val n = c.size
        val j = (i + 1) % n
        val a = c[i]
        val b = c[j]
        val prev = c[(i - 1 + n) % n]
        val next = c[(j + 1) % n]
        if (prev.distanceTo(a) < 1e-9 || next.distanceTo(b) < 1e-9) return null
        val edgeLine = Line2.through(a, b)
        val prevLine = Line2.through(prev, a)
        val nextLine = Line2.through(b, next)
        fun ends(d: Double): Pair<Vec2, Vec2>? {
            val moved = edgeLine.offset(d)
            val na = moved.intersect(prevLine, MIN_INTERSECT_SIN) ?: return null
            val nb = moved.intersect(nextLine, MIN_INTERSECT_SIN) ?: return null
            return na to nb
        }
        val oldLength = a.distanceTo(b)
        val (a1, b1) = ends(1.0) ?: return null
        val slope = ((b1 - a1) dot edgeLine.direction) - oldLength
        if (!slope.isFinite() || abs(slope) < 1e-3) return null
        val (na, nb) = ends((newLength - oldLength) / slope) ?: return null
        return c.toMutableList().also {
            it[i] = na
            it[j] = nb
        }
    }

    /**
     * [setWallLength] strategy 3: slide b (when [moveEnd]) along the wall after the edge, or a along the wall
     * before it, until the edge measures [newLength]; the edited wall turns, its neighbour keeps its direction.
     */
    private fun slideCandidate(c: List<Vec2>, i: Int, newLength: Double, moveEnd: Boolean): List<Vec2>? {
        val n = c.size
        val j = (i + 1) % n
        val anchor = if (moveEnd) c[i] else c[j]
        val moving = if (moveEnd) j else i
        val toward = if (moveEnd) c[(j + 1) % n] else c[(i - 1 + n) % n]
        val u = (toward - c[moving]).normalized()
        if (u == Vec2.ZERO) return null
        val w = c[moving] - anchor
        val half = w dot u
        val disc = half * half - (w.lengthSquared - newLength * newLength)
        if (!disc.isFinite() || disc < 0.0) return null
        val root = sqrt(disc)
        val s = listOf(-half + root, -half - root).minBy { abs(it) }
        return c.toMutableList().also { it[moving] = c[moving] + u * s }
    }

    /** A [setWallLength] candidate is usable when it is valid, exact, and creates no new too-short wall. */
    private fun isAcceptableLengthEdit(before: List<Vec2>, after: List<Vec2>, i: Int, newLength: Double): Boolean {
        if (after.any { !it.isFinite() }) return false
        if (abs(edgeLength(after, i) - newLength) > 1e-6 * max(1.0, newLength)) return false
        for (k in after.indices) {
            if (k == i) continue
            val l = edgeLength(after, k)
            if (l < MIN_WALL_LENGTH && l < edgeLength(before, k) - 1e-12) return false
        }
        return acceptable(before, after)
    }

    /**
     * How much an edit changes a room's shape: [DIRECTION_WEIGHT] x (angle each wall turns x its length)
     * plus the distance every corner moves (meters).
     */
    private fun editCost(before: List<Vec2>, after: List<Vec2>): Double {
        var cost = 0.0
        for (k in before.indices) {
            val d0 = before[(k + 1) % before.size] - before[k]
            val d1 = after[(k + 1) % after.size] - after[k]
            if (d0.length > 1e-9 && d1.length > 1e-9) {
                val turn = abs(Polygons.normalizeAngle(d1.angle() - d0.angle()))
                cost += DIRECTION_WEIGHT * turn * max(d0.length, d1.length)
            }
            cost += before[k].distanceTo(after[k])
        }
        return cost
    }

    private fun isValidOutline(c: List<Vec2>): Boolean =
        c.size >= 3 && c.all { it.isFinite() } && Polygons.isSimple(c) && Polygons.signedArea(c) > 0

    /** An edit is acceptable unless it breaks an outline that was valid before. */
    private fun acceptable(before: List<Vec2>, after: List<Vec2>): Boolean = !isValidOutline(before) || isValidOutline(after)

    /** Clamp an opening into an edge of length [edgeLength]: shrink if too wide, then slide inside. */
    internal fun clampToEdge(o: Opening, edgeLength: Double): Opening {
        val length = if (edgeLength.isFinite()) max(edgeLength, 0.0) else 0.0
        val width = (if (o.width.isFinite()) o.width else 0.0).coerceIn(0.0, length)
        val offset = (if (o.offset.isFinite()) o.offset else 0.0).coerceIn(0.0, length - width)
        return if (width == o.width && offset == o.offset) o else o.copy(width = width, offset = offset)
    }

    /** Segment of [o] on edge o.wallIndex of [c] (index must be valid), clamped onto the edge. */
    private fun segmentOnEdge(c: List<Vec2>, o: Opening): Pair<Vec2, Vec2> {
        val i = o.wallIndex
        val a = c[i]
        val b = c[(i + 1) % c.size]
        val length = a.distanceTo(b)
        if (length < 1e-12) return a to a
        val dir = (b - a) / length
        val t0 = (if (o.offset.isFinite()) o.offset else 0.0).coerceIn(0.0, length)
        val t1 = (t0 + (if (o.width.isFinite()) o.width else 0.0)).coerceIn(t0, length)
        return (a + dir * t0) to (a + dir * t1)
    }

    /**
     * After an edit that keeps the corner count and edge indices, keep each opening anchored to an unmoved
     * end of its edge: offset is kept when the edge's start corner stayed put (or both ends moved); when
     * only the start moved, the distance from the end corner is kept instead. Then clamp.
     */
    private fun reanchorOpenings(before: List<Vec2>, after: List<Vec2>, openings: List<Opening>): List<Opening> {
        val n = after.size
        return openings.mapNotNull { o ->
            val i = o.wallIndex
            if (i !in after.indices) return@mapNotNull null
            val j = (i + 1) % n
            val oldLength = edgeLength(before, i)
            val newLength = edgeLength(after, i)
            val startMoved = before[i] != after[i]
            val endMoved = before[j] != after[j]
            val offset = if (startMoved && !endMoved) newLength - (oldLength - o.offset) else o.offset
            clampToEdge(o.copy(offset = offset), newLength)
        }
    }

    /** Re-project [o] (on [oldCorners]) onto edge [edge] of [newCorners] by its absolute midpoint, then clamp. */
    private fun projectOntoEdge(oldCorners: List<Vec2>, o: Opening, newCorners: List<Vec2>, edge: Int): Opening? {
        val (s, e) = segmentOnEdge(oldCorners, o)
        val mid = Vec2.lerp(s, e, 0.5)
        val a = newCorners[edge]
        val b = newCorners[(edge + 1) % newCorners.size]
        val length = a.distanceTo(b)
        if (!length.isFinite() || length < 1e-9) return null
        val t = ((mid - a) dot ((b - a) / length)).coerceIn(0.0, length)
        return clampToEdge(o.copy(wallIndex = edge, offset = t - o.width / 2), length)
    }

    /**
     * Re-map [o] onto whichever edge of [newCorners] is nearest to its absolute midpoint (plus
     * [DIRECTION_PENALTY] * (1 - |cos|) for edges at an angle to its old wall). Null only when the opening
     * has no valid old edge or the new outline has no usable edge.
     */
    private fun remapToNearestEdge(oldCorners: List<Vec2>, o: Opening, newCorners: List<Vec2>): Opening? {
        if (o.wallIndex !in oldCorners.indices || oldCorners.size < 2 || newCorners.size < 2) return null
        val (s, e) = segmentOnEdge(oldCorners, o)
        val mid = Vec2.lerp(s, e, 0.5)
        val oldDir = (oldCorners[(o.wallIndex + 1) % oldCorners.size] - oldCorners[o.wallIndex]).normalized()
        val edges = Polygons.edges(newCorners)
        val best = edges.indices
            .filter { edges[it].length > 1e-9 }
            .minByOrNull { k ->
                val seg: Segment2 = edges[k]
                seg.distanceTo(mid) + DIRECTION_PENALTY * (1 - abs(seg.direction dot oldDir))
            } ?: return null
        return projectOntoEdge(oldCorners, o, newCorners, best)
    }
}

object FloorOps {
    /** Calibration factors outside this range are rejected as typos / wrong units. */
    const val MIN_CALIBRATION_FACTOR = 0.5
    const val MAX_CALIBRATION_FACTOR = 2.0

    /** [snapRoom]: a snapped room may overlap any other room by at most this much more than before (m²). */
    private const val SNAP_OVERLAP_TOLERANCE = 0.01

    /** [snapRoom]: a pose change smaller than this (radians / meters) means the room is already in place. */
    private const val SNAP_IDENTITY_ANGLE = 1e-9
    private const val SNAP_IDENTITY_SHIFT = 1e-6

    /**
     * Tape calibration: scale the floor's AR-measured rooms so that edge [wallIndex] of room [roomId] measures
     * [trueLength]. AR scale error is the only thing being corrected, so only AR measurements change:
     *
     * * Only scanned rooms ([CaptureMethod.WALLS] / [CaptureMethod.CORNERS]) are scaled; rooms drawn by hand
     *   ([CaptureMethod.MANUAL]) already use typed sizes and are left exactly as they are.
     * * In a scanned room: corners, observations (lines, extents, viewers, residuals), corner taps and the
     *   opening offsets / widths are scaled. Opening heights and sills (settings defaults) are not, and the
     *   ceiling height is scaled only when it was measured (`!ceilingEstimated`).
     * * All rooms are scaled about one origin so their relative placement is preserved: the first
     *   calibration uses the bounding-box center of the scanned rooms; a later one reuses the stored
     *   [Calibration.origin], so the stored [Calibration] (composed factor, origin) always describes the
     *   whole correction from the raw AR scale, and [applyCalibration] can repeat it for rooms added later.
     *
     * The resulting [Calibration] stores the composed factor (previous x this one), the edge's length before
     * this calibration ([Calibration.measuredLength]), [trueLength], [now] and the origin.
     *
     * @throws IllegalArgumentException with a user-presentable message when [trueLength] is not a positive
     *   number, the room or wall does not exist, the room was drawn by hand, or the implied correction is
     *   outside 0.5x..2x (almost always a typo or the wrong units).
     */
    fun calibrate(floor: Floor, roomId: String, wallIndex: Int, trueLength: Double, now: Long): Floor {
        require(trueLength.isFinite() && trueLength > 0.0) { "Enter a length greater than zero." }
        val room = requireNotNull(floor.rooms.firstOrNull { it.id == roomId }) { "That room is no longer on this floor." }
        require(isArMeasured(room)) {
            "${displayName(room)} was drawn by hand, so it already uses your typed sizes. Calibrate from a wall of a scanned room."
        }
        val c = room.corners
        require(c.size >= 3 && wallIndex in c.indices) { "That wall no longer exists in ${displayName(room)}." }
        val measured = c[wallIndex].distanceTo(c[(wallIndex + 1) % c.size])
        require(measured.isFinite() && measured > 1e-3) { "That wall is too short to calibrate from." }
        val factor = trueLength / measured
        require(factor in MIN_CALIBRATION_FACTOR..MAX_CALIBRATION_FACTOR) {
            "The entered length must be between half and twice the measured " +
                "${Units.formatLength(measured, UnitSystem.METRIC)} (${Units.formatLength(measured, UnitSystem.IMPERIAL)}). " +
                "Check the number and its units."
        }
        val previous = floor.calibration?.takeIf { it.factor.isFinite() && it.factor > 0.0 && it.origin.isFinite() }
        val origin = previous?.origin
            ?: Bounds.of(floor.rooms.filter(::isArMeasured).flatMap { r -> r.corners.filter { it.isFinite() } }).center
        return floor.copy(
            rooms = floor.rooms.map { if (isArMeasured(it)) scaleMeasured(it, factor, origin) else it },
            calibration = Calibration(
                factor = (previous?.factor ?: 1.0) * factor,
                roomId = roomId,
                wallIndex = wallIndex,
                measuredLength = measured,
                trueLength = trueLength,
                appliedAt = now,
                origin = origin,
            ),
        )
    }

    /** Replace the room with the same id (keeping its position in the list) or append it. */
    fun upsertRoom(floor: Floor, room: Room): Floor {
        val index = floor.rooms.indexOfFirst { it.id == room.id }
        val rooms = if (index < 0) floor.rooms + room else floor.rooms.toMutableList().also { it[index] = room }
        return floor.copy(rooms = rooms)
    }

    /** Remove the room with [roomId]; unchanged if it is not on the floor. */
    fun removeRoom(floor: Floor, roomId: String): Floor {
        if (floor.rooms.none { it.id == roomId }) return floor
        return floor.copy(rooms = floor.rooms.filterNot { it.id == roomId })
    }

    /**
     * Rotate every room about the floor's bounds center so the floor's dominant wall direction becomes
     * axis-aligned, using the smallest rotation (|angle| <= 45deg). Floors without rooms are returned unchanged.
     *
     * The dominant direction is length-weighted over every wall of every room and robust to angled walls
     * (bay windows, a 30° wall), see [FloorAxis]. A floor that is already straight (or has no usable walls)
     * is returned unchanged (same instance). See [rotate] for what moves.
     */
    fun straighten(floor: Floor): Floor {
        val axis = FloorAxis.of(floor.rooms) ?: return floor
        val turn = if (axis > PI / 4) PI / 2 - axis else -axis
        if (abs(turn) < 1e-12) return floor
        return rotate(floor, turn)
    }

    /**
     * Rotate every room (geometry, observations, taps) about the floor's bounds center by [angle] radians, CCW.
     *
     * Openings are stored per edge and move with their walls. The stored [Calibration.origin] is rotated too,
     * so it stays the same physical point of the plan. A zero or non-finite angle, or a floor without any
     * finite corner, returns the floor unchanged (same instance).
     */
    fun rotate(floor: Floor, angle: Double): Floor {
        if (!angle.isFinite() || angle == 0.0) return floor
        val corners = floor.rooms.flatMap { r -> r.corners.filter { it.isFinite() } }
        if (corners.isEmpty()) return floor
        val t = RigidTransform2.rotationAround(Bounds.of(corners).center, angle)
        return floor.copy(
            rooms = floor.rooms.map { RoomOps.transform(it, t) },
            calibration = floor.calibration?.let { c -> if (c.origin.isFinite()) c.copy(origin = t.apply(c.origin)) else c },
        )
    }

    /**
     * Snap room [roomId] against its best neighbour: among pairs (edge of this room, edge of another room) whose
     * faces are anti-parallel within [maxAngleDeg], whose extents overlap along the wall, and whose face gap is
     * within [maxDistance] of [wallThickness], pick the best (smallest combined angle/gap error, longest overlap);
     * rotate the room so the two faces are exactly parallel, then translate it along the neighbour's normal so
     * the gap equals [wallThickness]. Returns null when no pair qualifies.
     *
     * Details:
     * * Ranking: overlap length x (1 - 0.5 x gapError / [maxDistance]) x (1 - 0.5 x angleError / maxAngle), so
     *   the longest shared wall wins unless it is clearly worse aligned. Walls shorter than 20 cm and pairs
     *   facing each other along less than 15 cm are ignored.
     * * The room turns about the middle of the shared stretch of its own wall (not its centroid), so it does
     *   not slide along the wall it is being snapped to; only its distance from the neighbour changes.
     * * A snap is never allowed to make the room overlap any other room (by more than 0.01 m² beyond any
     *   overlap it already had); such a pair is skipped and the next best is tried.
     * * When the best pair is already exactly in place, the floor is returned unchanged (same instance), so
     *   callers can say "already aligned" with `===`.
     * * Null also for an unknown room, a room or neighbours without a usable outline, and invalid parameters.
     *   Only the room's pose changes (see [RoomOps.transform]); its shape, openings and the calibration stay.
     */
    fun snapRoom(floor: Floor, roomId: String, wallThickness: Double, maxDistance: Double = 0.6, maxAngleDeg: Double = 12.0): Floor? =
        snapGroup(floor, roomId, setOf(roomId), wallThickness, maxDistance, maxAngleDeg)

    /**
     * [snapRoom] for a group of rooms moved as one rigid block (typically every room of one scan session, which
     * share a frame and are already aligned with each other). The best pair is searched between the edges of
     * [roomId] (the room the user selected) and the edges of rooms NOT in [groupIds]; the resulting rigid
     * transform is applied to every room in [groupIds] (plus [roomId]). The overlap rule compares the moved
     * group with non-group rooms only, so members never "snap" to or block each other.
     * `snapGroup(floor, id, setOf(id), ...)` is exactly [snapRoom]. Null when no pair qualifies (also when every
     * other room is in the group); the same instance when already in place.
     */
    fun snapGroup(
        floor: Floor,
        roomId: String,
        groupIds: Set<String>,
        wallThickness: Double,
        maxDistance: Double = 0.6,
        maxAngleDeg: Double = 12.0,
    ): Floor? {
        val room = floor.rooms.firstOrNull { it.id == roomId } ?: return null
        if (!wallThickness.isFinite() || wallThickness < 0.0 || !maxDistance.isFinite() || maxDistance < 0.0 ||
            !maxAngleDeg.isFinite() || maxAngleDeg < 0.0
        ) return null
        val group = groupIds + roomId
        val outline = WallPairs.ccwOutline(room.corners) ?: return null
        val maxAngle = Math.toRadians(maxAngleDeg.coerceAtMost(45.0))
        val others = floor.rooms.filter { it.id !in group }.mapNotNull { r -> WallPairs.ccwOutline(r.corners)?.let { r.id to it } }
        val members = floor.rooms.filter { it.id in group }.mapNotNull { r -> WallPairs.ccwOutline(r.corners) }

        class Candidate(val pair: FacingWalls, val gapError: Double, val score: Double)
        val candidates = others.flatMap { (_, poly) -> WallPairs.facing(outline, poly, maxAngle) }
            .mapNotNull { p ->
                val gapError = abs(p.gap - wallThickness)
                if (gapError > maxDistance) return@mapNotNull null
                val gapFactor = if (maxDistance > 0.0) 1 - 0.5 * gapError / maxDistance else 1.0
                val angleFactor = if (maxAngle > 0.0) 1 - 0.5 * p.angleError / maxAngle else 1.0
                Candidate(p, gapError, p.overlap * gapFactor * angleFactor)
            }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.gapError }.thenBy { it.pair.angleError })
        if (candidates.isEmpty()) return null

        fun overlapWith(polys: List<List<Vec2>>, poly: List<Vec2>) = polys.sumOf { PolygonClipping.intersectionArea(it, poly) }
        val overlapBefore = others.associate { (id, poly) -> id to overlapWith(members, poly) }
        for (candidate in candidates) {
            val p = candidate.pair
            val turn = Polygons.normalizeAngle(p.targetDirection.angle() - p.direction.angle())
            val shift = p.outward * (wallThickness - p.gap)
            if (abs(turn) < SNAP_IDENTITY_ANGLE && shift.length < SNAP_IDENTITY_SHIFT) return floor
            val rotation = RigidTransform2.rotationAround(p.pivot, turn)
            val t = RigidTransform2(rotation.angle, rotation.translation + shift)
            val moved = members.map { poly -> poly.map { t.apply(it) } }
            val createsOverlap = others.any { (id, poly) ->
                overlapWith(moved, poly) > overlapBefore.getValue(id) + SNAP_OVERLAP_TOLERANCE
            }
            if (createsOverlap) continue
            return transformRooms(floor, group, t)
        }
        return null
    }

    /**
     * Apply [t] ([RoomOps.transform]) to every room whose id is in [ids]; other rooms and the calibration are
     * unchanged. Used to move / rotate a whole scan session as one block.
     */
    fun transformRooms(floor: Floor, ids: Set<String>, t: RigidTransform2): Floor {
        if (ids.isEmpty() || floor.rooms.none { it.id in ids }) return floor
        return floor.copy(rooms = floor.rooms.map { if (it.id in ids) RoomOps.transform(it, t) else it })
    }

    /**
     * Apply the floor's existing calibration to a room that is being ADDED to it (e.g. newly captured), scaling
     * about Calibration.origin. Only AR-measured quantities are scaled: corners, observations, taps, opening
     * offsets/widths, and ceilingHeight unless ceilingEstimated. MANUAL rooms and uncalibrated floors: unchanged.
     *
     * The factor is the floor's composed [Calibration.factor] (the whole correction from raw AR scale), so a
     * room scanned after calibrating matches its neighbours. A room whose id is already on the floor is
     * returned unchanged (it is already in the floor's calibrated scale; scaling it again would double the
     * correction). Unchanged rooms are returned as the same instance. A stored origin that is not finite
     * falls back to the plan origin.
     *
     * Rigid corrections computed in raw AR coordinates (ARCore anchor refinements, p' = R p + t) no longer fit
     * a room scaled this way. Either apply them to the raw room first and calibrate afterwards, or conjugate
     * them with the scale: p' = R p + f t + (f - 1)(R o - o) for factor f and origin o.
     */
    fun applyCalibration(floor: Floor, room: Room): Room {
        val calibration = floor.calibration ?: return room
        if (!isArMeasured(room) || floor.rooms.any { it.id == room.id }) return room
        val factor = calibration.factor
        if (!factor.isFinite() || factor <= 0.0 || abs(factor - 1.0) < 1e-12) return room
        val origin = if (calibration.origin.isFinite()) calibration.origin else Vec2.ZERO
        return scaleMeasured(room, factor, origin)
    }

    /** Rooms whose geometry comes from AR measurement (scanned walls or tapped corners). */
    internal fun isArMeasured(room: Room): Boolean =
        room.captureMethod == CaptureMethod.WALLS || room.captureMethod == CaptureMethod.CORNERS

    /** The room's name for messages, falling back to its type's name. */
    internal fun displayName(room: Room): String = room.name.replace('\n', ' ').trim().ifEmpty { room.type.displayName }

    /**
     * Uniform scale of the AR-measured parts of [room] about [origin] (see [calibrate]): unlike [RoomOps.scale],
     * opening heights / sills and an estimated ceiling keep their (typed or default) values.
     */
    private fun scaleMeasured(room: Room, factor: Double, origin: Vec2): Room {
        fun s(p: Vec2) = origin + (p - origin) * factor
        return room.copy(
            corners = room.corners.map(::s),
            cornerTaps = room.cornerTaps.map(::s),
            ceilingHeight = if (room.ceilingEstimated) room.ceilingHeight else room.ceilingHeight?.let { it * factor },
            openings = room.openings.map { it.copy(offset = it.offset * factor, width = it.width * factor) },
            observations = room.observations.map { o ->
                o.copy(
                    line = Line2(s(o.line.point), o.line.direction),
                    extentStart = s(o.extentStart),
                    extentEnd = s(o.extentEnd),
                    viewer = s(o.viewer),
                    rmsResidual = o.rmsResidual * factor,
                )
            },
        )
    }
}
