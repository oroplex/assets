package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.placementFrame
import com.oroplex.floorplan.core.solve.RoomSolver
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * Rooms that move as one block ("Whole scan"): the rooms of one AR scan session share a coordinate frame, so a
 * later session that landed turned or offset is fixed by moving / turning / snapping all of its rooms together
 * instead of one by one (which would also break their correct layout relative to each other).
 *
 * The block is the set of rooms sharing [Room.placementFrame]. A rigid edit of ONE room (move, turn, snap with
 * "Whole scan" off) takes that room out of the shared frame ([detached]); a block edit keeps the frame, so the
 * floor checks know which rooms really were moved into place.
 */
object ScanGroups {
    /** Ids of the rooms on [floor] sharing [roomId]'s placement frame, [roomId] included; empty for an unknown room. */
    fun groupOf(floor: Floor, roomId: String): Set<String> {
        val room = floor.rooms.firstOrNull { it.id == roomId } ?: return emptySet()
        val frame = room.placementFrame
        return floor.rooms.filter { it.placementFrame == frame }.mapTo(LinkedHashSet()) { it.id }
    }

    /** The floor's first scanned room: the one the rest of the floor was lined up with. */
    fun firstScanned(floor: Floor): Room? =
        floor.rooms.firstOrNull { it.sessionId != null && it.captureMethod != CaptureMethod.MANUAL }

    /**
     * Whether "Whole scan" starts switched on for [roomId]: when it belongs to a later scan session than the
     * floor's first scanned room (that session is the one that usually needs lining up) and its frame holds more
     * than one room. Rooms of the first session, drawn rooms and rooms alone in their frame start with it off.
     */
    fun defaultWholeScan(floor: Floor, roomId: String): Boolean {
        val room = floor.rooms.firstOrNull { it.id == roomId } ?: return false
        if (groupOf(floor, roomId).size < 2) return false
        val session = room.sessionId ?: return false
        val first = firstScanned(floor) ?: return false
        return session != first.sessionId && room.placementFrame != first.placementFrame
    }

    /** [room] after a rigid edit made on its own: it leaves the frame it shared with its scan session. */
    fun detached(room: Room): Room = if (room.placementFrame == room.id) room else room.copy(frameId = room.id)

    /** Pivot for turning [rooms] as one block: the centre of their bounds; null without any finite corner. */
    fun pivot(rooms: List<Room>): Vec2? {
        val bounds = Bounds.of(rooms.flatMap { r -> r.corners.filter { it.isFinite() } })
        return if (bounds.isEmpty) null else bounds.center
    }

    /**
     * The smallest turn (radians, in (-PI/4, PI/4]) that makes the walls of [rooms] run the same way as the walls
     * of [reference] (modulo 90°), using the robust shared wall direction of each set ([RoomSolver.sharedAxis],
     * so bays and angled walls do not pull it). Null when either set has no usable direction.
     */
    fun squareTurn(rooms: List<Room>, reference: List<Room>): Double? {
        val own = axisOf(rooms) ?: return null
        val target = axisOf(reference) ?: return null
        val quarter = PI / 2
        var d = (target - own) % quarter
        if (d < 0) d += quarter
        if (d > quarter / 2) d -= quarter
        return if (d.isFinite()) d else null
    }

    /**
     * The angle (radians, (-PI, PI]) a rigid move turned [before] into [after] (the same room), from their first
     * usable edge; null when the rooms have no comparable edge.
     */
    fun turnBetween(before: Room, after: Room): Double? {
        val a = before.corners
        val b = after.corners
        if (a.size != b.size || a.size < 2) return null
        for (i in a.indices) {
            val j = (i + 1) % a.size
            val d0 = a[j] - a[i]
            val d1 = b[j] - b[i]
            if (!d0.isFinite() || !d1.isFinite() || d0.length < 1e-6 || d1.length < 1e-6) continue
            return Polygons.normalizeAngle(d1.angle() - d0.angle())
        }
        return null
    }

    /** Robust shared wall direction of [rooms] (mod 90°), or null when none has a usable outline. */
    fun axisOf(rooms: List<Room>): Double? {
        val outlines = rooms.map { it.corners }.filter { c -> c.size >= 3 && c.all { it.isFinite() } }
        if (outlines.isEmpty()) return null
        return try {
            RoomSolver.sharedAxis(outlines)?.takeIf { it.isFinite() }
        } catch (e: RuntimeException) {
            null
        }
    }

    /** A turn for messages: "37°" (whole degrees from 10°), "1.5°" below that. */
    fun formatTurn(radians: Double): String {
        val deg = abs(Math.toDegrees(radians))
        if (!deg.isFinite()) return "?"
        return if (deg >= 9.95) "${Math.round(deg)}°" else "%.1f".format(Locale.US, deg).removeSuffix(".0") + "°"
    }
}
