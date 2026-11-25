package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.measure.AreaSummary
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.ui.common.formatVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure helpers of round 3: scan groups, accessibility labels, summaries and formatting. */
class ScanGroupsAndLabelsTest {
    private fun rect(id: String, x: Double, y: Double, w: Double, h: Double, session: String? = null, name: String = id) = Room(
        id = id,
        name = name,
        corners = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h)),
        captureMethod = if (session != null) CaptureMethod.WALLS else CaptureMethod.MANUAL,
        sessionId = session,
    )

    private val imperial = PlanSettings(unitSystem = UnitSystem.IMPERIAL)
    private val metric = PlanSettings(unitSystem = UnitSystem.METRIC)

    // ---- ScanGroups ----

    @Test
    fun groupsFollowPlacementFrames() {
        val floor = Floor(
            "f", "F",
            listOf(
                rect("drawn", -5.0, 0.0, 2.0, 2.0),
                rect("a", 0.0, 0.0, 3.0, 3.0, "s1"),
                rect("b", 4.0, 0.0, 3.0, 3.0, "s1"),
                rect("c", 8.0, 0.0, 3.0, 3.0, "s2"),
                rect("d", 12.0, 0.0, 3.0, 3.0, "s2"),
                rect("e", 16.0, 0.0, 3.0, 3.0, "s2").copy(frameId = "e"),
            ),
        )
        assertEquals(setOf("a", "b"), ScanGroups.groupOf(floor, "a"))
        assertEquals(setOf("c", "d"), ScanGroups.groupOf(floor, "d"))
        assertEquals("a room moved on its own is its own group", setOf("e"), ScanGroups.groupOf(floor, "e"))
        assertEquals(setOf("drawn"), ScanGroups.groupOf(floor, "drawn"))
        assertTrue(ScanGroups.groupOf(floor, "missing").isEmpty())

        assertEquals("a", ScanGroups.firstScanned(floor)?.id)
        assertFalse("the first scan is the reference", ScanGroups.defaultWholeScan(floor, "a"))
        assertTrue("a later scan starts with Whole scan on", ScanGroups.defaultWholeScan(floor, "c"))
        assertFalse("alone in its frame", ScanGroups.defaultWholeScan(floor, "e"))
        assertFalse(ScanGroups.defaultWholeScan(floor, "drawn"))
        assertFalse(ScanGroups.defaultWholeScan(floor, "missing"))
    }

    @Test
    fun detachingOnlyChangesRoomsThatShareAFrame() {
        val scanned = rect("a", 0.0, 0.0, 3.0, 3.0, "s1")
        assertEquals("a", ScanGroups.detached(scanned).frameId)
        val drawn = rect("d", 0.0, 0.0, 3.0, 3.0)
        assertTrue(ScanGroups.detached(drawn) === drawn)
        val alreadyOwn = scanned.copy(frameId = "a")
        assertTrue(ScanGroups.detached(alreadyOwn) === alreadyOwn)
    }

    @Test
    fun squareTurnIsTheSmallestTurnModuloNinetyDegrees() {
        val reference = listOf(rect("r", 0.0, 0.0, 4.0, 3.0))
        fun turned(deg: Double) = listOf(RoomOps.transform(rect("x", 5.0, 0.0, 3.0, 3.0), RigidTransform2.rotation(Math.toRadians(deg))))
        assertEquals(-37.0, Math.toDegrees(ScanGroups.squareTurn(turned(37.0), reference)!!), 1e-6)
        assertEquals(37.0, Math.toDegrees(ScanGroups.squareTurn(turned(-37.0), reference)!!), 1e-6)
        assertEquals(-10.0, Math.toDegrees(ScanGroups.squareTurn(turned(100.0), reference)!!), 1e-6)
        assertEquals(0.0, ScanGroups.squareTurn(turned(90.0), reference)!!, 1e-9)
        assertNull(ScanGroups.squareTurn(turned(10.0), emptyList()))
        assertNull(ScanGroups.squareTurn(emptyList(), reference))
        val broken = listOf(rect("b", 0.0, 0.0, 1.0, 1.0).copy(corners = listOf(Vec2(Double.NaN, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0))))
        assertNull(ScanGroups.squareTurn(broken, reference))
    }

    @Test
    fun turnBetweenAndPivot() {
        val room = rect("a", 0.0, 0.0, 4.0, 2.0)
        val turned = RoomOps.transform(room, RigidTransform2.rotationAround(Vec2(1.0, 1.0), Math.toRadians(-37.0)))
        assertEquals(-37.0, Math.toDegrees(ScanGroups.turnBetween(room, turned)!!), 1e-9)
        assertNull(ScanGroups.turnBetween(room, room.copy(corners = room.corners.drop(1))))
        assertEquals(Vec2(2.0, 1.0), ScanGroups.pivot(listOf(room)))
        assertNull(ScanGroups.pivot(emptyList()))
    }

    @Test
    fun turnsAreFormattedForMessages() {
        assertEquals("37°", ScanGroups.formatTurn(Math.toRadians(-37.2)))
        assertEquals("1.5°", ScanGroups.formatTurn(Math.toRadians(1.5)))
        assertEquals("2°", ScanGroups.formatTurn(Math.toRadians(2.0)))
        assertEquals("10°", ScanGroups.formatTurn(Math.toRadians(9.96)))
        assertEquals("?", ScanGroups.formatTurn(Double.NaN))
    }

    // ---- Accessibility ----

    @Test
    fun canvasActionsSelectRoomsThenWallsAndCorners() {
        val kitchen = rect("k", 0.0, 0.0, 4.0, 3.0, name = "Kitchen")
        val hall = rect("h", 5.0, 0.0, 2.0, 1.0, name = "Hall")
        val none = canvasActions(listOf(kitchen, hall), null, metric)
        assertEquals(listOf("Select Kitchen", "Select Hall"), none.map { it.label })
        assertEquals(CanvasAction.FocusRoom("Select Hall", "h"), none[1])

        val selected = canvasActions(listOf(kitchen, hall), Selection.Room("k"), metric)
        val labels = selected.map { it.label }
        assertTrue(labels.containsAll(listOf("Select Kitchen", "Select Hall")))
        assertTrue("Select wall 1 (4.00 m)" in labels)
        assertTrue("Select wall 2 (3.00 m)" in labels)
        assertTrue("Select corner 4" in labels)
        assertEquals("Clear selection", labels.last())
        assertEquals(CanvasAction.Select("Select corner 1", Selection.Corner("k", 0)), selected.first { it.label == "Select corner 1" })
        assertEquals(2 + 4 + 4 + 1, selected.size)
    }

    @Test
    fun canvasDescriptionNamesEveryRoomWithItsSize() {
        val kitchen = rect("k", 0.0, 0.0, 4.0, 3.0, name = "Kitchen")
        val hall = rect("h", 5.0, 0.0, 2.0, 1.0, name = "Hall")
        assertEquals("Floor plan, 0 rooms.", canvasDescription(emptyList(), null, metric))
        assertEquals(
            "Floor plan, 2 rooms: Kitchen 4.00 m × 3.00 m; Hall 2.00 m × 1.00 m.",
            canvasDescription(listOf(kitchen, hall), null, metric),
        )
        assertTrue(canvasDescription(listOf(kitchen, hall), Selection.Wall("k", 1), metric).endsWith("Kitchen selected, wall 2."))
        assertEquals("Kitchen · 4.00 m × 3.00 m · 12.0 m²", roomSummary(kitchen.copy(type = RoomType.KITCHEN), metric))
        assertEquals("Room", roomSummary(kitchen.copy(corners = emptyList()), metric))
    }

    // ---- Lists, summaries, formatting ----

    @Test
    fun everyRoomTypeIsInExactlyOnePickerGroup() {
        val all = RoomTypeGroups.groups.flatMap { it.second }
        assertEquals(RoomType.entries.size, all.size)
        assertEquals(RoomType.entries.toSet(), all.toSet())
        assertEquals(listOf(RoomType.PORCH, RoomType.DECK, RoomType.BALCONY, RoomType.PATIO), RoomTypeGroups.groups.first { it.first == "Outdoor" }.second)
    }

    @Test
    fun theHeadlineAreaIsLivingAreaWhenOtherRoomsExist() {
        assertEquals("1,000 sq ft", areaHeadline(AreaSummary(92.903, 0.0, 0.0, 0.0), imperial))
        assertEquals("Living area 1,000 sq ft", areaHeadline(AreaSummary(92.903, 40.0, 0.0, 0.0), imperial))
        assertEquals("Living area 12.0 m²", areaHeadline(AreaSummary(12.0, 0.0, 6.0, 0.0), metric))
        val floor = Floor(
            "f", "Main Floor",
            listOf(rect("a", 0.0, 0.0, 4.0, 3.0), rect("g", 5.0, 0.0, 6.0, 6.0).copy(type = RoomType.GARAGE)),
        )
        assertEquals("Main Floor · 2 rooms · Living area 12.0 m²", floorSummary(floor, metric))
        assertEquals("Main Floor · 1 room · 12.0 m²", floorSummary(floor.copy(rooms = floor.rooms.take(1)), metric))
        assertEquals("Main Floor · no rooms", floorSummary(floor.copy(rooms = emptyList()), metric))
    }

    @Test
    fun volumesAreFormattedInTheProjectsUnits() {
        assertEquals("1,152 cu ft", formatVolume(32.6201, UnitSystem.IMPERIAL))
        assertEquals("32.6 m³", formatVolume(32.6201, UnitSystem.METRIC))
        assertEquals("1,250.0 m³", formatVolume(1250.0, UnitSystem.METRIC))
        assertEquals("0.0 m³", formatVolume(-0.01, UnitSystem.METRIC))
        assertEquals("—", formatVolume(Double.NaN, UnitSystem.METRIC))
    }

    @Test
    fun pointsPerMeterIsBucketedForTheCurrentZoom() {
        val view = ViewTransform(pxPerMeter = 100.0, originPx = Vec2.ZERO, pxPerPt = 2.0)
        val ppm = quantizedPointsPerMeter(view)!!
        assertEquals(50.0, ppm, 50.0 * 0.1)
        // Nearby zooms share a bucket, so a pinch does not re-render every frame.
        assertEquals(ppm, quantizedPointsPerMeter(view.copy(pxPerMeter = 101.0))!!, 0.0)
        assertTrue(quantizedPointsPerMeter(view.copy(pxPerMeter = 200.0))!! > ppm)
        assertNull(quantizedPointsPerMeter(view.copy(pxPerMeter = 0.0)))
        assertNull(quantizedPointsPerMeter(view.copy(pxPerPt = Double.NaN)))
    }
}
