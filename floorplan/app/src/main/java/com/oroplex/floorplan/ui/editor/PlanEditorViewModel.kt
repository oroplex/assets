package com.oroplex.floorplan.ui.editor

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.Ids
import com.oroplex.floorplan.core.model.AreaCategory
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomNames
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.placementFrame
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.FloorIssueAction
import com.oroplex.floorplan.core.solve.FloorOps
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.core.solve.RoomQualityReport
import com.oroplex.floorplan.core.solve.SolverOptions
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.data.sanitized
import com.oroplex.floorplan.ui.common.formatLength
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

/**
 * State holder of the plan editor.
 *
 * The ViewModel keeps its own working copy of the project. Every committed edit (1) records the previous
 * project snapshot in a bounded [UndoHistory], (2) updates the UI state immediately and (3) persists the
 * whole project with [ProjectRepository.save]. Saves run in a non-cancellable coroutine so an edit made
 * right before leaving the screen is never lost.
 *
 * The repository's live flow is collected as well: emissions that match one of our own pending saves
 * (compared ignoring updatedAt) are echoes and are ignored; anything else is a change made elsewhere (for
 * example a room scanned by the capture screen) and is adopted as an undoable step.
 *
 * Drags (corner moves and room moves) only update [EditorUiState.previewRooms] while the finger moves and are
 * committed as ONE undo step when it lifts. A corner drag snaps onto square / alignment guides
 * ([CornerGuides]); a room move shows where the room would snap against a neighbour and, when released
 * there, commits the snap as a second undo step offered for undo in the snackbar.
 *
 * "Whole scan" ([EditorUiState.wholeScan], see [ScanGroups]): rooms scanned in one AR session share a frame,
 * so a later session that landed turned or offset is moved, turned, squared and snapped as one block. Block
 * edits keep the rooms' shared frame; a rigid edit of a single room (move, turn, snap, square) takes that room
 * out of it ([ScanGroups.detached]), so the floor checks only treat rooms as lined up once they really moved.
 *
 * Every committed change bumps a revision number, so a snackbar "Undo" can revert exactly the edit it
 * announced ([undoRevision]) and never something the user did afterwards.
 *
 * Floor checks ([EditorUiState.insights]) are recomputed whenever the shown floor or the settings change.
 * All core tools go through [tools]; a tool that throws is logged and treated as unavailable.
 */
class PlanEditorViewModel(
    private val repository: ProjectRepository,
    val projectId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tools: PlanTools = PlanTools.Core,
    private val log: (message: String, error: Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _messages = Channel<EditorMessage>(Channel.BUFFERED)

    /** One-off user-facing messages (snackbars). */
    val messages: Flow<EditorMessage> = _messages.receiveAsFlow()

    private val history = UndoHistory<Project>(MAX_UNDO)

    /** Content (updatedAt zeroed) of saves whose echo has not come back from the repository yet. */
    private val pendingSaves = ArrayDeque<Project>()

    /** Bumped by every change of the project content (edit, undo, redo, external change). */
    private var revision = 0L

    /**
     * Canvas transform per floor, kept here (not in the composable) so it survives navigating to the
     * capture screen and back. A missing entry means "fit on next layout". Compose snapshot state, so the
     * canvas redraws on pan/zoom without recomposing the rest of the screen.
     */
    private val viewTransforms = mutableStateMapOf<String, ViewTransform>()

    /** Pending animated view changes per floor, carried out (and consumed) by the canvas. */
    private val viewRequests = mutableStateMapOf<String, ViewRequest>()
    private var nextViewRequestId = 0L

    /** Floor + settings the current [EditorUiState.insights] were computed for. */
    private var insightsKey: Pair<Floor, PlanSettings>? = null
    private var insightsCache = FloorInsights()

    private var drag: DragSession? = null

    private sealed class DragSession(val base: Room) {
        class Corner(base: Room, val index: Int) : DragSession(base)

        /** [group]: the rooms moving together, [base] first (just [base] unless its whole scan moves). */
        class Move(base: Room, val group: List<Room>) : DragSession(base) {
            /** Where the rooms snap if released now. */
            var landing: SnapLanding? = null
        }
    }

    /**
     * Snapped positions of rooms moved as one rigid block ([rooms]; the room whose wall was paired comes
     * first) and the neighbour that wall now faces, if it could be named. [block] = a whole scan moved, so
     * the rooms keep their shared frame.
     */
    private class SnapLanding(val rooms: List<Room>, val block: Boolean, val neighbourId: String?, val neighbourName: String?)

    init {
        viewModelScope.launch {
            val initial = try {
                repository.get(projectId)
            } catch (e: Exception) {
                log("Could not load project $projectId", e)
                null
            }
            if (initial == null) {
                _state.update { it.copy(status = EditorStatus.MISSING) }
            } else {
                adopt(initial, selection = null)
            }
            repository.observe(projectId).collect { onRepositoryValue(it) }
        }
    }

    // ---- View transform -------------------------------------------------------------------------------

    fun viewFor(floorId: String): ViewTransform? = viewTransforms[floorId]

    fun setView(floorId: String, view: ViewTransform) {
        viewTransforms[floorId] = view
    }

    /**
     * Fit the plan of [floorId] (or of every floor) to the canvas. Floors that are already on screen glide to
     * the new view; floors without a view yet simply fit when they are first laid out.
     */
    fun requestFit(floorId: String? = null) {
        val ids = if (floorId == null) viewTransforms.keys.toList() else listOf(floorId)
        for (id in ids) {
            if (viewTransforms[id] != null) viewRequests[id] = ViewRequest.Fit(++nextViewRequestId)
        }
    }

    /** The view change the canvas of [floorId] should animate to, if any (snapshot state). */
    fun viewRequest(floorId: String): ViewRequest? = viewRequests[floorId]

    /** Marks [request] as done. A newer request for the floor is left alone. */
    fun consumeViewRequest(floorId: String, request: ViewRequest) {
        if (viewRequests[floorId] == request) viewRequests.remove(floorId)
    }

    /** The user pinched or panned: drop any pending or running view animation of [floorId]. */
    fun cancelViewRequest(floorId: String) {
        if (viewRequests.containsKey(floorId)) viewRequests.remove(floorId)
    }

    // ---- Selection & floors ------------------------------------------------------------------------------

    fun select(selection: Selection?) {
        cancelDrag()
        _state.update { s ->
            val valid = selection?.takeIf { isValid(it, s.floor) }
            s.copy(selection = valid, moveMode = s.moveMode && valid != null && valid.roomId == s.selection?.roomId)
        }
    }

    fun clearSelection() = select(null)

    /**
     * Selects [roomId] (switching floors if needed) and brings it into view, e.g. from the floor check list,
     * the rooms list or the overlap banner. On a floor that was never shown the canvas fits the floor on its
     * first layout and then focuses the room. Returns false when the room is not in the project.
     */
    fun focusRoom(roomId: String): Boolean {
        val project = _state.value.project ?: return false
        val floor = project.floors.firstOrNull { f -> f.rooms.any { it.id == roomId } } ?: return false
        if (floor.id != _state.value.floorId) selectFloor(floor.id)
        select(Selection.Room(roomId))
        val room = floor.rooms.first { it.id == roomId }
        val bounds = Bounds.of(room.corners.filter { it.isFinite() })
        if (!bounds.isEmpty) {
            viewRequests[floor.id] = ViewRequest.Focus(++nextViewRequestId, bounds.expanded(project.settings.wallThickness + FOCUS_MARGIN))
        }
        return true
    }

    /** A room another screen asked to show (e.g. tapped in the export pre-flight list): [focusRoom] or explain. */
    fun showRequestedRoom(roomId: String) {
        if (_state.value.project == null) return
        if (!focusRoom(roomId)) message("That room is no longer in this project.")
    }

    fun selectFloor(floorId: String) {
        cancelDrag()
        val s = _state.value
        val project = s.project ?: return
        val floor = project.floors.firstOrNull { it.id == floorId } ?: return
        val insights = insightsFor(floor, project.settings)
        _state.update { it.copy(floorId = floorId, selection = null, moveMode = false, insights = insights) }
    }

    fun addFloor(name: String) {
        val project = _state.value.project ?: return
        val floor = Floor(id = Ids.newId(), name = name.trim().ifEmpty { "Floor ${project.floors.size + 1}" })
        commit(project.copy(floors = project.floors + floor), floorId = floor.id, selection = null)
    }

    fun renameFloor(floorId: String, name: String) {
        val project = _state.value.project ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        commit(project.copy(floors = project.floors.map { if (it.id == floorId) it.copy(name = trimmed) else it }))
    }

    /**
     * Moves floor [floorId] [delta] places along the floor tabs (-1 = left, +1 = right), which is also the order
     * of the PDF pages and the room schedule. One undo step; nothing happens at either end.
     */
    fun moveFloor(floorId: String, delta: Int) {
        val project = _state.value.project ?: return
        val from = project.floors.indexOfFirst { it.id == floorId }
        if (from < 0 || delta == 0) return
        val to = (from + delta).coerceIn(0, project.floors.lastIndex)
        if (to == from) return
        val floors = project.floors.toMutableList()
        floors.add(to, floors.removeAt(from))
        commit(project.copy(floors = floors))
    }

    fun deleteFloor(floorId: String) {
        val project = _state.value.project ?: return
        if (project.floors.size <= 1) {
            message("A project needs at least one floor.")
            return
        }
        val index = project.floors.indexOfFirst { it.id == floorId }
        if (index < 0) return
        val floors = project.floors.filterNot { it.id == floorId }
        val nextFloor = floors[(index - 1).coerceAtLeast(0)]
        commit(project.copy(floors = floors), floorId = nextFloor.id, selection = null)
    }

    /**
     * Rotates every room of the current floor so its main walls run straight across and up the page
     * (FloorOps.straighten), keeping the rooms' layout relative to each other.
     */
    fun straightenFloor() {
        val floor = _state.value.floor ?: return
        if (floor.rooms.isEmpty()) {
            message("Add a room first, then straighten the floor.")
            return
        }
        val result = safely("straighten ${floor.name}") { tools.straighten(floor) }
        when {
            result == null -> message("Couldn't straighten this floor.")
            sameGeometry(result, floor) -> message("${floor.name} is already straight.")
            else -> commitFloorTransform(floor, result, "${floor.name} straightened.")
        }
    }

    /** Rotates the whole current floor by 90 degrees about its center ([clockwise] on screen by default). */
    fun rotateFloor(clockwise: Boolean = true) {
        val floor = _state.value.floor ?: return
        if (floor.rooms.isEmpty()) {
            message("There are no rooms to rotate on this floor yet.")
            return
        }
        val result = safely("rotate ${floor.name}") { tools.rotate(floor, if (clockwise) -PI / 2 else PI / 2) }
        if (result == null || sameGeometry(result, floor)) {
            message("Couldn't rotate this floor.")
            return
        }
        commitFloorTransform(floor, result, "${floor.name} rotated 90°.")
    }

    /**
     * Carries out the one-tap fix of a floor-check item ([FloorIssue.action]): STRAIGHTEN_FLOOR straightens the
     * shown floor (with undo); SNAP_ROOM selects the room with "Whole scan" on and snaps its scan to a
     * neighbour. Items without an action just show their room.
     */
    fun runIssueAction(issue: FloorIssue) {
        when (issue.action) {
            FloorIssueAction.STRAIGHTEN_FLOOR -> straightenFloor()
            FloorIssueAction.SNAP_ROOM -> {
                val id = issue.roomId ?: return
                if (!focusRoom(id)) return
                setWholeScan(true)
                snapToNeighbour(id)
            }
            null -> issue.roomId?.let { focusRoom(it) }
        }
    }

    // ---- Undo / redo ---------------------------------------------------------------------------------------

    fun undo() {
        cancelDrag()
        val current = _state.value.project ?: return
        val previous = history.undo(current) ?: return
        revision++
        adopt(previous, _state.value.selection)
        persist(previous)
    }

    fun redo() {
        cancelDrag()
        val current = _state.value.project ?: return
        val next = history.redo(current) ?: return
        revision++
        adopt(next, _state.value.selection)
        persist(next)
    }

    /**
     * Snackbar "Undo": reverts the edit announced with [EditorMessage.undoRevision] = [revision], but only
     * while it is still the latest change, so a late tap never undoes something the user did afterwards.
     */
    fun undoRevision(revision: Long) {
        if (revision != this.revision || !history.canUndo) {
            message("That change was followed by others. Use Undo at the top to step back.")
            return
        }
        undo()
    }

    // ---- Project -----------------------------------------------------------------------------------------

    fun renameProject(name: String, address: String) {
        val project = _state.value.project ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        commit(project.copy(name = trimmed, address = address.trim()))
    }

    /**
     * Saves the project's settings (sanitized). With [applyCeilingToEstimated], rooms whose ceiling is still the
     * OLD default estimate ([estimatedCeilingRooms]) get the new default ceiling height, in the same undo step;
     * they stay marked as estimated, and ceilings that were measured, typed or reused from another room are
     * left alone.
     */
    fun updateSettings(settings: PlanSettings, applyCeilingToEstimated: Boolean = false) {
        val project = _state.value.project ?: return
        val clean = settings.sanitized()
        val oldCeiling = project.settings.defaultCeilingHeight
        var next = project.copy(settings = clean)
        var updatedRooms = 0
        if (applyCeilingToEstimated && abs(clean.defaultCeilingHeight - oldCeiling) > 1e-9) {
            updatedRooms = estimatedCeilingRooms(project, oldCeiling)
            next = next.withEstimatedCeilings(oldCeiling, clean.defaultCeilingHeight)
        }
        val before = revision
        commit(next)
        if (updatedRooms > 0 && revision != before) {
            message(
                "Ceiling set to ${clean.formatLength(clean.defaultCeilingHeight)} (est.) in $updatedRooms " +
                    "${if (updatedRooms == 1) "room" else "rooms"}.",
                undoRevision = revision,
            )
        }
    }

    // ---- Drags -------------------------------------------------------------------------------------------

    /**
     * Starts dragging corner [index] of [roomId]; false when the room/corner does not exist. The selection
     * (and so the bottom panel) is left as it is, so the panel shows live values while dragging.
     */
    fun beginCornerDrag(roomId: String, index: Int): Boolean {
        val room = committedRoom(roomId) ?: return false
        if (index !in room.corners.indices) return false
        drag = DragSession.Corner(room, index)
        _state.update { it.copy(previewRooms = listOf(room), dragGuides = null) }
        return true
    }

    /**
     * Live corner position. With a positive [snapTolerance] (plan meters, finger-sized) the corner snaps onto
     * square / alignment guides and [EditorUiState.dragGuides] shows them. A position that would make the
     * outline invalid (self-intersecting) is not applied: the snapped spot falls back to the raw finger
     * position, and if that is invalid too the preview keeps the last valid shape.
     */
    fun updateCornerDrag(position: Vec2, snapTolerance: Double = 0.0) {
        val session = drag as? DragSession.Corner ?: return
        if (!position.isFinite()) return
        val base = session.base
        var snap = if (snapTolerance.isFinite() && snapTolerance > 0.0) {
            CornerGuides.snap(base.corners, session.index, position, snapTolerance)
        } else {
            CornerGuides.Result(position, emptyList())
        }
        var moved = RoomOps.moveCorner(base, session.index, snap.position)
        if (moved === base && snap.position != position) {
            snap = CornerGuides.Result(position, emptyList())
            moved = RoomOps.moveCorner(base, session.index, position)
        }
        if (moved === base) return
        _state.update { it.copy(previewRooms = listOf(moved), dragGuides = cornerGuides(moved, session.index, snap)) }
    }

    /**
     * Starts moving [roomId] (with its whole scan when "Whole scan" is on for it); false when it does not exist.
     * The preview shows every moving room.
     */
    fun beginMove(roomId: String): Boolean {
        val room = committedRoom(roomId) ?: return false
        val floor = floorOf(roomId) ?: return false
        val ids = movingIds(roomId)
        val group = listOf(room) + floor.rooms.filter { it.id in ids && it.id != roomId }
        drag = DragSession.Move(room, group)
        _state.update { it.copy(previewRooms = group, dragGuides = null) }
        return true
    }

    /**
     * [delta] is the total plan displacement since [beginMove]. With a positive [snapDistance] (plan meters)
     * the move looks for a neighbour to snap against and previews the landing spot in
     * [EditorUiState.dragGuides]; releasing there snaps the rooms (see [endDrag]).
     */
    fun updateMove(delta: Vec2, snapDistance: Double = 0.0) {
        val session = drag as? DragSession.Move ?: return
        if (!delta.isFinite()) return
        val t = RigidTransform2(0.0, delta)
        val moved = session.group.map { RoomOps.transform(it, t) }
        val distance = snapDistance.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(AUTO_SNAP_MAX_DISTANCE)
        val landing = distance?.let { d ->
            floorOf(session.base.id)?.let { floor ->
                landingFor(withRooms(floor, moved), listOf(session.base.id), moved.mapTo(LinkedHashSet()) { it.id }, d, AUTO_SNAP_MAX_ANGLE_DEG)
            }
        }
        session.landing = landing
        val guides = landing?.let {
            DragGuides(
                snapOutlines = it.rooms.map { r -> r.corners },
                snapTargetName = it.neighbourName,
                snapKey = "snap:${it.neighbourId.orEmpty()}",
            )
        }
        _state.update { it.copy(previewRooms = moved, dragGuides = guides) }
    }

    /**
     * Commits the drag preview as one undo step. A corner drag reshapes the room, so its (now stale) solver
     * warnings are cleared. A move of one room takes it out of its scan's shared frame; a whole-scan move keeps
     * it. A move released at a snap spot is then snapped as a second step, announced with a snackbar that
     * offers undoing just the snap.
     */
    fun endDrag() {
        val session = drag ?: return
        drag = null
        val result = _state.value.previewRooms
        _state.update { it.copy(previewRooms = emptyList(), dragGuides = null) }
        if (result.isEmpty()) return
        when (session) {
            is DragSession.Corner -> {
                val room = result.first()
                if (room != session.base) commitRooms(listOf(reshaped(session.base, room)))
            }
            is DragSession.Move -> {
                val block = session.group.size > 1
                if (result != session.group) commitRooms(if (block) result else result.map(ScanGroups::detached))
                val landing = session.landing ?: return
                val byId = result.associateBy { it.id }
                if (!landing.rooms.all { r -> byId[r.id]?.let { sameGeometry(r, it) } == true }) applySnap(landing)
            }
        }
    }

    fun cancelDrag() {
        if (drag == null && _state.value.previewRooms.isEmpty() && _state.value.dragGuides == null) return
        drag = null
        _state.update { it.copy(previewRooms = emptyList(), dragGuides = null) }
    }

    // ---- Room actions ---------------------------------------------------------------------------------

    fun toggleMoveMode() {
        _state.update { s -> if (s.selection == null) s.copy(moveMode = false) else s.copy(moveMode = !s.moveMode) }
    }

    /**
     * Switches "Whole scan" for the selected room's scan (its placement frame): moves, turns, Square to floor
     * and Snap then act on every room of the scan at once. Remembered per scan while the editor is open.
     */
    fun setWholeScan(enabled: Boolean) {
        cancelDrag()
        val s = _state.value
        val room = s.selection?.let { sel -> s.floor?.rooms?.firstOrNull { it.id == sel.roomId } } ?: return
        _state.update { it.copy(wholeScanChoices = it.wholeScanChoices + (room.placementFrame to enabled)) }
    }

    /** Renames [roomId] and sets its type, keeping its area category choice. See [updateRoomDetails]. */
    fun renameRoom(roomId: String, name: String, type: RoomType) {
        val room = committedRoom(roomId) ?: return
        updateRoomDetails(roomId, name, type, room.areaCategory)
    }

    /**
     * Sets the room's name, type and what its area counts as ([areaCategory]; null or the type's own default
     * = follow the type). Names follow [RoomNames]: a blank name becomes the type's numbered default name, and
     * when the type changes while the room still has its old default name ("Room 3", "Bedroom 2") it gets the
     * new type's default name ("Office 2"), numbered so it never duplicates another room on the floor.
     */
    fun updateRoomDetails(roomId: String, name: String, type: RoomType, areaCategory: AreaCategory?) {
        val floorRooms = floorOf(roomId)?.rooms.orEmpty()
        editRoom(roomId) { room ->
            val trimmed = name.trim()
            val finalName = when {
                trimmed.isEmpty() -> RoomNames.unique(type, floorRooms, room.id)
                type != room.type && trimmed == room.name.trim() -> nameAfterTypeChange(room.name, room.type, type, floorRooms, room.id)
                else -> trimmed
            }
            room.copy(name = finalName, type = type, areaCategory = areaCategory?.takeIf { it != type.defaultArea })
        }
    }

    /**
     * [height] null clears the ceiling height (unknown). Any height entered here is the user's own (often a tape
     * measurement): it is marked typed, so tape calibration never rescales it, and it is no longer estimated.
     */
    fun setCeilingHeight(roomId: String, height: Double?) {
        val h = height?.takeIf { it.isFinite() && it > 0 }
        editRoom(roomId) { it.copy(ceilingHeight = h, ceilingEstimated = false, ceilingTyped = h != null) }
    }

    /**
     * Turns [roomId] by [degrees] (counter-clockwise). With "Whole scan" on, every room of its scan turns as one
     * block about the scan's bounds centre and keeps the shared frame; otherwise the room turns about its own
     * centroid and leaves its scan's frame.
     */
    fun rotateRoom(roomId: String, degrees: Double) {
        if (!degrees.isFinite()) return
        val ids = movingIds(roomId)
        if (ids.size > 1) {
            val project = _state.value.project ?: return
            val floor = floorOf(roomId) ?: return
            val pivot = ScanGroups.pivot(floor.rooms.filter { it.id in ids }) ?: return
            commit(project.replaceFloor(FloorOps.transformRooms(floor, ids, RigidTransform2.rotationAround(pivot, Math.toRadians(degrees)))))
            return
        }
        editRoom(roomId) { room ->
            val pivot = Polygons.centroid(room.corners)
            if (!pivot.isFinite()) room else ScanGroups.detached(RoomOps.transform(room, RigidTransform2.rotationAround(pivot, Math.toRadians(degrees))))
        }
    }

    /**
     * "Square to floor": turns [roomId] (its whole scan when "Whole scan" is on) by the smallest angle that makes
     * its walls run the same way as the rest of the floor's ([ScanGroups.squareTurn]). The usual first step for a
     * later scan that landed at an angle; Snap can then line it up.
     */
    fun squareToFloor(roomId: String) {
        val room = committedRoom(roomId) ?: return
        val floor = floorOf(roomId) ?: return
        val ids = movingIds(roomId)
        val subject = if (ids.size > 1) "This scan" else room.name
        if (floor.rooms.none { it.id !in ids }) {
            message("Add another room to this floor first, then square ${if (ids.size > 1) "this scan" else room.name} to it.")
            return
        }
        val turn = ScanGroups.squareTurn(floor.rooms.filter { it.id in ids }, floor.rooms.filter { it.id !in ids })
        when {
            turn == null -> message("Couldn't tell which way this floor's walls run.")
            abs(turn) < SQUARE_EPS -> message("$subject already runs square with the floor.")
            else -> {
                val squared = squaredFloor(floor, ids, turn) ?: return message("Couldn't turn ${room.name}.")
                if (commitFloorRooms(squared) == EditResult.CHANGED) {
                    message("Turned ${ScanGroups.formatTurn(turn)} to line up with the floor.", undoRevision = revision)
                }
            }
        }
    }

    /**
     * Re-solves the room from its raw scan data. Squaring prefers the shared axis of the rooms scanned in the
     * same session ([floorAxisHint]), so a re-solved room comes out parallel to the rooms it was scanned with.
     */
    fun resolveRoom(roomId: String, squareAngles: Boolean) {
        val settings = _state.value.settings
        val options = SolverOptions(
            squareAngles = squareAngles,
            squareToleranceDeg = settings.squareToleranceDeg,
            axisHint = floorOf(roomId)?.let { floorAxisHint(it, roomId) },
        )
        when (editRoom(roomId) { RoomOps.resolve(it, options) }) {
            EditResult.CHANGED -> message("Room re-solved.", undoRevision = revision)
            EditResult.UNCHANGED -> message(
                if (squareAngles) "Nothing to change: the room is already as square as its measurements allow."
                else "Nothing to change.",
            )
            EditResult.NOT_FOUND -> Unit
        }
    }

    /**
     * Aligns [roomId] (its whole scan when "Whole scan" is on) against its best neighbour (FloorOps.snapGroup):
     * the closest facing walls become exactly parallel with one wall thickness between them. The usual fix for
     * rooms from a later scan session.
     *
     * When no wall is within the snap angle, the rooms are first squared to the floor ([squareToFloor]) and
     * snapped from there, as ONE undo step. When even that finds nothing but a facing wall within 45° exists,
     * the message says how far the walls are turned and to use Square to floor.
     */
    fun snapToNeighbour(roomId: String) {
        val room = committedRoom(roomId) ?: return
        val floor = floorOf(roomId) ?: return
        val ids = movingIds(roomId)
        if (floor.rooms.none { it.id !in ids }) {
            message(
                if (ids.size > 1) "Every room on this floor is part of this scan. Add a neighbour first, then snap the scan against it."
                else "Add another room to this floor first, then snap ${room.name} against it.",
            )
            return
        }
        val anchors = anchorsFor(floor, roomId, ids)
        val landing = landingFor(floor, anchors, ids, SNAP_MAX_DISTANCE, SNAP_MAX_ANGLE_DEG)
        if (landing != null) {
            if (landing.rooms.all { r -> floor.rooms.firstOrNull { it.id == r.id }?.let { sameGeometry(r, it) } == true }) {
                message(landing.neighbourName?.let { "Already snapped to $it." } ?: "Already in place.")
            } else {
                applySnap(landing)
            }
            return
        }
        // Turned too far for a snap: square the rooms to the floor, then snap from there (one step).
        val turn = ScanGroups.squareTurn(floor.rooms.filter { it.id in ids }, floor.rooms.filter { it.id !in ids })
        if (turn != null && abs(turn) >= SQUARE_EPS) {
            val squared = squaredFloor(floor, ids, turn)
            val squaredLanding = squared?.let { landingFor(it, anchors, ids, SNAP_MAX_DISTANCE, SNAP_MAX_ANGLE_DEG) }
            if (squaredLanding != null) {
                val name = squaredLanding.neighbourName
                applySnap(squaredLanding, "Turned ${ScanGroups.formatTurn(turn)} and snapped${name?.let { " to $it" } ?: " into place"}.")
                return
            }
        }
        val wide = landingFor(floor, anchors, ids, SNAP_MAX_DISTANCE, WIDE_SNAP_ANGLE_DEG)
        val wideTurn = wide?.let { l -> l.rooms.firstOrNull()?.let { moved -> floor.rooms.firstOrNull { it.id == moved.id }?.let { ScanGroups.turnBetween(it, moved) } } }
        if (wide != null && wideTurn != null && abs(wideTurn) >= Math.toRadians(1.0)) {
            message("Its walls are turned ${ScanGroups.formatTurn(wideTurn)} from ${wide.neighbourName?.let { "$it's" } ?: "its neighbour's"} — tap Square to floor.")
        } else {
            message(
                if (ids.size > 1) "No wall of another room is close enough. Move this scan next to its neighbour and try again."
                else "No wall of another room is close enough. Move ${room.name} next to its neighbour and try again.",
            )
        }
    }

    fun deleteRoom(roomId: String) {
        val project = _state.value.project ?: return
        val floor = floorOf(roomId) ?: return
        commit(project.replaceFloor(FloorOps.removeRoom(floor, roomId)), selection = null)
    }

    /**
     * Adds or replaces [opening] (matched by id) on [roomId], clamped inside its wall. An opening added here, or
     * one whose width or position the user changed, is marked [Opening.typed]: tape calibration keeps its width.
     */
    fun saveOpening(roomId: String, opening: Opening) {
        editRoom(roomId) { room ->
            if (opening.wallIndex !in room.corners.indices) return@editRoom room
            val fitted = clampOpening(room, opening)
            val existing = room.openings.firstOrNull { it.id == opening.id }
            val typed = existing == null || existing.typed || existing.wallIndex != fitted.wallIndex ||
                abs(existing.width - fitted.width) > 1e-9 || abs(existing.offset - fitted.offset) > 1e-9
            val saved = fitted.copy(typed = typed)
            room.copy(openings = if (existing != null) room.openings.map { if (it.id == opening.id) saved else it } else room.openings + saved)
        }
    }

    fun deleteOpening(roomId: String, openingId: String) {
        editRoom(roomId) { room -> room.copy(openings = room.openings.filterNot { it.id == openingId }) }
    }

    /** Trust grade of [room] for the room panel, or null when it cannot be assessed. */
    fun quality(room: Room): RoomQualityReport? =
        safely("assess ${room.name}") { tools.quality(room, _state.value.settings.unitSystem) }

    // ---- Wall actions ---------------------------------------------------------------------------------

    /**
     * Returns an error message for the dialog, or null when the length was applied. Goes through
     * [RoomOps.setWallLength], which records the wall as set by hand (kept under tape calibration).
     * Confirming the length the wall already shows moves no corner but still records it as tape-checked: that
     * is what a user does when the tape agrees with the plan, and it must protect the wall from a later
     * calibration and clear its "check with a tape" note just like a changed length does.
     */
    fun setWallLength(roomId: String, wallIndex: Int, length: Double): String? {
        if (!length.isFinite() || length <= MIN_WALL) return "The wall must be longer than ${_state.value.settings.formatLength(MIN_WALL)}."
        val room = committedRoom(roomId) ?: return null
        if (wallIndex in room.corners.indices && abs(edgeLength(room, wallIndex) - length) < 1e-6) {
            // The wall's exact current length, so RoomOps takes its "same length" branch and no corner moves.
            // CHANGED = the record was added (one undo step); UNCHANGED = it was already recorded.
            editRoom(roomId) { RoomOps.setWallLength(it, wallIndex, edgeLength(it, wallIndex)) }
            return null
        }
        return when (editRoom(roomId) { reshaped(it, RoomOps.setWallLength(it, wallIndex, length)) }) {
            EditResult.CHANGED, EditResult.NOT_FOUND -> null
            EditResult.UNCHANGED -> "That length would make the room's outline cross itself. Try moving a corner instead."
        }
    }

    /**
     * Adds a door, window or open passage centered on the wall with the project's default width (shrunk to
     * fit short walls) and default heights. Passages use the door width. Returns the new opening, or null
     * (with a message) when the wall is too short. The opening is [Opening.typed] (not marked in AR).
     */
    fun addOpening(roomId: String, wallIndex: Int, type: OpeningType): Opening? {
        val room = committedRoom(roomId) ?: return null
        if (wallIndex !in room.corners.indices) return null
        val settings = _state.value.settings
        val wallLength = edgeLength(room, wallIndex)
        val margin = 0.05
        val available = wallLength - 2 * margin
        if (available < MIN_OPENING_WIDTH) {
            message("This wall is too short for a ${type.label().lowercase()}.")
            return null
        }
        val defaultWidth = when (type) {
            OpeningType.WINDOW -> settings.defaultWindowWidth
            OpeningType.DOOR, OpeningType.OPENING -> settings.defaultDoorWidth
        }
        val width = defaultWidth.coerceIn(MIN_OPENING_WIDTH, available)
        val opening = Opening(
            id = Ids.newId(),
            type = type,
            wallIndex = wallIndex,
            offset = (wallLength - width) / 2,
            width = width,
            height = if (type == OpeningType.WINDOW) settings.defaultWindowHeight else settings.defaultDoorHeight,
            sillHeight = if (type == OpeningType.WINDOW) settings.defaultWindowSill else 0.0,
            swing = DoorSwing.LEFT_IN,
            typed = true,
        )
        editRoom(roomId) { it.copy(openings = it.openings + opening) }
        return opening
    }

    /** Splits the wall at its midpoint and selects the new corner so it can be dragged right away. */
    fun insertCorner(roomId: String, wallIndex: Int) {
        val room = committedRoom(roomId) ?: return
        if (wallIndex !in room.corners.indices) return
        val a = room.corners[wallIndex]
        val b = room.corners[(wallIndex + 1) % room.corners.size]
        val result = editRoom(roomId, selection = Selection.Corner(roomId, wallIndex + 1)) {
            reshaped(it, RoomOps.insertCorner(it, wallIndex, Vec2.lerp(a, b, 0.5)))
        }
        if (result == EditResult.UNCHANGED) message("This wall is too short to split.")
    }

    fun deleteCorner(roomId: String, index: Int) {
        val room = committedRoom(roomId) ?: return
        if (room.corners.size <= 3) {
            message("A room needs at least 3 corners.")
            return
        }
        if (editRoom(roomId, selection = Selection.Room(roomId)) { reshaped(it, RoomOps.deleteCorner(it, index)) } == EditResult.UNCHANGED) {
            message("Removing this corner would make the outline cross itself.")
        }
    }

    /** Current length of edge [wallIndex] of [roomId] (as committed), or null. */
    fun wallLength(roomId: String, wallIndex: Int): Double? {
        val room = committedRoom(roomId) ?: return null
        if (wallIndex !in room.corners.indices) return null
        return edgeLength(room, wallIndex)
    }

    /**
     * Scales the scanned rooms of the current floor so the wall measures [trueLength] (tape calibration; drawn
     * rooms and typed values keep their sizes, see FloorOps.calibrate).
     * Returns the error message to show in the dialog, or null on success.
     */
    fun calibrate(roomId: String, wallIndex: Int, trueLength: Double): String? {
        val project = _state.value.project ?: return "The project is not loaded."
        val floor = floorOf(roomId) ?: return "That room is no longer on this floor."
        val calibrated = try {
            FloorOps.calibrate(floor, roomId, wallIndex, trueLength, clock())
        } catch (e: IllegalArgumentException) {
            return e.message ?: "This length can't be used for calibration."
        }
        commit(project.replaceFloor(calibrated))
        val factor = calibrated.calibration?.factor
        message(
            if (factor != null) "Floor calibrated (total scale ×${"%.4f".format(Locale.US, factor)})." else "Floor calibrated.",
            undoRevision = revision,
        )
        return null
    }

    /**
     * Adds a [width] × [length] rectangle to the current floor, right of the existing rooms and built in the
     * floor's own wall direction ([RoomPlacement.drawingAxis]), so it is parallel to its neighbours even when
     * the plan is turned and Snap can line it up. Named with [RoomNames.unique].
     */
    fun drawRoom(type: RoomType, width: Double, length: Double) {
        val s = _state.value
        val project = s.project ?: return
        val floor = s.floor ?: return
        if (!width.isFinite() || !length.isFinite() || width <= 0 || length <= 0) return
        val room = Room(
            id = Ids.newId(),
            name = RoomNames.unique(type, floor.rooms),
            type = type,
            corners = RoomPlacement.rectangleRightOf(
                floor.rooms,
                width,
                length,
                project.settings.wallThickness,
                axisAngle = RoomPlacement.drawingAxis(floor.rooms),
            ),
            captureMethod = CaptureMethod.MANUAL,
        )
        commit(project.replaceFloor(FloorOps.upsertRoom(floor, room)), selection = Selection.Room(room.id))
        requestFit(floor.id)
    }

    // ---- Internals ---------------------------------------------------------------------------------------

    private enum class EditResult { CHANGED, UNCHANGED, NOT_FOUND }

    private fun committedRoom(roomId: String): Room? =
        _state.value.project?.floors?.firstNotNullOfOrNull { f -> f.rooms.firstOrNull { it.id == roomId } }

    private fun floorOf(roomId: String): Floor? =
        _state.value.project?.floors?.firstOrNull { f -> f.rooms.any { it.id == roomId } }

    /** Ids moved by a rigid edit of [roomId]: its whole scan when "Whole scan" is on for it, else just the room. */
    private fun movingIds(roomId: String): Set<String> {
        val s = _state.value
        return if (s.selection?.roomId == roomId && s.wholeScan) s.scanGroupIds else setOf(roomId)
    }

    /**
     * A manual edit of the outline: the solver's warnings describe the old shape (and refer to its corner
     * numbers), so they are dropped once the corners actually changed. Rigid moves keep them, since the shape
     * they describe is unchanged.
     */
    private fun reshaped(before: Room, after: Room): Room =
        if (after === before || after.corners == before.corners || after.warnings.isEmpty()) after else after.copy(warnings = emptyList())

    /** Applies [transform] to the room and commits when it changed anything. */
    private fun editRoom(
        roomId: String,
        selection: Selection? = _state.value.selection,
        transform: (Room) -> Room,
    ): EditResult {
        val project = _state.value.project ?: return EditResult.NOT_FOUND
        val floor = project.floors.firstOrNull { f -> f.rooms.any { it.id == roomId } } ?: return EditResult.NOT_FOUND
        val room = floor.rooms.first { it.id == roomId }
        val updated = transform(room)
        if (updated == room) return EditResult.UNCHANGED
        commit(project.replaceFloor(FloorOps.upsertRoom(floor, updated)), selection = selection)
        return EditResult.CHANGED
    }

    /** Replaces [rooms] (matched by id, all on one floor) as ONE undo step. */
    private fun commitRooms(rooms: List<Room>): EditResult {
        val project = _state.value.project ?: return EditResult.NOT_FOUND
        val first = rooms.firstOrNull() ?: return EditResult.UNCHANGED
        val floor = project.floors.firstOrNull { f -> f.rooms.any { it.id == first.id } } ?: return EditResult.NOT_FOUND
        val byId = rooms.associateBy { it.id }
        val updated = floor.copy(rooms = floor.rooms.map { byId[it.id] ?: it })
        if (updated == floor) return EditResult.UNCHANGED
        commit(project.replaceFloor(updated))
        return EditResult.CHANGED
    }

    /** Commits the rooms of [floor] (a modified copy of a floor of the project) as ONE undo step. */
    private fun commitFloorRooms(floor: Floor): EditResult {
        val project = _state.value.project ?: return EditResult.NOT_FOUND
        val current = project.floors.firstOrNull { it.id == floor.id } ?: return EditResult.NOT_FOUND
        val updated = current.copy(rooms = floor.rooms)
        if (updated == current) return EditResult.UNCHANGED
        commit(project.replaceFloor(updated))
        return EditResult.CHANGED
    }

    /** Commits a floor-wide rigid transform (straighten / rotate), refits the view and offers undo. */
    private fun commitFloorTransform(floor: Floor, result: Floor, text: String) {
        val project = _state.value.project ?: return
        cancelDrag()
        // Only the geometry may change; keep the floor's identity even if a tool returned something else.
        commit(project.replaceFloor(result.copy(id = floor.id, name = floor.name)))
        requestFit(floor.id)
        message(text, undoRevision = revision)
    }

    /** [floor] with [rooms] (matched by id) in place of its own. */
    private fun withRooms(floor: Floor, rooms: List<Room>): Floor {
        val byId = rooms.associateBy { it.id }
        return floor.copy(rooms = floor.rooms.map { byId[it.id] ?: it })
    }

    /** [floor] with the rooms [ids] turned by [turn] about their bounds centre; a single room leaves its frame. */
    private fun squaredFloor(floor: Floor, ids: Set<String>, turn: Double): Floor? {
        val group = floor.rooms.filter { it.id in ids }
        val pivot = ScanGroups.pivot(group) ?: return null
        val turned = FloorOps.transformRooms(floor, ids, RigidTransform2.rotationAround(pivot, turn))
        return if (ids.size > 1) turned else turned.copy(rooms = turned.rooms.map { if (it.id in ids) ScanGroups.detached(it) else it })
    }

    /**
     * Rooms of a block whose walls may pair with a neighbour, [roomId] (the one the user selected) first. A whole
     * scan can be lined up through any of its rooms (the selected one may have no neighbour at all).
     */
    private fun anchorsFor(floor: Floor, roomId: String, ids: Set<String>): List<String> =
        listOf(roomId) + floor.rooms.map { it.id }.filter { it in ids && it != roomId }

    /**
     * The snapped landing spot of the rooms [ids] (moved as one block) against their best neighbour outside the
     * block, pairing walls of the first of [anchors] that finds one; null when nothing qualifies or the tool fails.
     */
    private fun landingFor(floor: Floor, anchors: List<String>, ids: Set<String>, maxDistance: Double, maxAngleDeg: Double): SnapLanding? {
        if (floor.rooms.none { it.id !in ids }) return null
        val thickness = _state.value.settings.wallThickness
        for (anchorId in anchors) {
            val anchor = floor.rooms.firstOrNull { it.id == anchorId } ?: continue
            if (anchor.corners.size < 3) continue
            val snapped = safely("snap ${anchor.name}") { tools.snapGroup(floor, anchorId, ids, thickness, maxDistance, maxAngleDeg) } ?: continue
            val originals = listOf(anchor) + floor.rooms.filter { it.id in ids && it.id != anchorId }
            val landed = originals.map { o -> snapped.rooms.firstOrNull { it.id == o.id } }
            val valid = landed.indices.all { i ->
                val l = landed[i]
                l != null && l.corners.size == originals[i].corners.size && l.corners.all { it.isFinite() }
            }
            if (!valid) continue
            val neighbour = SnapNeighbour.find(snapped, anchorId, thickness, exclude = ids)
            return SnapLanding(landed.filterNotNull(), ids.size > 1, neighbour?.id, neighbour?.name)
        }
        return null
    }

    /** Commits a snap as one undo step and announces it ([text] or "Snapped to <neighbour>.") with Undo. */
    private fun applySnap(landing: SnapLanding, text: String? = null) {
        val rooms = if (landing.block) landing.rooms else landing.rooms.map(ScanGroups::detached)
        if (commitRooms(rooms) == EditResult.CHANGED) {
            message(text ?: landing.neighbourName?.let { "Snapped to $it." } ?: "Snapped into place.", undoRevision = revision)
        }
    }

    private fun cornerGuides(room: Room, index: Int, snap: CornerGuides.Result): DragGuides? {
        if (snap.guides.isEmpty()) return null
        val corner = room.corners.getOrNull(index) ?: return null
        val lines = snap.guides.mapNotNull { g ->
            if (g.shape !is CornerGuides.Shape.Line || g.anchorIndex == index) return@mapNotNull null
            room.corners.getOrNull(g.anchorIndex)?.let { Segment2(it, corner) }
        }
        return DragGuides(lines = lines, rightAngles = CornerGuides.rightAngles(room.corners, index), snapKey = snap.key)
    }

    private fun commit(
        next: Project,
        floorId: String? = _state.value.floorId,
        selection: Selection? = _state.value.selection,
    ) {
        val current = _state.value.project ?: return
        if (next == current) return
        history.record(current)
        revision++
        adopt(next, selection, floorId)
        persist(next)
    }

    /** Publishes [project] as the current state, keeping the floor and selection when they still exist. */
    private fun adopt(project: Project, selection: Selection?, floorId: String? = _state.value.floorId) {
        val floor = project.floors.firstOrNull { it.id == floorId } ?: project.floors.firstOrNull()
        val insights = insightsFor(floor, project.settings)
        _state.update { s ->
            val validSelection = selection?.takeIf { isValid(it, floor) }
            s.copy(
                status = EditorStatus.READY,
                project = project,
                floorId = floor?.id,
                selection = validSelection,
                moveMode = s.moveMode && validSelection != null,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                insights = insights,
            )
        }
    }

    /** Floor checks for [floor], cached until the floor or the settings change. */
    private fun insightsFor(floor: Floor?, settings: PlanSettings): FloorInsights {
        if (floor == null || floor.rooms.isEmpty()) {
            insightsKey = null
            return FloorInsights()
        }
        val key = floor to settings
        if (key == insightsKey) return insightsCache
        val ids = floor.rooms.map { it.id }.toSet()
        val overlaps = safely("check ${floor.name} for overlaps") { tools.overlaps(floor) }
            .orEmpty()
            .filter { (a, b) -> a != b && a in ids && b in ids }
        val issues = safely("check ${floor.name}") { tools.checks(floor, settings) }.orEmpty()
        insightsKey = key
        insightsCache = FloorInsights(issues = issues, overlaps = overlaps)
        return insightsCache
    }

    private fun isValid(selection: Selection, floor: Floor?): Boolean {
        val room = floor?.rooms?.firstOrNull { it.id == selection.roomId } ?: return false
        return when (selection) {
            is Selection.Room -> true
            is Selection.Wall -> selection.wallIndex in room.corners.indices
            is Selection.Corner -> selection.cornerIndex in room.corners.indices
        }
    }

    private fun persist(project: Project) {
        val key = project.contentKey()
        pendingSaves.addLast(key)
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    repository.save(project)
                } catch (e: Exception) {
                    pendingSaves.remove(key)
                    log("Saving project ${project.id} failed", e)
                    message("Couldn't save your changes (${e.message ?: e.javaClass.simpleName}). They will be retried with your next edit.")
                }
            }
        }
    }

    private fun onRepositoryValue(value: Project?) {
        val s = _state.value
        if (value == null) {
            if (s.status == EditorStatus.READY) {
                cancelDrag()
                _state.update { it.copy(status = EditorStatus.MISSING, selection = null) }
            }
            return
        }
        val key = value.contentKey()
        val echo = pendingSaves.indexOf(key)
        if (echo >= 0) {
            repeat(echo + 1) { pendingSaves.removeFirst() }
            // Our own save came back stamped with updatedAt; adopt it only if it is still what we show.
            if (s.project?.contentKey() == key) _state.update { it.copy(project = value) }
            return
        }
        val current = s.project
        when {
            current == null -> adopt(value, selection = null)
            current.contentKey() == key -> _state.update { it.copy(project = value) }
            else -> {
                // Changed elsewhere (e.g. rooms scanned on the capture screen): adopt it as an undoable step.
                cancelDrag()
                history.record(current)
                revision++
                adopt(value, s.selection)
                requestFit()
            }
        }
    }

    private fun message(text: String, undoRevision: Long? = null) {
        _messages.trySend(EditorMessage(text, undoRevision))
    }

    /** Runs a core tool; failures are logged and reported as null so the editor degrades instead of crashing. */
    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        log("Could not $what", e)
        null
    }

    private fun clampOpening(room: Room, o: Opening): Opening {
        val length = edgeLength(room, o.wallIndex)
        val width = o.width.coerceIn(0.0, length)
        return o.copy(width = width, offset = o.offset.coerceIn(0.0, (length - width).coerceAtLeast(0.0)))
    }

    class Factory(
        private val repository: ProjectRepository,
        private val projectId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlanEditorViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return PlanEditorViewModel(repository, projectId) as T
        }
    }

    companion object {
        const val MAX_UNDO = 50
        const val MIN_WALL = 0.05
        const val MIN_OPENING_WIDTH = 0.2

        /** "Snap to neighbour": the gap error and angle FloorOps.snapGroup accepts. */
        const val SNAP_MAX_DISTANCE = 0.6
        const val SNAP_MAX_ANGLE_DEG = 12.0

        /** Facing walls turned up to this much are reported ("turned N° — tap Square to floor"), never snapped. */
        const val WIDE_SNAP_ANGLE_DEG = 45.0

        /** Auto-snap when a move is released: the canvas passes a finger-sized distance, capped here. */
        const val AUTO_SNAP_MAX_DISTANCE = 0.6
        const val AUTO_SNAP_MAX_ANGLE_DEG = 12.0

        /** Turns smaller than this (radians, ~0.05°) count as "already square with the floor". */
        const val SQUARE_EPS = 1e-3

        /** Extra room around a room brought into view with [focusRoom], meters. */
        const val FOCUS_MARGIN = 1.0

        private const val TAG = "PlanEditor"
    }
}

private fun Project.contentKey(): Project = copy(updatedAt = 0L)

private fun Project.replaceFloor(floor: Floor): Project =
    copy(floors = floors.map { if (it.id == floor.id) floor else it })

/** Estimated ceilings within this distance (m) of the old default count as "still the default". */
private const val CEILING_MATCH = 0.001

/**
 * Rooms of [project] whose ceiling is still the default estimate [defaultHeight] (ceiling step skipped):
 * [Room.ceilingEstimated] and a height within 1 mm of it. Estimates reused from another room ("same as last
 * room") or measured / typed heights do not count.
 */
internal fun estimatedCeilingRooms(project: Project, defaultHeight: Double): Int =
    project.floors.sumOf { f -> f.rooms.count { it.hasDefaultCeiling(defaultHeight) } }

/** [this] with every [estimatedCeilingRooms] room's ceiling set to [newHeight] (still marked estimated). */
internal fun Project.withEstimatedCeilings(oldHeight: Double, newHeight: Double): Project {
    if (!newHeight.isFinite() || newHeight <= 0.0) return this
    return copy(
        floors = floors.map { f ->
            if (f.rooms.none { it.hasDefaultCeiling(oldHeight) }) f
            else f.copy(rooms = f.rooms.map { if (it.hasDefaultCeiling(oldHeight)) it.copy(ceilingHeight = newHeight) else it })
        },
    )
}

private fun Room.hasDefaultCeiling(defaultHeight: Double): Boolean {
    val h = ceilingHeight ?: return false
    return ceilingEstimated && !ceilingTyped && h.isFinite() && abs(h - defaultHeight) <= CEILING_MATCH
}

/**
 * Name of a room after its type changes from [oldType] to [newType]: when [currentName] is blank or still a
 * default name of the old type ("Room 3", "Bedroom 2"), the new type's numbered default name
 * ([RoomNames.unique] over [floorRooms], ignoring [roomId]); otherwise the user's own name is kept.
 */
fun nameAfterTypeChange(currentName: String, oldType: RoomType, newType: RoomType, floorRooms: List<Room>, roomId: String?): String {
    if (newType == oldType && currentName.isNotBlank()) return currentName.trim()
    return if (currentName.isBlank() || RoomNames.isDefaultName(currentName, oldType)) {
        RoomNames.unique(newType, floorRooms, roomId)
    } else {
        currentName.trim()
    }
}

/** True when both rooms have (practically) the same corners: a snap or a transform that changes nothing visible. */
internal fun sameGeometry(a: Room, b: Room, tolerance: Double = 1e-4): Boolean =
    a.corners.size == b.corners.size && a.corners.indices.all { a.corners[it].distanceTo(b.corners[it]) <= tolerance }

/** [sameGeometry] for every room of two floors (matched by id). */
internal fun sameGeometry(a: Floor, b: Floor, tolerance: Double = 1e-4): Boolean {
    if (a.rooms.size != b.rooms.size) return false
    val byId = b.rooms.associateBy { it.id }
    return a.rooms.all { r -> byId[r.id]?.let { sameGeometry(r, it, tolerance) } == true }
}

internal fun edgeLength(room: Room, index: Int): Double {
    val c = room.corners
    if (c.size < 2 || index !in c.indices) return 0.0
    return c[index].distanceTo(c[(index + 1) % c.size])
}

internal fun OpeningType.label(): String = when (this) {
    OpeningType.DOOR -> "Door"
    OpeningType.WINDOW -> "Window"
    OpeningType.OPENING -> "Opening"
}
