package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Room

/** Pure editing operations on rooms. All return new objects; inputs are never mutated. */
object RoomOps {
    /** Move one corner. Openings on the two adjacent edges are clamped to stay inside their edge. */
    fun moveCorner(room: Room, index: Int, position: Vec2): Room = TODO("implement")

    /**
     * Set the length of edge [wallIndex] by STRETCHING the room along that edge's direction: every corner
     * whose projection onto the edge direction (measured from corners[wallIndex]) is >= that of
     * corners[wallIndex+1] (minus 1 mm) moves by the length delta along the direction. For a rectangle this
     * moves the far wall and keeps it square. Rejects (returns the room unchanged) lengths <= 5 cm.
     */
    fun setWallLength(room: Room, wallIndex: Int, newLength: Double): Room = TODO("implement")

    /** Split edge [wallIndex] at the projection of [at]; openings re-assigned to the correct sub-edge. */
    fun insertCorner(room: Room, wallIndex: Int, at: Vec2): Room = TODO("implement")

    /** Remove a corner (min 3 remain). Openings on removed edges are re-projected onto the merged edge. */
    fun deleteCorner(room: Room, index: Int): Room = TODO("implement")

    /** Rigidly move/rotate the room: corners, observations (lines, extents, viewer) and taps. */
    fun transform(room: Room, t: RigidTransform2): Room = TODO("implement")

    /** Uniform scale about [origin]: corners, observations, taps, opening offsets/widths/heights, ceiling. */
    fun scale(room: Room, factor: Double, origin: Vec2): Room = TODO("implement")

    /**
     * Re-run the solver from the raw observations (WALLS) or taps (CORNERS) with [options].
     * Openings are re-mapped to the nearest new edge by their world position. MANUAL rooms only get squared.
     */
    fun resolve(room: Room, options: SolverOptions): Room = TODO("implement")

    /**
     * Create an opening from two plan points marking its two side edges (e.g. door jambs at the floor).
     * Both points are projected onto the nearest edge of the room; returns null if they are > 0.5 m from any
     * edge or closer than 0.2 m to each other. The opening is clamped within the edge.
     */
    fun placeOpening(
        room: Room,
        a: Vec2,
        b: Vec2,
        type: OpeningType,
        height: Double,
        sillHeight: Double,
        id: String,
    ): Opening? = TODO("implement")

    /** Plan-space segment (start, end) of an opening along its wall edge. */
    fun openingSegment(room: Room, opening: Opening): Pair<Vec2, Vec2> = TODO("implement")
}

object FloorOps {
    /**
     * Scale the whole floor so that edge [wallIndex] of room [roomId] measures [trueLength].
     * All rooms are scaled about the same origin (the floor's bounding-box center) so relative
     * placement is preserved. Composes with any previous calibration (factor multiplies).
     */
    fun calibrate(floor: Floor, roomId: String, wallIndex: Int, trueLength: Double, now: Long): Floor = TODO("implement")

    fun upsertRoom(floor: Floor, room: Room): Floor = TODO("implement")

    fun removeRoom(floor: Floor, roomId: String): Floor = TODO("implement")
}
