package com.oroplex.floorplan.core.solve

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.solve.Ops.T
import com.oroplex.floorplan.core.solve.Ops.drawn
import com.oroplex.floorplan.core.solve.Ops.floor
import com.oroplex.floorplan.core.solve.Ops.moved
import com.oroplex.floorplan.core.solve.Ops.rect
import com.oroplex.floorplan.core.solve.Ops.scanned
import com.oroplex.floorplan.core.solve.Ops.turned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FloorChecksTest {
    private val settings = PlanSettings()
    private val calibration = Calibration(1.01, "a", 0, 4.0, 4.04, 1L)

    private fun lShape(dx: Double = 0.0, dy: Double = 0.0) =
        Shapes.lShape.map { Vec2(it.x + dx, it.y + dy) }

    /** The L rotated 180° about (4.5, 4): its arms interlock with the original L's notch without overlapping. */
    private fun interlockingL() = Shapes.lShape.map { Vec2(9.0 - it.x, 8.0 - it.y) }

    // ---------------------------------------------------------------- overlappingRooms

    @Test fun overlapIsExactForLShapes() {
        // A room standing in the L's notch overlaps its bounding box but not the room itself.
        val inNotch = drawn("n", rect(3.2, 3.2, 5.8, 4.8))
        assertTrue(FloorChecks.overlappingRooms(floor(drawn("l", lShape()), inNotch)).isEmpty())
        // Two interlocking Ls: bounding boxes and convex hulls overlap, the rooms only touch at corners.
        assertTrue(FloorChecks.overlappingRooms(floor(drawn("l", lShape()), drawn("m", interlockingL()))).isEmpty())
        // Shifted by half a meter they really overlap (two 0.5 x 0.5 squares = 0.5 m²).
        val shifted = interlockingL().map { Vec2(it.x - 0.5, it.y - 0.5) }
        assertEquals(listOf("l" to "m"), FloorChecks.overlappingRooms(floor(drawn("l", lShape()), drawn("m", shifted))))
        assertEquals(listOf("l" to "m"), FloorChecks.overlappingRooms(floor(drawn("l", lShape()), drawn("m", shifted)), minOverlapArea = 0.49))
        assertTrue(FloorChecks.overlappingRooms(floor(drawn("l", lShape()), drawn("m", shifted)), minOverlapArea = 0.51).isEmpty())
    }

    @Test fun neighboursSharingAWallDoNotOverlap() {
        val sample = SampleProjects.demo(1L).floors.first()
        assertTrue(FloorChecks.overlappingRooms(sample).isEmpty())
        // Rooms touching along a full edge (no wall gap at all) still do not overlap.
        assertTrue(FloorChecks.overlappingRooms(floor(drawn("a", rect(0.0, 0.0, 3.0, 3.0)), drawn("b", rect(3.0, 0.0, 6.0, 3.0)))).isEmpty())
    }

    @Test fun overlapPairsAreOrderedAndSkipBrokenRooms() {
        val a = drawn("a", rect(0.0, 0.0, 4.0, 4.0))
        val b = drawn("b", rect(1.0, 1.0, 2.0, 2.0)) // inside a
        val c = drawn("c", rect(3.0, 3.0, 6.0, 6.0)) // overlaps a's corner by 1 m²
        val broken = drawn("x", listOf(v(0.0, 0.0), v(4.0, 4.0), v(4.0, 0.0), v(0.0, 4.0)))
        val cw = drawn("cw", rect(1.5, 1.5, 2.5, 2.5).reversed()) // clockwise but valid: still checked
        assertEquals(
            listOf("a" to "b", "a" to "c", "a" to "cw", "b" to "cw"),
            FloorChecks.overlappingRooms(floor(a, b, c, broken, cw)),
        )
    }

    // ---------------------------------------------------------------- check

    @Test fun samplePassesEveryCheck() {
        val sample = SampleProjects.demo(1L)
        assertEquals(emptyList(), FloorChecks.check(sample.floors.first(), sample.settings))
        assertEquals(emptyList(), FloorChecks.check(floor(), settings))
    }

    @Test fun overlappingRoomsAreErrorsNamingTheRoomToMove() {
        val hall = drawn("h", rect(0.0, 0.0, 6.0, 1.2), name = "Hallway")
        val bed = drawn("b", rect(1.0, 0.8, 4.0, 4.0), name = "Bedroom 2")
        val issues = FloorChecks.check(floor(hall, bed), settings)
        val overlap = issues.first()
        assertEquals(IssueSeverity.ERROR, overlap.severity)
        assertEquals("b", overlap.roomId)
        assertEquals("Bedroom 2 overlaps Hallway — move or snap it into place", overlap.message)
    }

    @Test fun brokenOutlinesAreErrors() {
        // Self-intersecting with a non-zero signed area (a symmetric bowtie would have none).
        val bowtie = drawn("x", listOf(v(0.0, 0.0), v(3.0, 2.0), v(3.0, 0.0), v(0.0, 1.0)), name = "Den")
        val flat = drawn("y", listOf(v(0.0, 0.0), v(1.0, 0.0), v(2.0, 0.0)), name = "")
        val nan = drawn("z", listOf(v(0.0, 0.0), v(Double.NaN, 0.0), v(1.0, 1.0)), name = "Nook")
        val issues = FloorChecks.check(floor(bowtie, flat, nan), settings)
        assertEquals(
            listOf(
                FloorIssue(IssueSeverity.ERROR, "x", "Den's walls cross each other — drag its corners to fix the outline"),
                FloorIssue(IssueSeverity.ERROR, "y", "Room has no usable outline — redraw it or scan it again"),
                FloorIssue(IssueSeverity.ERROR, "z", "Nook has no usable outline — redraw it or scan it again"),
            ),
            issues,
        )
    }

    @Test fun solverWarningsAndPoorRoomsAreWarnings() {
        val one = scanned("k", rect(0.0, 0.0, 4.0, 3.0), name = "Kitchen", warnings = listOf("Corner 3 forms a very sharp spike; check walls 2 and 3"))
        val many = scanned("b", rect(4.0 + T, 0.0, 7.0, 3.0), name = "Bath", warnings = listOf("A", "B"))
        // A scanned room whose captures are all weak.
        val weak = scanned("p", rect(0.0, 3.0 + T, 4.0, 6.0), name = "Pantry").let { r ->
            r.copy(observations = r.observations.map { it.copy(confidence = 0.1) })
        }
        val issues = FloorChecks.check(floor(one, many, weak).copy(calibration = calibration), settings)
        val warnings = issues.filter { it.severity == IssueSeverity.WARNING }
        assertEquals("Kitchen: Corner 3 forms a very sharp spike; check walls 2 and 3", warnings.single { it.roomId == "k" }.message)
        assertEquals("Bath has 2 scan warnings — select it to review them", warnings.single { it.roomId == "b" }.message)
        val poor = warnings.single { it.roomId == "p" }
        assertTrue(poor.message.startsWith("Pantry: low scan accuracy. Walls 1, 2, 3 and 4 were weak captures"), poor.message)
    }

    @Test fun estimatedCeilingsAreOneCombinedInfo() {
        val rooms = (1..5).map { i ->
            scanned("r$i", rect(i * 5.0, 0.0, i * 5.0 + 4.0, 3.0), name = "Room $i", ceilingEstimated = i != 3, ceiling = 2.4384)
        }
        val issues = FloorChecks.check(floor(*rooms.toTypedArray()).copy(calibration = calibration), settings)
        val ceiling = issues.single { "Ceiling" in it.message }
        assertEquals(IssueSeverity.INFO, ceiling.severity)
        assertEquals(null, ceiling.roomId)
        assertEquals("Ceiling heights are estimated in Room 1, Room 2, Room 4 and 1 more — measure them for accurate volumes", ceiling.message)
        val single = FloorChecks.check(floor(rooms[0]).copy(calibration = calibration), settings).single { "Ceiling" in it.message }
        assertEquals("Ceiling height is estimated in Room 1 — measure it for accurate volumes", single.message)
    }

    @Test fun separateScansThatAreNotAlignedAreReported() {
        val kitchen = scanned("k", rect(0.0, 0.0, 4.0, 3.0), name = "Kitchen", session = "s1")
        val dining = turned(scanned("d", rect(4.3, 0.2, 7.3, 3.2), name = "Dining", session = "s2"), 3.0)
        val den = scanned("n", rect(4.3, 4.5, 7.3, 7.5), name = "Den", session = "s2")
        val f = floor(kitchen, dining, den).copy(calibration = calibration)
        val info = FloorChecks.check(f, settings).single { "separately" in it.message }
        assertEquals(IssueSeverity.INFO, info.severity)
        assertEquals("d", info.roomId)
        assertEquals("Dining and Den were scanned separately and may not line up — snap them to a neighbour", info.message)
        // Snapping one room of the second scan to the first aligns the scans.
        val snapped = assertNotNull(FloorOps.snapRoom(f, "d", T))
        assertTrue(FloorChecks.check(snapped, settings).none { "separately" in it.message })
        // A single scan session, or drawn rooms, never trigger it.
        assertTrue(FloorChecks.check(floor(kitchen, moved(kitchen.copy(id = "x"), 10.0, 0.0)), settings).none { "separately" in it.message })
        assertTrue(FloorChecks.check(floor(kitchen, drawn("m", rect(20.0, 0.0, 22.0, 2.0))), settings).none { "separately" in it.message })
        // A third, unaligned scan gets its own note.
        val shed = scanned("s", rect(20.0, 0.0, 22.0, 2.0), name = "Shed", session = "s3")
        val notes = FloorChecks.check(floor(kitchen, dining, shed), settings).filter { "separately" in it.message }
        assertEquals(listOf("d", "s"), notes.map { it.roomId })
        assertEquals("Shed was scanned separately and may not line up — snap it to a neighbour", notes[1].message)
    }

    @Test fun uncalibratedFloorsWithScansGetATapeHint() {
        val kitchen = scanned("k", rect(0.0, 0.0, 4.0, 3.0), name = "Kitchen")
        val hint = FloorChecks.check(floor(kitchen), settings).single()
        assertEquals(FloorIssue(IssueSeverity.INFO, null, "Floor isn't tape-calibrated — measure one long wall and calibrate from it for best accuracy"), hint)
        assertTrue(FloorChecks.check(floor(kitchen).copy(calibration = calibration), settings).isEmpty())
        assertTrue(FloorChecks.check(floor(drawn("m", rect(0.0, 0.0, 2.0, 2.0))), settings).isEmpty(), "drawn rooms need no tape")
    }

    @Test fun issuesAreSortedMostSevereFirst() {
        val hall = scanned("h", rect(0.0, 0.0, 6.0, 1.2), name = "Hallway", ceilingEstimated = true, warnings = listOf("Walls were re-ordered"))
        val bed = scanned("b", rect(1.0, 0.8, 4.0, 4.0), name = "Bedroom 2", session = "s2")
        val bowtie = drawn("x", listOf(v(10.0, 0.0), v(12.0, 2.0), v(12.0, 0.0), v(10.0, 2.0)))
        val issues = FloorChecks.check(floor(hall, bed, bowtie), settings)
        val ranks = issues.map { it.severity.ordinal }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(setOf(IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.INFO), issues.map { it.severity }.toSet())
        assertTrue(issues.all { it.message.isNotBlank() && it.message.length < 140 })
    }
}
