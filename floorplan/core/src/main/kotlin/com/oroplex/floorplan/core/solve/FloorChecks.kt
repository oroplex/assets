package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.PolygonClipping
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import kotlin.math.abs

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

    /** Room lists in messages name at most this many rooms, then "and N more". */
    private const val MAX_NAMES = 3

    /**
     * Pre-flight checks for a floor, most severe first. Covers at least: rooms whose interiors overlap
     * (ERROR — usually a room from another scan session that was not moved into place), invalid outlines
     * (ERROR), rooms with unresolved solver warnings (WARNING), POOR-quality rooms per RoomQuality (WARNING),
     * estimated ceiling heights (INFO, one combined message listing the rooms), rooms captured in different AR
     * sessions that are not snapped/aligned to each other (INFO), and an uncalibrated floor (INFO, suggests a tape check).
     *
     * Details:
     * * Overlaps name the later room of the pair (in floor order) as the one to fix, e.g. "Bedroom 2 overlaps
     *   Hallway — move or snap it into place"; see [overlappingRooms].
     * * An outline is invalid with fewer than 3 finite corners, no area, or walls that cross each other.
     *   Clockwise outlines draw and measure correctly and are not reported.
     * * Scan sessions are aligned when some room of one session has a wall facing a wall of a room of the
     *   other (parallel within 1°, gap within 5 cm of [PlanSettings.wallThickness], facing along >= 15 cm),
     *   which is what [FloorOps.snapRoom] produces. Sessions are grouped transitively; every group except the
     *   one holding the floor's first scanned room gets one INFO naming its rooms. Drawn rooms are ignored.
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
            errors += FloorIssue(IssueSeverity.ERROR, later.id, "${name(later)} overlaps ${name(earlier)} — move or snap it into place")
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
            val quality = RoomQuality.assess(room)
            if (quality.level == QualityLevel.POOR) {
                val why = quality.reasons.firstOrNull()?.let { ". $it" } ?: " — check it or scan it again"
                warnings += FloorIssue(IssueSeverity.WARNING, room.id, "${name(room)}: low scan accuracy$why")
            }
        }

        val estimated = rooms.filter { it.ceilingEstimated }
        if (estimated.isNotEmpty()) {
            infos += FloorIssue(
                IssueSeverity.INFO, null,
                if (estimated.size == 1) "Ceiling height is estimated in ${name(estimated[0])} — measure it for accurate volumes"
                else "Ceiling heights are estimated in ${names(estimated)} — measure them for accurate volumes",
            )
        }
        infos += sessionIssues(rooms, settings)
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

    /** INFO per group of scan sessions that is not aligned with the group holding the first scanned room. */
    private fun sessionIssues(rooms: List<Room>, settings: PlanSettings): List<FloorIssue> {
        class Scan(val room: Room, val session: String, val outline: List<Vec2>)
        val scans = rooms.mapNotNull { r ->
            val session = r.sessionId ?: return@mapNotNull null
            if (!FloorOps.isArMeasured(r)) return@mapNotNull null
            WallPairs.ccwOutline(r.corners)?.let { Scan(r, session, it) }
        }
        val sessions = scans.map { it.session }.distinct()
        if (sessions.size < 2) return emptyList()
        val thickness = settings.wallThickness.takeIf { it.isFinite() && it >= 0.0 } ?: PlanSettings().wallThickness
        // Union-find over sessions: two sessions join when any of their rooms face each other aligned.
        val parent = sessions.associateWith { it }.toMutableMap()
        fun find(s: String): String {
            var x = s
            while (parent.getValue(x) != x) x = parent.getValue(x)
            return x
        }
        for (i in scans.indices) {
            for (j in i + 1 until scans.size) {
                val sa = find(scans[i].session)
                val sb = find(scans[j].session)
                if (sa == sb) continue
                val aligned = WallPairs.facing(scans[i].outline, scans[j].outline, ALIGNED_ANGLE)
                    .any { abs(it.gap - thickness) <= ALIGNED_GAP_TOLERANCE }
                if (aligned) parent[sb] = sa
            }
        }
        val main = find(scans.first().session)
        return scans.filter { find(it.session) != main }
            .groupBy { find(it.session) }
            .values
            .map { group ->
                val groupRooms = group.map { it.room }
                val message = if (groupRooms.size == 1) {
                    "${name(groupRooms[0])} was scanned separately and may not line up — snap it to a neighbour"
                } else {
                    "${names(groupRooms)} were scanned separately and may not line up — snap them to a neighbour"
                }
                FloorIssue(IssueSeverity.INFO, groupRooms.first().id, message)
            }
    }

    private fun name(room: Room): String = FloorOps.displayName(room)

    /** "A", "A and B", "A, B and C", "A, B, C and 2 more". */
    private fun names(rooms: List<Room>): String {
        val all = rooms.map(::name)
        if (all.size <= MAX_NAMES) return if (all.size == 1) all[0] else all.dropLast(1).joinToString(", ") + " and " + all.last()
        return all.take(MAX_NAMES).joinToString(", ") + " and ${all.size - MAX_NAMES} more"
    }
}
