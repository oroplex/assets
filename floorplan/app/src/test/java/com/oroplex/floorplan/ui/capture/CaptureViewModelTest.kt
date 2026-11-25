package com.oroplex.floorplan.ui.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.oroplex.floorplan.ar.ArCaptureController
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.MeasureRequest
import com.oroplex.floorplan.ar.MeasureResult
import com.oroplex.floorplan.ar.ReticleHit
import com.oroplex.floorplan.ar.SurfaceKind
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.fit.WallFitFailure
import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomNames
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.core.solve.RoomOps
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Capture state machine with a fake AR controller and an in-memory repository: floor locking, ceiling,
 * walls / corners (including re-capture and delete), closing, openings, review, saving (estimated
 * ceilings, calibrated floors, anchor corrections), exiting, session restarts and the first-scan tips.
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

    private fun newVm(
        repo: FakeRepository = FakeRepository(project),
        tipsSeen: Boolean = true,
        calibrate: (Floor, Room) -> Room = { floor, room ->
            val c = requireNotNull(floor.calibration)
            RoomOps.scale(room, c.factor, c.origin)
        },
        store: CaptureSnapshotStore = CaptureSnapshotStore.None,
    ) = CaptureViewModel(repo, project.id, floorId, OneTimeFlag.InMemory(tipsSeen), calibrate, store)

    /**
     * A tracking frame. By default the AR layer reports the floor under the user at [floorY] and calls it
     * auto-lockable (the round-3 AR contract); [localFloorY] / [lockable] override that.
     */
    private fun trackingFrame(
        floorY: Double? = 0.0,
        floorArea: Double = 2.0,
        camera: Vec3? = Vec3(0.0, 1.4, 0.0),
        reticle: ReticleHit? = null,
        t: Long = 0L,
        localFloorY: Double? = floorY,
        lockable: Boolean = localFloorY != null,
    ) = ArFrameState(
        tracking = TrackingStatus.TRACKING,
        problem = TrackingProblem.NONE,
        floorY = floorY,
        floorArea = floorArea,
        cameraPosition = camera,
        reticle = reticle,
        timestampNs = t,
        localFloorY = localFloorY,
        floorAutoLockable = lockable,
    )

    /** Lock the floor at y = 0 via the auto-lock path and return the VM in CEILING. */
    private fun vmInCeiling(controller: FakeController, repo: FakeRepository = FakeRepository(project), vm: CaptureViewModel = newVm(repo)): CaptureViewModel {
        vm.attach(controller)
        vm.onFrame(trackingFrame(), controller, nowNs = 0L)
        vm.onFrame(trackingFrame(), controller, nowNs = 1_100_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        return vm
    }

    private fun vmInWalls(controller: FakeController, repo: FakeRepository = FakeRepository(project), vm: CaptureViewModel = newVm(repo)): CaptureViewModel {
        controller.frames.value = trackingFrame()
        vmInCeiling(controller, repo, vm)
        vm.skipCeiling(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        return vm
    }

    private fun capture(vm: CaptureViewModel, controller: FakeController, wall: WallObservation) {
        controller.results += MeasureResult.WallMeasured(WallFit(wall, null, 200, 220))
        vm.captureWall(controller)
    }

    /** Capture the four walls of [rectangleWalls] and close the room. */
    private fun vmInOpenings(controller: FakeController, repo: FakeRepository = FakeRepository(project), vm: CaptureViewModel = newVm(repo)): CaptureViewModel {
        vmInWalls(controller, repo, vm)
        rectangleWalls().forEach { capture(vm, controller, it) }
        assertNotNull(vm.state.value.draft.solve)
        vm.closeRoom()
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)
        return vm
    }

    private fun vmInReview(controller: FakeController, repo: FakeRepository = FakeRepository(project), vm: CaptureViewModel = newVm(repo)): CaptureViewModel {
        vmInOpenings(controller, repo, vm)
        vm.finishOpenings()
        assertEquals(CaptureStep.REVIEW, vm.state.value.step)
        return vm
    }

    /** Mark a door on the bottom wall (plan y = -1.5) from x = [x0] to [x1]. */
    private fun markDoor(vm: CaptureViewModel, controller: FakeController, x0: Double = -0.5, x1: Double = 0.3) {
        controller.results += MeasureResult.Point(Vec3(x0, 0.0, 1.5), samples = 6, spread = 0.01)
        vm.markOpeningEdge(controller)
        controller.results += MeasureResult.Point(Vec3(x1, 0.0, 1.5), samples = 6, spread = 0.01)
        vm.markOpeningEdge(controller)
    }

    private fun savedRooms(repo: FakeRepository): List<Room> =
        repo.current?.floors?.firstOrNull { it.id == floorId }?.rooms.orEmpty()

    // ---- loading / floor ----

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
        vm.requestExit(FakeController())
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
        assertEquals(FeedbackKind.CAPTURED, vm.state.value.feedback?.kind)
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
        vm.onFrame(trackingFrame(floorY = 0.2, floorArea = 0.5, lockable = false), controller, nowNs = 2_000_000_000L) // not lockable
        vm.onFrame(trackingFrame(floorY = 0.2), controller, nowNs = 2_100_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        vm.onFrame(trackingFrame(floorY = 0.2), controller, nowNs = 3_100_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
    }

    @Test
    fun autoLockIgnoresSurfacesCloseBelowThePhone() {
        // A bed top 0.8 m below a phone held at 1.4 m: the AR layer reports it, auto-lock must not take it.
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        for (i in 0..30) vm.onFrame(trackingFrame(floorY = 0.6, floorArea = 3.0), controller, nowNs = i * 100_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertNull(vm.state.value.floorY)
    }

    @Test
    fun manualLockQuestionsARaisedSurfaceAndASecondTapKeepsIt() {
        val controller = FakeController()
        controller.frames.value = trackingFrame(floorY = 0.0, floorArea = 3.0, camera = Vec3(0.0, 1.5, 0.0))
        controller.results += MeasureResult.FloorLocked(0.75) // a table
        val vm = newVm()
        vm.attach(controller)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertNull(vm.state.value.floorY)
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.messages.last().text, vm.state.value.messages.last().text.contains("raised surface"))
        assertTrue(vm.state.value.messages.last().text.contains("Lock floor again"))
        assertEquals(FeedbackKind.FAILED, vm.state.value.feedback?.kind)

        // A different raised height is a new question; the same height again is the user's decision.
        controller.results += MeasureResult.FloorLocked(0.45)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        controller.results += MeasureResult.FloorLocked(0.47)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        assertEquals(0.47, vm.state.value.floorY ?: Double.NaN, 0.0)
    }

    @Test
    fun manualLockComparesWithTheFloorUnderTheUserNotTheLowestLevelInView() {
        // Split level: a sunken living room at -0.45 is in view (the tracker's global floor), the user stands on
        // the kitchen floor at 0. Locking the kitchen floor must not be refused as a "raised surface".
        val controller = FakeController()
        controller.frames.value = trackingFrame(floorY = -0.45, floorArea = 6.0, localFloorY = 0.0, lockable = false)
        controller.results += MeasureResult.FloorLocked(0.0)
        val vm = newVm()
        vm.attach(controller)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        assertEquals(0.0, vm.state.value.floorY ?: Double.NaN, 0.0)
    }

    @Test
    fun withoutALocalFloorTheGlobalFloorIsOnlyAQuestion() {
        // No floor under the user is known; the lowest plane in view is 0.45 m lower. One extra tap unblocks it.
        val controller = FakeController()
        controller.frames.value = trackingFrame(floorY = -0.45, floorArea = 6.0, localFloorY = null)
        val vm = newVm()
        vm.attach(controller)
        controller.results += MeasureResult.FloorLocked(0.0)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        controller.results += MeasureResult.FloorLocked(0.0)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
    }

    @Test
    fun autoLockNeedsTheArLayersLockableFloorUnderTheUser() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        // A large floor is tracked, but the AR layer doesn't call it lockable (e.g. once vetoed as furniture).
        for (i in 0..30) vm.onFrame(trackingFrame(floorY = 0.0, floorArea = 5.0, lockable = false), controller, nowNs = i * 100_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertNull(vm.state.value.floorY)

        // Lockable: the floor under the user locks, not the lower level elsewhere in view.
        val frame = trackingFrame(floorY = -0.45, floorArea = 5.0, localFloorY = 0.0, lockable = true)
        vm.onFrame(frame, controller, nowNs = 4_000_000_000L)
        vm.onFrame(frame, controller, nowNs = 5_100_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        assertEquals(0.0, vm.state.value.floorY ?: Double.NaN, 0.0)
    }

    @Test
    fun autoLockStillNeedsThePhoneWellAboveTheFloor() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        // Lockable but only 0.9 m below the phone (phone held low): manual Lock floor only, and the copy says so.
        val low = trackingFrame(floorY = 0.0, camera = Vec3(0.0, 0.9, 0.0))
        for (i in 0..30) vm.onFrame(low, controller, nowNs = i * 100_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertEquals(FloorStatus.MANUAL_ONLY, floorStatus(low))
        assertEquals("Floor found. Tap Lock floor.", stepInstruction(vm.state.value, floorStatus(low)))
    }

    @Test
    fun theLockedFloorIsSentToTheArLayerEveryFrame() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        vm.onFrame(trackingFrame(), controller, nowNs = 0L)
        assertEquals(listOf<Double?>(null), controller.referenceFloors)
        vm.onFrame(trackingFrame(), controller, nowNs = 1_100_000_000L) // locks
        vm.onFrame(trackingFrame(), controller, nowNs = 1_200_000_000L)
        assertEquals(listOf(null, null, 0.0), controller.referenceFloors)
        vm.relockFloor()
        vm.onFrame(trackingFrame(), controller, nowNs = 1_300_000_000L)
        assertNull(controller.referenceFloors.last())
    }

    @Test
    fun relockTurnsAutoLockOffForTheSession() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.relockFloor()
        val s = vm.state.value
        assertEquals(CaptureStep.FIND_FLOOR, s.step)
        assertFalse(s.autoLockFloor)
        assertEquals(0.0, s.rejectedFloorY ?: Double.NaN, 0.0)

        // The same confident floor keeps being reported: it must not lock again by itself.
        for (i in 0..40) vm.onFrame(trackingFrame(), controller, nowNs = 2_000_000_000L + i * 100_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertTrue(stepInstruction(vm.state.value, FloorStatus.CONFIDENT).contains("tap Lock floor"))

        // A manual lock at another height is taken right away.
        controller.results += MeasureResult.FloorLocked(-0.25)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        assertEquals(-0.25, vm.state.value.floorY ?: Double.NaN, 0.0)
        assertFalse(vm.state.value.autoLockFloor) // still off for the rest of the session
    }

    @Test
    fun manualLockAtTheRejectedHeightNeedsASecondTap() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.relockFloor()
        controller.results += MeasureResult.FloorLocked(0.03)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        assertTrue(vm.state.value.messages.last().text.contains("Lock floor again"))
        controller.results += MeasureResult.FloorLocked(0.03)
        vm.lockFloor(controller)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
    }

    @Test
    fun newSessionTurnsAutoLockBackOn() {
        val first = FakeController(sessionId = "s1")
        first.frames.value = trackingFrame()
        val vm = vmInCeiling(first)
        vm.relockFloor()
        vm.attach(FakeController(sessionId = "s2"))
        assertTrue(vm.state.value.autoLockFloor)
        assertNull(vm.state.value.rejectedFloorY)
    }

    // ---- ceiling ----

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
    fun skippedCeilingIsSavedAsAnEstimatedDefault() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        assertTrue(reviewStats(requireNotNull(vm.state.value.draftRoom), vm.state.value.settings).ceiling.endsWith("(est.)"))
        vm.saveAndScanNext(controller)
        val saved = savedRooms(repo).single()
        assertEquals(project.settings.defaultCeilingHeight, saved.ceilingHeight ?: Double.NaN, 0.0)
        assertTrue(saved.ceilingEstimated)
        assertNull(vm.state.value.lastMeasuredCeiling) // nothing measured to offer for the next room
    }

    @Test
    fun measuredCeilingIsSavedAsMeasuredAndOfferedForTheNextRoom() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller, repo)
        controller.results += MeasureResult.Height(2.61, samples = 15, spread = 0.01)
        vm.measureCeiling(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        rectangleWalls().forEach { capture(vm, controller, it) }
        vm.closeRoom()
        vm.finishOpenings()
        vm.saveAndScanNext(controller)
        val saved = savedRooms(repo).single()
        assertEquals(2.61, saved.ceilingHeight ?: Double.NaN, 1e-12)
        assertFalse(saved.ceilingEstimated)

        // Next room: "Same as last room" uses that height, marked as an estimate for this room.
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        assertEquals(2.61, vm.state.value.lastMeasuredCeiling ?: Double.NaN, 1e-12)
        vm.useLastCeiling(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        rectangleWalls().forEach { capture(vm, controller, it) }
        vm.closeRoom()
        val room = requireNotNull(vm.state.value.draftRoom)
        assertEquals(2.61, room.ceilingHeight ?: Double.NaN, 1e-12)
        assertTrue(room.ceilingEstimated)
    }

    // ---- walls / corners ----

    @Test
    fun failedWallOffersBaselineAndSuccessfulWallIsAdded() {
        val controller = FakeController()
        val vm = vmInWalls(controller)

        controller.results += MeasureResult.WallMeasured(WallFit(null, WallFitFailure.TOO_NOISY, 10, 40))
        vm.captureWall(controller)
        assertTrue(vm.state.value.draft.offerBaseline)
        assertTrue(vm.state.value.draft.observations.isEmpty())
        assertTrue(vm.state.value.messages.any { it.text == WallFitFailure.TOO_NOISY.message })
        assertTrue(controller.requests.single() is MeasureRequest.Wall)
        assertEquals(FeedbackKind.FAILED, vm.state.value.feedback?.kind)

        capture(vm, controller, wall())
        val d = vm.state.value.draft
        assertEquals(1, d.observations.size)
        assertFalse(d.offerBaseline)
        assertNull(d.solve) // fewer than 3 walls: nothing to solve yet
        assertFalse(vm.state.value.busy)
        assertEquals(FeedbackKind.CAPTURED, vm.state.value.feedback?.kind)

        vm.undo()
        assertTrue(vm.state.value.draft.observations.isEmpty())
    }

    @Test
    fun roughWallIsCapturedWithAdvice() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        capture(vm, controller, wall().copy(confidence = 0.2))
        assertEquals(1, vm.state.value.draft.observations.size)
        assertTrue(vm.state.value.messages.last().text.contains("rough"))
    }

    @Test
    fun closeRoomNeedsThreeCaptures() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        capture(vm, controller, wall())
        vm.closeRoom()
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        assertTrue(vm.state.value.messages.last().text.contains("at least 3"))
    }

    @Test
    fun deleteAWallAndUndoTheDelete() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        val walls = rectangleWalls()
        walls.forEach { capture(vm, controller, it) }
        assertNotNull(vm.state.value.draft.solve)

        vm.deleteCapture(1)
        assertEquals(listOf(walls[0], walls[2], walls[3]), vm.state.value.draft.observations)
        assertTrue(vm.state.value.messages.last().text.contains("Wall 2 deleted"))

        vm.undo()
        assertEquals(walls, vm.state.value.draft.observations)
        assertNotNull(vm.state.value.draft.solve)
        assertTrue(vm.state.value.messages.last().text.contains("Wall 2 restored"))

        vm.deleteCapture(9) // out of range: ignored
        assertEquals(walls, vm.state.value.draft.observations)
    }

    @Test
    fun recaptureReplacesTheWallInPlace() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        val walls = rectangleWalls()
        walls.forEach { capture(vm, controller, it) }

        vm.startRecapture(1)
        assertEquals(1, vm.state.value.draft.recaptureIndex)
        assertTrue(stepInstruction(vm.state.value, FloorStatus.CONFIDENT).contains("wall 2 again"))
        assertTrue(canUndo(vm.state.value))

        val better = walls[1].copy(confidence = 0.95, rmsResidual = 0.004)
        capture(vm, controller, better)
        val d = vm.state.value.draft
        assertEquals(listOf(walls[0], better, walls[2], walls[3]), d.observations)
        assertNull(d.recaptureIndex)
        assertTrue(vm.state.value.messages.last().text.contains("Wall 2 re-captured"))

        // Undo restores the earlier capture of wall 2.
        vm.undo()
        assertEquals(walls, vm.state.value.draft.observations)
    }

    @Test
    fun recaptureCanBeCancelled() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        rectangleWalls().take(2).forEach { capture(vm, controller, it) }
        vm.startRecapture(0)
        vm.undo() // cancels the re-capture, doesn't remove a wall
        assertNull(vm.state.value.draft.recaptureIndex)
        assertEquals(2, vm.state.value.draft.observations.size)
        vm.startRecapture(0)
        vm.startRecapture(0) // tapping the same one again cancels too
        assertNull(vm.state.value.draft.recaptureIndex)
        vm.startRecapture(5) // out of range
        assertNull(vm.state.value.draft.recaptureIndex)
    }

    @Test
    fun methodSwitchOnlyBeforeFirstCapture() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
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
    fun cornersCanBeRecapturedAndDeleted() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        vm.setMethod(CaptureMethod.CORNERS)
        val taps = listOf(Vec3(-2.0, 0.0, 1.5), Vec3(2.0, 0.0, 1.5), Vec3(2.0, 0.0, -1.5), Vec3(-2.0, 0.0, -1.5))
        taps.forEach {
            controller.results += MeasureResult.Point(it, samples = 6, spread = 0.01)
            vm.addCorner(controller)
        }
        assertEquals(4, vm.state.value.draft.cornerTaps.size)
        assertNotNull(vm.state.value.draft.solve)

        vm.startRecapture(2)
        // Re-capturing onto another corner is refused.
        controller.results += MeasureResult.Point(Vec3(-2.0, 0.0, 1.5), samples = 6, spread = 0.01)
        vm.addCorner(controller)
        assertEquals(Vec2(2.0, 1.5), vm.state.value.draft.cornerTaps[2])
        assertEquals(2, vm.state.value.draft.recaptureIndex)

        controller.results += MeasureResult.Point(Vec3(2.1, 0.0, -1.6), samples = 6, spread = 0.01)
        vm.addCorner(controller)
        assertEquals(Vec2(2.1, 1.6), vm.state.value.draft.cornerTaps[2])
        assertNull(vm.state.value.draft.recaptureIndex)

        vm.deleteCapture(3)
        assertEquals(3, vm.state.value.draft.cornerTaps.size)
        vm.undo()
        assertEquals(4, vm.state.value.draft.cornerTaps.size)
    }

    // ---- openings / review ----

    @Test
    fun reviewBackToWallsKeepsWallsAndOpenings() {
        val controller = FakeController()
        val vm = vmInOpenings(controller)
        markDoor(vm, controller)
        val door = vm.state.value.draft.openings.single()
        vm.finishOpenings()
        vm.setName("Den")
        assertEquals(CaptureStep.REVIEW, vm.state.value.step)

        vm.backToWalls()
        val walls = vm.state.value
        assertEquals(CaptureStep.WALLS, walls.step)
        assertEquals(4, walls.draft.observations.size)
        assertTrue(walls.draft.openings.isEmpty())
        assertEquals(listOf(door.id), walls.draft.parkedOpenings.map { it.opening.id })
        assertTrue(walls.hasRoomInProgress)

        // Fix a wall, close again: the door is back on its wall, the name is kept.
        vm.startRecapture(0)
        capture(vm, controller, rectangleWalls()[0].copy(confidence = 0.95))
        vm.closeRoom()
        val reopened = vm.state.value
        assertEquals(CaptureStep.OPENINGS, reopened.step)
        val restored = reopened.draft.openings.single()
        assertEquals(door.id, restored.id)
        assertEquals(door.wallIndex, restored.wallIndex)
        assertEquals(door.width, restored.width, 1e-9)
        assertTrue(reopened.draft.parkedOpenings.isEmpty())
        vm.finishOpenings()
        assertEquals("Den", vm.state.value.draft.name)
    }

    @Test
    fun deletedWallDropsItsDoorWithANote() {
        val controller = FakeController()
        val vm = vmInOpenings(controller)
        markDoor(vm, controller)
        vm.backToWalls()
        // Replace the bottom wall with one 1 m further out: the door's marks are now 1 m from any wall.
        vm.startRecapture(0)
        val moved = rectangleWalls()[0].let { w ->
            w.copy(line = Line2.of(Vec2(0.0, -2.5), Vec2(1.0, 0.0)), extentStart = Vec2(-1.0, -2.5), extentEnd = Vec2(1.0, -2.5))
        }
        capture(vm, controller, moved)
        vm.closeRoom()
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)
        assertTrue(vm.state.value.draft.openings.isEmpty())
        assertTrue(vm.state.value.messages.last().text.contains("no longer fits"))
    }

    @Test
    fun backInReviewGoesToDoorsAndWindows() {
        val controller = FakeController()
        val vm = vmInReview(controller)
        vm.undo()
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)
        vm.finishOpenings()
        vm.backToOpenings()
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)
        assertNotNull(vm.state.value.draft.solve)
    }

    @Test
    fun openingsUndoThenReopen() {
        val controller = FakeController()
        val vm = vmInOpenings(controller)
        markDoor(vm, controller)
        assertEquals(1, vm.state.value.draft.openings.size)
        vm.undo()
        assertTrue(vm.state.value.draft.openings.isEmpty())
        vm.undo() // nothing left to undo: reopen the room
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
    }

    // ---- saving ----

    private val calibration = Calibration(
        factor = 1.02,
        roomId = "old",
        wallIndex = 0,
        measuredLength = 3.0,
        trueLength = 3.06,
        appliedAt = 0L,
        origin = Vec2(0.5, -0.5),
    )

    private fun calibratedProject() = project.copy(floors = listOf(Floor(id = floorId, name = "Main Floor", calibration = calibration)))

    @Test
    fun roomSavedIntoACalibratedFloorIsScaled() {
        val repo = FakeRepository(calibratedProject())
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(controller)
        val saved = savedRooms(repo).single()
        val expected = RoomOps.scale(raw.copy(name = saved.name), calibration.factor, calibration.origin)
        assertCornersEqual(expected.corners, saved.corners)
        assertEquals(AppliedScale(1.02, Vec2(0.5, -0.5)), vm.state.value.sessionRoomScales[raw.id])
        assertTrue(vm.state.value.messages.last().text.contains("tape calibration"))
    }

    @Test
    fun uncalibratedFloorNeverCallsTheCalibration() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInReview(controller, repo, newVm(repo, calibrate = { _, _ -> error("must not be called") }))
        vm.saveAndScanNext(controller)
        assertEquals(1, savedRooms(repo).size)
        assertTrue(vm.state.value.sessionRoomScales.isEmpty())
    }

    @Test
    fun failingCalibrationStillSavesTheRoomAndSaysSo() {
        val repo = FakeRepository(calibratedProject())
        val controller = FakeController()
        val vm = vmInReview(controller, repo, newVm(repo, calibrate = { _, _ -> throw IllegalStateException("boom") }))
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(controller)
        assertCornersEqual(raw.corners, savedRooms(repo).single().corners)
        assertTrue(vm.state.value.messages.last().text.contains("couldn't be applied"))
        assertTrue(vm.state.value.sessionRoomScales.isEmpty())
    }

    @Test
    fun finishAppliesAnchorCorrectionsMatchedToTheCalibrationScale() {
        // A room from an earlier scan is on the floor, so the floor is not straightened at finish.
        val earlier = room("old", "Hall", RoomType.HALLWAY).copy(sessionId = "earlier", captureMethod = CaptureMethod.WALLS)
        val repo = FakeRepository(project.copy(floors = listOf(Floor(id = floorId, name = "Main Floor", rooms = listOf(earlier), calibration = calibration))))
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        val raw = requireNotNull(vm.state.value.draftRoom)
        val t = RigidTransform2(0.03, Vec2(0.2, -0.1))
        controller.corrections = mapOf(raw.id to t, "unrelated" to RigidTransform2(1.0, Vec2(5.0, 5.0)))
        vm.saveAndFinish(controller)
        assertTrue(vm.state.value.finished)
        val saved = savedRooms(repo).single { it.id == raw.id }
        // Corrected in the raw AR frame, then scaled like every other room of the floor.
        val expected = RoomOps.scale(RoomOps.transform(raw, t), calibration.factor, calibration.origin)
        assertCornersEqual(expected.corners, saved.corners)
        assertEquals(earlier, savedRooms(repo).single { it.id == "old" })
    }

    @Test
    fun reviewShowsTheRoomAsItWillBeSavedOnACalibratedFloor() {
        val repo = FakeRepository(calibratedProject())
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        val raw = requireNotNull(vm.state.value.draftRoom)
        val preview = requireNotNull(vm.roomAsSaved())
        assertCornersEqual(RoomOps.scale(raw, calibration.factor, calibration.origin).corners, preview.corners)
        // Same stats as the saved room (4 × 3 m scaled by 1.02), not the raw draft's.
        val metric = vm.state.value.settings.copy(unitSystem = com.oroplex.floorplan.core.model.UnitSystem.METRIC)
        assertEquals(reviewStats(RoomOps.scale(raw, 1.02, calibration.origin), metric), reviewStats(preview, metric))
        vm.saveAndScanNext(controller)
        assertCornersEqual(preview.corners, savedRooms(repo).single().corners)
    }

    @Test
    fun uncalibratedReviewRoomIsTheDraft() {
        val controller = FakeController()
        val vm = vmInReview(controller)
        assertEquals(vm.state.value.draftRoom, vm.roomAsSaved())
    }

    @Test
    fun firstSavedRoomSetsTheSessionAxisAndLaterRoomsUseIt() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        val first = requireNotNull(vm.state.value.draft.solve)
        val firstId = vm.state.value.draft.id
        vm.saveAndScanNext(controller)
        assertEquals(SessionAxis(canonicalAxis(first.dominantAngle), firstId), vm.state.value.sessionAxis)

        // ARCore refined the map: the first room's frame turned by 0.004 rad since it was captured, the new
        // room's anchor by 0.001 rad. The next room is squared to the shared axis in its own frame.
        vm.skipCeiling(controller)
        val draftId = vm.state.value.draft.id
        controller.corrections = mapOf(firstId to RigidTransform2(0.004, Vec2.ZERO), draftId to RigidTransform2(0.001, Vec2.ZERO))
        vm.onBackground(controller)
        rectangleWalls().forEach { capture(vm, controller, it) }
        val second = requireNotNull(vm.state.value.draft.solve)
        assertEquals(0.0, axisDistance(second.dominantAngle, first.dominantAngle + 0.004 - 0.001), 1e-9)

        // A third room still uses the first room's axis, not a re-estimate.
        vm.closeRoom()
        vm.finishOpenings()
        vm.saveAndScanNext(controller)
        assertEquals(firstId, vm.state.value.sessionAxis?.roomId)
    }

    @Test
    fun sessionAxisIsClearedWhenTheSessionResets() {
        val first = FakeController(sessionId = "s1")
        val vm = vmInReview(first)
        vm.saveAndScanNext(first)
        assertNotNull(vm.state.value.sessionAxis)
        vm.attach(FakeController(sessionId = "s2"))
        assertNull(vm.state.value.sessionAxis)
    }

    // ---- exiting / session ----

    @Test
    fun exitWithoutRoomsFinishesImmediately() {
        val controller = FakeController()
        val vm = newVm()
        vm.attach(controller)
        vm.requestExit(controller)
        assertTrue(vm.state.value.finished)
        assertFalse(vm.state.value.confirmDiscard)
    }

    @Test
    fun exitWithARoomInProgressAsksFirst() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        capture(vm, controller, wall())
        // Back from the AR host's error screen arrives without a live controller: same prompt.
        vm.requestExit(null)
        assertTrue(vm.state.value.confirmDiscard)
        assertFalse(vm.state.value.finished)
        vm.dismissDiscard()
        assertFalse(vm.state.value.confirmDiscard)
        vm.requestExit(controller)
        vm.discardAndExit(controller)
        assertTrue(vm.state.value.finished)
        assertFalse(vm.state.value.confirmDiscard)
    }

    @Test
    fun systemBackLeavesTransientModesBeforeAskingToLeave() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        rectangleWalls().take(2).forEach { capture(vm, controller, it) }

        vm.startRecapture(1)
        vm.onBack(controller)
        assertNull(vm.state.value.draft.recaptureIndex)
        assertFalse(vm.state.value.confirmDiscard)

        vm.startBaseline()
        vm.onBack(controller)
        assertNull(vm.state.value.draft.baseline)
        assertFalse(vm.state.value.confirmDiscard)

        vm.onBack(controller)
        assertTrue(vm.state.value.confirmDiscard)
        assertEquals(2, vm.state.value.draft.observations.size)
    }

    @Test
    fun systemBackInReviewAndOpeningsStepsBack() {
        val controller = FakeController()
        val vm = vmInOpenings(controller)
        controller.results += MeasureResult.Point(Vec3(-0.5, 0.0, 1.5), samples = 6, spread = 0.01)
        vm.markOpeningEdge(controller)
        assertNotNull(vm.state.value.draft.pendingOpeningEdge)
        vm.onBack(controller)
        assertNull(vm.state.value.draft.pendingOpeningEdge)
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)

        vm.finishOpenings()
        vm.onBack(controller)
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)
        assertFalse(vm.state.value.confirmDiscard)
        vm.onBack(controller)
        assertTrue(vm.state.value.confirmDiscard)
    }

    @Test
    fun discardingStillCorrectsTheSessionsSavedRooms() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(controller)
        vm.skipCeiling(controller)
        capture(vm, controller, wall())
        val t = RigidTransform2(0.0, Vec2(0.1, 0.0))
        controller.corrections = mapOf(raw.id to t)
        vm.requestExit(controller)
        vm.discardAndExit(controller)
        assertTrue(vm.state.value.finished)
        val saved = savedRooms(repo).single()
        assertCornersEqual(RoomOps.transform(raw, t).corners, saved.corners)
    }

    @Test
    fun sessionRestartResetsFloorAndRoomInProgress() {
        val first = FakeController(sessionId = "s1")
        val vm = vmInWalls(first)
        capture(vm, first, wall())
        assertTrue(vm.state.value.hasRoomInProgress)

        val second = FakeController(sessionId = "s2")
        vm.attach(second)
        val s = vm.state.value
        assertEquals(CaptureStep.FIND_FLOOR, s.step)
        assertNull(s.floorY)
        assertFalse(s.draft.hasCaptures)
        assertEquals("s2", s.sessionId)
        // A persistent notice explains it (one wall can't be saved on its own).
        assertEquals(InterruptedScan(savedRoomCount = 0, lostRoom = true, restorable = null), s.interrupted)
        assertTrue(interruptedScanText(requireNotNull(s.interrupted)).contains("needs to be scanned again"))
        vm.dismissInterrupted()
        assertNull(vm.state.value.interrupted)
    }

    @Test
    fun endedSessionKeepsItsAnchorCorrections() {
        val repo = FakeRepository(project)
        val first = FakeController(sessionId = "s1")
        val vm = vmInReview(first, repo)
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(first)
        val t = RigidTransform2(0.02, Vec2(-0.1, 0.05))
        first.corrections = mapOf(raw.id to t)

        // The activity is recreated: the old session ends, a new one attaches.
        vm.onSessionEnded(first)
        first.corrections = emptyMap() // a closed controller can't answer any more
        vm.attach(FakeController(sessionId = "s2"))
        assertCornersEqual(RoomOps.transform(raw, t).corners, savedRooms(repo).single().corners)
        assertTrue(vm.state.value.sessionRoomIds.isEmpty())
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

    @Test
    fun feedbackIsPlayedOnce() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        capture(vm, controller, wall())
        val f = requireNotNull(vm.state.value.feedback)
        vm.onFeedbackPlayed(f.id)
        assertEquals(f.id, vm.state.value.feedbackPlayedId)
        vm.onFeedbackPlayed(f.id - 1) // never goes back
        assertEquals(f.id, vm.state.value.feedbackPlayedId)
    }

    // ---- tips / coaching ----

    @Test
    fun tipsShowOnTheFirstScanOnlyAndPauseAutoLock() {
        val flag = OneTimeFlag.InMemory(false)
        val controller = FakeController()
        val vm = CaptureViewModel(FakeRepository(project), project.id, floorId, flag)
        assertTrue(vm.state.value.showTips)
        assertFalse(vm.state.value.tipsFromHelp)
        vm.attach(controller)
        vm.onFrame(trackingFrame(), controller, nowNs = 0L)
        vm.onFrame(trackingFrame(), controller, nowNs = 2_000_000_000L)
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step) // not while the user reads the tips

        vm.dismissTips()
        assertFalse(vm.state.value.showTips)
        assertTrue(flag.isSet())
        assertFalse(CaptureViewModel(FakeRepository(project), project.id, floorId, flag).state.value.showTips)

        vm.openTips()
        assertTrue(vm.state.value.showTips)
        assertTrue(vm.state.value.tipsFromHelp)
        vm.dismissTips()
        assertFalse(vm.state.value.showTips)
        assertFalse(vm.state.value.tipsFromHelp)
    }

    @Test
    fun coachingFollowsTheFrames() {
        val controller = FakeController()
        val vm = vmInWalls(controller)
        val low = ReticleHit(Vec3(0.0, 0.1, -2.0), SurfaceKind.DEPTH, 2.0, null)
        for (i in 0..30) vm.onFrame(trackingFrame(reticle = low, t = i * 33_333_333L), controller)
        assertEquals(CoachKind.AIM_HIGHER, vm.coaching.value?.kind)
    }

    // ---- Back / leaving the scan ----

    @Test
    fun backInWallsWithNothingCapturedStepsBackToTheCeiling() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        controller.results += MeasureResult.Height(2.6, samples = 15, spread = 0.01)
        vm.measureCeiling(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)

        vm.onBack(controller)
        val s = vm.state.value
        assertEquals(CaptureStep.CEILING, s.step)
        assertEquals(2.6, s.draft.ceilingHeight ?: Double.NaN, 1e-12)
        assertTrue(s.draft.ceilingMeasured)
        assertTrue(s.canRelockFloor)
        assertFalse(s.confirmEndSession)
        assertFalse(s.finished)
        assertTrue(stepInstruction(s, FloorStatus.CONFIDENT).contains("Keep it"))

        vm.keepCeiling(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        assertEquals(2.6, vm.state.value.draft.ceilingHeight ?: Double.NaN, 1e-12)

        // Once something is captured, Back asks instead of stepping back.
        capture(vm, controller, wall())
        vm.onBack(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        assertTrue(vm.state.value.confirmDiscard)
    }

    @Test
    fun relockingKeepsAMeasuredCeilingWhereItIs() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        controller.results += MeasureResult.Height(2.6, samples = 15, spread = 0.01)
        vm.measureCeiling(controller)
        vm.onBack(controller)
        vm.relockFloor()
        // The room is a step down: its floor is 0.2 m lower, so the same ceiling is 2.8 m above it.
        controller.results += MeasureResult.FloorLocked(-0.2)
        vm.lockFloor(controller)
        val s = vm.state.value
        assertEquals(CaptureStep.CEILING, s.step)
        assertEquals(2.8, s.draft.ceilingHeight ?: Double.NaN, 1e-12)
        assertTrue(s.draft.ceilingMeasured)
    }

    @Test
    fun backRightAfterRelockKeepsThePreviousFloor() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.relockFloor()
        assertEquals(CaptureStep.FIND_FLOOR, vm.state.value.step)
        vm.onBack(controller)
        val s = vm.state.value
        assertEquals(CaptureStep.CEILING, s.step)
        assertEquals(0.0, s.floorY ?: Double.NaN, 0.0)
        assertNull(s.rejectedFloorY)
        assertFalse(s.confirmEndSession)
        // From the ceiling step, Back now asks before ending the scan.
        vm.onBack(controller)
        assertTrue(vm.state.value.confirmEndSession)
    }

    @Test
    fun backBetweenRoomsAsksBeforeEndingTheScan() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(controller)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)

        vm.onBack(controller)
        assertTrue(vm.state.value.confirmEndSession)
        assertFalse(vm.state.value.confirmDiscard)
        assertFalse(vm.state.value.finished)
        vm.dismissEndSession()
        assertFalse(vm.state.value.confirmEndSession)

        // The close button and the AR host's error screen ask the same way.
        vm.requestExit(controller)
        assertTrue(vm.state.value.confirmEndSession)
        vm.dismissEndSession()
        vm.requestExit(null)
        assertTrue(vm.state.value.confirmEndSession)

        val t = RigidTransform2(0.0, Vec2(0.1, 0.0))
        controller.corrections = mapOf(raw.id to t)
        vm.endSession(controller)
        assertTrue(vm.state.value.finished)
        assertCornersEqual(RoomOps.transform(raw, t).corners, savedRooms(repo).single().corners)
    }

    @Test
    fun backOnTheFirstRoomAsksOnlyOnceAFloorIsLocked() {
        val controller = FakeController()
        controller.frames.value = trackingFrame()
        val vm = vmInCeiling(controller)
        vm.onBack(controller)
        assertTrue(vm.state.value.confirmEndSession)
        assertFalse(vm.state.value.finished)

        // Before anything is locked or saved there is nothing to lose: Back leaves right away.
        val fresh = newVm()
        fresh.attach(controller)
        fresh.onBack(controller)
        assertTrue(fresh.state.value.finished)
        assertFalse(fresh.state.value.confirmEndSession)
    }

    @Test
    fun discardRoomKeepsScanningInTheSameSession() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        vm.saveAndScanNext(controller)
        vm.skipCeiling(controller)
        capture(vm, controller, wall())
        val discardedId = vm.state.value.draft.id
        vm.requestExit(controller)
        assertTrue(vm.state.value.confirmDiscard)

        vm.discardRoom()
        val s = vm.state.value
        assertFalse(s.confirmDiscard)
        assertFalse(s.finished)
        assertEquals(CaptureStep.CEILING, s.step)
        assertFalse(s.draft.hasCaptures)
        assertTrue(s.draft.id != discardedId)
        assertEquals(1, s.sessionRoomIds.size)
        assertEquals(0.0, s.floorY ?: Double.NaN, 0.0)
        assertEquals("session", s.sessionId)
        assertEquals(1, savedRooms(repo).size)
    }

    // ---- wall band ----

    @Test
    fun wallCapturesUseAGenerousBandTopUnlessTheCeilingIsMeasured() {
        val controller = FakeController()
        val vm = vmInWalls(controller) // ceiling skipped
        capture(vm, controller, wall())
        val default = vm.state.value.settings.defaultCeilingHeight
        val skipped = controller.requests.filterIsInstance<MeasureRequest.Wall>().last()
        assertEquals(max(default, 2.4) + 0.3, skipped.ceilingY ?: Double.NaN, 1e-12)

        // The coach's "aim lower" uses the same band top.
        val nearTop = ReticleHit(Vec3(0.0, (skipped.ceilingY ?: 0.0) - 0.1, -2.0), SurfaceKind.DEPTH, 2.0, null)
        for (i in 0..30) vm.onFrame(trackingFrame(reticle = nearTop, t = i * 33_333_333L), controller)
        assertEquals(CoachKind.AIM_LOWER, vm.coaching.value?.kind)

        val measured = FakeController()
        measured.frames.value = trackingFrame()
        val vm2 = vmInCeiling(measured)
        measured.results += MeasureResult.Height(2.61, samples = 15, spread = 0.01)
        vm2.measureCeiling(measured)
        capture(vm2, measured, wall())
        assertEquals(2.61, measured.requests.filterIsInstance<MeasureRequest.Wall>().last().ceilingY ?: Double.NaN, 1e-12)
    }

    @Test
    fun wallBandTop() {
        val settings = project.settings.copy(defaultCeilingHeight = 2.0)
        val d = RoomDraft(id = "d")
        assertEquals(1.0 + 2.4 + 0.3, wallBandTopY(d, 1.0, settings), 1e-12) // default below 2.4 m
        assertEquals(2.7 + 0.3, wallBandTopY(d, 0.0, settings.copy(defaultCeilingHeight = 2.7)), 1e-12)
        assertEquals(2.9 + 0.3, wallBandTopY(d.copy(ceilingHeight = 2.9), 0.0, settings), 1e-12) // "Same as last room"
        assertEquals(2.55, wallBandTopY(d.copy(ceilingHeight = 2.55, ceilingMeasured = true), 0.0, settings), 1e-12)
        assertEquals(2.4 + 0.3, wallBandTopY(d.copy(ceilingHeight = Double.NaN), 0.0, settings.copy(defaultCeilingHeight = Double.NaN)), 1e-12)
    }

    // ---- straightening a finished scan ----

    private fun WallObservation.rotated(a: Double) = copy(
        line = Line2.of(line.point.rotated(a), line.direction.rotated(a)),
        extentStart = extentStart.rotated(a),
        extentEnd = extentEnd.rotated(a),
        viewer = viewer.rotated(a),
    )

    /** Largest distance of the room's edge directions from the page axes (radians, mod 90°). */
    private fun tiltOf(room: Room): Double = room.corners.indices.maxOf { i ->
        val e = room.corners[(i + 1) % room.corners.size] - room.corners[i]
        axisDistance(e.angle(), 0.0)
    }

    private fun scanTiltedRoom(vm: CaptureViewModel, controller: FakeController, angle: Double): Room {
        rectangleWalls().forEach { capture(vm, controller, it.rotated(angle)) }
        vm.closeRoom()
        vm.finishOpenings()
        return requireNotNull(vm.state.value.draftRoom)
    }

    @Test
    fun finishSquaresAFirstScanToThePage() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInWalls(controller, repo)
        val raw = scanTiltedRoom(vm, controller, 0.2)
        assertEquals(0.2, tiltOf(raw), 1e-6)
        vm.saveAndFinish(controller)
        val saved = savedRooms(repo).single()
        assertEquals(0.0, tiltOf(saved), 1e-9)
        assertEquals(Polygons.area(raw.corners), Polygons.area(saved.corners), 1e-9)
    }

    @Test
    fun endingTheScanBetweenRoomsSquaresItToo() {
        val repo = FakeRepository(project)
        val controller = FakeController()
        val vm = vmInWalls(controller, repo)
        scanTiltedRoom(vm, controller, 0.2)
        vm.saveAndScanNext(controller)
        vm.requestExit(controller)
        vm.endSession(controller)
        assertTrue(vm.state.value.finished)
        assertEquals(0.0, tiltOf(savedRooms(repo).single()), 1e-9)
    }

    @Test
    fun finishKeepsTheHeadingOfAFloorWithOtherRooms() {
        val drawn = room("drawn", "Closet", RoomType.CLOSET) // MANUAL, no session
        val earlier = room("old", "Hall", RoomType.HALLWAY).copy(sessionId = "earlier", captureMethod = CaptureMethod.WALLS)
        for (other in listOf(drawn, earlier)) {
            val repo = FakeRepository(project.copy(floors = listOf(Floor(id = floorId, name = "Main Floor", rooms = listOf(other)))))
            val controller = FakeController()
            val vm = vmInWalls(controller, repo)
            val raw = scanTiltedRoom(vm, controller, 0.2)
            vm.saveAndFinish(controller)
            val saved = savedRooms(repo)
            assertEquals(other.name, 0.2, tiltOf(saved.single { it.id == raw.id }), 1e-6)
            assertEquals(other, saved.single { it.id == other.id })
        }
    }

    // ---- surviving process death ----

    @Test
    fun snapshotRestoresTheFlowAfterProcessDeath() {
        val repo = FakeRepository(project)
        val store = CaptureSnapshotStore.InMemory()
        val first = FakeController(sessionId = "s1")
        val vm = vmInReview(first, repo, newVm(repo, store = store))
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(first)
        vm.skipCeiling(first)
        rectangleWalls().take(2).forEach { capture(vm, first, it) }
        val t = RigidTransform2(0.01, Vec2(0.05, 0.0))
        first.corrections = mapOf(raw.id to t)
        vm.onBackground(first) // ON_STOP: corrections go into the snapshot
        store.saveNow()
        assertNotNull(store.saved)

        // Android kills the process; navigation restores the screen with a new ViewModel...
        val restored = newVm(repo, store = CaptureSnapshotStore.InMemory(store.saved))
        val s = restored.state.value
        assertEquals(CaptureStep.WALLS, s.step)
        assertEquals("s1", s.sessionId)
        assertEquals(setOf(raw.id), s.sessionRoomIds)
        assertEquals(2, s.draft.observations.size)
        assertEquals(0.0, s.floorY ?: Double.NaN, 0.0)
        assertEquals(vm.state.value.sessionAxis, s.sessionAxis)

        // ...and a new AR session, whose frame has nothing to do with the old one.
        restored.attach(FakeController(sessionId = "s2"))
        val after = restored.state.value
        assertEquals(CaptureStep.FIND_FLOOR, after.step)
        assertEquals("s2", after.sessionId)
        assertTrue(after.sessionRoomIds.isEmpty())
        assertEquals(InterruptedScan(savedRoomCount = 1, lostRoom = true, restorable = null), after.interrupted)
        // The saved room got the corrections taken when the app went to the background.
        assertCornersEqual(RoomOps.transform(raw, t).corners, savedRooms(repo).single().corners)
        assertTrue(interruptedScanText(requireNotNull(after.interrupted)).contains("Snap to neighbour"))
    }

    @Test
    fun aSolvedRoomInProgressCanBeSavedAfterProcessDeath() {
        val repo = FakeRepository(project)
        val store = CaptureSnapshotStore.InMemory()
        val first = FakeController(sessionId = "s1")
        val vm = vmInReview(first, repo, newVm(repo, store = store))
        val raw = requireNotNull(vm.state.value.draftRoom)
        val t = RigidTransform2(0.02, Vec2(0.1, -0.1))
        first.corrections = mapOf(raw.id to t)
        vm.onBackground(first)
        store.saveNow()

        val restored = newVm(repo, store = CaptureSnapshotStore.InMemory(store.saved))
        assertEquals(CaptureStep.REVIEW, restored.state.value.step)
        restored.attach(FakeController(sessionId = "s2"))
        val notice = requireNotNull(restored.state.value.interrupted)
        assertFalse(notice.lostRoom)
        assertNotNull(notice.restorable)
        assertTrue(interruptedScanText(notice).contains("can still be saved"))

        restored.saveInterruptedRoom()
        val saved = savedRooms(repo).single()
        assertEquals("s1", saved.sessionId) // it shares the old session's frame
        assertEquals("Room", saved.name)
        assertCornersEqual(RoomOps.transform(raw, t).corners, saved.corners)
        assertNull(restored.state.value.interrupted)
        assertEquals(CaptureStep.FIND_FLOOR, restored.state.value.step)
    }

    @Test
    fun leavingARestoredScanBeforeTheCameraIsBackStillAppliesItsCorrections() {
        val repo = FakeRepository(project)
        val store = CaptureSnapshotStore.InMemory()
        val first = FakeController(sessionId = "s1")
        val vm = vmInReview(first, repo, newVm(repo, store = store))
        val raw = requireNotNull(vm.state.value.draftRoom)
        vm.saveAndScanNext(first)
        val t = RigidTransform2(0.0, Vec2(0.2, 0.1))
        first.corrections = mapOf(raw.id to t)
        vm.onBackground(first)
        store.saveNow()

        // Restored, but the user backs out of the camera-permission screen (no AR session attached).
        val restored = newVm(repo, store = CaptureSnapshotStore.InMemory(store.saved))
        restored.requestExit(null)
        assertTrue(restored.state.value.confirmEndSession)
        restored.endSession(null)
        assertTrue(restored.state.value.finished)
        assertCornersEqual(RoomOps.transform(raw, t).corners, savedRooms(repo).single().corners)
    }

    @Test
    fun snapshotIsClearedWhenTheFlowEndsAndIgnoredForAnotherFloor() {
        val repo = FakeRepository(project)
        val store = CaptureSnapshotStore.InMemory()
        val controller = FakeController()
        val idle = newVm(repo, store = store)
        store.saveNow()
        assertNull(store.saved) // nothing started yet

        val vm = vmInReview(controller, repo, newVm(repo, store = store))
        store.saveNow()
        val json = requireNotNull(store.saved)
        vm.saveAndFinish(controller)
        store.saveNow()
        assertNull(store.saved)
        assertFalse(idle.state.value.finished)

        val other = CaptureViewModel(repo, project.id, "other-floor", OneTimeFlag.InMemory(true), snapshotStore = CaptureSnapshotStore.InMemory(json))
        assertNull(other.state.value.sessionId)
        val corrupt = newVm(repo, store = CaptureSnapshotStore.InMemory("{not json"))
        assertNull(corrupt.state.value.sessionId)
        assertEquals(CaptureStep.FIND_FLOOR, corrupt.state.value.step)
    }

    // ---- pure helpers ----

    @Test
    fun reviewNamesRoomsWithTheSharedNumberingRule() {
        val existing = listOf(room("a", "Bedroom", RoomType.BEDROOM), room("b", "Room", RoomType.OTHER))
        val repo = FakeRepository(project.copy(floors = listOf(Floor(id = floorId, name = "Main Floor", rooms = existing))))
        val controller = FakeController()
        val vm = vmInReview(controller, repo)
        assertEquals(RoomNames.unique(RoomType.OTHER, existing, vm.state.value.draft.id), vm.state.value.draft.name)
        assertEquals("Room 2", vm.state.value.draft.name)
        vm.setType(RoomType.BEDROOM)
        assertEquals("Bedroom 2", vm.state.value.draft.name)
        // A typed name is kept when the type changes.
        vm.setName("Guest room")
        vm.setType(RoomType.OFFICE)
        assertEquals("Guest room", vm.state.value.draft.name)
    }

    @Test
    fun openingOverlapDetection() {
        val existing = listOf(Opening("o1", OpeningType.DOOR, wallIndex = 0, offset = 1.0, width = 0.8, height = 2.0))
        assertTrue(overlapsExisting(Opening("o2", OpeningType.DOOR, 0, 1.5, 0.8, 2.0), existing))
        assertFalse(overlapsExisting(Opening("o3", OpeningType.DOOR, 0, 1.81, 0.8, 2.0), existing))
        assertFalse(overlapsExisting(Opening("o4", OpeningType.DOOR, 1, 1.0, 0.8, 2.0), existing))
        // An opening never overlaps itself.
        assertFalse(overlapsExisting(existing.single(), existing))
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
    fun skippedCeilingDraftBecomesEstimatedRoom() {
        val draft = RoomDraft(
            id = "d",
            solve = com.oroplex.floorplan.core.solve.SolveResult(room("x", "x", RoomType.OTHER).corners, listOf(0, 1, 2, 3), 0.0, emptyList()),
        )
        val settings = project.settings
        val skipped = requireNotNull(draft.toRoom("s", settings))
        assertEquals(settings.defaultCeilingHeight, skipped.ceilingHeight ?: Double.NaN, 0.0)
        assertTrue(skipped.ceilingEstimated)
        val measured = requireNotNull(draft.copy(ceilingHeight = 2.7, ceilingMeasured = true).toRoom("s", settings))
        assertEquals(2.7, measured.ceilingHeight ?: Double.NaN, 0.0)
        assertFalse(measured.ceilingEstimated)
        // A "measured" flag without a usable height is not a measurement.
        val broken = requireNotNull(draft.copy(ceilingHeight = Double.NaN, ceilingMeasured = true).toRoom("s", settings))
        assertTrue(broken.ceilingEstimated)
        assertEquals(settings.defaultCeilingHeight, broken.ceilingHeight ?: Double.NaN, 0.0)
    }

    @Test
    fun floorStatusAndExpectedDurations() {
        assertEquals(FloorStatus.SEARCHING, floorStatus(ArFrameState()))
        assertEquals(FloorStatus.FOUND, floorStatus(trackingFrame(floorArea = 0.4, lockable = false)))
        assertEquals(FloorStatus.FOUND, floorStatus(trackingFrame(floorY = 0.0, localFloorY = null)))
        assertEquals(FloorStatus.CONFIDENT, floorStatus(trackingFrame(floorArea = 1.5)))
        assertEquals(FloorStatus.MANUAL_ONLY, floorStatus(trackingFrame(camera = Vec3(0.0, 0.85, 0.0), lockable = false)))
        assertEquals(FloorStatus.SEARCHING, floorStatus(trackingFrame(floorY = Double.NaN, localFloorY = null)))
        // The instruction only promises an auto-lock when one can happen.
        val ui = CaptureUiState(draft = RoomDraft(id = "d"))
        assertTrue(stepInstruction(ui, FloorStatus.CONFIDENT).contains("Hold still"))
        assertFalse(stepInstruction(ui, FloorStatus.FOUND).contains("Hold still"))
        assertTrue(stepInstruction(ui, FloorStatus.FOUND).contains("Tap Lock floor"))
        assertEquals("Floor found. Tap Lock floor.", stepInstruction(ui, FloorStatus.MANUAL_ONLY))
        val d = RoomDraft(id = "d")
        assertTrue(expectedMeasureMillis(CaptureStep.WALLS, d) > expectedMeasureMillis(CaptureStep.CORNERS, d))
        assertEquals(expectedMeasureMillis(CaptureStep.CORNERS, d), expectedMeasureMillis(CaptureStep.WALLS, d.copy(baseline = BaselineCapture())))
        assertEquals(0, expectedMeasureMillis(CaptureStep.REVIEW, d))
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

    private fun assertCornersEqual(expected: List<Vec2>, actual: List<Vec2>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertTrue("$e vs $a", e.approxEquals(a, 1e-9)) }
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

    /**
     * The four walls of a 4 m × 3 m room centered on the viewer, captured in order (CCW), each oriented
     * interior-left: bottom (y = -1.5), right (x = 2), top (y = 1.5), left (x = -2).
     */
    private fun rectangleWalls(): List<WallObservation> {
        fun w(point: Vec2, dir: Vec2, a: Vec2, b: Vec2) = WallObservation(
            line = Line2.of(point, dir),
            extentStart = a,
            extentEnd = b,
            source = WallSource.DEPTH_FIT,
            rmsResidual = 0.006,
            pointCount = 220,
            confidence = 0.85,
            viewer = Vec2.ZERO,
        )
        return listOf(
            w(Vec2(0.0, -1.5), Vec2(1.0, 0.0), Vec2(-1.0, -1.5), Vec2(1.0, -1.5)),
            w(Vec2(2.0, 0.0), Vec2(0.0, 1.0), Vec2(2.0, -0.8), Vec2(2.0, 0.8)),
            w(Vec2(0.0, 1.5), Vec2(-1.0, 0.0), Vec2(1.0, 1.5), Vec2(-1.0, 1.5)),
            w(Vec2(-2.0, 0.0), Vec2(0.0, -1.0), Vec2(-2.0, 0.8), Vec2(-2.0, -0.8)),
        )
    }

    private fun room(id: String, name: String, type: RoomType) = Room(
        id = id,
        name = name,
        type = type,
        corners = listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 3.0), Vec2(0.0, 3.0)),
    )

    /** Distance between two squaring axes (mod 90°), radians. */
    private fun axisDistance(a: Double, b: Double): Double {
        val d = abs(canonicalAxis(a) - canonicalAxis(b))
        return min(d, PI / 2 - d)
    }

    private class FakeController(override val sessionId: String = "session") : ArCaptureController {
        val frames = MutableStateFlow(ArFrameState())
        override val frameState: StateFlow<ArFrameState> get() = frames
        val results = ArrayDeque<MeasureResult>()
        val requests = mutableListOf<MeasureRequest>()
        val anchors = mutableListOf<Pair<String, Vec3>>()
        var corrections: Map<String, RigidTransform2> = emptyMap()
        val referenceFloors = mutableListOf<Double?>()

        override fun setReferenceFloor(y: Double?) {
            referenceFloors += y
        }

        override suspend fun measure(request: MeasureRequest): MeasureResult {
            requests += request
            return results.removeFirstOrNull() ?: MeasureResult.Failed("no result queued")
        }

        override fun createRoomAnchor(roomId: String, position: Vec3) {
            anchors += roomId to position
        }

        override fun anchorCorrections(): Map<String, RigidTransform2> = corrections
    }

    private class FakeRepository(initial: Project?) : ProjectRepository {
        private val project = MutableStateFlow(initial)
        val current: Project? get() = project.value
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
