package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.AreaCategory
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.FloorIssueAction
import com.oroplex.floorplan.core.solve.IssueSeverity
import com.oroplex.floorplan.core.solve.QualityLevel
import com.oroplex.floorplan.core.solve.RoomOps
import com.oroplex.floorplan.core.solve.RoomQualityReport
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.data.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round-3 editor behaviour: aligning a later scan as one block ("Whole scan"), Square to floor, frame
 * bookkeeping, floor-check actions, typed-value provenance, ceiling-default propagation, floor order, room
 * naming / area categories and focus requests from other screens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorRound3Test {

    private class FakeRepository(initial: Project?) : ProjectRepository {
        val flow = MutableStateFlow(initial)
        var clock = 100L
        var saves = 0

        override val summaries: StateFlow<List<ProjectSummary>> = MutableStateFlow(emptyList())
        override fun observe(projectId: String): StateFlow<Project?> = flow
        override suspend fun get(projectId: String): Project? = flow.value?.takeIf { it.id == projectId }
        override suspend fun create(name: String, address: String): Project = error("unused")
        override suspend fun save(project: Project): Project {
            saves++
            return project.copy(updatedAt = ++clock).also { flow.value = it }
        }
        override suspend fun update(projectId: String, transform: (Project) -> Project): Project? =
            flow.value?.let { transform(it).copy(updatedAt = ++clock) }?.also { flow.value = it }
        override suspend fun delete(projectId: String) {
            flow.value = null
        }
        override suspend fun duplicate(projectId: String): Project? = error("unused")
        override suspend fun importJson(json: String): Project = error("unused")
        override fun exportJson(project: Project): String = error("unused")
    }

    /** The real core tools, with a fixed quality grade and recording of every snap request. */
    private open class RecordingTools : PlanTools by PlanTools.Core {
        val snapCalls = mutableListOf<Pair<String, Set<String>>>()
        var straightens = 0

        override fun snapGroup(
            floor: Floor,
            roomId: String,
            groupIds: Set<String>,
            wallThickness: Double,
            maxDistance: Double,
            maxAngleDeg: Double,
        ): Floor? {
            snapCalls += roomId to groupIds
            return PlanTools.Core.snapGroup(floor, roomId, groupIds, wallThickness, maxDistance, maxAngleDeg)
        }

        override fun straighten(floor: Floor): Floor {
            straightens++
            return floor.copy(rooms = floor.rooms.map { RoomOps.transform(it, RigidTransform2.rotation(0.1)) })
        }

        override fun quality(room: Room, unitSystem: UnitSystem) = RoomQualityReport(QualityLevel.GOOD, 1.0, emptyList())
    }

    private val t = PlanSettings().wallThickness

    private fun rect(id: String, x: Double, y: Double, w: Double, h: Double, name: String = id, session: String? = null) = Room(
        id = id,
        name = name,
        corners = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h)),
        captureMethod = if (session != null) CaptureMethod.WALLS else CaptureMethod.MANUAL,
        sessionId = session,
    )

    private fun project(vararg floors: Floor, settings: PlanSettings = PlanSettings()) =
        Project(id = "p1", name = "House", createdAt = 1, updatedAt = 1, floors = floors.toList(), settings = settings)

    private fun Room.moved(t: RigidTransform2) = RoomOps.transform(this, t)

    /**
     * Hallway (first scan) at x 0..4, and a later scan of two bedrooms that should sit right of it (B1 at
     * x 4+t..7+t, B2 one wall further). The later scan is offset by [offset] and then turned by [turnDeg] about
     * its own centre, as a second AR session would land.
     */
    private fun laterScan(offset: Double = 0.3, turnDeg: Double = 0.0): Floor {
        val hall = rect("hall", 0.0, 0.0, 4.0, 3.0, "Hallway", session = "s1")
        val b1 = rect("b1", 4.0 + t + offset, 0.0, 3.0, 3.0, "Bedroom", session = "s2")
        val b2 = rect("b2", 7.0 + 2 * t + offset, 0.0, 3.0, 3.0, "Bedroom 2", session = "s2")
        val center = Bounds.of(b1.corners + b2.corners).center
        val turn = RigidTransform2.rotationAround(center, Math.toRadians(turnDeg))
        return Floor("f1", "Main Floor", listOf(hall, b1.moved(turn), b2.moved(turn)))
    }

    private val logged = mutableListOf<String>()

    private fun vm(repo: FakeRepository, tools: PlanTools = RecordingTools()) =
        PlanEditorViewModel(repo, "p1", clock = { 1234L }, tools = tools, log = { m, _ -> logged += m })

    private fun PlanEditorViewModel.room(id: String): Room = state.value.project!!.floors.flatMap { it.rooms }.first { it.id == id }

    private fun TestScope.messagesOf(vm: PlanEditorViewModel): List<EditorMessage> {
        val list = mutableListOf<EditorMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { list += it } }
        return list
    }

    private fun minX(room: Room) = room.corners.minOf { it.x }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Whole scan ----

    @Test
    fun wholeScanStartsOnForALaterScanOnly() = runTest {
        val vm = vm(FakeRepository(project(laterScan())))
        vm.select(Selection.Room("b1"))
        assertEquals(setOf("b1", "b2"), vm.state.value.scanGroupIds)
        assertTrue(vm.state.value.wholeScan)
        vm.select(Selection.Room("hall"))
        assertTrue("the hallway is alone in its scan", vm.state.value.scanGroupIds.isEmpty())
        assertFalse(vm.state.value.wholeScan)

        // The user's choice is remembered for that scan.
        vm.select(Selection.Room("b1"))
        vm.setWholeScan(false)
        vm.select(Selection.Room("b2"))
        assertFalse(vm.state.value.wholeScan)
    }

    @Test
    fun aWholeScanMovesAsOneBlockAndKeepsItsFrame() = runTest {
        val floor = laterScan()
        val vm = vm(FakeRepository(project(floor)))
        vm.select(Selection.Room("b1"))
        vm.toggleMoveMode()
        assertTrue(vm.beginMove("b1"))
        assertEquals("the preview shows the whole scan", listOf("b1", "b2"), vm.state.value.previewRooms.map { it.id })

        vm.updateMove(Vec2(0.0, 1.0))
        assertEquals(1.0, vm.state.value.displayRooms.first { it.id == "b2" }.corners.minOf { it.y }, 1e-9)
        vm.endDrag()
        assertEquals(1.0, vm.room("b1").corners.minOf { it.y }, 1e-9)
        assertEquals(1.0, vm.room("b2").corners.minOf { it.y }, 1e-9)
        assertNull("a block move keeps the shared frame", vm.room("b1").frameId)
        assertNull(vm.room("b2").frameId)

        vm.undo()
        assertEquals(floor.rooms, vm.state.value.floor?.rooms)
        assertFalse("one drag is one undo step", vm.state.value.canUndo)
    }

    @Test
    fun movingOneRoomOfAScanTakesItOutOfTheScansFrame() = runTest {
        val vm = vm(FakeRepository(project(laterScan())))
        vm.select(Selection.Room("b1"))
        vm.setWholeScan(false)
        vm.beginMove("b1")
        assertEquals(listOf("b1"), vm.state.value.previewRooms.map { it.id })
        vm.updateMove(Vec2(0.0, 1.0))
        vm.endDrag()
        assertEquals("b1", vm.room("b1").frameId)
        assertNull(vm.room("b2").frameId)
        assertEquals(0.0, vm.room("b2").corners.minOf { it.y }, 1e-9)
        // B1 is now on its own: no Whole scan choice any more.
        assertTrue(vm.state.value.scanGroupIds.isEmpty())
    }

    @Test
    fun turningAWholeScanPivotsOnItsBoundsCentre() = runTest {
        val floor = laterScan()
        val vm = vm(FakeRepository(project(floor)))
        vm.select(Selection.Room("b1"))
        val before = Bounds.of(floor.rooms.filter { it.id != "hall" }.flatMap { it.corners })
        vm.rotateRoom("b1", 90.0)
        val after = Bounds.of(listOf(vm.room("b1"), vm.room("b2")).flatMap { it.corners })
        assertTrue(before.center.approxEquals(after.center, 1e-9))
        // The two bedrooms keep their layout relative to each other.
        val d0 = floor.rooms[1].corners[0].distanceTo(floor.rooms[2].corners[0])
        assertEquals(d0, vm.room("b1").corners[0].distanceTo(vm.room("b2").corners[0]), 1e-9)
        assertNull(vm.room("b1").frameId)

        // Turning a single room turns it about its own centre and detaches it.
        vm.setWholeScan(false)
        vm.rotateRoom("b1", 1.0)
        assertEquals("b1", vm.room("b1").frameId)
        assertNull(vm.room("b2").frameId)
    }

    @Test
    fun snapMovesTheWholeScanAgainstItsNeighbour() = runTest {
        val tools = RecordingTools()
        val vm = vm(FakeRepository(project(laterScan(offset = 0.3))), tools)
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b1"))
        vm.snapToNeighbour("b1")
        assertEquals(4.0 + t, minX(vm.room("b1")), 1e-6)
        assertEquals(7.0 + 2 * t, minX(vm.room("b2")), 1e-6)
        assertNull(vm.room("b1").frameId)
        assertNull(vm.room("b2").frameId)
        assertEquals("Snapped to Hallway.", messages.last().text)
        assertEquals("b1" to setOf("b1", "b2"), tools.snapCalls.first())

        vm.undoRevision(messages.last().undoRevision!!)
        assertEquals(4.0 + t + 0.3, minX(vm.room("b1")), 1e-9)
        assertEquals(7.0 + 2 * t + 0.3, minX(vm.room("b2")), 1e-9)
    }

    @Test
    fun aWholeScanCanBeSnappedThroughAnyOfItsRooms() = runTest {
        val vm = vm(FakeRepository(project(laterScan(offset = 0.3))))
        // B2 has no neighbour outside the scan; B1 does, so the scan still snaps.
        vm.select(Selection.Room("b2"))
        vm.snapToNeighbour("b2")
        assertEquals(4.0 + t, minX(vm.room("b1")), 1e-6)
        assertEquals(7.0 + 2 * t, minX(vm.room("b2")), 1e-6)
    }

    @Test
    fun snappingOneRoomDetachesItAndLeavesTheRestOfTheScan() = runTest {
        // Within a scan the rooms are already aligned with each other: alone, B1 is "already snapped" to B2.
        val messages0 = mutableListOf<String>()
        val vm0 = vm(FakeRepository(project(laterScan(offset = 0.3))))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm0.messages.collect { messages0 += it.text } }
        vm0.select(Selection.Room("b1"))
        vm0.setWholeScan(false)
        vm0.snapToNeighbour("b1")
        assertEquals("Already snapped to Bedroom 2.", messages0.last())
        assertFalse(vm0.state.value.canUndo)

        // A room of a later scan whose sibling is far away snaps on its own and leaves the scan's frame.
        val hall = rect("hall", 0.0, 0.0, 4.0, 3.0, "Hallway", session = "s1")
        val b = rect("b", 4.0 + t + 0.3, 0.0, 3.0, 3.0, "Bedroom", session = "s2")
        val c = rect("c", 20.0, 0.0, 3.0, 3.0, "Closet", session = "s2")
        val vm = vm(FakeRepository(project(Floor("f1", "Main Floor", listOf(hall, b, c)))))
        vm.select(Selection.Room("b"))
        vm.setWholeScan(false)
        vm.snapToNeighbour("b")
        assertEquals(4.0 + t, minX(vm.room("b")), 1e-6)
        assertEquals("b", vm.room("b").frameId)
        assertEquals(20.0, minX(vm.room("c")), 1e-9)
        assertNull(vm.room("c").frameId)
    }

    @Test
    fun squareToFloorTurnsTheScanBySmallestAngle() = runTest {
        val floor = laterScan(turnDeg = 37.0)
        val vm = vm(FakeRepository(project(floor)))
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b1"))
        vm.squareToFloor("b1")
        val b1 = vm.room("b1")
        val angle = Math.toDegrees((b1.corners[1] - b1.corners[0]).angle())
        assertEquals(0.0, angle, 1e-6)
        // Both bedrooms turned together: the scan is exactly back in its un-turned layout (offset 0.3).
        val reference = laterScan(turnDeg = 0.0)
        for (id in listOf("b1", "b2")) {
            val expected = reference.rooms.first { it.id == id }
            assertTrue(sameGeometry(expected, vm.room(id), 1e-6))
            assertNull(vm.room(id).frameId)
        }
        assertEquals("Turned 37° to line up with the floor.", messages.last().text)
        assertNotNull(messages.last().undoRevision)

        vm.squareToFloor("b1")
        assertEquals("This scan already runs square with the floor.", messages.last().text)
    }

    @Test
    fun squaringASingleRoomDetachesIt() = runTest {
        val vm = vm(FakeRepository(project(laterScan(turnDeg = -20.0))))
        vm.select(Selection.Room("b1"))
        vm.setWholeScan(false)
        vm.squareToFloor("b1")
        assertEquals("b1", vm.room("b1").frameId)
        val b2 = vm.room("b2")
        assertEquals(-20.0, Math.toDegrees((b2.corners[1] - b2.corners[0]).angle()), 1e-6)
    }

    @Test
    fun snapSquaresAndSnapsATurnedScanInOneStep() = runTest {
        val floor = laterScan(offset = 0.3, turnDeg = 37.0)
        val vm = vm(FakeRepository(project(floor)))
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b1"))
        vm.snapToNeighbour("b1")
        assertEquals("Turned 37° and snapped to Hallway.", messages.last().text)
        assertEquals(4.0 + t, minX(vm.room("b1")), 1e-6)
        assertEquals(7.0 + 2 * t, minX(vm.room("b2")), 1e-6)
        assertEquals(0.0, vm.room("b1").corners.minOf { it.y }, 1e-6)

        // One undo step brings back the turned scan.
        vm.undoRevision(messages.last().undoRevision!!)
        assertEquals(floor.rooms, vm.state.value.floor?.rooms)
    }

    @Test
    fun aTurnedScanTooFarAwayIsExplained() = runTest {
        // Pretend the 12° snap and the squared snap find nothing, but a facing wall 37° off exists.
        val turned = laterScan(offset = 0.0, turnDeg = 37.0)
        val aligned = laterScan(offset = 0.0, turnDeg = 0.0)
        val tools = object : RecordingTools() {
            override fun snapGroup(
                floor: Floor,
                roomId: String,
                groupIds: Set<String>,
                wallThickness: Double,
                maxDistance: Double,
                maxAngleDeg: Double,
            ): Floor? = if (maxAngleDeg >= 45.0) aligned else null
        }
        val vm = vm(FakeRepository(project(turned)), tools)
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b1"))
        vm.snapToNeighbour("b1")
        assertEquals("Its walls are turned 37° from Hallway's — tap Square to floor.", messages.last().text)
        assertEquals("nothing moved", turned.rooms, vm.state.value.floor?.rooms)
        assertFalse(vm.state.value.canUndo)
    }

    @Test
    fun noNeighbourAtAllSaysSo() = runTest {
        val vm = vm(FakeRepository(project(laterScan(offset = 3.0))))
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b1"))
        vm.snapToNeighbour("b1")
        assertEquals("No wall of another room is close enough. Move this scan next to its neighbour and try again.", messages.last().text)
        // A floor that only holds the scan has nothing to snap or square to.
        val alone = Floor("f1", "Main Floor", laterScan().rooms.filter { it.id != "hall" })
        val vm2 = vm(FakeRepository(project(alone)))
        val messages2 = messagesOf(vm2)
        vm2.select(Selection.Room("b1"))
        vm2.setWholeScan(true)
        vm2.snapToNeighbour("b1")
        assertTrue(messages2.last().text.startsWith("Every room on this floor is part of this scan"))
        vm2.squareToFloor("b1")
        assertTrue(messages2.last().text.startsWith("Add another room to this floor first"))
    }

    @Test
    fun movingAWholeScanNextToItsNeighbourSnapsAllOfIt() = runTest {
        val vm = vm(FakeRepository(project(laterScan(offset = 1.0))))
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b1"))
        vm.toggleMoveMode()
        vm.beginMove("b1")
        vm.updateMove(Vec2(-0.8, 0.0), snapDistance = 0.3)
        val guides = vm.state.value.dragGuides
        assertEquals("a ghost per moved room", 2, guides?.snapOutlines?.size)
        assertEquals("Hallway", guides?.snapTargetName)
        vm.endDrag()
        assertEquals(4.0 + t, minX(vm.room("b1")), 1e-6)
        assertEquals(7.0 + 2 * t, minX(vm.room("b2")), 1e-6)
        assertEquals("Snapped to Hallway.", messages.last().text)
        assertNull(vm.room("b1").frameId)
    }

    // ---- Floor-check actions ----

    @Test
    fun floorCheckActionsStraightenOrSnapTheWholeScan() = runTest {
        val tools = RecordingTools()
        val vm = vm(FakeRepository(project(laterScan(offset = 0.3))), tools)
        val messages = messagesOf(vm)

        vm.runIssueAction(FloorIssue(IssueSeverity.INFO, null, "Plan is turned", FloorIssueAction.STRAIGHTEN_FLOOR))
        assertEquals(1, tools.straightens)
        assertEquals("Main Floor straightened.", messages.last().text)
        vm.undoRevision(messages.last().undoRevision!!)

        // SNAP_ROOM selects the room with Whole scan on, even after the user turned it off.
        vm.select(Selection.Room("b2"))
        vm.setWholeScan(false)
        vm.clearSelection()
        vm.runIssueAction(FloorIssue(IssueSeverity.INFO, "b2", "Bedroom 2 was scanned separately", FloorIssueAction.SNAP_ROOM))
        assertEquals(Selection.Room("b2"), vm.state.value.selection)
        assertTrue(vm.state.value.wholeScan)
        assertEquals(setOf("b1", "b2"), tools.snapCalls.last().second)
        assertEquals(4.0 + t, minX(vm.room("b1")), 1e-6)

        // An item without an action just shows its room.
        vm.runIssueAction(FloorIssue(IssueSeverity.WARNING, "hall", "Hallway: 1 warning"))
        assertEquals(Selection.Room("hall"), vm.state.value.selection)
    }

    // ---- Provenance of typed values ----

    @Test
    fun editedOpeningsAreTypedButUntouchedCaptureOpeningsStayMeasured() = runTest {
        val captured = Opening("o1", OpeningType.DOOR, 0, 1.0, 0.8, 2.0)
        val room = rect("a", 0.0, 0.0, 4.0, 3.0, session = "s1").copy(openings = listOf(captured))
        val vm = vm(FakeRepository(project(Floor("f1", "Main Floor", listOf(room)))))
        vm.saveOpening("a", captured.copy(swing = com.oroplex.floorplan.core.model.DoorSwing.RIGHT_OUT))
        assertFalse("only the swing changed", vm.room("a").openings.single().typed)
        vm.saveOpening("a", vm.room("a").openings.single().copy(width = 0.9))
        assertTrue(vm.room("a").openings.single().typed)
        vm.saveOpening("a", Opening("o2", OpeningType.WINDOW, 1, 0.5, 1.0, 1.2))
        assertTrue("a new opening is the user's own", vm.room("a").openings.first { it.id == "o2" }.typed)
    }

    // ---- Project settings ----

    @Test
    fun aNewDefaultCeilingCanBeAppliedToEstimatedRooms() = runTest {
        val old = PlanSettings().defaultCeilingHeight
        val estimated = rect("a", 0.0, 0.0, 3.0, 3.0, session = "s").copy(ceilingHeight = old, ceilingEstimated = true)
        val reused = rect("b", 4.0, 0.0, 3.0, 3.0, session = "s").copy(ceilingHeight = 2.7, ceilingEstimated = true)
        val measured = rect("c", 8.0, 0.0, 3.0, 3.0, session = "s").copy(ceilingHeight = old)
        val typed = rect("d", 12.0, 0.0, 3.0, 3.0, session = "s").copy(ceilingHeight = old, ceilingTyped = true)
        val upstairs = rect("e", 0.0, 0.0, 3.0, 3.0, session = "s").copy(ceilingHeight = old + 0.0005, ceilingEstimated = true)
        val p = project(Floor("f1", "Main", listOf(estimated, reused, measured, typed)), Floor("f2", "Upper", listOf(upstairs)))
        assertEquals(2, estimatedCeilingRooms(p, old))

        val vm = vm(FakeRepository(p))
        val messages = messagesOf(vm)
        val nine = 2.7432
        vm.updateSettings(p.settings.copy(defaultCeilingHeight = nine), applyCeilingToEstimated = true)
        assertEquals(nine, vm.state.value.settings.defaultCeilingHeight, 0.0)
        assertEquals(nine, vm.room("a").ceilingHeight ?: 0.0, 0.0)
        assertTrue("still an estimate", vm.room("a").ceilingEstimated)
        assertEquals(nine, vm.room("e").ceilingHeight ?: 0.0, 0.0)
        assertEquals(2.7, vm.room("b").ceilingHeight ?: 0.0, 0.0)
        assertEquals(old, vm.room("c").ceilingHeight ?: 0.0, 0.0)
        assertEquals(old, vm.room("d").ceilingHeight ?: 0.0, 0.0)
        assertTrue(messages.last().text.contains("in 2 rooms"))

        // Settings and rooms change in ONE undo step.
        vm.undo()
        assertEquals(p.floors, vm.state.value.project?.floors)
        assertEquals(old, vm.state.value.settings.defaultCeilingHeight, 0.0)
        assertFalse(vm.state.value.canUndo)

        // Without the option only the setting changes.
        vm.updateSettings(p.settings.copy(defaultCeilingHeight = nine))
        assertEquals(old, vm.room("a").ceilingHeight ?: 0.0, 0.0)
    }

    // ---- Floors ----

    @Test
    fun floorsCanBeReorderedAsOneUndoStep() = runTest {
        val p = project(Floor("f1", "Main"), Floor("f2", "Basement"), Floor("f3", "Upper"))
        val vm = vm(FakeRepository(p))
        vm.moveFloor("f2", -1)
        assertEquals(listOf("f2", "f1", "f3"), vm.state.value.project?.floors?.map { it.id })
        vm.moveFloor("f2", -1) // already first
        vm.moveFloor("f3", 5) // already last
        vm.moveFloor("nope", 1)
        vm.undo()
        assertEquals(listOf("f1", "f2", "f3"), vm.state.value.project?.floors?.map { it.id })
        assertFalse(vm.state.value.canUndo)
        vm.moveFloor("f1", 1)
        assertEquals(listOf("f2", "f1", "f3"), vm.state.value.project?.floors?.map { it.id })
    }

    // ---- Room details ----

    @Test
    fun changingTheTypeRenamesDefaultNamesWithoutDuplicates() = runTest {
        val floor = Floor(
            "f1", "Main",
            listOf(
                rect("1", 0.0, 0.0, 3.0, 3.0, "Bedroom").copy(type = RoomType.BEDROOM),
                rect("2", 4.0, 0.0, 3.0, 3.0, "Bedroom 2").copy(type = RoomType.BEDROOM),
                rect("3", 8.0, 0.0, 3.0, 3.0, "Room 3"),
                rect("4", 12.0, 0.0, 3.0, 3.0, "Kid's room"),
            ),
        )
        val vm = vm(FakeRepository(project(floor)))
        vm.renameRoom("3", "Room 3", RoomType.BEDROOM)
        assertEquals("Bedroom 3", vm.room("3").name)
        vm.renameRoom("4", "Kid's room", RoomType.BEDROOM)
        assertEquals("a name the user chose is kept", "Kid's room", vm.room("4").name)
        vm.renameRoom("4", "  ", RoomType.OFFICE)
        assertEquals("Office", vm.room("4").name)
    }

    @Test
    fun areaCategoryIsStoredOnlyWhenItDiffersFromTheType() = runTest {
        val garage = rect("g", 0.0, 0.0, 6.0, 6.0, "Garage").copy(type = RoomType.GARAGE)
        val vm = vm(FakeRepository(project(Floor("f1", "Main", listOf(garage)))))
        vm.updateRoomDetails("g", "Garage", RoomType.GARAGE, AreaCategory.LIVING)
        assertEquals(AreaCategory.LIVING, vm.room("g").areaCategory)
        vm.updateRoomDetails("g", "Garage", RoomType.GARAGE, AreaCategory.NON_LIVING)
        assertNull("the type's own default is not stored", vm.room("g").areaCategory)
        // Renaming keeps an explicit choice.
        vm.updateRoomDetails("g", "Garage", RoomType.GARAGE, AreaCategory.EXCLUDED)
        vm.renameRoom("g", "Shop", RoomType.GARAGE)
        assertEquals(AreaCategory.EXCLUDED, vm.room("g").areaCategory)
        assertEquals("Shop", vm.room("g").name)
    }

    @Test
    fun drawnRoomsFollowATurnedFloorAndGetUniqueNames() = runTest {
        val turn = RigidTransform2.rotation(Math.toRadians(23.0))
        val bedroom = rect("a", 0.0, 0.0, 4.0, 3.0, "Bedroom", session = "s").copy(type = RoomType.BEDROOM).moved(turn)
        val vm = vm(FakeRepository(project(Floor("f1", "Main", listOf(bedroom)))))
        vm.drawRoom(RoomType.BEDROOM, 2.0, 1.5)
        val drawn = vm.state.value.floor!!.rooms.last()
        assertEquals("Bedroom 2", drawn.name)
        assertEquals(CaptureMethod.MANUAL, drawn.captureMethod)
        assertEquals(23.0, Math.toDegrees((drawn.corners[1] - drawn.corners[0]).angle()), 1e-6)
        assertEquals(Selection.Room(drawn.id), vm.state.value.selection)
    }

    // ---- Focus requests ----

    @Test
    fun aRequestedRoomIsShownEvenOnAFloorNeverShownBefore() = runTest {
        val upstairs = Floor("f2", "Upstairs", listOf(rect("c", 0.0, 0.0, 2.0, 2.0)))
        val vm = vm(FakeRepository(project(laterScan(), upstairs)))
        val messages = messagesOf(vm)
        assertNull(vm.viewFor("f2"))
        vm.showRequestedRoom("c")
        assertEquals("f2", vm.state.value.floorId)
        assertEquals(Selection.Room("c"), vm.state.value.selection)
        // The canvas fits the new floor first, then carries out this focus.
        val request = vm.viewRequest("f2")
        assertTrue(request is ViewRequest.Focus)
        assertTrue((request as ViewRequest.Focus).bounds.contains(Vec2(1.0, 1.0)))
        assertTrue(messages.isEmpty())

        vm.showRequestedRoom("gone")
        assertEquals("That room is no longer in this project.", messages.last().text)
        assertEquals(Selection.Room("c"), vm.state.value.selection)
    }

    @Test
    fun focusOnAFloorAlreadyShownAlsoAnimates() = runTest {
        val vm = vm(FakeRepository(project(laterScan())))
        vm.setView("f1", ViewTransform(50.0, Vec2(0.0, 500.0), 2.0))
        assertTrue(vm.focusRoom("b2"))
        assertTrue(vm.viewRequest("f1") is ViewRequest.Focus)
        assertFalse(vm.focusRoom("missing"))
    }
}
