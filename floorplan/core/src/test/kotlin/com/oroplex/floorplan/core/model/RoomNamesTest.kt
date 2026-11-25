package com.oroplex.floorplan.core.model

import com.oroplex.floorplan.core.geometry.Vec2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomNamesTest {
    private val square = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))

    private fun room(id: String, name: String, type: RoomType = RoomType.OTHER) = Room(id = id, name = name, type = type, corners = square)

    @Test fun firstRoomOfATypeGetsThePlainName() {
        assertEquals("Bedroom", RoomNames.unique(RoomType.BEDROOM, emptyList()))
        assertEquals("Kitchen", RoomNames.unique(RoomType.KITCHEN, listOf(room("a", "Bedroom", RoomType.BEDROOM))))
        assertEquals("Room", RoomNames.unique(RoomType.OTHER, emptyList()))
    }

    @Test fun laterRoomsAreNumberedFromTwo() {
        val one = listOf(room("a", "Bedroom", RoomType.BEDROOM))
        assertEquals("Bedroom 2", RoomNames.unique(RoomType.BEDROOM, one))
        val two = one + room("b", "Bedroom 2", RoomType.BEDROOM)
        assertEquals("Bedroom 3", RoomNames.unique(RoomType.BEDROOM, two))
        // A renamed bedroom still counts as a bedroom ("Kids room" is the second bedroom).
        assertEquals("Bedroom 2", RoomNames.unique(RoomType.BEDROOM, listOf(room("k", "Kids room", RoomType.BEDROOM))))
        // A room named like the default (but of another type) counts too, so the names never clash.
        assertEquals("Office 2", RoomNames.unique(RoomType.OFFICE, listOf(room("x", "Office"))))
    }

    @Test fun neverReturnsANameAlreadyOnTheFloor() {
        // "Bedroom 3" is taken by a renamed room; the next free number is used (case and spaces ignored).
        val rooms = listOf(room("a", "Bedroom", RoomType.BEDROOM), room("b", "  bedroom 2 ", RoomType.BEDROOM), room("c", "BEDROOM 3"))
        val name = RoomNames.unique(RoomType.BEDROOM, rooms)
        assertEquals("Bedroom 4", name)
        assertTrue(rooms.none { it.name.trim().equals(name, ignoreCase = true) })
        // Many rooms: still unique, numbered after the last one.
        val many = (1..30).map { room("r$it", if (it == 1) "Bathroom" else "Bathroom $it", RoomType.BATHROOM) }
        val next = RoomNames.unique(RoomType.BATHROOM, many)
        assertEquals("Bathroom 31", next)
        assertTrue(many.none { it.name.equals(next, ignoreCase = true) }, next)
    }

    @Test fun theRoomBeingRenamedIsIgnored() {
        val rooms = listOf(room("a", "Bedroom", RoomType.BEDROOM), room("b", "Office", RoomType.OFFICE))
        // Changing the office into a bedroom: it becomes the second bedroom.
        assertEquals("Bedroom 2", RoomNames.unique(RoomType.BEDROOM, rooms, excludeRoomId = "b"))
        // Re-naming the only bedroom keeps the plain name.
        assertEquals("Bedroom", RoomNames.unique(RoomType.BEDROOM, rooms, excludeRoomId = "a"))
    }

    @Test fun defaultNamesAreRecognised() {
        assertTrue(RoomNames.isDefaultName("Bedroom", RoomType.BEDROOM))
        assertTrue(RoomNames.isDefaultName("bedroom 12", RoomType.BEDROOM))
        assertTrue(RoomNames.isDefaultName("  Bedroom 2  ", RoomType.BEDROOM))
        assertTrue(RoomNames.isDefaultName("Primary Bedroom", RoomType.PRIMARY_BEDROOM))
        assertFalse(RoomNames.isDefaultName("Primary Bedroom", RoomType.BEDROOM))
        assertFalse(RoomNames.isDefaultName("Bedroom upstairs", RoomType.BEDROOM))
        assertFalse(RoomNames.isDefaultName("Bedrooms", RoomType.BEDROOM))
        assertFalse(RoomNames.isDefaultName("", RoomType.BEDROOM))
        assertTrue(RoomNames.isDefaultName("Room 3", RoomType.OTHER))
    }
}
