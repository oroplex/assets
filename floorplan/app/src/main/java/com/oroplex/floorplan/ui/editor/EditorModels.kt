package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.IssueSeverity

/** What the user has selected on the canvas. Every selection belongs to one room. */
sealed interface Selection {
    val roomId: String

    data class Room(override val roomId: String) : Selection

    /** Edge [wallIndex] = corners[wallIndex] -> corners[wallIndex + 1]. */
    data class Wall(override val roomId: String, val wallIndex: Int) : Selection

    data class Corner(override val roomId: String, val cornerIndex: Int) : Selection
}

enum class EditorStatus { LOADING, READY, MISSING }

data class EditorUiState(
    val status: EditorStatus = EditorStatus.LOADING,
    val project: Project? = null,
    /** Always the id of an existing floor once READY (or null for a project without floors). */
    val floorId: String? = null,
    val selection: Selection? = null,
    /** One-finger drags translate the selected room instead of panning. */
    val moveMode: Boolean = false,
    /** Live result of an in-progress corner drag / move, drawn instead of the committed room. */
    val preview: Room? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Checks of the current floor (as committed), recomputed whenever the floor or the settings change. */
    val insights: FloorInsights = FloorInsights(),
    /** Snap guides of the drag in progress, or null when nothing is being dragged / snapped. */
    val dragGuides: DragGuides? = null,
) {
    val floor: Floor? get() = project?.floors?.firstOrNull { it.id == floorId }
    val settings: PlanSettings get() = project?.settings ?: PlanSettings()

    /**
     * Rooms drawn with a warning outline: every room of an overlapping pair. While a room is being dragged,
     * pairs involving it are left out (their overlap is re-checked when the finger lifts) so the highlight
     * never lags behind the moving room.
     */
    val highlightRoomIds: Set<String>
        get() {
            val moving = preview?.id
            return insights.overlaps
                .filter { moving == null || (it.first != moving && it.second != moving) }
                .flatMap { listOf(it.first, it.second) }
                .toSet()
        }

    /** Rooms to draw: the committed rooms with the live drag preview substituted. */
    val displayRooms: List<Room>
        get() {
            val rooms = floor?.rooms ?: return emptyList()
            val p = preview ?: return rooms
            return rooms.map { if (it.id == p.id) p else it }
        }

    /** The selected room as currently displayed (preview while dragging). */
    val selectedRoom: Room?
        get() = selection?.let { s -> displayRooms.firstOrNull { it.id == s.roomId } }
}

/** Results of the floor checks shown in the editor (overlap banner, "Floor check" sheet and its badge). */
data class FloorInsights(
    /** FloorChecks.check results, most severe first. */
    val issues: List<FloorIssue> = emptyList(),
    /** Pairs of room ids whose interiors overlap. */
    val overlaps: List<Pair<String, String>> = emptyList(),
) {
    /** Issues that need the user's attention (errors and warnings, not tips). */
    val attentionCount: Int get() = issues.count { it.severity != IssueSeverity.INFO }
}

/** A right-angle marker drawn at [vertex], with unit vectors [a] and [b] along its two walls. */
data class RightAngleMark(val vertex: Vec2, val a: Vec2, val b: Vec2)

/** Visual hints shown while a corner or a room is dragged. All geometry in plan meters. */
data class DragGuides(
    /** Alignment guides the dragged corner snapped to, each from the corner it is aligned with to the dragged corner. */
    val lines: List<Segment2> = emptyList(),
    /** Corners that are exactly square after the snap. */
    val rightAngles: List<RightAngleMark> = emptyList(),
    /** Where the moved room lands if the finger lifts now (auto-snap preview), or null. */
    val snapOutline: List<Vec2>? = null,
    /** Name of the room the moved room would snap to. */
    val snapTargetName: String? = null,
    /** Identifies what the drag is snapped to; changes whenever it snaps onto something new (haptic tick). */
    val snapKey: String? = null,
)

/**
 * One-off snackbar message. When [undoRevision] is set the snackbar offers "Undo", which reverts exactly the
 * edit that produced the message (see [PlanEditorViewModel.undoRevision]).
 */
data class EditorMessage(val text: String, val undoRevision: Long? = null)

/** A request to move the canvas view of one floor, carried out (animated) by the canvas. */
sealed interface ViewRequest {
    /** Unique per request, so asking twice for the same thing still restarts the animation. */
    val id: Long

    /** Fit the whole floor. */
    data class Fit(override val id: Long) : ViewRequest

    /** Bring [bounds] (plan meters) into view, zooming out if needed but never zooming in. */
    data class Focus(override val id: Long, val bounds: Bounds) : ViewRequest
}

/**
 * Bounded snapshot undo/redo history. [record] stores the state BEFORE an edit and clears the redo
 * stack; the oldest snapshot is dropped beyond [limit].
 */
class UndoHistory<T>(private val limit: Int = 50) {
    private val undoStack = ArrayDeque<T>()
    private val redoStack = ArrayDeque<T>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun record(previous: T) {
        push(undoStack, previous)
        redoStack.clear()
    }

    /** Returns the state to restore, remembering [current] for redo; null when there is nothing to undo. */
    fun undo(current: T): T? {
        val previous = undoStack.removeLastOrNull() ?: return null
        push(redoStack, current)
        return previous
    }

    /** Returns the state to restore, remembering [current] for undo; null when there is nothing to redo. */
    fun redo(current: T): T? {
        val next = redoStack.removeLastOrNull() ?: return null
        push(undoStack, current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun push(stack: ArrayDeque<T>, value: T) {
        stack.addLast(value)
        while (stack.size > limit) stack.removeFirst()
    }
}
