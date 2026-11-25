package com.oroplex.floorplan.core.measure

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.AreaCategory
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MeasurementsTest {
    private val rect = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0))
    private val lShape = listOf(
        Vec2(0.0, 0.0), Vec2(6.0, 0.0), Vec2(6.0, 3.0), Vec2(3.0, 3.0), Vec2(3.0, 5.0), Vec2(0.0, 5.0),
    )

    private fun room(corners: List<Vec2>, ceiling: Double? = null, openings: List<Opening> = emptyList()) =
        Room(id = "r", name = "R", corners = corners, ceilingHeight = ceiling, openings = openings)

    @Test fun rectangleStats() {
        val door = Opening("d", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.9, height = 2.0)
        val window = Opening("w", OpeningType.WINDOW, wallIndex = 1, offset = 0.5, width = 1.2, height = 1.0, sillHeight = 1.0)
        val s = Measurements.room(room(rect, 2.5, listOf(door, window)))
        assertEquals(12.0, s.area, 1e-12)
        assertEquals(14.0, s.perimeter, 1e-12)
        assertEquals(4.0, s.length, 1e-12)
        assertEquals(3.0, s.width, 1e-12)
        assertEquals(listOf(4.0, 3.0, 4.0, 3.0), s.wallLengths)
        assertEquals(2.5, s.ceilingHeight)
        assertEquals(30.0, assertNotNull(s.volume), 1e-12)
        assertEquals(14.0 * 2.5 - 0.9 * 2.0 - 1.2 * 1.0, assertNotNull(s.wallArea), 1e-12)
    }

    @Test fun lShapeStats() {
        val s = Measurements.room(room(lShape, 2.4))
        assertEquals(24.0, s.area, 1e-12)
        assertEquals(22.0, s.perimeter, 1e-12)
        assertEquals(6.0, s.length, 1e-12)
        assertEquals(5.0, s.width, 1e-12)
        assertEquals(listOf(6.0, 3.0, 3.0, 2.0, 3.0, 5.0), s.wallLengths)
        assertEquals(24.0 * 2.4, assertNotNull(s.volume), 1e-12)
        assertEquals(22.0 * 2.4, assertNotNull(s.wallArea), 1e-12)
    }

    @Test fun rotatedRoomReportsTrueLengthAndWidth() {
        val rotated = rect.map { it.rotated(PI / 7) + Vec2(3.0, -8.0) }
        val s = Measurements.room(room(rotated))
        assertEquals(4.0, s.length, 1e-9)
        assertEquals(3.0, s.width, 1e-9)
        assertEquals(12.0, s.area, 1e-9)
    }

    @Test fun clockwiseOutlineStillHasPositiveArea() {
        assertEquals(12.0, Measurements.room(room(rect.reversed())).area, 1e-12)
    }

    @Test fun withoutCeilingVolumeAndWallAreaAreUnknown() {
        for (ceiling in listOf(null, 0.0, -1.0, Double.NaN)) {
            val s = Measurements.room(room(rect, ceiling))
            assertNull(s.ceilingHeight, "ceiling $ceiling")
            assertNull(s.volume, "ceiling $ceiling")
            assertNull(s.wallArea, "ceiling $ceiling")
        }
    }

    @Test fun badOpeningsNeverMakeWallAreaNegative() {
        val huge = Opening("x", OpeningType.OPENING, wallIndex = 0, offset = 0.0, width = 100.0, height = 50.0)
        val stray = Opening("y", OpeningType.DOOR, wallIndex = 9, offset = 0.0, width = 1.0, height = 2.0)
        val s = Measurements.room(room(rect, 2.5, listOf(huge, stray)))
        assertEquals(0.0, assertNotNull(s.wallArea), 1e-12)
        // An opening taller than the wall above its sill only removes what is there.
        val tall = Opening("t", OpeningType.WINDOW, wallIndex = 0, offset = 0.0, width = 1.0, height = 3.0, sillHeight = 1.0)
        assertEquals(35.0 - 1.5, assertNotNull(Measurements.room(room(rect, 2.5, listOf(tall))).wallArea), 1e-12)
    }

    @Test fun degenerateRoomsGiveZeros() {
        val empty = Measurements.room(room(emptyList(), 2.4))
        assertEquals(0.0, empty.area)
        assertEquals(0.0, empty.perimeter)
        assertEquals(0.0, empty.length)
        assertEquals(emptyList(), empty.wallLengths)
        assertEquals(0.0, assertNotNull(empty.volume))
        val line = Measurements.room(room(listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0))))
        assertEquals(0.0, line.area)
        assertEquals(0.0, line.perimeter)
        val withNaN = Measurements.room(room(rect + Vec2(Double.NaN, 1.0)))
        assertEquals(12.0, withNaN.area, 1e-12)
    }

    @Test fun floorAndProjectAreas() {
        val f1 = Floor("f1", "Ground", rooms = listOf(room(rect), room(lShape).copy(id = "l")))
        val f2 = Floor("f2", "Upper", rooms = listOf(room(rect)))
        assertEquals(36.0, Measurements.floorArea(f1), 1e-12)
        assertEquals(0.0, Measurements.floorArea(Floor("e", "Empty")), 0.0)
        val p = Project(id = "p", name = "House", createdAt = 0L, updatedAt = 0L, floors = listOf(f1, f2))
        assertEquals(48.0, Measurements.projectArea(p), 1e-12)
    }

    @Test fun areaSummarySplitsLivingFromGarageAndOutdoor() {
        // A ranch house: living rooms, an attached garage (non-living by type), a porch (outdoor), a laundry the user
        // excluded by hand, and storage the user counts as living.
        val living = room(rect).copy(id = "lv", type = RoomType.LIVING) // 12
        val bed = room(rect).copy(id = "bd", type = RoomType.BEDROOM) // 12
        val garage = room(lShape).copy(id = "g", type = RoomType.GARAGE) // 24
        val porch = room(listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0), Vec2(2.0, 1.5), Vec2(0.0, 1.5))).copy(id = "p", type = RoomType.PORCH) // 3
        val laundry = room(rect).copy(id = "la", type = RoomType.LAUNDRY, areaCategory = AreaCategory.EXCLUDED) // 12
        val finished = room(rect).copy(id = "st", type = RoomType.STORAGE, areaCategory = AreaCategory.LIVING) // 12
        val s = Measurements.areaSummary(listOf(living, bed, garage, porch, laundry, finished))
        assertEquals(36.0, s.living, 1e-12)
        assertEquals(24.0, s.nonLiving, 1e-12)
        assertEquals(3.0, s.exterior, 1e-12)
        assertEquals(12.0, s.excluded, 1e-12)
        assertEquals(60.0, s.enclosed, 1e-12, "living + garage & unfinished; outdoor and excluded rooms are not enclosed area")
        // Outdoor and unfinished types default to their categories.
        for (t in listOf(RoomType.PORCH, RoomType.DECK, RoomType.BALCONY, RoomType.PATIO)) assertEquals(AreaCategory.EXTERIOR, t.defaultArea)
        for (t in listOf(RoomType.GARAGE, RoomType.STORAGE, RoomType.UTILITY, RoomType.WORKSHOP, RoomType.BASEMENT)) {
            assertEquals(AreaCategory.NON_LIVING, t.defaultArea)
        }
        assertEquals(AreaCategory.LIVING, RoomType.BEDROOM.defaultArea)
        // Floors and projects add up; broken rooms count as nothing and never crash.
        val broken = room(listOf(Vec2(Double.NaN, 0.0), Vec2(1.0, 1.0))).copy(id = "x")
        val f1 = Floor("f1", "Main", rooms = listOf(living, garage, broken))
        val f2 = Floor("f2", "Upper", rooms = listOf(bed, porch))
        val p = Project(id = "p", name = "House", createdAt = 0L, updatedAt = 0L, floors = listOf(f1, f2, Floor("e", "Empty")))
        assertEquals(AreaSummary(24.0, 24.0, 3.0, 0.0), Measurements.areaSummary(p))
        assertEquals(AreaSummary.ZERO, Measurements.areaSummary(emptyList()))
        val total = Measurements.areaSummary(p).let { it.enclosed + it.exterior + it.excluded }
        assertEquals(Measurements.projectArea(p), total, 1e-12)
    }
}
