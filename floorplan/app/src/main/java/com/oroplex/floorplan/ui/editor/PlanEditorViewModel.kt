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
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.ViewTransform
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
 * Drags (corner moves and room moves) only update [EditorUiState.preview] while the finger moves and are
 * committed as ONE undo step when it lifts. A corner drag snaps onto square / alignment guides
 * ([CornerGuides]); a room move shows where the room would snap against a neighbour and, when released
 * there, commits the snap as a second undo step offered for undo in the snackbar.
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

        class Move(base: Room) : DragSession(base) {
            /** Where the room snaps if released now. */
            var landing: SnapLanding? = null
        }
    }

    /** A snapped position of a room and the neighbour it now shares a wall with (if it could be named). */
    private class SnapLanding(val room: Room, val neighbourId: String?, val neighbourName: String?)

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
     * Selects [roomId] (switching floors if needed) and brings it into view, e.g. from the floor check list
     * or the overlap banner.
     */
    fun focusRoom(roomId: String) {
        val project = _state.value.project ?: return
        val floor = project.floors.firstOrNull { f -> f.rooms.any { it.id == roomId } } ?: return
        if (floor.id != _state.value.floorId) selectFloor(floor.id)
        select(Selection.Room(roomId))
        val room = floor.rooms.first { it.id == roomId }
        val bounds = Bounds.of(room.corners.filter { it.isFinite() })
        if (!bounds.isEmpty && viewTransforms[floor.id] != null) {
            viewRequests[floor.id] = ViewRequest.Focus(++nextViewRequestId, bounds.expanded(project.settings.wallThickness + FOCUS_MARGIN))
        }
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

    fun updateSettings(settings: PlanSettings) {
        val project = _state.value.project ?: return
        commit(project.copy(settings = settings.sanitized()))
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
        _state.update { it.copy(preview = room, dragGuides = null) }
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
        _state.update { it.copy(preview = moved, dragGuides = cornerGuides(moved, session.index, snap)) }
    }

    fun beginMove(roomId: String): Boolean {
        val room = committedRoom(roomId) ?: return false
        drag = DragSession.Move(room)
        _state.update { it.copy(preview = room, dragGuides = null) }
        return true
    }

    /**
     * [delta] is the total plan displacement since [beginMove]. With a positive [snapDistance] (plan meters)
     * the move looks for a neighbour to snap against and previews the landing spot in
     * [EditorUiState.dragGuides]; releasing there snaps the room (see [endDrag]).
     */
    fun updateMove(delta: Vec2, snapDistance: Double = 0.0) {
        val session = drag as? DragSession.Move ?: return
        if (!delta.isFinite()) return
        val moved = RoomOps.transform(session.base, RigidTransform2(0.0, delta))
        val distance = snapDistance.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(AUTO_SNAP_MAX_DISTANCE)
        val landing = distance?.let { landingFor(moved, it, AUTO_SNAP_MAX_ANGLE_DEG) }
        session.landing = landing
        val guides = landing?.let {
            DragGuides(snapOutline = it.room.corners, snapTargetName = it.neighbourName, snapKey = "snap:${it.neighbourId.orEmpty()}")
        }
        _state.update { it.copy(preview = moved, dragGuides = guides) }
    }

    /**
     * Commits the drag preview as one undo step. A corner drag reshapes the room, so its (now stale) solver
     * warnings are cleared. A move released at a snap spot is then snapped as a second step, announced with a
     * snackbar that offers undoing just the snap.
     */
    fun endDrag() {
        val session = drag ?: return
        drag = null
        val result = _state.value.preview
        _state.update { it.copy(preview = null, dragGuides = null) }
        if (result == null) return
        when (session) {
            is DragSession.Corner -> if (result != session.base) commitRoom(reshaped(session.base, result))
            is DragSession.Move -> {
                if (result != session.base) commitRoom(result)
                val landing = session.landing ?: return
                if (!sameGeometry(landing.room, result)) applySnap(landing)
            }
        }
    }

    fun cancelDrag() {
        if (drag == null && _state.value.preview == null && _state.value.dragGuides == null) return
        drag = null
        _state.update { it.copy(preview = null, dragGuides = null) }
    }

    // ---- Room actions ---------------------------------------------------------------------------------

    fun toggleMoveMode() {
        _state.update { s -> if (s.selection == null) s.copy(moveMode = false) else s.copy(moveMode = !s.moveMode) }
    }

    fun renameRoom(roomId: String, name: String, type: RoomType) {
        val trimmed = name.trim().ifEmpty { type.displayName }
        editRoom(roomId) { it.copy(name = trimmed, type = type) }
    }

    /**
     * [height] null clears the ceiling height (unknown). Any height entered here is the user's own, so the
     * room's "estimated" flag is cleared either way.
     */
    fun setCeilingHeight(roomId: String, height: Double?) {
        editRoom(roomId) { it.copy(ceilingHeight = height?.takeIf { h -> h.isFinite() && h > 0 }, ceilingEstimated = false) }
    }

    fun rotateRoom(roomId: String, degrees: Double) {
        if (!degrees.isFinite()) return
        editRoom(roomId) { room ->
            val pivot = Polygons.centroid(room.corners)
            if (!pivot.isFinite()) room else RoomOps.transform(room, RigidTransform2.rotationAround(pivot, Math.toRadians(degrees)))
        }
    }

    /**
     * Re-solves the room from its raw scan data. Squaring uses the other rooms on the floor as the preferred
     * axis, so a re-solved room comes out parallel to its neighbours when it is close to their direction.
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
     * Aligns [roomId] against its best neighbour (FloorOps.snapRoom): the closest facing walls become exactly
     * parallel with one wall thickness between them. The usual fix for a room from a later scan session.
     */
    fun snapToNeighbour(roomId: String) {
        val room = committedRoom(roomId) ?: return
        val floor = floorOf(roomId) ?: return
        if (floor.rooms.size < 2) {
            message("Add another room to this floor first, then snap ${room.name} against it.")
            return
        }
        val landing = landingFor(room, SNAP_MAX_DISTANCE, SNAP_MAX_ANGLE_DEG)
        when {
            landing == null -> message(
                "No wall of another room is close enough. Move ${room.name} next to its neighbour and try again.",
            )
            sameGeometry(landing.room, room) -> message(landing.neighbourName?.let { "Already snapped to $it." } ?: "Already in place.")
            else -> applySnap(landing)
        }
    }

    fun deleteRoom(roomId: String) {
        val project = _state.value.project ?: return
        val floor = floorOf(roomId) ?: return
        commit(project.replaceFloor(FloorOps.removeRoom(floor, roomId)), selection = null)
    }

    /** Adds or replaces [opening] (matched by id) on [roomId], clamped inside its wall. */
    fun saveOpening(roomId: String, opening: Opening) {
        editRoom(roomId) { room ->
            if (opening.wallIndex !in room.corners.indices) return@editRoom room
            val fitted = clampOpening(room, opening)
            val exists = room.openings.any { it.id == opening.id }
            room.copy(openings = if (exists) room.openings.map { if (it.id == opening.id) fitted else it } else room.openings + fitted)
        }
    }

    fun deleteOpening(roomId: String, openingId: String) {
        editRoom(roomId) { room -> room.copy(openings = room.openings.filterNot { it.id == openingId }) }
    }

    /** Trust grade of [room] for the room panel, or null when it cannot be assessed. */
    fun quality(room: Room): RoomQualityReport? = safely("assess ${room.name}") { tools.quality(room) }

    // ---- Wall actions ---------------------------------------------------------------------------------

    /** Returns an error message for the dialog, or null when the length was applied. */
    fun setWallLength(roomId: String, wallIndex: Int, length: Double): String? {
        if (!length.isFinite() || length <= MIN_WALL) return "The wall must be longer than ${_state.value.settings.formatLength(MIN_WALL)}."
        val room = committedRoom(roomId) ?: return null
        if (wallIndex in room.corners.indices && abs(edgeLength(room, wallIndex) - length) < 1e-6) return null
        return when (editRoom(roomId) { reshaped(it, RoomOps.setWallLength(it, wallIndex, length)) }) {
            EditResult.CHANGED, EditResult.NOT_FOUND -> null
            EditResult.UNCHANGED -> "That length would make the room's outline cross itself. Try moving a corner instead."
        }
    }

    /**
     * Adds a door, window or open passage centered on the wall with the project's default width (shrunk to
     * fit short walls) and default heights. Passages use the door width. Returns the new opening, or null
     * (with a message) when the wall is too short.
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
     * Scales the whole current floor so the wall measures [trueLength] (tape calibration).
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

    /** Adds a [width] (x) × [length] (y) rectangle to the current floor, right of the existing rooms. */
    fun drawRoom(type: RoomType, width: Double, length: Double) {
        val s = _state.value
        val project = s.project ?: return
        val floor = s.floor ?: return
        if (!width.isFinite() || !length.isFinite() || width <= 0 || length <= 0) return
        val room = Room(
            id = Ids.newId(),
            name = RoomPlacement.uniqueName(type, floor.rooms),
            type = type,
            corners = RoomPlacement.rectangleRightOf(floor.rooms, width, length, project.settings.wallThickness),
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

    private fun commitRoom(room: Room) {
        editRoom(room.id) { room }
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

    /**
     * The snapped landing spot of [room] against its best neighbour on its floor (with [room] in place of the
     * committed version), or null when nothing qualifies or the tool fails.
     */
    private fun landingFor(room: Room, maxDistance: Double, maxAngleDeg: Double): SnapLanding? {
        val floor = floorOf(room.id) ?: return null
        if (floor.rooms.size < 2 || room.corners.size < 3) return null
        val thickness = _state.value.settings.wallThickness
        val withRoom = FloorOps.upsertRoom(floor, room)
        val snapped = safely("snap ${room.name}") { tools.snapRoom(withRoom, room.id, thickness, maxDistance, maxAngleDeg) } ?: return null
        val landed = snapped.rooms.firstOrNull { it.id == room.id } ?: return null
        if (landed.corners.size != room.corners.size || landed.corners.any { !it.isFinite() }) return null
        val neighbour = SnapNeighbour.find(FloorOps.upsertRoom(floor, landed), room.id, thickness)
        return SnapLanding(landed, neighbour?.id, neighbour?.name)
    }

    private fun applySnap(landing: SnapLanding) {
        if (editRoom(landing.room.id) { landing.room } == EditResult.CHANGED) {
            message(landing.neighbourName?.let { "Snapped to $it." } ?: "Snapped into place.", undoRevision = revision)
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

        /** "Snap to neighbour": the gap error and angle FloorOps.snapRoom accepts by default. */
        const val SNAP_MAX_DISTANCE = 0.6
        const val SNAP_MAX_ANGLE_DEG = 12.0

        /** Auto-snap when a move is released: the canvas passes a finger-sized distance, capped here. */
        const val AUTO_SNAP_MAX_DISTANCE = 0.6
        const val AUTO_SNAP_MAX_ANGLE_DEG = 12.0

        /** Extra room around a room brought into view with [focusRoom], meters. */
        const val FOCUS_MARGIN = 1.0

        private const val TAG = "PlanEditor"
    }
}

private fun Project.contentKey(): Project = copy(updatedAt = 0L)

private fun Project.replaceFloor(floor: Floor): Project =
    copy(floors = floors.map { if (it.id == floor.id) floor else it })

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
