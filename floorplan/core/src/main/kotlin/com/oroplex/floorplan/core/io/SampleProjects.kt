package com.oroplex.floorplan.core.io

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import java.util.UUID

object SampleProjects {
    private const val M_PER_IN = 0.0254

    /** Interior partition: 2x4 studs + drywall = 4.5" (the default PlanSettings wall thickness). */
    private const val WALL_IN = 4.5
    private const val CEILING_M = 2.44

    private const val DOOR_HEIGHT_IN = 80.0
    private const val WINDOW_HEIGHT_IN = 48.0
    private const val WINDOW_SILL_IN = 36.0

    /**
     * A realistic demo house (one floor, ~6-8 rooms incl. an L-shaped living/kitchen, bedrooms, bath,
     * hallway, closet) with doors and windows, rooms sharing walls with a realistic wall-thickness gap
     * between neighbouring interior outlines. Used for the "Open sample" button and renderer tests.
     *
     * A ~980 sq ft three-bedroom ranch laid out on a 4.5" wall grid (dimensions are whole feet / inches
     * so the imperial labels read like a real plan):
     *
     * ```
     *  +--------------------+------------+-----------+
     *  |                    | Bedroom 2  | Bedroom 3 |
     *  |  Living / Kitchen  +------------+------+----+
     *  |     (L-shaped)     |      Hallway      | Cl |
     *  |          +---------+---------+---------+----+
     *  |          |  Entry  | Primary Bedroom   |Bath|
     *  +----------+---------+-------------------+----+
     * ```
     *
     * Every outline is counter-clockwise and simple; facing walls of neighbouring rooms are exactly one
     * wall thickness ([PlanSettings.wallThickness]) apart. Ids are derived from [now] (name-based UUIDs),
     * so the result is deterministic for a given [now] yet distinct from samples opened at other times.
     */
    fun demo(now: Long): Project {
        fun id(key: String) = UUID.nameUUIDFromBytes("floorplan-sample:$now:$key".toByteArray()).toString()

        // x grid (inches): great room | wall | east wing columns ...
        val greatRoomEast = 216.0 // 18'
        val wingWest = greatRoomEast + WALL_IN
        val primaryEast = wingWest + 156.0 // 13'
        val bathWest = primaryEast + WALL_IN
        val houseEast = bathWest + 84.0 // 7'
        val hallEast = 432.0 // 36'
        val closetWest = hallEast + WALL_IN
        val bed2East = wingWest + 120.0 // 10'
        val bed3West = bed2East + WALL_IN
        val entryWest = 132.0 // 11'
        val livingSouthArmEast = entryWest - WALL_IN
        // y grid (inches)
        val southRowNorth = 144.0 // 12'
        val hallSouth = southRowNorth + WALL_IN
        val hallNorth = hallSouth + 42.0 // 3' 6"
        val northRowSouth = hallNorth + WALL_IN
        val houseNorth = northRowSouth + 120.0 // 10'
        val entryNorth = 96.0 // 8'
        val notchNorth = entryNorth + WALL_IN

        fun room(key: String, name: String, type: RoomType, corners: List<Vec2>, openings: List<Opening>) = Room(
            id = id("room:$key"),
            name = name,
            type = type,
            corners = corners,
            ceilingHeight = CEILING_M,
            openings = openings,
            captureMethod = CaptureMethod.MANUAL,
        )

        fun door(key: String, wall: Int, offsetIn: Double, widthIn: Double, swing: DoorSwing) = Opening(
            id = id("door:$key"),
            type = OpeningType.DOOR,
            wallIndex = wall,
            offset = offsetIn * M_PER_IN,
            width = widthIn * M_PER_IN,
            height = DOOR_HEIGHT_IN * M_PER_IN,
            swing = swing,
        )

        fun window(key: String, wall: Int, offsetIn: Double, widthIn: Double, heightIn: Double = WINDOW_HEIGHT_IN, sillIn: Double = WINDOW_SILL_IN) =
            Opening(
                id = id("window:$key"),
                type = OpeningType.WINDOW,
                wallIndex = wall,
                offset = offsetIn * M_PER_IN,
                width = widthIn * M_PER_IN,
                height = heightIn * M_PER_IN,
                sillHeight = sillIn * M_PER_IN,
            )

        fun passage(key: String, wall: Int, offsetIn: Double, widthIn: Double) = Opening(
            id = id("opening:$key"),
            type = OpeningType.OPENING,
            wallIndex = wall,
            offset = offsetIn * M_PER_IN,
            width = widthIn * M_PER_IN,
            height = DOOR_HEIGHT_IN * M_PER_IN,
        )

        val rooms = listOf(
            // Edges: 0 south arm (exterior), 1 east of the arm (entry), 2 north of the entry,
            // 3 east (bedroom 2 / hallway / primary), 4 north (exterior), 5 west (exterior).
            room(
                "living", "Living / Kitchen", RoomType.LIVING,
                inches(
                    0.0 to 0.0, livingSouthArmEast to 0.0, livingSouthArmEast to notchNorth,
                    greatRoomEast to notchNorth, greatRoomEast to houseNorth, 0.0 to houseNorth,
                ),
                listOf(
                    window("living-south", 0, 27.75, 72.0),
                    window("kitchen-north", 4, 84.0, 48.0, heightIn = 36.0, sillIn = 44.0),
                    window("kitchen-west", 5, 36.0, 48.0),
                    window("living-west", 5, 207.0, 72.0),
                ),
            ),
            // Edges: 0 south (front, exterior), 1 east (primary), 2 north (great room), 3 west (great room).
            room(
                "entry", "Entry", RoomType.ENTRY,
                rect(entryWest, 0.0, greatRoomEast, entryNorth),
                listOf(
                    door("front", 0, 24.0, 36.0, DoorSwing.RIGHT_IN),
                    passage("entry-living", 2, 18.0, 48.0),
                ),
            ),
            // Edges: 0 south (primary / bath), 1 east (closet), 2 north (bedrooms), 3 west (great room).
            room(
                "hall", "Hallway", RoomType.HALLWAY,
                rect(wingWest, hallSouth, hallEast, hallNorth),
                listOf(
                    passage("hall-living", 3, 3.0, 36.0),
                    // Bedroom 3's door, captured from the hallway: it swings away from the hall.
                    door("bedroom3", 2, hallEast - (bed3West + 6.0 + 32.0), 32.0, DoorSwing.RIGHT_OUT),
                ),
            ),
            // Edges: 0 south (exterior), 1 east (bath), 2 north (hallway), 3 west (entry / great room).
            room(
                "primary", "Primary Bedroom", RoomType.PRIMARY_BEDROOM,
                rect(wingWest, 0.0, primaryEast, southRowNorth),
                listOf(
                    door("primary", 2, primaryEast - (wingWest + 6.0 + 32.0), 32.0, DoorSwing.RIGHT_IN),
                    window("primary-south", 0, 48.0, 60.0),
                ),
            ),
            // Edges: 0 south (exterior), 1 east (exterior), 2 north (hallway / closet), 3 west (primary).
            room(
                "bath", "Bathroom", RoomType.BATHROOM,
                rect(bathWest, 0.0, houseEast, southRowNorth),
                listOf(
                    door("bath", 2, houseEast - (bathWest + 4.0 + 28.0), 28.0, DoorSwing.RIGHT_IN),
                    window("bath-south", 0, 27.0, 30.0, heightIn = 24.0, sillIn = 54.0),
                ),
            ),
            // Edges: 0 south (bath), 1 east (exterior), 2 north (bedroom 3), 3 west (hallway).
            room(
                "closet", "Linen Closet", RoomType.CLOSET,
                rect(closetWest, hallSouth, houseEast, hallNorth),
                listOf(door("closet", 3, 9.0, 24.0, DoorSwing.LEFT_OUT)),
            ),
            // Edges: 0 south (hallway), 1 east (bedroom 3), 2 north (exterior), 3 west (great room).
            room(
                "bedroom2", "Bedroom 2", RoomType.BEDROOM,
                rect(wingWest, northRowSouth, bed2East, houseNorth),
                listOf(
                    door("bedroom2", 0, 79.5, 32.0, DoorSwing.LEFT_IN),
                    window("bedroom2-north", 2, 36.0, 48.0),
                ),
            ),
            // Edges: 0 south (hallway / closet), 1 east (exterior), 2 north (exterior), 3 west (bedroom 2).
            room(
                "bedroom3", "Bedroom 3", RoomType.BEDROOM,
                rect(bed3West, northRowSouth, houseEast, houseNorth),
                listOf(
                    window("bedroom3-north", 2, 36.0, 48.0),
                    window("bedroom3-east", 1, 42.0, 36.0),
                ),
            ),
        )

        return Project(
            id = id("project"),
            name = "Sample House",
            address = "123 Example St",
            notes = "Demo plan: a three-bedroom ranch you can explore, edit and export.",
            createdAt = now,
            updatedAt = now,
            floors = listOf(Floor(id = id("floor:main"), name = "Main Floor", rooms = rooms)),
            settings = PlanSettings(wallThickness = WALL_IN * M_PER_IN),
        )
    }

    private fun inches(vararg pts: Pair<Double, Double>): List<Vec2> = pts.map { (x, y) -> Vec2(x * M_PER_IN, y * M_PER_IN) }

    /** Counter-clockwise rectangle (south-west corner first) from inch coordinates. */
    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double): List<Vec2> =
        inches(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
}
