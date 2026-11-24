package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.solve.FloorIssue
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditorViewModelTest {

    /** In-memory repository holding one project; emits through a StateFlow like the real one. */
    private class FakeRepository(initial: Project?) : ProjectRepository {
        val flow = MutableStateFlow(initial)
        var clock = 100L
        var saves = 0
        var failSaves = false

        override val summaries: StateFlow<List<ProjectSummary>> = MutableStateFlow(emptyList())
        override fun observe(projectId: String): StateFlow<Project?> = flow
        override suspend fun get(projectId: String): Project? = flow.value?.takeIf { it.id == projectId }
        override suspend fun create(name: String, address: String): Project = error("unused")
        override suspend fun save(project: Project): Project {
            if (failSaves) throw java.io.IOException("disk full")
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

    /** Deterministic stand-ins for the core floor tools; every hook can be replaced per test. */
    private class FakeTools : PlanTools {
        var snap: (Floor, String, Double, Double, Double) -> Floor? = { _, _, _, _, _ -> null }
        var straightenFn: (Floor) -> Floor = { it }
        val rotations = mutableListOf<Double>()
        var overlapsFn: (Floor) -> List<Pair<String, String>> = { emptyList() }
        var checksFn: (Floor, PlanSettings) -> List<FloorIssue> = { _, _ -> emptyList() }
        var checkCalls = 0
        var qualityFn: (Room) -> RoomQualityReport = { RoomQualityReport(QualityLevel.GOOD, 1.0, emptyList()) }

        override fun snapRoom(floor: Floor, roomId: String, wallThickness: Double, maxDistance: Double, maxAngleDeg: Double) =
            snap(floor, roomId, wallThickness, maxDistance, maxAngleDeg)
        override fun straighten(floor: Floor) = straightenFn(floor)
        override fun rotate(floor: Floor, angle: Double): Floor {
            rotations += angle
            return floor.copy(rooms = floor.rooms.map { RoomOps.transform(it, RigidTransform2.rotation(angle)) })
        }
        override fun checks(floor: Floor, settings: PlanSettings): List<FloorIssue> {
            checkCalls++
            return checksFn(floor, settings)
        }
        override fun overlaps(floor: Floor) = overlapsFn(floor)
        override fun quality(room: Room) = qualityFn(room)
    }

    /** Tools that fail on every call, like a core bug on unexpected data would. */
    private class BrokenTools : PlanTools {
        override fun snapRoom(floor: Floor, roomId: String, wallThickness: Double, maxDistance: Double, maxAngleDeg: Double): Floor? = error("boom")
        override fun straighten(floor: Floor): Floor = error("boom")
        override fun rotate(floor: Floor, angle: Double): Floor = error("boom")
        override fun checks(floor: Floor, settings: PlanSettings): List<FloorIssue> = error("boom")
        override fun overlaps(floor: Floor): List<Pair<String, String>> = error("boom")
        override fun quality(room: Room): RoomQualityReport = error("boom")
    }

    private fun rect(id: String, x: Double, y: Double, w: Double, h: Double, name: String = id) = Room(
        id = id,
        name = name,
        corners = listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h)),
    )

    private val project = Project(
        id = "p1",
        name = "House",
        createdAt = 1,
        updatedAt = 1,
        floors = listOf(Floor("f1", "Main Floor")),
    )

    private val thickness = PlanSettings().wallThickness

    private fun oneFloor(vararg rooms: Room) = project.copy(floors = listOf(Floor("f1", "Main Floor", rooms.toList())))

    /** Hallway A at x 0..4 and bedroom B 1 m to its right (x 5..8). */
    private val twoRooms = project.copy(
        floors = listOf(Floor("f1", "Main Floor", listOf(rect("a", 0.0, 0.0, 4.0, 3.0, "Hallway"), rect("b", 5.0, 0.0, 3.0, 3.0, "Bedroom")))),
    )

    /** Like [twoRooms] but the bedroom is only 0.5 m away, close enough for "Snap to neighbour". */
    private val closeRooms = twoRooms.copy(
        floors = listOf(twoRooms.floors[0].copy(rooms = listOf(twoRooms.floors[0].rooms[0], rect("b", 4.5, 0.0, 3.0, 3.0, "Bedroom")))),
    )

    /** Snaps a room so its left wall sits one wall thickness right of x = 4, if it is within maxDistance of that. */
    private val snapRightOfHallway: (Floor, String, Double, Double, Double) -> Floor? = { floor, id, t, maxDistance, _ ->
        val room = floor.rooms.first { it.id == id }
        val minX = room.corners.minOf { it.x }
        val target = 4.0 + t
        if (abs(minX - target) > maxDistance) {
            null
        } else {
            floor.copy(rooms = floor.rooms.map { if (it.id == id) RoomOps.transform(it, RigidTransform2(0.0, Vec2(target - minX, 0.0))) else it })
        }
    }

    private val logged = mutableListOf<String>()

    private fun vm(repo: FakeRepository, tools: PlanTools = FakeTools()) =
        PlanEditorViewModel(repo, "p1", clock = { 1234L }, tools = tools, log = { m, _ -> logged += m })

    private fun PlanEditorViewModel.room(id: String): Room = state.value.project!!.floors.flatMap { it.rooms }.first { it.id == id }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.messagesOf(vm: PlanEditorViewModel): List<EditorMessage> {
        val list = mutableListOf<EditorMessage>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { list += it } }
        return list
    }

    // ---- Loading, saving, undo ----

    @Test
    fun loadsTheProjectAndSelectsTheFirstFloor() = runTest {
        val vm = vm(FakeRepository(project))
        val s = vm.state.value
        assertEquals(EditorStatus.READY, s.status)
        assertEquals("f1", s.floorId)
        assertEquals(project, s.project)
        assertFalse(s.canUndo)
    }

    @Test
    fun missingProjectIsReported() = runTest {
        val vm = vm(FakeRepository(null))
        assertEquals(EditorStatus.MISSING, vm.state.value.status)
    }

    @Test
    fun editsAreSavedAndUndoable() = runTest {
        val repo = FakeRepository(project)
        val vm = vm(repo)

        vm.renameProject("  Cottage ", "1 Lane")
        assertEquals("Cottage", vm.state.value.project?.name)
        assertEquals("Cottage", repo.flow.value?.name)
        assertEquals("1 Lane", repo.flow.value?.address)
        assertTrue(vm.state.value.canUndo)
        // The echo of our own save is adopted (stamped updatedAt) without becoming an undo step.
        assertEquals(repo.flow.value?.updatedAt, vm.state.value.project?.updatedAt)

        vm.undo()
        assertEquals("House", vm.state.value.project?.name)
        assertEquals("House", repo.flow.value?.name)
        assertFalse(vm.state.value.canUndo)
        assertTrue(vm.state.value.canRedo)

        vm.redo()
        assertEquals("Cottage", vm.state.value.project?.name)
        assertEquals("Cottage", repo.flow.value?.name)
        assertEquals(3, repo.saves)
    }

    @Test
    fun unchangedEditsAreNotRecorded() = runTest {
        val repo = FakeRepository(project)
        val vm = vm(repo)
        vm.renameProject("House", "")
        vm.renameProject("   ", "x") // blank names are ignored
        assertFalse(vm.state.value.canUndo)
        assertEquals(0, repo.saves)
    }

    @Test
    fun externalChangesAreAdoptedAsAnUndoableStepAndRefitTheView() = runTest {
        val repo = FakeRepository(project)
        val vm = vm(repo)
        val view = ViewTransform(10.0, Vec2.ZERO, 1.0)
        vm.setView("f1", view)

        // e.g. the capture screen saved new data into the project
        repo.update("p1") { it.copy(notes = "scanned") }
        assertEquals("scanned", vm.state.value.project?.notes)
        assertTrue(vm.state.value.canUndo)
        // The current view stays until the canvas animates it to the new fit.
        assertEquals(view, vm.viewFor("f1"))
        assertTrue(vm.viewRequest("f1") is ViewRequest.Fit)

        vm.undo()
        assertEquals("", vm.state.value.project?.notes)
        assertEquals("", repo.flow.value?.notes)
    }

    @Test
    fun deletionElsewhereShowsMissing() = runTest {
        val repo = FakeRepository(project)
        val vm = vm(repo)
        repo.delete("p1")
        assertEquals(EditorStatus.MISSING, vm.state.value.status)
    }

    @Test
    fun floorsCanBeAddedRenamedAndDeleted() = runTest {
        val repo = FakeRepository(project)
        val vm = vm(repo)
        val messages = messagesOf(vm)

        vm.deleteFloor("f1")
        assertEquals(1, vm.state.value.project?.floors?.size)
        assertEquals(listOf("A project needs at least one floor."), messages.map { it.text })

        vm.addFloor("Basement")
        val floors = vm.state.value.project?.floors.orEmpty()
        assertEquals(listOf("Main Floor", "Basement"), floors.map { it.name })
        assertEquals("the new floor is shown", floors[1].id, vm.state.value.floorId)

        vm.renameFloor(floors[1].id, "Lower Level")
        assertEquals("Lower Level", repo.flow.value?.floors?.get(1)?.name)

        vm.deleteFloor(floors[1].id)
        assertEquals(listOf("f1"), vm.state.value.project?.floors?.map { it.id })
        assertEquals("f1", vm.state.value.floorId)

        vm.undo()
        assertEquals(2, vm.state.value.project?.floors?.size)
    }

    @Test
    fun selectingAMissingRoomIsIgnored() = runTest {
        val vm = vm(FakeRepository(project))
        vm.select(Selection.Room("nope"))
        assertNull(vm.state.value.selection)
        vm.toggleMoveMode()
        assertFalse(vm.state.value.moveMode)
    }

    @Test
    fun settingsAreSanitizedAndUndoable() = runTest {
        val repo = FakeRepository(project)
        val vm = vm(repo)
        vm.updateSettings(PlanSettings(unitSystem = UnitSystem.METRIC, wallThickness = -3.0))
        val saved = repo.flow.value?.settings
        assertNotNull(saved)
        assertEquals(UnitSystem.METRIC, saved?.unitSystem)
        assertEquals(PlanSettings().wallThickness, saved?.wallThickness ?: 0.0, 0.0)
        vm.undo()
        assertEquals(UnitSystem.IMPERIAL, repo.flow.value?.settings?.unitSystem)
    }

    @Test
    fun failedSavesKeepTheEditAndReportIt() = runTest {
        val repo = FakeRepository(project).apply { failSaves = true }
        val vm = vm(repo)
        val messages = messagesOf(vm)
        vm.renameProject("Cottage", "")
        assertEquals("Cottage", vm.state.value.project?.name)
        assertEquals("House", repo.flow.value?.name)
        assertEquals(1, messages.size)
        assertTrue(messages[0].text.startsWith("Couldn't save your changes"))

        repo.failSaves = false
        vm.renameProject("Cottage 2", "")
        assertEquals("Cottage 2", repo.flow.value?.name)
    }

    @Test
    fun undoHistoryIsCappedAtFifty() = runTest {
        val vm = vm(FakeRepository(project))
        repeat(60) { vm.renameProject("Name $it", "") }
        var undos = 0
        while (vm.state.value.canUndo) {
            vm.undo()
            undos++
        }
        assertEquals(PlanEditorViewModel.MAX_UNDO, undos)
        assertEquals("Name 9", vm.state.value.project?.name)
    }

    // ---- Corner drags ----

    @Test
    fun cornerDragIsOneUndoStepAndClearsStaleWarnings() = runTest {
        val warned = rect("a", 0.0, 0.0, 4.0, 3.0).copy(warnings = listOf("Corner 3 forms a very sharp spike; check walls 2 and 3"))
        val vm = vm(FakeRepository(oneFloor(warned)))
        vm.select(Selection.Room("a"))
        assertTrue(vm.beginCornerDrag("a", 2))
        vm.updateCornerDrag(Vec2(4.4, 3.3))
        vm.updateCornerDrag(Vec2(4.5, 3.5))
        assertEquals(Vec2(4.5, 3.5), vm.state.value.preview?.corners?.get(2))
        assertEquals(warned, vm.room("a")) // nothing committed while dragging
        vm.endDrag()

        val edited = vm.room("a")
        assertEquals(Vec2(4.5, 3.5), edited.corners[2])
        assertTrue("manual reshaping clears the solver's warnings", edited.warnings.isEmpty())
        assertNull(vm.state.value.preview)

        vm.undo()
        assertEquals(warned, vm.room("a"))
        assertFalse("one drag is one undo step", vm.state.value.canUndo)
    }

    @Test
    fun cornerDragSnapsOntoSquareCornersAndShowsGuides() = runTest {
        val vm = vm(FakeRepository(oneFloor(rect("a", 0.0, 0.0, 4.0, 3.0))))
        vm.select(Selection.Room("a"))
        vm.beginCornerDrag("a", 2)

        vm.updateCornerDrag(Vec2(4.03, 3.02), snapTolerance = 0.05)
        assertTrue(Vec2(4.0, 3.0).approxEquals(vm.state.value.preview!!.corners[2], 1e-9))
        val guides = vm.state.value.dragGuides
        assertNotNull(guides?.snapKey)
        assertEquals(3, guides?.rightAngles?.size)
        // Guide lines run from the aligned corners to the dragged one.
        assertTrue(guides!!.lines.all { it.b.approxEquals(Vec2(4.0, 3.0), 1e-9) })

        vm.updateCornerDrag(Vec2(4.5, 3.5), snapTolerance = 0.05)
        assertEquals(Vec2(4.5, 3.5), vm.state.value.preview?.corners?.get(2))
        assertNull("no guides once the corner moves freely", vm.state.value.dragGuides)

        vm.endDrag()
        assertNull(vm.state.value.dragGuides)
    }

    @Test
    fun anInvalidCornerPositionKeepsTheLastValidShape() = runTest {
        val vm = vm(FakeRepository(oneFloor(rect("a", 0.0, 0.0, 4.0, 3.0))))
        vm.beginCornerDrag("a", 1)
        vm.updateCornerDrag(Vec2(4.2, 0.1))
        val valid = vm.state.value.preview
        vm.updateCornerDrag(Vec2(2.0, 4.0)) // would cross the top wall
        assertSame(valid, vm.state.value.preview)
        vm.updateCornerDrag(Vec2(Double.NaN, 0.0))
        assertSame(valid, vm.state.value.preview)
    }

    // ---- Moving and snapping rooms ----

    @Test
    fun moveReleasedNearANeighbourSnapsAsASecondUndoStep() = runTest {
        val tools = FakeTools().apply { snap = snapRightOfHallway }
        val vm = vm(FakeRepository(twoRooms), tools)
        val messages = messagesOf(vm)
        vm.select(Selection.Room("b"))
        vm.toggleMoveMode()
        assertTrue(vm.beginMove("b"))

        vm.updateMove(Vec2(-0.8, 0.0), snapDistance = 0.3)
        val guides = vm.state.value.dragGuides
        assertNotNull(guides?.snapOutline)
        assertEquals(4.0 + thickness, guides!!.snapOutline!!.minOf { it.x }, 1e-9)
        assertEquals("Hallway", guides.snapTargetName)
        assertEquals(4.2, vm.state.value.preview!!.corners.minOf { it.x }, 1e-9)

        vm.endDrag()
        assertEquals(4.0 + thickness, vm.room("b").corners.minOf { it.x }, 1e-9)
        val snapped = messages.last()
        assertEquals("Snapped to Hallway.", snapped.text)
        assertNotNull(snapped.undoRevision)

        // Snackbar Undo reverts only the snap: the room stays where the finger left it.
        vm.undoRevision(snapped.undoRevision!!)
        assertEquals(4.2, vm.room("b").corners.minOf { it.x }, 1e-9)
        vm.undo()
        assertEquals(5.0, vm.room("b").corners.minOf { it.x }, 1e-9)
    }

    @Test
    fun aMoveAwayFromNeighboursJustMoves() = runTest {
        val tools = FakeTools().apply { snap = snapRightOfHallway }
        val vm = vm(FakeRepository(twoRooms), tools)
        val messages = messagesOf(vm)
        vm.beginMove("b")
        vm.updateMove(Vec2(2.0, 1.0), snapDistance = 0.3)
        assertNull(vm.state.value.dragGuides)
        vm.endDrag()
        assertEquals(7.0, vm.room("b").corners.minOf { it.x }, 1e-9)
        assertTrue(messages.isEmpty())
        // Without a snap distance the move never looks for neighbours.
        vm.beginMove("b")
        vm.updateMove(Vec2(-2.8, -1.0))
        assertNull(vm.state.value.dragGuides)
        vm.endDrag()
        assertEquals(4.2, vm.room("b").corners.minOf { it.x }, 1e-9)
    }

    @Test
    fun snapToNeighbourAlignsTheRoomOrExplainsWhyNot() = runTest {
        val tools = FakeTools().apply { snap = snapRightOfHallway }
        val vm = vm(FakeRepository(closeRooms), tools)
        val messages = messagesOf(vm)

        vm.snapToNeighbour("b")
        assertEquals(4.0 + thickness, vm.room("b").corners.minOf { it.x }, 1e-9)
        assertEquals("Snapped to Hallway.", messages.last().text)
        assertNotNull(messages.last().undoRevision)

        vm.snapToNeighbour("b")
        assertEquals("Already snapped to Hallway.", messages.last().text)
        assertNull(messages.last().undoRevision)

        tools.snap = { _, _, _, _, _ -> null }
        vm.snapToNeighbour("a")
        assertTrue(messages.last().text.startsWith("No wall of another room is close enough"))
        assertNull(messages.last().undoRevision)
    }

    @Test
    fun snapToNeighbourNeedsAnotherRoom() = runTest {
        val tools = FakeTools().apply { snap = { _, _, _, _, _ -> error("must not be called") } }
        val vm = vm(FakeRepository(project.copy(floors = listOf(Floor("f1", "Main Floor", listOf(rect("a", 0.0, 0.0, 4.0, 3.0)))))), tools)
        val messages = messagesOf(vm)
        vm.snapToNeighbour("a")
        assertTrue(messages.single().text.startsWith("Add another room"))
    }

    @Test
    fun aLateSnackbarUndoNeverRevertsALaterEdit() = runTest {
        val tools = FakeTools().apply { snap = snapRightOfHallway }
        val vm = vm(FakeRepository(closeRooms), tools)
        val messages = messagesOf(vm)
        vm.snapToNeighbour("b")
        val revision = messages.last().undoRevision!!
        vm.renameProject("Cottage", "")

        vm.undoRevision(revision)
        assertEquals("Cottage", vm.state.value.project?.name)
        assertEquals(4.0 + thickness, vm.room("b").corners.minOf { it.x }, 1e-9)
        assertTrue(messages.last().text.contains("Use Undo at the top"))
    }

    // ---- Floor tools ----

    @Test
    fun straightenFloorCommitsRefitsAndOffersUndo() = runTest {
        val tools = FakeTools().apply {
            straightenFn = { f -> f.copy(rooms = f.rooms.map { RoomOps.transform(it, RigidTransform2.rotation(0.2)) }) }
        }
        val vm = vm(FakeRepository(twoRooms), tools)
        val messages = messagesOf(vm)
        vm.setView("f1", ViewTransform(50.0, Vec2(0.0, 500.0), 2.0))

        vm.straightenFloor()
        val expected = RoomOps.transform(twoRooms.floors[0].rooms[0], RigidTransform2.rotation(0.2))
        assertEquals(expected.corners, vm.room("a").corners)
        assertEquals("Main Floor straightened.", messages.last().text)
        assertTrue(vm.viewRequest("f1") is ViewRequest.Fit)

        vm.undoRevision(messages.last().undoRevision!!)
        assertEquals(twoRooms.floors[0].rooms[0], vm.room("a"))
    }

    @Test
    fun straighteningAStraightFloorChangesNothing() = runTest {
        val repo = FakeRepository(twoRooms)
        val vm = vm(repo)
        val messages = messagesOf(vm)
        vm.straightenFloor()
        assertEquals("Main Floor is already straight.", messages.last().text)
        assertFalse(vm.state.value.canUndo)
        assertEquals(0, repo.saves)
    }

    @Test
    fun rotateFloorTurnsClockwiseByDefault() = runTest {
        val tools = FakeTools()
        val vm = vm(FakeRepository(twoRooms), tools)
        vm.rotateFloor()
        vm.rotateFloor(clockwise = false)
        assertEquals(listOf(-PI / 2, PI / 2), tools.rotations)
        assertTrue(vm.state.value.canUndo)
    }

    @Test
    fun floorToolsOnAnEmptyFloorOnlyExplain() = runTest {
        val tools = FakeTools().apply { straightenFn = { error("must not be called") } }
        val vm = vm(FakeRepository(project), tools)
        val messages = messagesOf(vm)
        vm.straightenFloor()
        vm.rotateFloor()
        assertEquals(2, messages.size)
        assertTrue(tools.rotations.isEmpty())
    }

    // ---- Floor checks ----

    @Test
    fun insightsListOverlapsAndIssuesAndHighlightTheRooms() = runTest {
        val tools = FakeTools().apply {
            overlapsFn = { f -> if (f.rooms.size >= 2) listOf("a" to "b", "b" to "gone") else emptyList() }
            checksFn = { _, _ ->
                listOf(
                    FloorIssue(IssueSeverity.ERROR, "b", "Bedroom overlaps Hallway."),
                    FloorIssue(IssueSeverity.INFO, null, "Floor not tape-calibrated."),
                )
            }
        }
        val vm = vm(FakeRepository(twoRooms), tools)
        val insights = vm.state.value.insights
        assertEquals("pairs with unknown rooms are dropped", listOf("a" to "b"), insights.overlaps)
        assertEquals(1, insights.attentionCount)
        assertEquals(setOf("a", "b"), vm.state.value.highlightRoomIds)

        // While B is dragged, its overlap highlight is hidden (it is re-checked on release).
        vm.beginMove("b")
        assertTrue(vm.state.value.highlightRoomIds.isEmpty())
        vm.cancelDrag()
        assertEquals(setOf("a", "b"), vm.state.value.highlightRoomIds)

        // Fixing the overlap clears it.
        tools.overlapsFn = { emptyList() }
        tools.checksFn = { _, _ -> emptyList() }
        vm.deleteRoom("b")
        assertTrue(vm.state.value.insights.overlaps.isEmpty())
        assertEquals(0, vm.state.value.insights.attentionCount)
    }

    @Test
    fun checksAreOnlyRecomputedWhenTheFloorOrSettingsChange() = runTest {
        val tools = FakeTools()
        val vm = vm(FakeRepository(twoRooms), tools)
        val initial = tools.checkCalls
        assertEquals(1, initial)
        vm.renameProject("Cottage", "")
        vm.select(Selection.Room("a"))
        assertEquals(initial, tools.checkCalls)
        vm.rotateRoom("a", 90.0)
        assertEquals(initial + 1, tools.checkCalls)
        vm.updateSettings(twoRooms.settings.copy(wallThickness = 0.15))
        assertEquals(initial + 2, tools.checkCalls)
    }

    @Test
    fun failingCoreToolsDegradeWithMessagesInsteadOfCrashing() = runTest {
        val vm = vm(FakeRepository(twoRooms), BrokenTools())
        val messages = messagesOf(vm)
        assertEquals(EditorStatus.READY, vm.state.value.status)
        assertEquals(FloorInsights(), vm.state.value.insights)
        assertNull(vm.quality(vm.room("a")))

        vm.snapToNeighbour("b")
        vm.straightenFloor()
        vm.rotateFloor()
        vm.beginMove("b")
        vm.updateMove(Vec2(-0.8, 0.0), snapDistance = 0.3)
        vm.endDrag()
        assertEquals(4.2, vm.room("b").corners.minOf { it.x }, 1e-9)
        assertEquals(
            listOf(
                "No wall of another room is close enough. Move Bedroom next to its neighbour and try again.",
                "Couldn't straighten this floor.",
                "Couldn't rotate this floor.",
            ),
            messages.map { it.text },
        )
        assertTrue(logged.isNotEmpty())
    }

    @Test
    fun qualityComesFromTheTools() = runTest {
        val report = RoomQualityReport(QualityLevel.FAIR, 0.6, listOf("Wall 3 was noisy (±2.4 cm) — consider recapturing"))
        val tools = FakeTools().apply { qualityFn = { report } }
        val vm = vm(FakeRepository(twoRooms), tools)
        assertEquals(report, vm.quality(vm.room("a")))
    }

    @Test
    fun focusRoomSelectsItAndAsksTheCanvasToShowIt() = runTest {
        val second = Floor("f2", "Upstairs", listOf(rect("c", 0.0, 0.0, 2.0, 2.0)))
        val vm = vm(FakeRepository(twoRooms.copy(floors = twoRooms.floors + second)))
        vm.setView("f2", ViewTransform(50.0, Vec2(0.0, 500.0), 2.0))

        vm.focusRoom("c")
        assertEquals("f2", vm.state.value.floorId)
        assertEquals(Selection.Room("c"), vm.state.value.selection)
        val request = vm.viewRequest("f2")
        assertTrue(request is ViewRequest.Focus)
        assertTrue((request as ViewRequest.Focus).bounds.contains(Vec2(1.0, 1.0)))

        vm.consumeViewRequest("f2", request)
        assertNull(vm.viewRequest("f2"))
        vm.focusRoom("missing")
        assertEquals(Selection.Room("c"), vm.state.value.selection)
    }

    @Test
    fun fitRequestsOnlyConcernFloorsThatAreOnScreen() = runTest {
        val vm = vm(FakeRepository(twoRooms))
        vm.requestFit("f1")
        assertNull("a floor without a view fits on its first layout anyway", vm.viewRequest("f1"))
        vm.setView("f1", ViewTransform(50.0, Vec2(0.0, 500.0), 2.0))
        vm.requestFit("f1")
        val first = vm.viewRequest("f1")
        vm.requestFit()
        val second = vm.viewRequest("f1")
        assertTrue(first is ViewRequest.Fit && second is ViewRequest.Fit && first != second)
        vm.consumeViewRequest("f1", first!!) // stale: ignored
        assertEquals(second, vm.viewRequest("f1"))
        // A pinch or pan by the user drops the pending animation.
        vm.cancelViewRequest("f1")
        assertNull(vm.viewRequest("f1"))
    }

    // ---- Room details ----

    @Test
    fun enteringACeilingHeightClearsTheEstimatedFlag() = runTest {
        val estimated = rect("a", 0.0, 0.0, 4.0, 3.0).copy(ceilingHeight = 2.4384, ceilingEstimated = true)
        val vm = vm(FakeRepository(oneFloor(estimated)))
        vm.setCeilingHeight("a", 2.7)
        assertEquals(2.7, vm.room("a").ceilingHeight ?: 0.0, 0.0)
        assertFalse(vm.room("a").ceilingEstimated)

        // Confirming the default as-is also makes it the user's own value.
        vm.undo()
        vm.setCeilingHeight("a", 2.4384)
        assertFalse(vm.room("a").ceilingEstimated)

        vm.setCeilingHeight("a", null)
        assertNull(vm.room("a").ceilingHeight)
        vm.setCeilingHeight("a", Double.NaN)
        assertNull(vm.room("a").ceilingHeight)
    }

    @Test
    fun newOpeningsUseTheProjectsDefaultWidths() = runTest {
        val settings = PlanSettings(defaultDoorWidth = 0.9, defaultWindowWidth = 1.5)
        val small = Room("s", "Closet", corners = listOf(Vec2(10.0, 0.0), Vec2(10.5, 0.0), Vec2(10.5, 2.0), Vec2(10.0, 2.0)))
        val vm = vm(
            FakeRepository(
                project.copy(settings = settings, floors = listOf(Floor("f1", "Main Floor", listOf(rect("a", 0.0, 0.0, 4.0, 3.0), small)))),
            ),
        )
        val door = vm.addOpening("a", 0, OpeningType.DOOR)!!
        assertEquals(0.9, door.width, 1e-12)
        assertEquals((4.0 - 0.9) / 2, door.offset, 1e-12)
        assertEquals(settings.defaultDoorHeight, door.height, 0.0)
        val window = vm.addOpening("a", 1, OpeningType.WINDOW)!!
        assertEquals(1.5, window.width, 1e-12)
        assertEquals(settings.defaultWindowSill, window.sillHeight, 0.0)
        assertEquals(0.9, vm.addOpening("a", 2, OpeningType.OPENING)!!.width, 1e-12)
        // A 0.5 m wall only fits 0.4 m (5 cm clear at each end).
        assertEquals(0.4, vm.addOpening("s", 0, OpeningType.DOOR)!!.width, 1e-12)
        assertEquals(4, vm.room("a").openings.size + vm.room("s").openings.size)
    }

    @Test
    fun settingAWallLengthClearsWarningsButTheSameLengthIsANoOp() = runTest {
        val warned = rect("a", 0.0, 0.0, 4.0, 3.0).copy(warnings = listOf("A wall seems to be missing"))
        val repo = FakeRepository(project.copy(floors = listOf(Floor("f1", "Main Floor", listOf(warned)))))
        val vm = vm(repo)

        assertNull(vm.setWallLength("a", 0, 4.0))
        assertFalse(vm.state.value.canUndo)
        assertEquals(warned.warnings, vm.room("a").warnings)

        assertNull(vm.setWallLength("a", 0, 5.0))
        assertEquals(5.0, edgeLength(vm.room("a"), 0), 1e-9)
        assertTrue(vm.room("a").warnings.isEmpty())
        assertNotNull(vm.setWallLength("a", 0, 0.01))
    }

    @Test
    fun insertingAndDeletingCornersClearWarnings() = runTest {
        val warned = rect("a", 0.0, 0.0, 4.0, 3.0).copy(warnings = listOf("Corner 3 is 1.6 m beyond what was measured"))
        val vm = vm(FakeRepository(oneFloor(warned)))
        vm.insertCorner("a", 0)
        assertEquals(5, vm.room("a").corners.size)
        assertTrue(vm.room("a").warnings.isEmpty())
        assertEquals(Selection.Corner("a", 1), vm.state.value.selection)
        vm.undo()
        vm.deleteCorner("a", 0) // 4 corners: a triangle remains
        assertEquals(3, vm.room("a").corners.size)
        assertTrue(vm.room("a").warnings.isEmpty())
    }

    @Test
    fun rigidMovesKeepTheShapeWarnings() = runTest {
        val warned = rect("a", 0.0, 0.0, 4.0, 3.0).copy(warnings = listOf("A wall seems to be missing"))
        val vm = vm(FakeRepository(oneFloor(warned)))
        vm.rotateRoom("a", 90.0)
        vm.beginMove("a")
        vm.updateMove(Vec2(1.0, 1.0))
        vm.endDrag()
        assertEquals(warned.warnings, vm.room("a").warnings)
    }
}
