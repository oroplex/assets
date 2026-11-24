package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room

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
) {
    val floor: Floor? get() = project?.floors?.firstOrNull { it.id == floorId }
    val settings: PlanSettings get() = project?.settings ?: PlanSettings()

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
