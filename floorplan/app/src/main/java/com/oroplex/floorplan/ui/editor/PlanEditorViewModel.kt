package com.oroplex.floorplan.ui.editor

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
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
 * committed as ONE undo step when it lifts.
 */
class PlanEditorViewModel(
    private val repository: ProjectRepository,
    val projectId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off user-facing messages (snackbars). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val history = UndoHistory<Project>(MAX_UNDO)

    /** Content (updatedAt zeroed) of saves whose echo has not come back from the repository yet. */
    private val pendingSaves = ArrayDeque<Project>()

    /**
     * Canvas transform per floor, kept here (not in the composable) so it survives navigating to the
     * capture screen and back. A missing entry means "fit on next layout". Compose snapshot state, so the
     * canvas redraws on pan/zoom without recomposing the rest of the screen.
     */
    private val viewTransforms = mutableStateMapOf<String, ViewTransform>()

    private var drag: DragSession? = null

    private sealed interface DragSession {
        val base: Room

        data class Corner(override val base: Room, val index: Int) : DragSession
        data class Move(override val base: Room) : DragSession
    }

    init {
        viewModelScope.launch {
            val initial = try {
                repository.get(projectId)
            } catch (e: Exception) {
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

    /** Forget the transform of [floorId] (or of every floor) so the canvas fits the plan again. */
    fun requestFit(floorId: String? = null) {
        if (floorId == null) viewTransforms.clear() else viewTransforms.remove(floorId)
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

    fun selectFloor(floorId: String) {
        cancelDrag()
        _state.update { s ->
            if (s.project?.floors?.any { it.id == floorId } != true) s
            else s.copy(floorId = floorId, selection = null, moveMode = false)
        }
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

    // ---- Undo / redo ---------------------------------------------------------------------------------------

    fun undo() {
        cancelDrag()
        val current = _state.value.project ?: return
        val previous = history.undo(current) ?: return
        adopt(previous, _state.value.selection)
        persist(previous)
    }

    fun redo() {
        cancelDrag()
        val current = _state.value.project ?: return
        val next = history.redo(current) ?: return
        adopt(next, _state.value.selection)
        persist(next)
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
        _state.update { it.copy(preview = room) }
        return true
    }

    /**
     * Live corner position. A position that would make the outline invalid (self-intersecting) is not
     * applied; the preview keeps the last valid shape.
     */
    fun updateCornerDrag(position: Vec2) {
        val session = drag as? DragSession.Corner ?: return
        val moved = RoomOps.moveCorner(session.base, session.index, position)
        val rejected = moved == session.base && position != session.base.corners[session.index]
        if (!rejected) _state.update { it.copy(preview = moved) }
    }

    fun beginMove(roomId: String): Boolean {
        val room = committedRoom(roomId) ?: return false
        drag = DragSession.Move(room)
        _state.update { it.copy(preview = room) }
        return true
    }

    /** [delta] is the total plan displacement since [beginMove]. */
    fun updateMove(delta: Vec2) {
        val session = drag as? DragSession.Move ?: return
        if (!delta.isFinite()) return
        _state.update { it.copy(preview = RoomOps.transform(session.base, RigidTransform2(0.0, delta))) }
    }

    /** Commits the drag preview as one undo step. */
    fun endDrag() {
        val session = drag ?: return
        drag = null
        val result = _state.value.preview
        _state.update { it.copy(preview = null) }
        if (result != null && result != session.base) commitRoom(result)
    }

    fun cancelDrag() {
        if (drag == null && _state.value.preview == null) return
        drag = null
        _state.update { it.copy(preview = null) }
    }

    // ---- Room actions ---------------------------------------------------------------------------------

    fun toggleMoveMode() {
        _state.update { s -> if (s.selection == null) s.copy(moveMode = false) else s.copy(moveMode = !s.moveMode) }
    }

    fun renameRoom(roomId: String, name: String, type: RoomType) {
        val trimmed = name.trim().ifEmpty { type.displayName }
        editRoom(roomId) { it.copy(name = trimmed, type = type) }
    }

    /** [height] null clears the ceiling height (unknown). */
    fun setCeilingHeight(roomId: String, height: Double?) {
        editRoom(roomId) { it.copy(ceilingHeight = height?.takeIf { h -> h.isFinite() && h > 0 }) }
    }

    fun rotateRoom(roomId: String, degrees: Double) {
        editRoom(roomId) { room ->
            val pivot = Polygons.centroid(room.corners)
            RoomOps.transform(room, RigidTransform2.rotationAround(pivot, Math.toRadians(degrees)))
        }
    }

    fun resolveRoom(roomId: String, squareAngles: Boolean) {
        val settings = _state.value.settings
        val options = SolverOptions(squareAngles = squareAngles, squareToleranceDeg = settings.squareToleranceDeg)
        when (editRoom(roomId) { RoomOps.resolve(it, options) }) {
            EditResult.CHANGED -> message("Room re-solved.")
            EditResult.UNCHANGED -> message(
                if (squareAngles) "Nothing to change: the room is already as square as its measurements allow."
                else "Nothing to change.",
            )
            EditResult.NOT_FOUND -> Unit
        }
    }

    fun deleteRoom(roomId: String) {
        val project = _state.value.project ?: return
        val floor = project.floors.firstOrNull { f -> f.rooms.any { it.id == roomId } } ?: return
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

    // ---- Wall actions ---------------------------------------------------------------------------------

    /** Returns an error message for the dialog, or null when the length was applied. */
    fun setWallLength(roomId: String, wallIndex: Int, length: Double): String? {
        if (!length.isFinite() || length <= MIN_WALL) return "The wall must be longer than ${_state.value.settings.formatLength(MIN_WALL)}."
        return when (editRoom(roomId) { RoomOps.setWallLength(it, wallIndex, length) }) {
            EditResult.CHANGED, EditResult.NOT_FOUND -> null
            EditResult.UNCHANGED -> {
                val room = committedRoom(roomId)
                if (room != null && abs(edgeLength(room, wallIndex) - length) < 1e-6) null
                else "That length would make the room's outline cross itself. Try moving a corner instead."
            }
        }
    }

    /**
     * Adds a door or window centered on the wall with the project's default sizes (shrunk to fit short walls).
     * Returns the new opening, or null (with a message) when the wall is too short.
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
            OpeningType.DOOR -> DEFAULT_DOOR_WIDTH
            OpeningType.WINDOW -> DEFAULT_WINDOW_WIDTH
            OpeningType.OPENING -> DEFAULT_PASSAGE_WIDTH
        }
        val width = defaultWidth.coerceAtMost(available)
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
            RoomOps.insertCorner(it, wallIndex, Vec2.lerp(a, b, 0.5))
        }
        if (result == EditResult.UNCHANGED) message("This wall is too short to split.")
    }

    fun deleteCorner(roomId: String, index: Int) {
        val room = committedRoom(roomId) ?: return
        if (room.corners.size <= 3) {
            message("A room needs at least 3 corners.")
            return
        }
        if (editRoom(roomId, selection = Selection.Room(roomId)) { RoomOps.deleteCorner(it, index) } == EditResult.UNCHANGED) {
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
        val floor = project.floors.firstOrNull { f -> f.rooms.any { it.id == roomId } } ?: return "That room is no longer on this floor."
        val calibrated = try {
            FloorOps.calibrate(floor, roomId, wallIndex, trueLength, clock())
        } catch (e: IllegalArgumentException) {
            return e.message ?: "This length can't be used for calibration."
        }
        commit(project.replaceFloor(calibrated))
        val factor = calibrated.calibration?.factor
        message(if (factor != null) "Floor calibrated (total scale ×${"%.4f".format(Locale.US, factor)})." else "Floor calibrated.")
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

    private fun commit(
        next: Project,
        floorId: String? = _state.value.floorId,
        selection: Selection? = _state.value.selection,
    ) {
        val current = _state.value.project ?: return
        if (next == current) return
        history.record(current)
        adopt(next, selection, floorId)
        persist(next)
    }

    /** Publishes [project] as the current state, keeping the floor and selection when they still exist. */
    private fun adopt(project: Project, selection: Selection?, floorId: String? = _state.value.floorId) {
        _state.update { s ->
            val floor = project.floors.firstOrNull { it.id == floorId } ?: project.floors.firstOrNull()
            val validSelection = selection?.takeIf { isValid(it, floor) }
            s.copy(
                status = EditorStatus.READY,
                project = project,
                floorId = floor?.id,
                selection = validSelection,
                moveMode = s.moveMode && validSelection != null,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
            )
        }
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
                adopt(value, s.selection)
                requestFit()
            }
        }
    }

    private fun message(text: String) {
        _messages.trySend(text)
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
        const val DEFAULT_DOOR_WIDTH = 0.8128 // 32"
        const val DEFAULT_WINDOW_WIDTH = 0.9144 // 36"
        const val DEFAULT_PASSAGE_WIDTH = 0.9144 // 36"
    }
}

private fun Project.contentKey(): Project = copy(updatedAt = 0L)

private fun Project.replaceFloor(floor: Floor): Project =
    copy(floors = floors.map { if (it.id == floor.id) floor else it })

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
