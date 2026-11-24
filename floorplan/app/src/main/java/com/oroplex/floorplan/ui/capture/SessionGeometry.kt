package com.oroplex.floorplan.ui.capture

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.core.solve.RoomSolver
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Pure geometry used by the capture flow across rooms of one AR session: the shared squaring axis,
 * calibration-aware anchor corrections, and keeping doors / windows while a room is reopened.
 */

/**
 * Below this coherence (|Σ w·e^{4iθ}| / Σ w, 0..1) the session's rooms have no clear common axis (e.g. a
 * 45° bay dominates), so no axis hint is given.
 */
internal const val MIN_AXIS_COHERENCE = 0.3

/**
 * The squaring axis shared by the rooms already saved in this AR session (they share its coordinate
 * frame): [RoomSolver.dominantAngle] over all their edge directions, weighted by edge length. Passing it as
 * `SolverOptions.axisHint` makes neighbouring rooms come out exactly parallel instead of each being squared
 * to its own (slightly noisy) axis.
 *
 * Returns null when there is nothing to go by (no rooms, degenerate outlines) or when the edges disagree
 * too much to define one axis (coherence below [MIN_AXIS_COHERENCE]).
 */
internal fun sessionAxisHint(rooms: List<Room>): Double? {
    val angles = ArrayList<Double>()
    val weights = ArrayList<Double>()
    for (room in rooms) {
        val c = room.corners
        if (c.size < 3 || c.any { !it.isFinite() }) continue
        for (i in c.indices) {
            val e = c[(i + 1) % c.size] - c[i]
            val length = e.length
            if (length.isFinite() && length > 1e-6) {
                angles += e.angle()
                weights += length
            }
        }
    }
    val total = weights.sum()
    if (angles.isEmpty() || !total.isFinite() || total <= 0.0) return null
    // Same 4θ circular mean as RoomSolver.dominantAngle; its resultant length tells how coherent the axis is.
    var c = 0.0
    var s = 0.0
    for (i in angles.indices) {
        c += weights[i] * cos(4 * angles[i])
        s += weights[i] * sin(4 * angles[i])
    }
    if (hypot(c, s) / total < MIN_AXIS_COHERENCE) return null
    return RoomSolver.dominantAngle(angles, weights).takeIf { it.isFinite() }
}

/** The uniform scale a room was saved with (a floor's tape calibration), so later corrections can match it. */
data class AppliedScale(val factor: Double, val origin: Vec2) {
    val isIdentity: Boolean get() = abs(factor - 1.0) < 1e-12

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
