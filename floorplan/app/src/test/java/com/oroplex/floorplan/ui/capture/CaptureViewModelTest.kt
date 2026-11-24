package com.oroplex.floorplan.ui.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.oroplex.floorplan.ar.ArCaptureController
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.MeasureRequest
import com.oroplex.floorplan.ar.MeasureResult
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.data.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Capture state machine paths that don't depend on the solver (fewer than three walls / corners), with a
 * fake AR controller and an in-memory repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val floorId = "floor-1"
    private val project = Project(
        id = "p1",
        name = "House",
        createdAt = 0L,
        updatedAt = 0L,
        floors = listOf(Floor(id = floorId, name = "Main Floor")),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(repo: FakeRepository = FakeRepository(project)) = CaptureViewModel(repo, project.id, floorId)

    private fun trackingFrame(floorY: Double? = 0.0, floorArea: Double = 2.0, camera: Vec3? = Vec3(0.0, 1.4, 0.0)) = ArFrameState(
        tracking = TrackingStatus.TRACKING,
        problem = TrackingProblem.NONE,
        floorY = floorY,
        floorArea = floorArea,
        cameraPosition = camera,
    )

    /** Lock the floor at y = 0 via the auto-lock path and return the VM in CEILING. */
    private fun vmInCeiling(controller: FakeController): CaptureViewModel {
        val vm = newVm()
        vm.attach(controller)
        vm.onFrame(trackingFrame(), controller, nowNs = 0L)
        vm.onFrame(trackingFrame(), controller, nowNs = 1_100_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        return vm
    }

    @Test
    fun loadsProjectSettingsAndFloor() {
        val vm = newVm()
        assertTrue(vm.state.value.projectLoaded)
        assertNull(vm.state.value.loadError)
    }

    @Test
    fun missingProjectShowsLoadError() {
        val vm = newVm(FakeRepository(null))
        assertTrue(vm.state.value.loadError != null)
        vm.exit(FakeController())
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun autoLocksFloorAfterOneStableSecond() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        vm.onFrame(trackingFrame(floorY = 0.01), controller, nowNs = 0L)
        vm.onFrame(trackingFrame(floorY = 0.0), controller, nowNs = 500_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        vm.onFrame(trackingFrame(floorY = 0.005), controller, nowNs = 1_000_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        assertEquals(0.005, vm.state.value.floorY ?: Double.NaN, 1e-12)
        assertTrue(vm.state.value.hapticPending)
    }

    @Test
    fun autoLockRestartsWhenFloorJumpsOrIsSmall() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        vm.onFrame(trackingFrame(floorY = 0.0), controller, nowNs = 0L)
        vm.onFrame(trackingFrame(floorY = 0.2), controller, nowNs = 900_000_000L) // jump: restart timer
        vm.onFrame(trackingFrame(floorY = 0.2), controller, nowNs = 1_500_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        vm.onFrame(trackingFrame(floorY = 0.2, floorArea = 0.5), controller, nowNs = 2_000_000_000L) // too small
        vm.onFrame(trackingFrame(floorY = 0.2), controller, nowNs = 2_100_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        vm.onFrame(trackingFrame(floorY = 0.2), controller, nowNs = 3_100_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
    }

    @Test
    fun manualLockRejectsRaisedSurface() {
        val controller = FakeController()
        controller.frames.value = trackingFrame(floorY = 0.0, floorArea = 3.0, camera = Vec3(0.0, 1.5, 0.0))
        controller.results += MeasureResult.FloorLocked(0.75) // a table
        val vm = newVm()
        vm.attach(controller)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertNull(vm.state.value.floorY)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.messages.isNotEmpty())
    }

    @Test
    fun skipCeilingEntersWallsAndCreatesAnchorOnFloor() {
        val controller = FakeController()
        controller.frames.value = trackingFrame(camera = Vec3(1.0, 1.4, -2.0))
        val vm = vmInCeiling(controller)
        vm.skipCeiling(controller)
        val s = vm.state.value
        assertEquals(CaptureStep.WALLS, s.step)
        assertNull(s.draft.ceilingHeight)
        assertFalse(s.draft.ceilingMeasured)
        assertTrue(s.draft.anchorCreated)
        assertEquals(listOf(s.draft.id to Vec3(1.0, 0.0, -2.0)), controller.anchors)
    }

    @Test
    fun failedWallOffersBaselineAndSuccessfulWallIsAdded() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.skipCeiling(controller)

        controller.results += MeasureResult.WallMeasured(WallFit(null, WallFitFailure.TOO_NOISY, 10, 40))
        vm.captureWall(controller)
        assertTrue(vm.state.value.draft.offerBaseline)
        assertTrue(vm.state.value.draft.observations.isEmpty())
        assertTrue(vm.state.value.messages.any { it.text == WallFitFailure.TOO_NOISY.message })
        assertTrue(controller.requests.single() is MeasureRequest.Wall)

        controller.results += MeasureResult.WallMeasured(WallFit(wall(), null, 200, 220))
        vm.captureWall(controller)
        val d = vm.state.value.draft
        assertEquals(1, d.observations.size)
        assertFalse(d.offerBaseline)
        assertNull(d.solve) // fewer than 3 walls: nothing to solve yet
        assertFalse(vm.state.value.busy)

        vm.undo()
        assertTrue(vm.state.value.draft.observations.isEmpty())
    }

    @Test
    fun closeRoomNeedsThreeCaptures() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.skipCeiling(controller)
        controller.results += MeasureResult.WallMeasured(WallFit(wall(), null, 200, 220))
        vm.captureWall(controller)
        vm.closeRoom()
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        assertTrue(vm.state.value.messages.last().text.contains("at least 3"))
    }

    @Test
    fun methodSwitchOnlyBeforeFirstCapture() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.skipCeiling(controller)
        vm.setMethod(CaptureMethod.CORNERS)
        assertEquals(CaptureStep.CORNERS, vm.state.value.step)

        controller.results += MeasureResult.Point(Vec3(1.0, 0.0, 1.0), samples = 6, spread = 0.01)
        vm.addCorner(controller)
        assertEquals(listOf(Vec2(1.0, -1.0)), vm.state.value.draft.cornerTaps)

        // Same spot again is rejected as a duplicate.
        controller.results += MeasureResult.Point(Vec3(1.01, 0.0, 1.0), samples = 6, spread = 0.01)
        vm.addCorner(controller)
        assertEquals(1, vm.state.value.draft.cornerTaps.size)

        vm.setMethod(CaptureMethod.WALLS)
        assertEquals(CaptureStep.CORNERS, vm.state.value.step)
    }

    @Test
    fun sessionRestartResetsFloorAndRoomInProgress() {
        val first = FakeController(sessionId = "s1")
        first.frames.value = trackingFrame()
        val vm = vmInCeiling(first)
        vm.skipCeiling(first)
        controllerAddsWall(vm, first)
        assertTrue(vm.state.value.hasRoomInProgress)

        val second = FakeController(sessionId = "s2")
        vm.attach(second)
        val s = vm.state.value
        assertEquals(CaptureStep.FIND_FLOOR, s.step)
        assertNull(s.floorY)
        assertFalse(s.draft.hasCaptures)
        assertEquals("s2", s.sessionId)
    }

    @Test
    fun exitWithoutRoomsFinishesImmediately() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        vm.exit(controller)
        assertTrue(vm.state.value.finished)
    }

    @Test
    fun messageAcknowledgementDropsOlderMessages() {
        val controller = FakeController()
        controller.results += MeasureResult.Failed("one")
        controller.results += MeasureResult.Failed("two")
        val vm = newVm()
        vm.attach(controller)
        vm.lockFloor(controller)
        vm.lockFloor(controller)
        val messages = vm.state.value.messages
        assertEquals(listOf("one", "two"), messages.map { it.text })
        vm.onMessageShown(messages.last().id)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    // ---- pure helpers ----

    @Test
    fun defaultRoomNameNumbersByType() {
        assertEquals("Bedroom", defaultRoomName(RoomType.BEDROOM, emptyList(), null))
        val rooms = listOf(room("a", "Bedroom", RoomType.BEDROOM))
        assertEquals("Bedroom 2", defaultRoomName(RoomType.BEDROOM, rooms, null))
        // The room being named doesn't count against itself.
        assertEquals("Bedroom", defaultRoomName(RoomType.BEDROOM, rooms, "a"))
        val more = rooms + room("b", "Bedroom 2", RoomType.BEDROOM) + room("c", "Bedroom 3", RoomType.OTHER)
        assertEquals("Bedroom 4", defaultRoomName(RoomType.BEDROOM, more, null))
        assertEquals("Kitchen", defaultRoomName(RoomType.KITCHEN, more, null))
        // A custom-named room of the same type still bumps the number.
        assertEquals("Office 2", defaultRoomName(RoomType.OFFICE, listOf(room("d", "Study", RoomType.OFFICE)), null))
    }

    @Test
    fun openingOverlapDetection() {
        val existing = listOf(Opening("o1", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.8, height = 2.0))
        assertTrue(overlapsExisting(Opening("o2", OpeningType.DOOR, 0, 1.5, 0.8, 2.0), existing))
        assertFalse(overlapsExisting(Opening("o3", OpeningType.DOOR, 0, 1.81, 0.8, 2.0), existing))
        assertFalse(overlapsExisting(Opening("o4", OpeningType.DOOR, 1, 1.0, 0.8, 2.0), existing))
    }

    @Test
    fun withFloorReplacesOrRecreatesFloor() {
        val r = room("r", "Kitchen", RoomType.KITCHEN)
        val updated = project.withFloor(floorId, createIfMissing = false) { it.copy(rooms = listOf(r)) }
        assertEquals(listOf(r), updated.floors.single().rooms)

        val untouched = project.withFloor("missing", createIfMissing = false) { it.copy(rooms = listOf(r)) }
        assertSame(project, untouched)

        val recreated = project.withFloor("missing", createIfMissing = true) { it.copy(rooms = listOf(r)) }
        assertEquals(2, recreated.floors.size)
        assertEquals("missing", recreated.floors.last().id)
        assertEquals(listOf(r), recreated.floors.last().rooms)
    }

    @Test
    fun applyCorrectionsWithoutCorrectionsIsIdentity() {
        val floor = Floor(floorId, "Main", rooms = listOf(room("r", "Room", RoomType.OTHER)))
        assertSame(floor, applyCorrections(floor, emptyMap()))
    }

    @Test
    fun clipSegmentToRect() {
        val r = Rect(0f, 0f, 100f, 100f)
        val (a, b) = clipSegment(Offset(-50f, 50f), Offset(150f, 50f), r) ?: error("expected a segment")
        assertEquals(Offset(0f, 50f), a)
        assertEquals(Offset(100f, 50f), b)
        assertNull(clipSegment(Offset(-50f, -10f), Offset(150f, -10f), r))
        assertNull(clipSegment(Offset(Float.NaN, 0f), Offset(10f, 10f), r))
        val inside = clipSegment(Offset(10f, 10f), Offset(20f, 30f), r)
        assertEquals(Offset(10f, 10f) to Offset(20f, 30f), inside)
    }

    @Test
    fun minSpanGrowsSmallBounds() {
        val b = withMinSpan(Bounds(0.0, 0.0, 1.0, 0.5), 3.0)
        assertEquals(3.0, b.width, 1e-12)
        assertEquals(3.0, b.height, 1e-12)
        assertEquals(Vec2(0.5, 0.25), b.center)
        assertTrue(withMinSpan(Bounds.EMPTY, 3.0).isEmpty)
        val big = Bounds(0.0, 0.0, 6.0, 4.0)
        assertEquals(big, withMinSpan(big, 3.0))
    }

    // ---- fixtures ----

    private fun controllerAddsWall(vm: CaptureViewModel, controller: FakeController) {
        controller.results += MeasureResult.WallMeasured(WallFit(wall(), null, 200, 220))
        vm.captureWall(controller)
        assertEquals(1, vm.state.value.draft.observations.size)
    }

    /** A wall at plan y = 2 seen from the origin; direction -x keeps the viewer (interior) on its left. */
    private fun wall(): WallObservation {
        val line = Line2.of(Vec2(0.0, 2.0), Vec2(-1.0, 0.0))
        return WallObservation(
            line = line,
            extentStart = Vec2(1.0, 2.0),
            extentEnd = Vec2(-1.0, 2.0),
            source = WallSource.DEPTH_FIT,
            rmsResidual = 0.01,
            pointCount = 200,
            confidence = 0.9,
            viewer = Vec2.ZERO,
        )
    }

    private fun room(id: String, name: String, type: RoomType) = Room(
        id = id,
        name = name,
        type = type,
        corners = listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 3.0), Vec2(0.0, 3.0)),
    )

    private class FakeController(override val sessionId: String = "session") : ArCaptureController {
        val frames = MutableStateFlow(ArFrameState())
        override val frameState: StateFlow<ArFrameState> get() = frames
        val results = ArrayDeque<MeasureResult>()
        val requests = mutableListOf<MeasureRequest>()
        val anchors = mutableListOf<Pair<String, Vec3>>()

        override suspend fun measure(request: MeasureRequest): MeasureResult {
            requests += request
            return results.removeFirstOrNull() ?: MeasureResult.Failed("no result queued")
        }

        override fun createRoomAnchor(roomId: String, position: Vec3) {
            anchors += roomId to position
        }

        override fun anchorCorrections(): Map<String, RigidTransform2> = emptyMap()
    }

    private class FakeRepository(initial: Project?) : ProjectRepository {
        private val project = MutableStateFlow(initial)
        override val summaries: StateFlow<List<ProjectSummary>> = MutableStateFlow(emptyList())
        override fun observe(projectId: String): StateFlow<Project?> = project
        override suspend fun get(projectId: String): Project? = project.value
        override suspend fun create(name: String, address: String): Project = error("unused")
        override suspend fun save(project: Project): Project = project.also { this.project.value = it }
        override suspend fun update(projectId: String, transform: (Project) -> Project): Project? {
            val p = project.value ?: return null
            return transform(p).also { project.value = it }
        }
        override suspend fun delete(projectId: String) = Unit
        override suspend fun duplicate(projectId: String): Project? = null
        override suspend fun importJson(json: String): Project = error("unused")
        override fun exportJson(project: Project): String = error("unused")
    }
}
