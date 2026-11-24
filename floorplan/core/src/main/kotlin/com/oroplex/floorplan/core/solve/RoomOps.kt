package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
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
import kotlin.math.abs
import kotlin.math.max

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

    /** [setWallLength]: corners this close to the far end's projection count as "at the far side". */
    private const val STRETCH_SLACK = 0.001

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
     * Set the length of edge [wallIndex] by STRETCHING the room along that edge's direction: every corner
     * whose projection onto the edge direction (measured from corners[wallIndex]) is >= that of
     * corners[wallIndex+1] (minus 1 mm) moves by the length delta along the direction. For a rectangle this
     * moves the far wall and keeps it square. Rejects (returns the room unchanged) lengths <= 5 cm.
     * Also rejected: invalid indices, degenerate edges and stretches that would make a valid outline
     * self-intersecting. Openings stay anchored to an unmoved corner of their edge (see [moveCorner]).
     */
    fun setWallLength(room: Room, wallIndex: Int, newLength: Double): Room {
        val c = room.corners
        val n = c.size
        if (n < 3 || wallIndex !in c.indices || !newLength.isFinite() || newLength <= MIN_WALL_LENGTH) return room
        val a = c[wallIndex]
        val b = c[(wallIndex + 1) % n]
        val oldLength = a.distanceTo(b)
        if (!oldLength.isFinite() || oldLength < 0.01) return room
        val dir = (b - a) / oldLength
        val shift = dir * (newLength - oldLength)
        val threshold = oldLength - STRETCH_SLACK
        val corners = c.map { p -> if (((p - a) dot dir) >= threshold) p + shift else p }
        if (!acceptable(c, corners)) return room
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

    /** Rigidly move/rotate the room: corners, observations (lines, extents, viewer) and taps. */
    fun transform(room: Room, t: RigidTransform2): Room {
        if (!t.angle.isFinite() || !t.translation.isFinite()) return room
        return room.copy(
            corners = room.corners.map { t.apply(it) },
            cornerTaps = room.cornerTaps.map { t.apply(it) },
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

    /**
     * Scale the whole floor so that edge [wallIndex] of room [roomId] measures [trueLength].
     * All rooms are scaled about the same origin (the floor's bounding-box center) so relative
     * placement is preserved. Composes with any previous calibration (factor multiplies).
     *
     * The resulting [Calibration] stores the composed factor, the edge's length before this calibration
     * ([Calibration.measuredLength]) and [trueLength].
     *
     * @throws IllegalArgumentException with a user-presentable message when [trueLength] is not a positive
     *   number, the room or wall does not exist, or the implied correction is outside 0.5x..2x (almost
     *   always a typo or the wrong units).
     */
    fun calibrate(floor: Floor, roomId: String, wallIndex: Int, trueLength: Double, now: Long): Floor {
        require(trueLength.isFinite() && trueLength > 0.0) { "Enter a length greater than zero." }
        val room = requireNotNull(floor.rooms.firstOrNull { it.id == roomId }) { "That room is no longer on this floor." }
        val c = room.corners
        require(c.size >= 3 && wallIndex in c.indices) { "That wall no longer exists in ${room.name}." }
        val measured = c[wallIndex].distanceTo(c[(wallIndex + 1) % c.size])
        require(measured.isFinite() && measured > 1e-3) { "That wall is too short to calibrate from." }
        val factor = trueLength / measured
        require(factor in MIN_CALIBRATION_FACTOR..MAX_CALIBRATION_FACTOR) {
            "The entered length must be between half and twice the measured " +
                "${Units.formatLength(measured, UnitSystem.METRIC)} (${Units.formatLength(measured, UnitSystem.IMPERIAL)}). " +
                "Check the number and its units."
        }
        val origin = Bounds.of(floor.rooms.flatMap { r -> r.corners.filter { it.isFinite() } }).center
        val previous = floor.calibration?.factor?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return floor.copy(
            rooms = floor.rooms.map { RoomOps.scale(it, factor, origin) },
            calibration = Calibration(
                factor = previous * factor,
                roomId = roomId,
                wallIndex = wallIndex,
                measuredLength = measured,
                trueLength = trueLength,
                appliedAt = now,
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
     */
    fun straighten(floor: Floor): Floor = TODO("implement")

    /** Rotate every room (geometry, observations, taps) about the floor's bounds center by [angle] radians, CCW. */
    fun rotate(floor: Floor, angle: Double): Floor = TODO("implement")

    /**
     * Snap room [roomId] against its best neighbour: among pairs (edge of this room, edge of another room) whose
     * faces are anti-parallel within [maxAngleDeg], whose extents overlap along the wall, and whose face gap is
     * within [maxDistance] of [wallThickness], pick the best (smallest combined angle/gap error, longest overlap);
     * rotate the room about its centroid so the two faces are exactly parallel, then translate it along the
     * neighbour's normal so the gap equals [wallThickness]. Returns null when no pair qualifies.
     */
    fun snapRoom(floor: Floor, roomId: String, wallThickness: Double, maxDistance: Double = 0.6, maxAngleDeg: Double = 12.0): Floor? =
        TODO("implement")

    /**
     * Apply the floor's existing calibration to a room that is being ADDED to it (e.g. newly captured), scaling
     * about Calibration.origin. Only AR-measured quantities are scaled: corners, observations, taps, opening
     * offsets/widths, and ceilingHeight unless ceilingEstimated. MANUAL rooms and uncalibrated floors: unchanged.
     */
    fun applyCalibration(floor: Floor, room: Room): Room = TODO("implement")
}
