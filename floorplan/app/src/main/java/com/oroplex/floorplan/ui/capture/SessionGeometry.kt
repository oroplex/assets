package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.FloorOps
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.core.solve.RoomSolver
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs

/*
 * Pure geometry used by the capture flow across rooms of one AR session: the shared squaring axis,
 * calibration-aware anchor corrections, straightening a finished scan, and keeping doors / windows while a
 * room is reopened.
 */

/**
 * The squaring axis of one AR session: [angle] (radians, mod 90°) is the axis room [roomId] was actually
 * squared to (its SolveResult.dominantAngle), in that room's own capture frame (the tracking frame when its
 * anchor was created). Set when the session's first room is saved and reused for every later room, so the
 * session's rooms come out exactly parallel.
 */
@Serializable
data class SessionAxis(val angle: Double, val roomId: String)

/** [a] reduced to the squaring range [0, PI/2) (walls repeat every 90°); non-finite input gives 0. */
internal fun canonicalAxis(a: Double): Double {
    if (!a.isFinite()) return 0.0
    val quarter = PI / 2
    val r = a % quarter
    val c = if (r < 0) r + quarter else r
    return if (c >= quarter) 0.0 else c
}

/**
 * `SolverOptions.axisHint` for the next solve of draft [draftId]: the session's shared squaring axis, so
 * neighbouring rooms come out exactly parallel without being pulled by angled walls (bays, chamfers).
 *
 * * Preferred: [axis], the axis the session's first room was really squared to.
 * * Fallback: the robust [RoomSolver.sharedAxis] over the saved [sessionRooms]; never the plain 4θ mean of
 *   RoomSolver.dominantAngle, which a single 30° wall tilts by about a degree.
 *
 * Frames (CAPX-6): each room's geometry is in the tracking frame of when its anchor was created, and ARCore's
 * later map refinements turn that frame by the room's anchor correction ([corrections], keyed by room id; it
 * is applied to every room at finish). So the axis is first brought into the CURRENT frame (plus its room's
 * correction angle; each fallback outline is rotated by its own room's angle), then into the draft's frame
 * (minus the draft's own correction angle). After finish, when every room gets its correction, the rooms are
 * parallel instead of the drift being corrected twice. A missing or non-finite correction counts as 0.
 *
 * Returns the hint in [0, PI/2), or null when there is nothing to go by.
 */
internal fun sessionAxisHint(
    axis: SessionAxis?,
    sessionRooms: List<Room>,
    corrections: Map<String, RigidTransform2>,
    draftId: String?,
): Double? {
    fun turn(id: String?): Double = id?.let { corrections[it]?.angle }?.takeIf { it.isFinite() } ?: 0.0
    val current = if (axis != null && axis.angle.isFinite()) {
        axis.angle + turn(axis.roomId)
    } else {
        val outlines = sessionRooms.mapNotNull { room ->
            val c = room.corners
            if (c.size < 3 || c.any { !it.isFinite() }) return@mapNotNull null
            val a = turn(room.id)
            if (a == 0.0) c else c.map { it.rotated(a) }
        }
        if (outlines.isEmpty()) return null
        val shared = try {
            RoomSolver.sharedAxis(outlines)
        } catch (e: Exception) {
            null
        }
        shared?.takeIf { it.isFinite() } ?: return null
    }
    return canonicalAxis(current - turn(draftId))
}

/**
 * [floor] squared to the page ([FloorOps.straighten]) when every room on it was scanned in AR session
 * [sessionId]: a first scan then comes out square instead of at ARCore's arbitrary starting heading. A floor
 * that also holds rooms from other sessions or drawn rooms is left alone (the user may already have placed
 * them), as is a floor without rooms. Call it after the anchor corrections, which live in the raw AR frame.
 * A failing straighten returns [floor] unchanged.
 */
internal fun straightenedIfOnlySession(floor: Floor, sessionId: String?): Floor {
    if (sessionId == null || floor.rooms.isEmpty() || floor.rooms.any { it.sessionId != sessionId }) return floor
    return try {
        FloorOps.straighten(floor)
    } catch (e: Exception) {
        floor
    }
}

/** The uniform scale a room was saved with (a floor's tape calibration), so later corrections can match it. */
@Serializable
data class AppliedScale(val factor: Double, val origin: Vec2) {
    val isIdentity: Boolean get() = abs(factor - 1.0) < 1e-12

    /** [p] scaled about [origin]: where a raw AR plan point lies on the calibrated floor. */
    fun map(p: Vec2): Vec2 = origin + (p - origin) * factor

    companion object {
        /** The scale [Calibration] applies to a room added to its floor, or null when it changes nothing. */
        fun of(calibration: Calibration?): AppliedScale? {
            val c = calibration ?: return null
            if (!c.factor.isFinite() || c.factor <= 0.0 || !c.origin.isFinite()) return null
            return AppliedScale(c.factor, c.origin).takeUnless { it.isIdentity }
        }
    }
}

/**
 * An anchor correction (computed by ARCore in the raw AR frame) expressed for a room that was saved
 * scaled by [scale]: S∘T∘S⁻¹ with S(p) = o + f(p − o). The result is still rigid —
 * p' ↦ R·p' + f·t + (1 − f)(o − R·o) — so it can be applied with RoomOps.transform. Invalid scales leave
 * the correction unchanged.
 */
internal fun RigidTransform2.forScaledRoom(scale: AppliedScale?): RigidTransform2 {
    val s = scale ?: return this
    val f = s.factor
    val o = s.origin
    if (!f.isFinite() || f <= 0.0 || !o.isFinite()) return this
    return RigidTransform2(angle, translation * f + (o - o.rotated(angle)) * (1.0 - f))
}

/**
 * A door / window kept by its plan position while its room is reopened: edge indices change when walls are
 * edited, so the opening is put back on whichever wall is nearest once the room is closed again.
 */
@Serializable
data class ParkedOpening(val opening: Opening, val start: Vec2, val end: Vec2)

/** Park every opening of [room] at its position along its wall (unplaceable ones are dropped). */
internal fun parkOpenings(room: Room): List<ParkedOpening> = room.openings.mapNotNull { o ->
    val segment = try {
        RoomOps.openingSegment(room, o)
    } catch (e: Exception) {
        null
    } ?: return@mapNotNull null
    val (a, b) = segment
    if (!a.isFinite() || !b.isFinite() || a.distanceTo(b) < 1e-6) null else ParkedOpening(o, a, b)
}

/** Openings put back on a re-solved room, and the ones that no longer fit any wall. */
internal data class RestoredOpenings(val openings: List<Opening>, val dropped: List<Opening>)

/**
 * Place [parked] openings back onto [room] (the newly solved outline) with RoomOps.placeOpening, keeping
 * each opening's id, type, height, sill and swing. An opening is dropped when it is no longer near a wall
 * (its wall was deleted) or would overlap one already placed.
 */
internal fun restoreOpenings(room: Room, parked: List<ParkedOpening>): RestoredOpenings {
    val placed = ArrayList<Opening>(parked.size)
    val dropped = ArrayList<Opening>()
    for (p in parked) {
        val o = p.opening
        val restored = try {
            RoomOps.placeOpening(room, p.start, p.end, o.type, o.height, o.sillHeight, o.id)
        } catch (e: Exception) {
            null
        }?.copy(swing = o.swing)
        if (restored == null || overlapsExisting(restored, room.openings + placed)) dropped += o else placed += restored
    }
    return RestoredOpenings(placed, dropped)
}
