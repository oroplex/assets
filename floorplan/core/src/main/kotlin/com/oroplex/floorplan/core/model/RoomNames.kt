package com.oroplex.floorplan.core.model

/**
 * Default room names, shared by capture, the editor and room-type changes so every path numbers rooms the
 * same way: "Bedroom", then "Bedroom 2", "Bedroom 3", ... on the same floor.
 */
object RoomNames {
    /**
     * Default name for a room of [type] on a floor holding [floorRooms]: the type's display name ("Bedroom"),
     * or numbered ("Bedroom 2", "Bedroom 3", ...) when the floor already has a room of that type or a room
     * with that default name. Never returns a name already used on the floor (case-insensitive, trimmed).
     * The room [excludeRoomId] (the one being named or renamed) is ignored.
     */
    fun unique(type: RoomType, floorRooms: List<Room>, excludeRoomId: String? = null): String {
        val base = type.displayName
        val others = floorRooms.filter { it.id != excludeRoomId }
        val usedNames = others.map { it.name.trim().lowercase() }.toSet()
        val sameKind = others.count { it.type == type || isDefaultName(it.name, type) }
        if (sameKind == 0 && base.lowercase() !in usedNames) return base
        var n = maxOf(2, sameKind + 1)
        while ("${base.lowercase()} $n" in usedNames) n++
        return "$base $n"
    }

    /** True when [name] is a default name for [type]: its display name, optionally followed by a number. */
    fun isDefaultName(name: String, type: RoomType): Boolean {
        val base = type.displayName
        val trimmed = name.trim()
        if (trimmed.equals(base, ignoreCase = true)) return true
        if (!trimmed.startsWith("$base ", ignoreCase = true)) return false
        return trimmed.substring(base.length + 1).trim().toIntOrNull() != null
    }
}
