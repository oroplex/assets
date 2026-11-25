package com.oroplex.floorplan.core.io

import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.AreaCategory
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.HandSetWall
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.effectiveAreaCategory
import com.oroplex.floorplan.core.model.placementFrame
import com.oroplex.floorplan.core.geometry.Polygons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectJsonTest {
    private val sample = SampleProjects.demo(1_700_000_000_000L)

    @Test fun sampleRoundTripsToAnEqualProject() {
        val json = ProjectJson.encode(sample)
        assertEquals(sample, ProjectJson.decode(json))
        assertFalse('\n' in json, "compact")
        // Defaults are written explicitly so backups do not depend on future default changes.
        assertTrue("\"wallThickness\"" in json && "\"schemaVersion\"" in json && "\"captureMethod\"" in json)
        assertEquals(json, ProjectJson.encode(ProjectJson.decode(json)), "stable encoding")
    }

    /**
     * `tools/walkthrough/build_plan.py` writes projects for this app from a walk-through recording. The file
     * read here was made by that tool from a synthetic recording of a 4 x 3 m and a 3 x 3 m room, turned 27°;
     * when the tool's output changes, make it again (the command is in docs/HANDOFF.md).
     */
    @Test fun aPlanMadeFromAWalkthroughRecordingOpens() {
        val text = checkNotNull(javaClass.getResource("/walkthrough-plan-sample.json")).readText()
        val project = ProjectJson.decode(text)
        val rooms = project.floors.single().rooms
        assertEquals(listOf("Room 1", "Room 2"), rooms.map { it.name })
        assertEquals(12.0, Polygons.area(rooms[0].corners), 0.05)
        assertEquals(9.0, Polygons.area(rooms[1].corners), 0.05)
        // Interior outlines, counter-clockwise, as the rest of the app expects.
        assertTrue(rooms.all { Polygons.signedArea(it.corners) > 0 })
        // One recording is one frame: the rooms are already placed against each other.
        assertEquals(1, rooms.map { it.placementFrame }.toSet().size)
        assertTrue(rooms.all { it.ceilingHeight != null && !it.ceilingEstimated && it.warnings.isNotEmpty() })
        // It is an ordinary project from here on.
        assertEquals(project, ProjectJson.decode(ProjectJson.encode(project)))
    }

    @Test fun garbageIsRejectedWithAClearMessage() {
        val inputs = listOf(
            "",
            "   ",
            "not json",
            "[]",
            "{}",
            "{\"id\": \"x\"}",
            ProjectJson.encode(sample).dropLast(10),
            "{\"id\": 5, \"name\": [], \"createdAt\": \"soon\", \"updatedAt\": 0}",
            "\u0000\u0001",
        )
        for (input in inputs) {
            val e = assertFailsWith<IllegalArgumentException>("input: ${input.take(30)}") { ProjectJson.decode(input) }
            val message = e.message ?: ""
            assertTrue(message.startsWith("Not a valid FloorPlan project file: "), message)
            assertTrue(message.length < 300, "short message: $message")
            assertFalse('\n' in message)
        }
    }

    @Test fun decodeIsLenientAboutNewerFiles() {
        val minimal = """{"id":"p","name":"P","createdAt":1,"updatedAt":2,"futureField":{"a":[1,2]},
            |"floors":[{"id":"f","name":"F","rooms":[{"id":"r","name":"R","type":"WINE_CELLAR_XYZ","corners":[],"extra":true}]}]}""".trimMargin()
        val p = ProjectJson.decode(minimal)
        assertEquals("P", p.name)
        assertEquals(RoomType.OTHER, p.floors.single().rooms.single().type, "unknown enum falls back to the default")
        assertEquals(1, p.schemaVersion)
    }

    @Test fun roundTwoFieldsRoundTripAndOldFilesGetSafeDefaults() {
        val floor = sample.floors.first()
        val room = floor.rooms.first().copy(ceilingEstimated = true)
        val calibration = Calibration(1.02, room.id, 0, 4.0, 4.08, 5L, origin = Vec2(1.5, -2.25))
        val settings = sample.settings.copy(defaultDoorWidth = 0.9, defaultWindowWidth = 1.2, showOverallDimensions = false)
        val project = sample.copy(
            settings = settings,
            floors = listOf(floor.copy(rooms = listOf(room) + floor.rooms.drop(1), calibration = calibration)),
        )
        val json = ProjectJson.encode(project)
        assertTrue("\"ceilingEstimated\":true" in json && "\"origin\"" in json && "\"showOverallDimensions\":false" in json)
        assertEquals(project, ProjectJson.decode(json))
        // A round-1 backup has none of these fields: ceilings count as measured, calibrations as scaled about
        // the plan origin, and the new settings take their defaults.
        val old = """{"id":"p","name":"P","createdAt":1,"updatedAt":2,"settings":{"unitSystem":"METRIC"},
            |"floors":[{"id":"f","name":"F","calibration":{"factor":1.03,"roomId":"r","wallIndex":0,"measuredLength":4.0,
            |"trueLength":4.12,"appliedAt":3},"rooms":[{"id":"r","name":"R","corners":[],"ceilingHeight":2.4}]}]}""".trimMargin()
        val decoded = ProjectJson.decode(old)
        val f = decoded.floors.single()
        assertFalse(f.rooms.single().ceilingEstimated)
        assertEquals(Vec2.ZERO, f.calibration?.origin)
        assertEquals(PlanSettings().defaultDoorWidth, decoded.settings.defaultDoorWidth)
        assertTrue(decoded.settings.showOverallDimensions)
    }

    @Test fun roundThreeFieldsRoundTripAndOldFilesGetSafeDefaults() {
        val floor = sample.floors.first()
        val first = floor.rooms.first()
        val opening = first.openings.firstOrNull()?.copy(typed = true)
            ?: Opening("o", OpeningType.DOOR, 0, 0.2, 0.8, 2.0, typed = true)
        val room = first.copy(
            type = RoomType.PORCH,
            areaCategory = AreaCategory.EXCLUDED,
            ceilingTyped = true,
            openings = listOf(opening) + first.openings.drop(1),
            handSetWalls = listOf(
                HandSetWall(Segment2(Vec2(0.0, 0.0), Vec2(4.0, 0.0)), typedLength = 4.0),
                HandSetWall(Segment2(Vec2(4.0, 0.0), Vec2(4.0, 3.0))),
            ),
            frameId = "frame:x",
        )
        val types = listOf(RoomType.DEN, RoomType.PANTRY, RoomType.BONUS, RoomType.SUNROOM, RoomType.MUDROOM, RoomType.WORKSHOP,
            RoomType.BASEMENT, RoomType.DECK, RoomType.BALCONY, RoomType.PATIO)
        val extra = types.mapIndexed { i, t -> floor.rooms[1].copy(id = "t$i", type = t) }
        for (thickness in listOf<Double?>(0.2, null)) {
            val project = sample.copy(
                settings = sample.settings.copy(exteriorWallThickness = thickness),
                floors = listOf(floor.copy(rooms = listOf(room) + extra)),
            )
            val json = ProjectJson.encode(project)
            assertEquals(project, ProjectJson.decode(json), "exterior $thickness")
            assertTrue("\"ceilingTyped\":true" in json && "\"typed\":true" in json && "\"handSetWalls\"" in json && "\"frameId\":\"frame:x\"" in json)
            assertTrue("\"areaCategory\":\"EXCLUDED\"" in json && "\"typedLength\":4.0" in json && "\"PORCH\"" in json)
        }
        // A round-2 backup has none of these: typed flags off, no hand-set walls, the session frame, the type's area
        // category, and the 6.5" exterior wall default.
        val old = """{"id":"p","name":"P","createdAt":1,"updatedAt":2,"settings":{"unitSystem":"METRIC","wallThickness":0.1},
            |"floors":[{"id":"f","name":"F","rooms":[{"id":"r","name":"Garage","type":"GARAGE","corners":[],"ceilingHeight":2.4,
            |"sessionId":"s1","openings":[{"id":"o","type":"DOOR","wallIndex":0,"offset":0.1,"width":0.8,"height":2.0}]}]}]}""".trimMargin()
        val decoded = ProjectJson.decode(old)
        val r = decoded.floors.single().rooms.single()
        assertFalse(r.ceilingTyped)
        assertFalse(r.openings.single().typed)
        assertTrue(r.handSetWalls.isEmpty())
        assertNull(r.frameId)
        assertEquals("s1", r.placementFrame)
        assertNull(r.areaCategory)
        assertEquals(AreaCategory.NON_LIVING, r.effectiveAreaCategory)
        assertEquals(PlanSettings().exteriorWallThickness, decoded.settings.exteriorWallThickness)
        assertEquals(0.1, decoded.settings.wallThickness)
        // An area category from a newer version falls back to "follow the room type".
        val newer = old.replace("\"type\":\"GARAGE\"", "\"type\":\"GARAGE\",\"areaCategory\":\"MEZZANINE_XYZ\"")
        assertNull(ProjectJson.decode(newer).floors.single().rooms.single().areaCategory)
    }

    @Test fun nonFiniteNumbersNeverMakeAProjectUnsavable() {
        val room = sample.floors.first().rooms.first().copy(ceilingHeight = Double.NaN)
        val broken = sample.copy(floors = listOf(sample.floors.first().copy(rooms = listOf(room))))
        val back = ProjectJson.decode(ProjectJson.encode(broken))
        assertTrue(back.floors.first().rooms.first().ceilingHeight?.isNaN() == true)
    }
}
