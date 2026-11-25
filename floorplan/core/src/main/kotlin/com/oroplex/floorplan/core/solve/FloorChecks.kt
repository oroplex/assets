package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.PolygonClipping
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.placementFrame
import kotlin.math.abs
import kotlin.math.roundToLong

enum class IssueSeverity { ERROR, WARNING, INFO }

/** A one-tap fix the UI can offer next to a [FloorIssue]. */
enum class FloorIssueAction {
    /** Straighten the whole floor (FloorOps.straighten); floor-wide, [FloorIssue.roomId] is null. */
    STRAIGHTEN_FLOOR,
    /** Snap the issue's room (and the rest of its scan session) to a neighbour (FloorOps.snapGroup). */
    SNAP_ROOM,
}

/**
 * A pre-export / editor check result. [roomId] is null for floor-wide issues. Messages are short and actionable.
 * [action] is an optional one-tap fix the UI may offer (a button or a tappable row).
 */
data class FloorIssue(
    val severity: IssueSeverity,
    val roomId: String?,
    val message: String,
    val action: FloorIssueAction? = null,
)

object FloorChecks {
    /** Two rooms count as aligned when facing walls are parallel within this angle... */
    private val ALIGNED_ANGLE = Math.toRadians(1.0)

    /** ...and their gap is within this distance (m) of the wall thickness. */
    private const val ALIGNED_GAP_TOLERANCE = 0.05

    /**
     * Rooms turned more than this (degrees, mod 90°) from their neighbours cannot be snapped (FloorOps.snapRoom
     * accepts 12° by default), so the message says to rotate them first.
     */
    private const val SNAP_MAX_ANGLE_DEG = 12.0

    /** A floor whose wall axis is more than this (degrees) off square gets a "Straighten floor" tip. */
    private const val STRAIGHTEN_MIN_DEG = 2.0

    /** Choosing the room to act on: facing walls within this angle and this gap error (m) count as near. */
    private val NEAR_ANGLE = Math.toRadians(45.0)
    private const val NEAR_GAP = 0.6

    /** Room lists in messages name at most this many rooms, then "and N more". */
    private const val MAX_NAMES = 3

    /**
     * Pre-flight checks for a floor, most severe first. Covers at least: rooms whose interiors overlap
     * (ERROR — usually a room from another scan session that was not moved into place), invalid outlines
     * (ERROR), rooms with unresolved solver warnings (WARNING), POOR-quality rooms per RoomQuality (WARNING),
     * estimated ceiling heights (INFO, one combined message listing the rooms), scanned rooms whose placement
     * frames are not snapped / aligned to each other (INFO), a plan turned off square (INFO), and an uncalibrated
     * floor (INFO, suggests a tape check).
     *
     * Details:
     * * Overlaps name the later room of the pair (in floor order) as the one to fix, e.g. "Bedroom 2 overlaps
     *   Hallway — move or snap it into place", with [FloorIssueAction.SNAP_ROOM] when the two rooms are in
     *   different placement frames (snapping can separate them); when the later room is turned more than 12°
     *   from the other, it says to rotate it first. Rooms of one frame (one scan) overlapping each other are
     *   a capture problem: no action, fix the corners. See [overlappingRooms].
     * * An outline is invalid with fewer than 3 finite corners, no area, or walls that cross each other.
     *   Clockwise outlines draw and measure correctly and are not reported.
     * * Alignment uses placement frames ([Room.placementFrame]): rooms of one scan session share a frame until
     *   one is moved, rotated or snapped on its own. Two frames are aligned when some room of one has a wall
     *   facing a wall of a room of the other (parallel within 1°, gap within 5 cm of [PlanSettings.wallThickness],
     *   facing along >= 15 cm), which is what FloorOps.snapRoom produces; frames are grouped transitively, drawn
     *   rooms can link frames but are never reported. Every group of scanned rooms except the one holding the
     *   floor's first scanned room gets one INFO naming its rooms, with [FloorIssueAction.SNAP_ROOM] on the
     *   group's room nearest to the rest of the plan; a group turned more than 12° from the main group is told to
     *   rotate first.
     * * A floor whose dominant wall axis ([FloorAxis]) is more than 2° off square gets "Plan is turned N° —
     *   straighten the floor…" with [FloorIssueAction.STRAIGHTEN_FLOOR].
     * * The calibration hint appears only on floors with scanned rooms.
     * Within one severity, issues follow the floor's room order. An empty floor has no issues.
     */
    fun check(floor: Floor, settings: PlanSettings): List<FloorIssue> {
        val rooms = floor.rooms
        if (rooms.isEmpty()) return emptyList()
        val errors = ArrayList<FloorIssue>()
        val warnings = ArrayList<FloorIssue>()
        val infos = ArrayList<FloorIssue>()
        val byId = rooms.associateBy { it.id }

        for ((first, second) in overlappingRooms(floor)) {
            val earlier = byId[first] ?: continue
            val later = byId[second] ?: continue
            errors += overlapIssue(earlier, later)
        }
        for (room in rooms) outlineProblem(room)?.let { errors += FloorIssue(IssueSeverity.ERROR, room.id, it) }

        for (room in rooms) {
            if (outlineProblem(room) != null) continue
            val roomWarnings = room.warnings.map { it.trim() }.filter { it.isNotEmpty() }
            when (roomWarnings.size) {
                0 -> Unit
                1 -> warnings += FloorIssue(IssueSeverity.WARNING, room.id, "${name(room)}: ${roomWarnings[0]}")
                else -> warnings += FloorIssue(
                    IssueSeverity.WARNING, room.id, "${name(room)} has ${roomWarnings.size} scan warnings — select it to review them",
                )
            }
            val quality = RoomQuality.assess(room, WallNumbering.OUTLINE, settings.unitSystem)
            if (quality.level == QualityLevel.POOR) {
                val why = quality.reasons.firstOrNull()?.let { ". $it" } ?: " — check it or scan it again"
                warnings += FloorIssue(IssueSeverity.WARNING, room.id, "${name(room)}: low scan accuracy$why")
            }
        }

        val estimated = rooms.filter { it.ceilingEstimated }
        if (estimated.isNotEmpty()) {
            infos += FloorIssue(
                IssueSeverity.INFO, null,
                if (estimated.size == 1) "Ceiling height is estimated in ${name(estimated[0])} — measure it so wall areas and volumes are right"
                else "Ceiling heights are estimated in ${names(estimated)} — measure them so wall areas and volumes are right",
            )
        }
        infos += frameIssues(rooms, settings)
        turnedPlanIssue(rooms)?.let { infos += it }
        if (floor.calibration == null && rooms.any(FloorOps::isArMeasured)) {
            infos += FloorIssue(
                IssueSeverity.INFO, null, "Floor isn't tape-calibrated — measure one long wall and calibrate from it for best accuracy",
            )
        }
        return errors + warnings + infos
    }

    /**
     * Ids of room pairs whose interiors overlap by more than [minOverlapArea] m².
     *
     * Exact for non-convex rooms (L, U, T shapes; see [PolygonClipping.intersectionArea]). Rooms sharing a
     * wall or touching only along a line do not overlap. Rooms with an unusable outline are skipped (see
     * [check]). Pairs are (earlier, later) in floor order, sorted by the earlier room, then the later.
     */
    fun overlappingRooms(floor: Floor, minOverlapArea: Double = 0.05): List<Pair<String, String>> {
        val threshold = if (minOverlapArea.isFinite()) minOverlapArea.coerceAtLeast(0.0) else 0.05
        val outlines = floor.rooms.map { WallPairs.ccwOutline(it.corners) }
        val out = ArrayList<Pair<String, String>>()
        for (i in floor.rooms.indices) {
            val a = outlines[i] ?: continue
            for (j in i + 1 until floor.rooms.size) {
                val b = outlines[j] ?: continue
                if (floor.rooms[i].id == floor.rooms[j].id) continue
                if (PolygonClipping.intersectionArea(a, b) > threshold) out += floor.rooms[i].id to floor.rooms[j].id
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------

    private fun overlapIssue(earlier: Room, later: Room): FloorIssue {
        if (earlier.placementFrame == later.placementFrame) {
            return FloorIssue(
                IssueSeverity.ERROR, later.id, "${name(later)} overlaps ${name(earlier)} — drag its corners so the walls don't cross",
            )
        }
        val turn = turnBetween(listOf(later.corners), listOf(earlier.corners))
        val message = if (turn != null && abs(turn) > SNAP_MAX_ANGLE_DEG) {
            "${name(later)} overlaps ${name(earlier)} and is turned ${degrees(turn)}° — rotate it to line up, then snap it into place"
        } else {
            "${name(later)} overlaps ${name(earlier)} — move or snap it into place"
        }
        return FloorIssue(IssueSeverity.ERROR, later.id, message, FloorIssueAction.SNAP_ROOM)
    }

    /** Why [room]'s outline cannot be used, or null when it is fine. */
    private fun outlineProblem(room: Room): String? {
        val c = room.corners
        val finite = c.filter { it.isFinite() }
        if (finite.size < 3 || finite.size != c.size) return "${name(room)} has no usable outline — redraw it or scan it again"
        val area = Polygons.signedArea(c)
        if (!area.isFinite() || abs(area) < 1e-6) return "${name(room)} has no usable outline — redraw it or scan it again"
        if (!Polygons.isSimple(c)) return "${name(room)}'s walls cross each other — drag its corners to fix the outline"
        return null
    }

    /**
     * INFO per group of placement frames (see [check]) that is not aligned with the group holding the floor's
     * first scanned room.
     */
    private fun frameIssues(rooms: List<Room>, settings: PlanSettings): List<FloorIssue> {
        class Placed(val room: Room, val frame: String, val outline: List<Vec2>, val scanned: Boolean)
        val placed = rooms.mapNotNull { r ->
            val outline = WallPairs.ccwOutline(r.corners) ?: return@mapNotNull null
            val scanned = FloorOps.isArMeasured(r) && (r.sessionId != null || r.frameId != null)
            Placed(r, r.placementFrame, outline, scanned)
        }
        val scans = placed.filter { it.scanned }
        if (scans.map { it.frame }.distinct().size < 2) return emptyList()
        val thickness = settings.wallThickness.takeIf { it.isFinite() && it >= 0.0 } ?: PlanSettings().wallThickness
        // Union-find over frames: two frames join when any of their rooms face each other aligned.
        val parent = HashMap<String, String>()
        for (p in placed) parent[p.frame] = p.frame
        fun find(s: String): String {
            var x = s
            while (parent.getValue(x) != x) x = parent.getValue(x)
            return x
        }
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val fa = find(placed[i].frame)
                val fb = find(placed[j].frame)
                if (fa == fb) continue
                val aligned = WallPairs.facing(placed[i].outline, placed[j].outline, ALIGNED_ANGLE)
                    .any { abs(it.gap - thickness) <= ALIGNED_GAP_TOLERANCE }
                if (aligned) parent[fb] = fa
            }
        }
        val main = find(scans.first().frame)
        val mainSessions = scans.filter { find(it.frame) == main }.mapNotNull { it.room.sessionId }.toSet()
        val mainOutlines = placed.filter { find(it.frame) == main }.map { it.outline }
        return scans.filter { find(it.frame) != main }
            .groupBy { find(it.frame) }
            .values
            .map { group ->
                val groupRooms = group.map { it.room }
                val groupIds = groupRooms.map { it.id }.toSet()
                val rest = placed.filter { find(it.frame) != find(group.first().frame) }
                val target = nearestRoom(group.map { it.room to it.outline }, rest.map { it.outline }, thickness)
                val turn = turnBetween(group.map { it.outline }, mainOutlines)
                val one = groupRooms.size == 1
                val them = if (one) "it" else "them"
                val who = if (one) name(groupRooms[0]) else names(groupRooms)
                val message = when {
                    turn != null && abs(turn) > SNAP_MAX_ANGLE_DEG ->
                        "$who ${if (one) "is" else "are"} turned ${degrees(turn)}° from the rest of the plan — " +
                            "rotate $them to line up, then snap $them to a neighbour"
                    groupRooms.all { it.frameId != null && it.sessionId in mainSessions } ->
                        "$who ${if (one) "was" else "were"} moved on ${if (one) "its" else "their"} own and may not line up — snap $them to a neighbour"
                    else ->
                        "$who ${if (one) "was" else "were"} scanned separately and may not line up — snap $them to a neighbour"
                }
                FloorIssue(IssueSeverity.INFO, target.takeIf { it in groupIds } ?: groupRooms.first().id, message, FloorIssueAction.SNAP_ROOM)
            }
    }

    /**
     * The room of [group] the user should snap: the one with the longest wall facing a room of [rest] (antiparallel
     * within 45°, gap within 60 cm of [thickness]); without any, the one whose centroid is nearest to [rest].
     */
    private fun nearestRoom(group: List<Pair<Room, List<Vec2>>>, rest: List<List<Vec2>>, thickness: Double): String {
        var best: String? = null
        var bestOverlap = 0.0
        for ((room, outline) in group) {
            for (other in rest) {
                for (p in WallPairs.facing(outline, other, NEAR_ANGLE)) {
                    if (abs(p.gap - thickness) > NEAR_GAP) continue
                    if (p.overlap > bestOverlap + 1e-9) {
                        bestOverlap = p.overlap
                        best = room.id
                    }
                }
            }
        }
        if (best != null) return best
        if (rest.isEmpty()) return group.first().first.id
        val centers = rest.map { Polygons.centroid(it) }
        return group.minBy { (_, outline) ->
            val c = Polygons.centroid(outline)
            centers.minOf { it.distanceTo(c) }
        }.first.id
    }

    /**
     * How far (degrees, signed, in (-45, 45]) the wall axis of [outlines] is turned from that of [reference]
     * (both mod 90°, see [FloorAxis]); null when either has no usable wall.
     */
    private fun turnBetween(outlines: List<List<Vec2>>, reference: List<List<Vec2>>): Double? {
        val a = FloorAxis.ofOutlines(outlines) ?: return null
        val b = FloorAxis.ofOutlines(reference) ?: return null
        return Math.toDegrees(FloorAxis.deviation(a, b))
    }

    /** "Plan is turned N°" when the floor's wall axis is more than [STRAIGHTEN_MIN_DEG] off square. */
    private fun turnedPlanIssue(rooms: List<Room>): FloorIssue? {
        val axis = FloorAxis.of(rooms) ?: return null
        val tilt = Math.toDegrees(FloorAxis.tilt(axis))
        if (!tilt.isFinite() || abs(tilt) <= STRAIGHTEN_MIN_DEG) return null
        return FloorIssue(
            IssueSeverity.INFO, null, "Plan is turned ${degrees(tilt)}° — straighten the floor so it prints square",
            FloorIssueAction.STRAIGHTEN_FLOOR,
        )
    }

    /** |[deg]| rounded to whole degrees. */
    private fun degrees(deg: Double): Long = abs(deg).roundToLong()

    private fun name(room: Room): String = FloorOps.displayName(room)

    /** "A", "A and B", "A, B and C", "A, B, C and 2 more". */
    private fun names(rooms: List<Room>): String {
        val all = rooms.map(::name)
        if (all.size <= MAX_NAMES) return if (all.size == 1) all[0] else all.dropLast(1).joinToString(", ") + " and " + all.last()
        return all.take(MAX_NAMES).joinToString(", ") + " and ${all.size - MAX_NAMES} more"
    }
}
