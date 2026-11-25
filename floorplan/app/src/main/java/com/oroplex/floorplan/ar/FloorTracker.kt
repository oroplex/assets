package com.oroplex.floorplan.ar

import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.oroplex.floorplan.core.geometry.Vec3
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** A floor estimate: world height of the floor plane and the area (m²) of the plane backing it. */
internal data class FloorEstimate(val y: Double, val area: Double)

/** Why the AR layer would not lock the local floor by itself; [NONE] when it would. */
internal enum class AutoLockBlock {
    /** Nothing blocks it: the local floor may be locked without the user. */
    NONE,

    /** No qualifying, not vetoed upward plane within [FloorSelection.LOCAL_RADIUS] of the user. */
    NO_LOCAL_FLOOR,

    /**
     * The local floor's height was vetoed as furniture earlier in this session; the veto was lifted because
     * the user came to stand on a plane at that height, but only the user may lock it.
     */
    VETOED_BEFORE,

    /** The local floor's plane is smaller than [FloorSelection.MIN_AUTO_LOCK_AREA]. */
    TOO_SMALL,

    /** The crosshair recently saw floor-like surfaces well below the local floor: it may be furniture. */
    HITS_BELOW,

    /**
     * The plane is less than [FloorSelection.AMBIGUOUS_DROP] below the phone (bed / sofa height for most
     * users) and the phone is not above it: the user stands beside it, not on it.
     */
    NOT_UNDER_USER,
}

/**
 * What the floor tracker knows after a frame.
 * - [global]: the session-wide estimate ([ArFrameState.floorY]); kept while nothing qualifies.
 * - [local]: the floor under the user ([ArFrameState.localFloorY]); null when no plane qualifies near them.
 * - [autoLockBlock]: [AutoLockBlock.NONE] exactly when [local] may be locked without the user
 *   ([ArFrameState.floorAutoLockable]).
 */
internal data class FloorState(
    val global: FloorEstimate? = null,
    val local: FloorEstimate? = null,
    val autoLockBlock: AutoLockBlock = AutoLockBlock.NO_LOCAL_FLOOR,
) {
    val autoLockable: Boolean get() = autoLockBlock == AutoLockBlock.NONE
}

/**
 * An upward-facing plane considered as the floor: center height (world Y), polygon area (m²) and the
 * horizontal [distance] (m) from the camera's ground point (the point straight below the phone) to the plane's
 * polygon: 0 when the phone is above the plane, +∞ when unknown.
 */
internal data class FloorCandidate(
    val y: Double,
    val area: Double,
    val distance: Double = Double.POSITIVE_INFINITY,
) {
    /** The plane contains, or lies within [FloorSelection.LOCAL_RADIUS] of, the camera's ground point. */
    val isNearUser: Boolean get() = distance <= FloorSelection.LOCAL_RADIUS

    /** The phone is above the plane's polygon: the user stands on this surface (or leans over it). */
    val isUnderUser: Boolean get() = distance <= 0.0
}

/**
 * Tracks the floor across frames (GL thread only). Translates ARCore planes into [FloorCandidate]s and
 * leaves every decision to the pure [FloorModel]; logs floor-selection changes (rate-limited) for device
 * diagnostics.
 *
 * Candidates are TRACKING, non-subsumed HORIZONTAL_UPWARD_FACING planes whose center lies
 * [FloorSelection.MIN_DROP_BELOW_CAMERA]–[FloorSelection.MAX_DROP_BELOW_CAMERA] below the camera. For each,
 * the camera position is transformed into the plane's own frame (its +Y is the plane normal, so its X-Z
 * plane is the plane itself) and [FloorSelection.distanceToPolygon] gives how far the user is from it.
 */
internal class FloorTracker {
    /** Non-subsumed TRACKING planes of each orientation in the last update (horizontal = up + down). */
    var horizontalPlanes: Int = 0
        private set
    var verticalPlanes: Int = 0
        private set

    private val model = FloorModel()
    private val candidates = ArrayList<FloorCandidate>(8)
    private val worldPoint = FloatArray(3)
    private val localPoint = FloatArray(3)
    private val changes = ChangeLog(LOG_INTERVAL_NS)
    private var loggedVetoChanges = 0

    /**
     * [camera] is null while the camera is not tracking: plane counts update, the global floor is kept, the
     * local floor is unknown. [floorLikeHitY] is the height of a floor-like crosshair hit this frame
     * ([HitTesting.floorFallbackSample]), or null. [floorLocked] only slows the diagnostics down: once the
     * capture flow has locked a floor, changes of the tracker's estimates are logged less often.
     */
    fun update(
        planes: Collection<Plane>,
        camera: Vec3?,
        floorLikeHitY: Double?,
        nowNs: Long,
        floorLocked: Boolean = false,
    ): FloorState {
        var horizontal = 0
        var vertical = 0
        candidates.clear()
        for (plane in planes) {
            try {
                if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
                when (plane.type) {
                    Plane.Type.VERTICAL -> vertical++
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> horizontal++
                    Plane.Type.HORIZONTAL_UPWARD_FACING -> {
                        horizontal++
                        if (camera != null) candidate(plane, camera)?.let { candidates += it }
                    }
                    null -> Unit
                }
            } catch (e: Exception) {
                // A plane ARCore invalidated mid-frame: skip it rather than failing the whole frame.
            }
        }
        horizontalPlanes = horizontal
        verticalPlanes = vertical
        val state = if (camera != null) {
            model.update(candidates, camera.y, floorLikeHitY, nowNs)
        } else {
            model.onTrackingLost()
        }
        if (camera != null) logChanges(state, camera.y, nowNs, floorLocked)
        return state
    }

    /** The plane as a floor candidate, or null when it is not at a plausible floor height. */
    private fun candidate(plane: Plane, camera: Vec3): FloorCandidate? {
        val pose = plane.centerPose
        val y = pose.ty().toDouble()
        // The polygon is only read for planes at a plausible floor height.
        if (!FloorSelection.isInFloorRange(y, camera.y)) return null
        val polygon = plane.polygon
        worldPoint[0] = camera.x.toFloat()
        worldPoint[1] = camera.y.toFloat()
        worldPoint[2] = camera.z.toFloat()
        pose.inverse().transformPoint(worldPoint, 0, localPoint, 0)
        val distance = FloorSelection.distanceToPolygon(polygon, localPoint[0].toDouble(), localPoint[2].toDouble())
        return FloorCandidate(y, FloorSelection.polygonArea(polygon), distance)
    }

    private fun logChanges(state: FloorState, cameraY: Double, nowNs: Long, floorLocked: Boolean) {
        try {
            // Veto changes are rare by construction (a veto is sticky; lifting one takes a second above the
            // plane), so each one gets its own line.
            if (model.vetoChanges != loggedVetoChanges) {
                loggedVetoChanges = model.vetoChanges
                model.lastVeto?.let { ArLog.i("floor: " + FloorReport.veto(it)) }
            }
            val key = FloorReport.key(state, model.vetoChanges)
            val interval = if (floorLocked) LOCKED_LOG_INTERVAL_NS else LOG_INTERVAL_NS
            changes.offer(nowNs, key, interval) {
                "floor: " + FloorReport.state(state, cameraY, candidates, model::isVetoed, model.hitsBelowLocal)
            }?.let { ArLog.i(it) }
        } catch (e: Exception) {
            // Diagnostics only.
        }
    }

    private companion object {
        /** While the floor is being found, every change matters: at most one line per second. */
        const val LOG_INTERVAL_NS = 1_000_000_000L

        /** After the lock the estimates only feed hints; one line per five seconds keeps the log readable. */
        const val LOCKED_LOG_INTERVAL_NS = 5_000_000_000L
    }
}

/**
 * A change to the furniture veto (diagnostics): the height [y] was vetoed ([lifted] false) with the evidence
 * that did it, or a veto on it was lifted because the user came to stand on a plane at that height.
 */
internal data class FloorVeto(val y: Double, val dropBelowCamera: Double, val hitsBelow: Int, val lifted: Boolean = false)

/**
 * The floor-tracking state machine (pure: no ARCore types, unit-tested on the JVM; GL thread only).
 *
 * Each [update] takes this frame's [FloorCandidate]s and decides three things (rules in [FloorSelection]):
 *
 * 1. Which heights are furniture. A plane less than [FloorSelection.AMBIGUOUS_DROP] below the phone may be
 *    a bed, sofa or table. When the crosshair has seen floor-like surfaces clearly below it
 *    ([FloorEvidence], [FloorSelection.isContradicted]) while the phone is NOT above that plane (people
 *    stand on floors and beside furniture), its height is vetoed, and the veto is STICKY: the floor is the
 *    lowest surface in a room, so something solid seen lower proves it. The memory of those hits is
 *    time-based (about 10 s), so sweeping the crosshair over the furniture afterwards does not wash it
 *    out; and once vetoed, no further evidence is needed, for the rest of the session.
 *
 *    One thing lifts a veto: the phone staying above a plane at that height for [UNVETO_HOLD_NS]. Seen from
 *    a sunken living room, the kitchen floor two steps up looks exactly like a bed (a raised surface beside
 *    the user, with floor below it), and gets vetoed; when the user walks up and stands on it, it is a
 *    floor after all. A height that was ever vetoed is still never locked without the user
 *    ([AutoLockBlock.VETOED_BEFORE]): leaning over a bed must not undo the veto's whole point.
 * 2. The LOCAL floor: the lowest qualifying, not vetoed plane near the user. Another level seen through a
 *    doorway (sunken living room, step-down garage) is beside the user, not under them, so it never wins.
 * 3. The GLOBAL floor: the session-wide estimate, which follows the local floor's level while one exists and
 *    otherwise the lowest plane anywhere; it is kept while nothing qualifies and is not raised onto furniture.
 *
 * It also says whether the local floor may be locked without the user ([FloorSelection.autoLockBlock]).
 */
internal class FloorModel(private val evidence: FloorEvidence = FloorEvidence()) {
    var state: FloorState = FloorState()
        private set

    /** Number of distinct heights vetoed so far in this session (lifted ones included). */
    var vetoCount: Int = 0
        private set

    /** Incremented whenever a veto is set or lifted (so diagnostics can tell that something changed). */
    var vetoChanges: Int = 0
        private set

    /** The most recent veto change (diagnostics). */
    var lastVeto: FloorVeto? = null
        private set

    /** Recent floor-like hits well below the local floor in the last update (diagnostics). */
    var hitsBelowLocal: Int = 0
        private set

    private val vetoHeights = DoubleArray(MAX_VETOES)
    private val vetoActive = BooleanArray(MAX_VETOES)

    /** Since when the phone has been above a plane at the vetoed height without a break, or [NOT_ABOVE]. */
    private val vetoAboveSinceNs = LongArray(MAX_VETOES) { NOT_ABOVE }

    /**
     * One frame with a tracking camera at height [cameraY]. [floorLikeHitY] is the height of a floor-like
     * crosshair hit this frame, or null; [nowNs] is a monotonic time.
     */
    fun update(candidates: List<FloorCandidate>, cameraY: Double, floorLikeHitY: Double?, nowNs: Long): FloorState {
        if (floorLikeHitY != null) evidence.add(floorLikeHitY, nowNs)
        if (!cameraY.isFinite()) return onTrackingLost()

        liftVetoes(candidates, cameraY, nowNs)
        for (c in candidates) {
            // A plane the phone is directly above is what the user stands on: never vetoed, whatever the
            // crosshair sees lower down (another level, reflections in a glossy floor).
            if (c.isUnderUser || !FloorSelection.isInFloorRange(c.y, cameraY) || isVetoed(c.y)) continue
            val limit = c.y - FloorSelection.MAX_RAISE
            val hits = evidence.countBelow(limit, nowNs)
            if (hits < FloorSelection.MIN_CONTRADICTING_HITS) continue
            if (FloorSelection.isContradicted(c.y, cameraY, hits, evidence.slotsBelow(limit, nowNs))) {
                veto(FloorVeto(c.y, cameraY - c.y, hits))
            }
        }

        val vetoed: (Double) -> Boolean = ::isVetoed
        val global = FloorSelection.next(state.global, candidates, cameraY, vetoed)
        val localPick = FloorSelection.selectLocal(candidates, cameraY, vetoed)
        hitsBelowLocal = localPick?.let { evidence.countBelow(it.y - FloorSelection.MAX_RAISE, nowNs) } ?: 0
        state = FloorState(
            global = global,
            local = localPick?.let { FloorEstimate(it.y, it.area) },
            autoLockBlock = FloorSelection.autoLockBlock(
                local = localPick,
                cameraY = cameraY,
                hitsWellBelow = hitsBelowLocal,
                vetoedBefore = localPick != null && wasEverVetoed(localPick.y),
            ),
        )
        return state
    }

    /** The camera is not tracking: the global floor is kept, the floor under the user is unknown. */
    fun onTrackingLost(): FloorState {
        hitsBelowLocal = 0
        vetoAboveSinceNs.fill(NOT_ABOVE)
        state = FloorState(global = state.global, local = null, autoLockBlock = AutoLockBlock.NO_LOCAL_FLOOR)
        return state
    }

    /** Whether [y] is within [FloorSelection.SAME_FLOOR_TOLERANCE] of a height currently vetoed as furniture. */
    fun isVetoed(y: Double): Boolean = vetoIndex(y, activeOnly = true) >= 0

    /** Whether [y] is at a height that was vetoed at any time in this session, lifted since or not. */
    fun wasEverVetoed(y: Double): Boolean = vetoIndex(y, activeOnly = false) >= 0

    private fun vetoIndex(y: Double, activeOnly: Boolean): Int {
        if (!y.isFinite()) return -1
        for (i in 0 until minOf(vetoCount, MAX_VETOES)) {
            if ((!activeOnly || vetoActive[i]) && abs(vetoHeights[i] - y) <= FloorSelection.SAME_FLOOR_TOLERANCE) return i
        }
        return -1
    }

    private fun veto(v: FloorVeto) {
        var i = vetoIndex(v.y, activeOnly = false)
        if (i < 0) {
            // A full list overwrites its oldest entry; 32 distinct furniture heights do not occur in practice.
            i = vetoCount % MAX_VETOES
            vetoHeights[i] = v.y
            vetoCount++
        }
        vetoActive[i] = true
        vetoAboveSinceNs[i] = NOT_ABOVE
        vetoChanges++
        lastVeto = v
    }

    /** Lifts every veto whose height the phone has been above (on a qualifying plane) for [UNVETO_HOLD_NS]. */
    private fun liftVetoes(candidates: List<FloorCandidate>, cameraY: Double, nowNs: Long) {
        for (i in 0 until minOf(vetoCount, MAX_VETOES)) {
            if (!vetoActive[i]) continue
            val above = candidates.any { c ->
                c.isUnderUser && FloorSelection.qualifies(c, cameraY) &&
                    abs(c.y - vetoHeights[i]) <= FloorSelection.SAME_FLOOR_TOLERANCE
            }
            when {
                !above -> vetoAboveSinceNs[i] = NOT_ABOVE
                vetoAboveSinceNs[i] == NOT_ABOVE || nowNs < vetoAboveSinceNs[i] -> vetoAboveSinceNs[i] = nowNs
                nowNs - vetoAboveSinceNs[i] >= UNVETO_HOLD_NS -> {
                    vetoActive[i] = false
                    vetoAboveSinceNs[i] = NOT_ABOVE
                    vetoChanges++
                    lastVeto = FloorVeto(vetoHeights[i], cameraY - vetoHeights[i], hitsBelow = 0, lifted = true)
                }
            }
        }
    }

    companion object {
        /**
         * The phone must stay above a plane at a vetoed height this long before the veto is lifted: standing
         * on a floor does that, the phone passing over the corner of a bed does not.
         */
        const val UNVETO_HOLD_NS = 1_000_000_000L
        private const val MAX_VETOES = 32
        private const val NOT_ABOVE = Long.MIN_VALUE
    }
}

/** Pure floor-selection math (no ARCore types), unit-tested on the JVM. */
internal object FloorSelection {
    /**
     * A floor plane's center must be at least this far below the camera, meters. A phone held to scan is
     * 1.1–1.6 m above the floor, while counters, islands and tables are only 0.5–0.85 m below it.
     */
    const val MIN_DROP_BELOW_CAMERA = 0.8

    /** ... and at most this far (excludes reflections in glossy floors and a lower storey), meters. */
    const val MAX_DROP_BELOW_CAMERA = 2.2

    /**
     * Minimum plane area (m²). Keeps small spurious planes (glossy-floor reflections, a shoe box) from
     * winning just because they are low.
     */
    const val MIN_AREA = 0.5

    /**
     * Upward planes within this height of the lowest candidate are pieces of the same floor (ARCore often
     * splits one floor into patches a centimeter or two apart, or reports a rug separately); the largest
     * of them backs the estimate. Anything higher (bed, table, island, step) is ignored, meters.
     */
    const val SAME_FLOOR_TOLERANCE = 0.08

    /**
     * Over time the global estimate is never raised by more than this onto a higher surface while the
     * previous floor is still plausibly below the camera ([MAX_KEEP_DROP]), unless the user now stands on
     * the higher surface. Also the margin by which a floor-like hit must lie below a plane to speak against
     * it, meters.
     */
    const val MAX_RAISE = 0.25

    /**
     * The previous floor is considered "still plausible" while it is less than this far below the camera.
     * Beyond it the user went up a storey, and a higher floor may replace it, meters.
     */
    const val MAX_KEEP_DROP = 3.2

    /**
     * A plane less than this far below the camera (m) might be a bed, sofa or table top; only such planes
     * can be vetoed by [isContradicted], and auto-lock only takes them when the phone is above them
     * ([autoLockBlock]). Real floors are this close only while the phone is held unusually low, and
     * glossy-floor reflections (which also look "lower") cannot veto a floor seen from a normal height.
     */
    const val AMBIGUOUS_DROP = 1.2

    /** Floor-like crosshair hits more than [MAX_RAISE] below a plane needed to veto its height. */
    const val MIN_CONTRADICTING_HITS = 5

    /**
     * ... and they must come from at least this many distinct [FloorEvidence] time slots (a quarter second
     * each), so a single burst of bad depth readings cannot veto a floor for the whole session.
     */
    const val MIN_CONTRADICTING_SLOTS = 2

    /**
     * A plane is "near the user" when its polygon contains, or lies within this horizontal distance of, the
     * camera's ground point, meters. The floor someone scans from is under or just in front of them; another
     * level (sunken room, garage, patio) lies beyond a doorway or step.
     */
    const val LOCAL_RADIUS = 1.0

    /** The local floor's plane must be at least this large (m²) to be locked without the user. */
    const val MIN_AUTO_LOCK_AREA = 1.0

    /**
     * This many recent floor-like hits more than [MAX_RAISE] below the local floor stop auto-lock, however
     * far below the phone the plane is: stricter than the veto, because a manual Lock floor is still there.
     */
    const val MIN_BLOCKING_HITS = 2

    /**
     * Whether a plane at [planeY] is proven NOT to be the floor: it is ambiguous (less than
     * [AMBIGUOUS_DROP] below the camera) and at least [MIN_CONTRADICTING_HITS] recent floor-like crosshair
     * hits from at least [MIN_CONTRADICTING_SLOTS] time slots lay more than [MAX_RAISE] below it.
     */
    fun isContradicted(
        planeY: Double,
        cameraY: Double,
        hitsWellBelow: Int,
        slotsWellBelow: Int = MIN_CONTRADICTING_SLOTS,
    ): Boolean =
        planeY.isFinite() && cameraY.isFinite() && cameraY - planeY < AMBIGUOUS_DROP &&
            hitsWellBelow >= MIN_CONTRADICTING_HITS && slotsWellBelow >= MIN_CONTRADICTING_SLOTS

    /** Whether a plane at [planeY] lies at a plausible floor height below a camera at [cameraY]. */
    fun isInFloorRange(planeY: Double, cameraY: Double): Boolean {
        if (!planeY.isFinite() || !cameraY.isFinite()) return false
        val drop = cameraY - planeY
        return drop >= MIN_DROP_BELOW_CAMERA && drop <= MAX_DROP_BELOW_CAMERA
    }

    /**
     * The floor UNDER THE USER among this frame's [candidates]: of the candidates that qualify (finite area
     * ≥ [MIN_AREA], [isInFloorRange]), are near the user ([FloorCandidate.isNearUser]) and are not [vetoed],
     * the largest within [SAME_FLOOR_TOLERANCE] of the LOWEST. "Lowest" still beats a bed or table next to
     * the user; restricting it to planes near the user keeps a lower level seen elsewhere out.
     * Null if none qualifies.
     */
    fun selectLocal(
        candidates: List<FloorCandidate>,
        cameraY: Double,
        vetoed: (y: Double) -> Boolean = { false },
    ): FloorCandidate? = lowestGroup(candidates) { qualifies(it, cameraY) && it.isNearUser && !vetoed(it.y) }

    /**
     * The session-wide floor among this frame's [candidates]: the largest qualifying, non-[vetoed] candidate
     * within [SAME_FLOOR_TOLERANCE] of the lowest one. When a local floor exists ([selectLocal]), planes that
     * are NOT near the user and lie more than [SAME_FLOOR_TOLERANCE] below it are another level, not a better
     * floor, and are ignored; without a local floor the lowest plane anywhere wins. Null if none qualifies.
     */
    fun select(
        candidates: List<FloorCandidate>,
        cameraY: Double,
        vetoed: (y: Double) -> Boolean = { false },
    ): FloorCandidate? {
        val localY = selectLocal(candidates, cameraY, vetoed)?.y
        return lowestGroup(candidates) { c ->
            qualifies(c, cameraY) && !vetoed(c.y) &&
                (localY == null || c.isNearUser || c.y >= localY - SAME_FLOOR_TOLERANCE)
        }
    }

    /**
     * One tracking step of the session-wide estimate: the estimate after a frame with [candidates], given
     * the [previous] one.
     * - No qualifying candidate: keep [previous] (so it does not flicker back to null), unless its height is
     *   [vetoed] by now (it was furniture after all): then null.
     * - A pick more than [MAX_RAISE] above [previous] while [previous] is still less than [MAX_KEEP_DROP]
     *   below the camera: keep [previous] (furniture must not replace a floor that was already seen), unless
     *   the pick is the local floor and the phone is above it: the user walked up onto a higher level.
     * - Otherwise the pick ([select]: small refinements and lower floors are followed, but not a lower level
     *   seen elsewhere while a local floor exists).
     */
    fun next(
        previous: FloorEstimate?,
        candidates: List<FloorCandidate>,
        cameraY: Double,
        vetoed: (y: Double) -> Boolean = { false },
    ): FloorEstimate? {
        val kept = previous?.takeIf { it.y.isFinite() && !vetoed(it.y) }
        val pick = select(candidates, cameraY, vetoed) ?: return kept
        if (kept != null && pick.y - kept.y > MAX_RAISE && cameraY - kept.y <= MAX_KEEP_DROP) {
            val local = selectLocal(candidates, cameraY, vetoed)
            if (local == null || !local.isUnderUser) return kept
        }
        return FloorEstimate(pick.y, pick.area)
    }

    /**
     * Why the [local] floor pick ([selectLocal]) must not be locked without the user, or [AutoLockBlock.NONE].
     * [hitsWellBelow] is the number of recent floor-like crosshair hits more than [MAX_RAISE] below it;
     * [vetoedBefore] whether its height was vetoed as furniture at any time in this session.
     *
     * Auto-lock needs a plane that is local (so not vetoed now) and was never vetoed, is at least
     * [MIN_AUTO_LOCK_AREA], is not contradicted by [MIN_BLOCKING_HITS] or more such hits (whatever its drop
     * below the phone), and, when it is less than [AMBIGUOUS_DROP] below the phone, lies directly under the
     * phone: a bed or sofa seen before any floor evidence exists is beside the user, while the floor is what
     * they stand on.
     */
    fun autoLockBlock(
        local: FloorCandidate?,
        cameraY: Double,
        hitsWellBelow: Int,
        vetoedBefore: Boolean = false,
    ): AutoLockBlock = when {
        local == null || !cameraY.isFinite() -> AutoLockBlock.NO_LOCAL_FLOOR
        vetoedBefore -> AutoLockBlock.VETOED_BEFORE
        !(local.area >= MIN_AUTO_LOCK_AREA) -> AutoLockBlock.TOO_SMALL
        hitsWellBelow >= MIN_BLOCKING_HITS -> AutoLockBlock.HITS_BELOW
        cameraY - local.y < AMBIGUOUS_DROP && !local.isUnderUser -> AutoLockBlock.NOT_UNDER_USER
        else -> AutoLockBlock.NONE
    }

    /** A candidate large enough ([MIN_AREA]) and at a plausible floor height ([isInFloorRange]). */
    fun qualifies(c: FloorCandidate, cameraY: Double): Boolean =
        c.area.isFinite() && c.area >= MIN_AREA && isInFloorRange(c.y, cameraY)

    /** The largest accepted candidate within [SAME_FLOOR_TOLERANCE] of the lowest accepted one. */
    private inline fun lowestGroup(candidates: List<FloorCandidate>, accept: (FloorCandidate) -> Boolean): FloorCandidate? {
        var lowest = Double.POSITIVE_INFINITY
        for (c in candidates) {
            if (accept(c) && c.y < lowest) lowest = c.y
        }
        if (lowest == Double.POSITIVE_INFINITY) return null
        var best: FloorCandidate? = null
        for (c in candidates) {
            if (!accept(c) || c.y > lowest + SAME_FLOOR_TOLERANCE) continue
            if (best == null || c.area > best.area) best = c
        }
        return best
    }

    /**
     * Shoelace area of an ARCore plane polygon: (x, z) pairs in the plane's local frame, read from the
     * buffer's position to its limit without consuming it. Returns 0 for fewer than 3 vertices.
     */
    fun polygonArea(xz: FloatBuffer): Double {
        val base = xz.position()
        val n = (xz.limit() - base) / 2
        if (n < 3) return 0.0
        var twiceArea = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val xi = xz.get(base + 2 * i).toDouble()
            val zi = xz.get(base + 2 * i + 1).toDouble()
            val xj = xz.get(base + 2 * j).toDouble()
            val zj = xz.get(base + 2 * j + 1).toDouble()
            twiceArea += xi * zj - xj * zi
        }
        val area = abs(twiceArea) / 2
        return if (area.isFinite()) area else 0.0
    }

    /**
     * Distance from the point ([px], [pz]) to a plane polygon given as (x, z) pairs in the same 2D frame
     * (read from the buffer's position to its limit without consuming it): 0 when the point is inside or on
     * the boundary (even-odd rule, so it also works for a non-convex outline), otherwise the distance to the
     * nearest edge. +∞ for fewer than 3 vertices or non-finite input ("unknown" must never read as "near").
     */
    fun distanceToPolygon(xz: FloatBuffer, px: Double, pz: Double): Double {
        val base = xz.position()
        val n = (xz.limit() - base) / 2
        if (n < 3 || !px.isFinite() || !pz.isFinite()) return Double.POSITIVE_INFINITY
        var inside = false
        var nearestSq = Double.POSITIVE_INFINITY
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val ax = xz.get(base + 2 * i).toDouble()
            val az = xz.get(base + 2 * i + 1).toDouble()
            val bx = xz.get(base + 2 * j).toDouble()
            val bz = xz.get(base + 2 * j + 1).toDouble()
            if (!ax.isFinite() || !az.isFinite() || !bx.isFinite() || !bz.isFinite()) return Double.POSITIVE_INFINITY
            // Even-odd crossing test against a ray from the point toward +x.
            if ((az > pz) != (bz > pz)) {
                val crossX = ax + (pz - az) / (bz - az) * (bx - ax)
                if (px < crossX) inside = !inside
            }
            // Squared distance to the edge a-b.
            val ex = bx - ax
            val ez = bz - az
            val lengthSq = ex * ex + ez * ez
            val t = if (lengthSq > 0.0) (((px - ax) * ex + (pz - az) * ez) / lengthSq).coerceIn(0.0, 1.0) else 0.0
            val dx = px - (ax + t * ex)
            val dz = pz - (az + t * ez)
            val dSq = dx * dx + dz * dz
            if (dSq < nearestSq) nearestSq = dSq
        }
        if (inside) return 0.0
        val d = sqrt(nearestSq)
        return if (d.isFinite()) d else Double.POSITIVE_INFINITY
    }
}

/**
 * Recent heights of floor-like surfaces seen at the crosshair (pure; GL thread only).
 *
 * The memory is TIME-based: every sample is kept for [maxAgeNs] (about 10 s) and then forgotten, so
 * evidence from a room left long ago does not linger, and nothing but time removes it. Samples are stored
 * in time slots of [slotNs] (a quarter second) holding up to [perSlot] samples each (more than a 60 fps
 * camera delivers), so sweeping the crosshair over a bed for a few seconds cannot push the floor readings
 * taken just before out of memory, which a fixed-size FIFO did.
 */
internal class FloorEvidence(
    private val maxAgeNs: Long = DEFAULT_MAX_AGE_NS,
    private val slotNs: Long = DEFAULT_SLOT_NS,
    private val perSlot: Int = DEFAULT_PER_SLOT,
) {
    private val slots = (if (slotNs > 0) (maxAgeNs / slotNs).coerceIn(1L, 4096L).toInt() else 1) + 2
    private val slotIds = LongArray(slots) { EMPTY }
    private val counts = IntArray(slots)
    private val heights = DoubleArray(slots * perSlot.coerceAtLeast(1))
    private val times = LongArray(slots * perSlot.coerceAtLeast(1))

    fun add(y: Double, nowNs: Long) {
        if (!y.isFinite() || slotNs <= 0 || perSlot <= 0) return
        val id = Math.floorDiv(nowNs, slotNs)
        val i = Math.floorMod(id, slots.toLong()).toInt()
        if (slotIds[i] != id) {
            slotIds[i] = id
            counts[i] = 0
        }
        // A full slot drops further samples: a quarter second already holds enough of them.
        if (counts[i] >= perSlot) return
        val k = i * perSlot + counts[i]
        heights[k] = y
        times[k] = nowNs
        counts[i]++
    }

    /** Number of samples not older than [maxAgeNs] at [nowNs] whose height is below [y]. */
    fun countBelow(y: Double, nowNs: Long): Int = scan(y, nowNs, countSlots = false)

    /** Number of distinct time slots holding at least one such sample (how spread out the evidence is). */
    fun slotsBelow(y: Double, nowNs: Long): Int = scan(y, nowNs, countSlots = true)

    private fun scan(y: Double, nowNs: Long, countSlots: Boolean): Int {
        if (!y.isFinite()) return 0
        var total = 0
        for (i in 0 until slots) {
            if (slotIds[i] == EMPTY) continue
            var inSlot = 0
            val base = i * perSlot
            for (k in base until base + counts[i]) {
                val age = nowNs - times[k]
                if (age in 0..maxAgeNs && heights[k] < y) inSlot++
            }
            total += if (countSlots) (if (inSlot > 0) 1 else 0) else inSlot
        }
        return total
    }

    private companion object {
        const val DEFAULT_MAX_AGE_NS = 10_000_000_000L
        const val DEFAULT_SLOT_NS = 250_000_000L
        const val DEFAULT_PER_SLOT = 16
        const val EMPTY = Long.MIN_VALUE
    }
}

/** Log lines for floor-selection changes (pure; see [ArLog] for the rules). Heights are world Y, meters. */
internal object FloorReport {
    /** What must change before a new line is logged: heights rounded to 2 cm, the auto-lock verdict, vetoes. */
    fun key(state: FloorState, vetoChanges: Int): Any =
        listOf(quantize(state.global?.y), quantize(state.local?.y), state.autoLockBlock, vetoChanges)

    fun state(
        state: FloorState,
        cameraY: Double,
        candidates: List<FloorCandidate>,
        vetoed: (Double) -> Boolean,
        hitsBelowLocal: Int,
    ): String {
        val sb = StringBuilder(160)
        sb.append("global ").append(estimate(state.global))
        sb.append(" | local ").append(estimate(state.local))
        sb.append(" | auto-lock ").append(if (state.autoLockable) "yes" else "no (${state.autoLockBlock})")
        sb.append(" | phone y=").append(cameraY.fmt(2))
        state.local?.let { sb.append(" (").append((cameraY - it.y).fmt(2)).append(" m above local)") }
        if (hitsBelowLocal > 0) sb.append(" | floor hits below local: ").append(hitsBelowLocal)
        sb.append(" | planes:")
        if (candidates.isEmpty()) sb.append(" none in floor range")
        for ((index, c) in candidates.withIndex()) {
            if (index == MAX_LISTED) {
                sb.append(" +").append(candidates.size - MAX_LISTED).append(" more")
                break
            }
            sb.append(" [y=").append(c.y.fmt(2)).append(' ').append(c.area.fmt(1)).append(" m2 ")
            sb.append(
                when {
                    c.isUnderUser -> "under phone"
                    c.distance.isFinite() -> "${c.distance.fmt(1)} m away"
                    else -> "distance n/a"
                },
            )
            if (vetoed(c.y)) sb.append(" VETOED")
            sb.append(']')
        }
        return sb.toString()
    }

    fun veto(v: FloorVeto): String = if (v.lifted) {
        "veto on height y=${v.y.fmt(2)} lifted: the phone stayed above a plane at that height " +
            "(${v.dropBelowCamera.fmt(2)} m below it); it will still not auto-lock"
    } else {
        "vetoed height y=${v.y.fmt(2)} as furniture (${v.dropBelowCamera.fmt(2)} m below the phone, " +
            "${v.hitsBelow} floor-like hits more than ${FloorSelection.MAX_RAISE.fmt(2)} m lower)"
    }

    private fun estimate(e: FloorEstimate?): String = if (e == null) "none" else "y=${e.y.fmt(2)} (${e.area.fmt(1)} m2)"

    private fun quantize(y: Double?): Long? = y?.takeIf { it.isFinite() }?.let { floor(it / QUANTUM + 0.5).toLong() }

    private const val QUANTUM = 0.02
    private const val MAX_LISTED = 6
}
