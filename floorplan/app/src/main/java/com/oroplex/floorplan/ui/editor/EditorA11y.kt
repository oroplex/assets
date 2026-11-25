package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.ui.common.formatArea
import com.oroplex.floorplan.ui.common.formatLength

/**
 * One accessibility action of the plan canvas (TalkBack / Switch Access "Actions" menu). The canvas maps them to
 * semantics custom actions; selection otherwise needs precise pointing, which these users cannot do.
 */
internal sealed interface CanvasAction {
    val label: String

    /** Select a room and bring it into view. */
    data class FocusRoom(override val label: String, val roomId: String) : CanvasAction

    /** Select a wall or corner of the selected room. */
    data class Select(override val label: String, val selection: Selection) : CanvasAction

    data class Clear(override val label: String = "Clear selection") : CanvasAction
}

/** "12' 0\" × 10' 6\"" (oriented length × width), or null when the room's size cannot be measured. */
internal fun roomSize(room: Room, settings: PlanSettings): String? {
    val stats = safeStats(room) ?: return null
    if (!(stats.length > 0) || !stats.length.isFinite() || !stats.width.isFinite()) return null
    return "${settings.formatLength(stats.length)} × ${settings.formatLength(stats.width)}"
}

/** "Bedroom · 12' 0\" × 10' 6\" · 126 sq ft" for room lists. */
internal fun roomSummary(room: Room, settings: PlanSettings): String {
    val parts = ArrayList<String>(3)
    parts += room.type.displayName
    roomSize(room, settings)?.let { parts += it }
    safeStats(room)?.area?.takeIf { it.isFinite() && it > 0 }?.let { parts += settings.formatArea(it) }
    return parts.joinToString(" · ")
}

/**
 * Accessibility actions of the canvas: "Select <room>" for every room (in floor order); for the selected room
 * also "Select wall n (length)" for each wall, "Select corner n" for each corner and "Clear selection".
 */
internal fun canvasActions(rooms: List<Room>, selection: Selection?, settings: PlanSettings): List<CanvasAction> {
    val out = ArrayList<CanvasAction>()
    val selected = selection?.let { s -> rooms.firstOrNull { it.id == s.roomId } }
    for (room in rooms) out += CanvasAction.FocusRoom("Select ${displayName(room)}", room.id)
    if (selected != null) {
        val n = selected.corners.size
        for (i in 0 until n) {
            val length = edgeLength(selected, i)
            val text = if (length.isFinite() && length > 0) " (${settings.formatLength(length)})" else ""
            out += CanvasAction.Select("Select wall ${i + 1}$text", Selection.Wall(selected.id, i))
        }
        for (i in 0 until n) out += CanvasAction.Select("Select corner ${i + 1}", Selection.Corner(selected.id, i))
        out += CanvasAction.Clear()
    }
    return out
}

/**
 * Spoken description of the canvas: the room count, every room with its size, and what is selected, e.g.
 * "Floor plan, 2 rooms: Kitchen 12' 0" × 10' 0"; Hall 8' 0" × 4' 0". Kitchen selected, wall 2."
 */
internal fun canvasDescription(rooms: List<Room>, selection: Selection?, settings: PlanSettings): String = buildString {
    append("Floor plan, ")
    append(if (rooms.size == 1) "1 room" else "${rooms.size} rooms")
    if (rooms.isNotEmpty()) {
        append(": ")
        append(rooms.joinToString("; ") { r -> roomSize(r, settings)?.let { "${displayName(r)} $it" } ?: displayName(r) })
    }
    append(".")
    val selected = selection?.let { s -> rooms.firstOrNull { it.id == s.roomId } } ?: return@buildString
    append(" ${displayName(selected)} selected")
    when (selection) {
        is Selection.Wall -> append(", wall ${selection.wallIndex + 1}")
        is Selection.Corner -> append(", corner ${selection.cornerIndex + 1}")
        is Selection.Room -> Unit
    }
    append(".")
}

private fun displayName(room: Room): String = room.name.replace('\n', ' ').trim().ifEmpty { room.type.displayName }
