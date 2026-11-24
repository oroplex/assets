package com.oroplex.floorplan.core.io

import com.oroplex.floorplan.core.model.RoomType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            |"floors":[{"id":"f","name":"F","rooms":[{"id":"r","name":"R","type":"SUNROOM","corners":[],"extra":true}]}]}""".trimMargin()
        val p = ProjectJson.decode(minimal)
        assertEquals("P", p.name)
        assertEquals(RoomType.OTHER, p.floors.single().rooms.single().type, "unknown enum falls back to the default")
        assertEquals(1, p.schemaVersion)
    }

    @Test fun nonFiniteNumbersNeverMakeAProjectUnsavable() {
        val room = sample.floors.first().rooms.first().copy(ceilingHeight = Double.NaN)
        val broken = sample.copy(floors = listOf(sample.floors.first().copy(rooms = listOf(room))))
        val back = ProjectJson.decode(ProjectJson.encode(broken))
        assertTrue(back.floors.first().rooms.first().ceilingHeight?.isNaN() == true)
    }
}
