package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.TextAlign
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.data.sanitized
import com.oroplex.floorplan.ui.common.LengthInput
import com.oroplex.floorplan.core.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class EditorLogicTest {
    private fun rect(id: String, x: Double, y: Double, w: Double, h: Double) = Room(
        id = id,
        name = id,
        corners = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h)),
    )

    /** 100 px per meter, plan origin at screen (0, 1000). */
    private val view = ViewTransform(pxPerMeter = 100.0, originPx = Vec2(0.0, 1000.0), pxPerPt = 2.0)

    // ---- UndoHistory ----

    @Test
    fun undoRedoRoundTrip() {
        val h = UndoHistory<Int>(limit = 3)
        assertFalse(h.canUndo)
        h.record(1) // 1 -> 2
        h.record(2) // 2 -> 3
        assertEquals(2, h.undo(3))
        assertEquals(1, h.undo(2))
        assertNull(h.undo(1))
        assertEquals(2, h.redo(1))
        assertEquals(3, h.redo(2))
        assertNull(h.redo(3))
    }

    @Test
    fun recordClearsRedoAndHistoryIsBounded() {
        val h = UndoHistory<Int>(limit = 3)
        (1..5).forEach { h.record(it) }
        assertEquals(5, h.undo(6))
        h.record(9)
        assertFalse(h.canRedo)
        assertEquals(9, h.undo(10))
        assertEquals(4, h.undo(9))
        assertEquals(3, h.undo(4))
        assertNull("oldest snapshots dropped beyond the limit", h.undo(3))
    }

    // ---- Hit testing ----

    @Test
    fun cornerHitWithinRadiusPicksNearest() {
        val room = rect("a", 0.0, 0.0, 4.0, 3.0)
        // Corner (4,0) is at screen (400, 1000).
        assertEquals(1, EditorHitTest.cornerAt(room, Vec2(410.0, 990.0), view, 24.0))
        assertNull(EditorHitTest.cornerAt(room, Vec2(450.0, 1000.0), view, 24.0))
    }

    @Test
    fun wallHitPrefersSelectedRoomAndCountsTheBand() {
        val a = rect("a", 0.0, 0.0, 3.0, 3.0)
        val b = rect("b", 3.1, 0.0, 3.0, 3.0) // shares the x=3..3.1 wall band with a
        val rooms = listOf(a, b)
        // On the band between the rooms: both rooms' walls qualify.
        assertEquals("b" to 3, EditorHitTest.wallAt(rooms, Vec2(3.05, 1.5), 0.16, 0.1, preferredRoomId = "b"))
        assertEquals("a" to 1, EditorHitTest.wallAt(rooms, Vec2(3.05, 1.5), 0.16, 0.1, preferredRoomId = "a"))
        // Inside a, close to its bottom wall.
        assertEquals("a" to 0, EditorHitTest.wallAt(rooms, Vec2(1.5, 0.1), 0.16, 0.1, preferredRoomId = null))
        // Middle of a: no wall.
        assertNull(EditorHitTest.wallAt(rooms, Vec2(1.5, 1.5), 0.16, 0.1, preferredRoomId = null))
        // Far outside.
        assertNull(EditorHitTest.wallAt(rooms, Vec2(10.0, 10.0), 0.16, 0.1, preferredRoomId = null))
    }

    @Test
    fun wallToleranceInsideNarrowRoomsIsCapped() {
        val closet = rect("c", 0.0, 0.0, 0.6, 2.0)
        // 0.3 m from both long walls: with an uncapped 0.5 m tolerance it would hit a wall.
        assertNull(EditorHitTest.wallAt(listOf(closet), Vec2(0.3, 1.0), 0.5, 0.1, null))
        assertEquals("c", EditorHitTest.roomAt(listOf(closet), Vec2(0.3, 1.0)))
    }

    @Test
    fun roomHitPrefersSmallestContainingRoom() {
        val big = rect("big", 0.0, 0.0, 10.0, 10.0)
        val small = rect("small", 2.0, 2.0, 1.0, 1.0)
        assertEquals("small", EditorHitTest.roomAt(listOf(big, small), Vec2(2.5, 2.5)))
        assertEquals("big", EditorHitTest.roomAt(listOf(big, small), Vec2(5.0, 5.0)))
        assertNull(EditorHitTest.roomAt(listOf(big, small), Vec2(-1.0, 5.0)))
    }

    @Test
    fun wallLabelHitAccountsForRotationAndOffset() {
        // Vertical label (plan angle 90deg) at plan (2, 1), shifted 10 pt "up" in its own frame.
        val label = DrawOp.Text(
            anchor = Vec2(2.0, 1.0),
            text = "10' 0\"",
            sizePt = 10.0,
            color = 0,
            align = TextAlign.CENTER,
            angle = PI / 2,
            offsetPt = Vec2(0.0, 10.0),
            tag = "wall:room-1:2",
        )
        val list = DrawList(listOf(label), Bounds.EMPTY)
        val measure = { _: DrawOp.Text, _: Double -> 60.0 }
        // Anchor on screen: (200, 900). Plan angle 90deg = baseline pointing up the screen; the text's "up"
        // (+y in the label frame) then points to screen -x, so +10 pt = 20 px moves the label to x = 180.
        assertEquals("room-1" to 2, EditorHitTest.wallLabelAt(list, Vec2(180.0, 900.0), view, 4.0, measure))
        // Along the baseline (screen y) the label is 60 px long.
        assertEquals("room-1" to 2, EditorHitTest.wallLabelAt(list, Vec2(180.0, 875.0), view, 4.0, measure))
        assertNull(EditorHitTest.wallLabelAt(list, Vec2(220.0, 900.0), view, 4.0, measure))
        assertNull(EditorHitTest.wallLabelAt(list, Vec2(180.0, 850.0), view, 4.0, measure))
    }

    @Test
    fun parseWallTag() {
        assertEquals("abc" to 3, EditorHitTest.parseWallTag("wall:abc:3"))
        assertEquals("a:b" to 0, EditorHitTest.parseWallTag("wall:a:b:0"))
        assertNull(EditorHitTest.parseWallTag("room:abc"))
        assertNull(EditorHitTest.parseWallTag("wall:abc:x"))
        assertNull(EditorHitTest.parseWallTag(null))
    }

    // ---- Placement / naming ----

    @Test
    fun newRectangleIsPlacedRightOfTheFloorAndCcw() {
        val existing = listOf(rect("a", 0.0, 0.0, 4.0, 3.0))
        val corners = RoomPlacement.rectangleRightOf(existing, width = 2.0, length = 1.5, wallThickness = 0.1)
        assertTrue(Polygons.isCounterClockwise(corners))
        assertEquals(4.0 + 0.2 + RoomPlacement.NEW_ROOM_GAP, corners[0].x, 1e-9)
        assertEquals(3.0, corners.maxOf { it.y }, 1e-9) // top-aligned
        assertEquals(3.0, Polygons.area(corners), 1e-9)
        val first = RoomPlacement.rectangleRightOf(emptyList(), 2.0, 1.0, 0.1)
        assertEquals(Vec2(0.0, 0.0), first[0])
    }

    @Test
    fun aRectangleDrawnOnATurnedFloorFollowsItsWalls() {
        val turn = Math.toRadians(23.0)
        val existing = listOf(rect("a", 0.0, 0.0, 4.0, 3.0).let { r -> r.copy(corners = r.corners.map { it.rotated(turn) }) })
        val axis = RoomPlacement.drawingAxis(existing)
        assertEquals(turn, axis, 1e-6)
        val corners = RoomPlacement.rectangleRightOf(existing, width = 2.0, length = 1.5, wallThickness = 0.1, axisAngle = axis)
        assertTrue(Polygons.isCounterClockwise(corners))
        assertEquals(3.0, Polygons.area(corners), 1e-9)
        // Its first wall runs along the floor's walls, and it sits right of the floor in the floor's own frame.
        assertEquals(turn, (corners[1] - corners[0]).angle(), 1e-9)
        val local = corners.map { it.rotated(-turn) }
        assertEquals(4.0 + 0.2 + RoomPlacement.NEW_ROOM_GAP, local.minOf { it.x }, 1e-9)
        assertEquals(3.0, local.maxOf { it.y }, 1e-9)
        // A floor turned by more than 45° folds to the nearest page direction (width stays "left to right").
        val steep = listOf(rect("b", 0.0, 0.0, 4.0, 3.0).let { r -> r.copy(corners = r.corners.map { it.rotated(Math.toRadians(80.0)) }) })
        assertEquals(Math.toRadians(-10.0), RoomPlacement.drawingAxis(steep), 1e-6)
        assertEquals(0.0, RoomPlacement.drawingAxis(emptyList()), 0.0)
    }

    @Test
    fun typeChangesRenameOnlyDefaultNames() {
        val rooms = listOf(
            rect("1", 0.0, 0.0, 1.0, 1.0).copy(name = "Bedroom", type = RoomType.BEDROOM),
            rect("2", 0.0, 0.0, 1.0, 1.0).copy(name = "Bedroom 2", type = RoomType.BEDROOM),
            rect("3", 0.0, 0.0, 1.0, 1.0).copy(name = "Room 3"),
        )
        assertEquals("Bedroom 3", nameAfterTypeChange("Room 3", RoomType.OTHER, RoomType.BEDROOM, rooms, "3"))
        assertEquals("Office", nameAfterTypeChange("Room 3", RoomType.OTHER, RoomType.OFFICE, rooms, "3"))
        assertEquals("Kid's room", nameAfterTypeChange(" Kid's room ", RoomType.OTHER, RoomType.BEDROOM, rooms, "3"))
        assertEquals("Bedroom 3", nameAfterTypeChange("", RoomType.OTHER, RoomType.BEDROOM, rooms, "3"))
        assertEquals("Room 3", nameAfterTypeChange("Room 3", RoomType.OTHER, RoomType.OTHER, rooms, "3"))
    }

    // ---- Corner snapping ----

    @Test
    fun cornerSnapsSquareToNeighbours() {
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners
        // Dragging corner 2 (4,3) to slightly off-square: snaps x to corner 1 and y to corner 3.
        val snapped = CornerSnap.snap(corners, 2, Vec2(4.03, 3.04), axisAngle = 0.0, tolerance = 0.05)
        assertEquals(4.0, snapped.x, 1e-12)
        assertEquals(3.0, snapped.y, 1e-12)
        // Beyond tolerance: untouched.
        val free = CornerSnap.snap(corners, 2, Vec2(4.3, 3.4), axisAngle = 0.0, tolerance = 0.05)
        assertEquals(Vec2(4.3, 3.4), free)
    }

    @Test
    fun cornerSnapWorksInARotatedRoom() {
        val angle = Math.toRadians(30.0)
        val corners = rect("a", 0.0, 0.0, 4.0, 3.0).corners.map { it.rotated(angle) }
        val target = Vec2(4.02, 2.97).rotated(angle)
        val snapped = CornerSnap.snap(corners, 2, target, CornerSnap.axisAngle(corners), 0.05)
        assertTrue(snapped.approxEquals(Vec2(4.0, 3.0).rotated(angle), 1e-9))
    }

    @Test
    fun revealShiftBringsTheSelectedRoomAboveThePanel() {
        // Plan y 0..3 is screen y 1000..700.
        val room = rect("a", 0.0, 0.0, 2.0, 3.0)
        assertEquals(-140.0, revealShift(room, view, heightPx = 900.0, marginPx = 40.0), 1e-9)
        assertEquals(0.0, revealShift(room, view, heightPx = 1100.0, marginPx = 40.0), 0.0)
        // A room taller than the canvas: shift only until its top reaches the margin.
        val tall = rect("t", 0.0, 0.0, 2.0, 9.0) // screen y 1000..100
        assertEquals(-60.0, revealShift(tall, view, heightPx = 500.0, marginPx = 40.0), 1e-9)
    }

    // ---- Length input ----

    @Test
    fun bareNumberAndExplicitZeroDetection() {
        assertTrue(LengthInput.isBareNumber("32"))
        assertTrue(LengthInput.isBareNumber(" 4 1/2 "))
        assertTrue(LengthInput.isBareNumber("2,5"))
        assertFalse(LengthInput.isBareNumber("32\""))
        assertFalse(LengthInput.isBareNumber("2'8"))
        assertFalse(LengthInput.isBareNumber("80cm"))
        assertFalse(LengthInput.isBareNumber(""))

        assertTrue(LengthInput.isExplicitZero("0"))
        assertTrue(LengthInput.isExplicitZero("0.0"))
        assertTrue(LengthInput.isExplicitZero("0' 0\""))
        assertTrue(LengthInput.isExplicitZero("0 cm"))
        assertTrue(LengthInput.isExplicitZero("0ft 0in"))
        assertFalse(LengthInput.isExplicitZero("10"))
        assertFalse(LengthInput.isExplicitZero("-0"))
        assertFalse(LengthInput.isExplicitZero("0 apples"))
        assertEquals(0.0, LengthInput.parse("0\"", UnitSystem.IMPERIAL, allowZero = true))
        assertNull(LengthInput.parse("   ", UnitSystem.IMPERIAL, allowZero = true))
    }

    // ---- Settings sanitizing ----

    @Test
    fun sanitizedReplacesOnlyInvalidValues() {
        val ok = PlanSettings(wallThickness = 0.15, imperialDenominator = 8, squareToleranceDeg = 3.0)
        assertEquals(ok, ok.sanitized())
        val bad = PlanSettings(wallThickness = Double.NaN, imperialDenominator = 3, squareToleranceDeg = -1.0, defaultCeilingHeight = 0.0)
        val fixed = bad.sanitized()
        val d = PlanSettings()
        assertEquals(d.wallThickness, fixed.wallThickness, 0.0)
        assertEquals(d.imperialDenominator, fixed.imperialDenominator)
        assertEquals(d.squareToleranceDeg, fixed.squareToleranceDeg, 0.0)
        assertEquals(d.defaultCeilingHeight, fixed.defaultCeilingHeight, 0.0)
    }

    @Test
    fun sanitizedRepairsDefaultOpeningWidths() {
        val custom = PlanSettings(defaultDoorWidth = 0.9144, defaultWindowWidth = 1.2192)
        assertEquals(custom, custom.sanitized())
        val d = PlanSettings()
        val fixed = PlanSettings(defaultDoorWidth = -1.0, defaultWindowWidth = Double.POSITIVE_INFINITY).sanitized()
        assertEquals(d.defaultDoorWidth, fixed.defaultDoorWidth, 0.0)
        assertEquals(d.defaultWindowWidth, fixed.defaultWindowWidth, 0.0)
        assertEquals(d.defaultWindowWidth, PlanSettings(defaultWindowWidth = 0.0).sanitized().defaultWindowWidth, 0.0)
    }
}
