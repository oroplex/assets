package com.oroplex.floorplan.walkthrough

import android.opengl.Matrix
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oroplex.floorplan.MainActivity
import com.oroplex.floorplan.ar.ArCaptureController
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.MeasureRequest
import com.oroplex.floorplan.ar.MeasureResult
import com.oroplex.floorplan.ar.ReticleHit
import com.oroplex.floorplan.ar.SurfaceKind
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.fit.WallFit
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.RigidTransform2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.WallObservation
import com.oroplex.floorplan.core.model.WallSource
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.data.ProjectSummary
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.ui.capture.CaptureContent
import com.oroplex.floorplan.ui.capture.CaptureDialogs
import com.oroplex.floorplan.ui.capture.CaptureSnapshotStore
import com.oroplex.floorplan.ui.capture.CaptureStep
import com.oroplex.floorplan.ui.capture.CaptureViewModel
import com.oroplex.floorplan.ui.capture.OneTimeFlag
import com.oroplex.floorplan.ui.theme.FloorPlanTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the scanning screens without a camera: the real capture UI and ViewModel are driven by a simulated
 * AR feed (floor found, a 4 m x 3 m room captured wall by wall, a door, review), over a plain backdrop that
 * stands in for the camera image. Run once on a dark and once on a bright backdrop, because the overlays
 * must stay readable on both a dim room and a white wall. Screenshots: app/build/ui-walkthrough/capture-*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class CaptureWalkthroughTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun scanARoomInADimRoom() = scanARoom("capture-dark", listOf(Color(0xFF2B2E33), Color(0xFF4A4741)))

    @Test
    fun scanARoomFacingAWhiteWall() = scanARoom("capture-bright", listOf(Color(0xFFF4F2EE), Color(0xFFD9D5CC)))

    /** Shows the capture UI (and its dialogs) for [vm] over [backdrop]; returns the driver to pump and shoot with. */
    private fun host(vm: CaptureViewModel, controller: FakeController, backdrop: List<Color>): RawUi {
        rule.mainClock.autoAdvance = false
        rule.activity.setContent {
            FloorPlanTheme {
                val state = vm.state.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(backdrop))) {
                    CaptureContent(vm, state, controller)
                }
                CaptureDialogs(vm, state) { controller }
            }
        }
        return RawUi(rule.mainClock)
    }

    private fun scanARoom(group: String, backdrop: List<Color>) {
        val controller = FakeController()
        val vm = CaptureViewModel(FakeRepository(project), project.id, FLOOR_ID, OneTimeFlag.InMemory(true))
        val ui = host(vm, controller, backdrop)
        fun show(frame: ArFrameState) {
            controller.frames.value = frame
            ui.pump()
        }

        // 1. Looking for the floor.
        show(frame(t = 1, floorY = null, lookAt = FLOOR_AHEAD, reticle = null))
        ui.shoot(group, "find-floor-searching")

        // 2. Floor found and auto-locked -> ceiling step.
        val floorFrame = frame(t = 2, lookAt = FLOOR_AHEAD, reticle = ReticleHit(FLOOR_AHEAD, SurfaceKind.FLOOR, 2.0, Vec3.UP))
        show(floorFrame)
        // The screen's own frame collector has already seen this floor "now"; 1.5 s later it is stable.
        vm.onFrame(floorFrame.copy(timestampNs = 3), controller, nowNs = System.nanoTime() + 1_500_000_000L)
        assertEquals(CaptureStep.CEILING, vm.state.value.step)
        show(frame(t = 4, lookAt = CEILING_ABOVE, reticle = ReticleHit(CEILING_ABOVE, SurfaceKind.CEILING, 1.1, null)))
        ui.shoot(group, "ceiling")

        // 3. Walls.
        vm.skipCeiling(controller)
        assertEquals(CaptureStep.WALLS, vm.state.value.step)
        val wallFrame = frame(t = 5, lookAt = WALL_AHEAD, reticle = ReticleHit(WALL_AHEAD, SurfaceKind.DEPTH, 1.5, null))
        show(wallFrame)
        ui.shoot(group, "walls-none-yet")
        val walls = rectangleWalls()
        walls.take(2).forEach { capture(vm, controller, it) }
        show(wallFrame.copy(timestampNs = 6))
        ui.shoot(group, "walls-two-captured")
        walls.drop(2).forEach { capture(vm, controller, it) }
        show(wallFrame.copy(timestampNs = 7))
        ui.shoot(group, "walls-all-captured")

        // 4. Doors and windows.
        vm.closeRoom()
        assertEquals(CaptureStep.OPENINGS, vm.state.value.step)
        show(frame(t = 8, lookAt = DOOR_BASE, reticle = ReticleHit(DOOR_BASE, SurfaceKind.FLOOR, 2.0, Vec3.UP)))
        ui.shoot(group, "openings")

        // 5. Review.
        vm.finishOpenings()
        assertEquals(CaptureStep.REVIEW, vm.state.value.step)
        ui.pump()
        ui.shoot(group, "review")

        // 6. Leaving with a room in progress: the discard dialog.
        vm.requestExit(controller)
        ui.shoot(group, "discard-dialog")
        vm.dismissDiscard()
        ui.pump()

        // 7. Save and start the next room; leaving now asks before ending the scan.
        vm.saveAndScanNext(controller)
        ui.pump(1_000)
        ui.shoot(group, "next-room")
        vm.requestExit(controller)
        ui.shoot(group, "end-scan-dialog")
        vm.dismissEndSession()
        ui.pump()

        // 8. The help sheet.
        vm.openTips()
        ui.shoot(group, "how-to-scan")
        vm.dismissTips()
        ui.pump()

        // 9. Second room in corners mode, with a failed measurement and then lost tracking.
        if (vm.state.value.step == CaptureStep.CEILING) vm.skipCeiling(controller)
        vm.setMethod(CaptureMethod.CORNERS)
        show(frame(t = 20, lookAt = FLOOR_AHEAD, reticle = ReticleHit(FLOOR_AHEAD, SurfaceKind.FLOOR, 2.0, Vec3.UP)))
        for (corner in listOf(Vec3(5.0, 0.0, 1.5), Vec3(8.0, 0.0, 1.5), Vec3(8.0, 0.0, -1.5))) {
            controller.results += MeasureResult.Point(corner, samples = 6, spread = 0.01)
            vm.addCorner(controller)
        }
        ui.shoot(group, "corners-three-tapped")
        vm.addCorner(controller) // nothing queued: the measurement fails and says why
        ui.shoot(group, "measurement-failed")
        show(
            frame(t = 21, lookAt = WALL_AHEAD, reticle = null).copy(
                tracking = TrackingStatus.PAUSED,
                problem = TrackingProblem.INSUFFICIENT_FEATURES,
            ),
        )
        ui.pump(1_500)
        ui.shoot(group, "tracking-lost")
    }

    /** The app was killed mid-scan and restored into a new AR session: the recovery dialog. */
    @Test
    fun interruptedScan() {
        val backdrop = listOf(Color(0xFF2B2E33), Color(0xFF4A4741))
        val store = CaptureSnapshotStore.InMemory()
        val repository = FakeRepository(project)
        val first = FakeController("session-before")
        val before = CaptureViewModel(repository, project.id, FLOOR_ID, OneTimeFlag.InMemory(true), snapshotStore = store)
        before.attach(first)
        val floorFrame = frame(t = 2, lookAt = FLOOR_AHEAD, reticle = ReticleHit(FLOOR_AHEAD, SurfaceKind.FLOOR, 2.0, Vec3.UP))
        first.frames.value = floorFrame
        before.onFrame(floorFrame, first, nowNs = 0L)
        before.onFrame(floorFrame.copy(timestampNs = 3), first, nowNs = 1_500_000_000L)
        before.skipCeiling(first)
        rectangleWalls().forEach { capture(before, first, it) }
        before.closeRoom()
        before.finishOpenings()
        assertEquals(CaptureStep.REVIEW, before.state.value.step)
        before.onBackground(first)
        store.saveNow()

        val second = FakeController("session-after")
        val after = CaptureViewModel(repository, project.id, FLOOR_ID, OneTimeFlag.InMemory(true), snapshotStore = store)
        val ui = host(after, second, backdrop)
        second.frames.value = frame(t = 30, floorY = null, lookAt = FLOOR_AHEAD, reticle = null)
        ui.pump(1_000)
        ui.shoot("capture-interrupted", "recovery-dialog")
    }

    private fun capture(vm: CaptureViewModel, controller: FakeController, wall: WallObservation) {
        controller.results += MeasureResult.WallMeasured(WallFit(wall, null, 200, 220))
        vm.captureWall(controller)
    }

    /** A tracking frame from a phone held 1.4 m up at the room centre, looking at [lookAt]. */
    private fun frame(t: Long, lookAt: Vec3, reticle: ReticleHit?, floorY: Double? = 0.0): ArFrameState {
        val view = FloatArray(16)
        val projection = FloatArray(16)
        val viewProjection = FloatArray(16)
        Matrix.setLookAtM(
            view, 0,
            CAMERA.x.toFloat(), CAMERA.y.toFloat(), CAMERA.z.toFloat(),
            lookAt.x.toFloat(), lookAt.y.toFloat(), lookAt.z.toFloat(),
            0f, 1f, 0f,
        )
        Matrix.perspectiveM(projection, 0, 65f, WIDTH.toFloat() / HEIGHT, 0.1f, 100f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
        return ArFrameState(
            tracking = TrackingStatus.TRACKING,
            problem = TrackingProblem.NONE,
            floorY = floorY,
            floorArea = if (floorY != null) 3.0 else 0.0,
            reticle = reticle,
            cameraPosition = CAMERA,
            viewProjection = viewProjection,
            viewportWidth = WIDTH,
            viewportHeight = HEIGHT,
            depthEnabled = true,
            timestampNs = t,
            localFloorY = floorY,
            floorAutoLockable = floorY != null,
            cameraForward = (lookAt - CAMERA).normalized(),
        )
    }

    /**
     * The four walls of a 4 m x 3 m room centred on the viewer, captured in order, each oriented
     * interior-left: bottom (plan y = -1.5), right (x = 2), top (y = 1.5), left (x = -2).
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

    private class FakeController(override val sessionId: String = "walkthrough-session") : ArCaptureController {
        val frames = MutableStateFlow(ArFrameState())
        override val frameState: StateFlow<ArFrameState> get() = frames
        val results = ArrayDeque<MeasureResult>()

        override suspend fun measure(request: MeasureRequest): MeasureResult =
            results.removeFirstOrNull() ?: MeasureResult.Failed("Nothing to measure in the walkthrough")

        override fun createRoomAnchor(roomId: String, position: Vec3) = Unit

        override fun anchorCorrections(): Map<String, RigidTransform2> = emptyMap()
    }

    private class FakeRepository(initial: Project) : ProjectRepository {
        private val project = MutableStateFlow<Project?>(initial)
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

    private companion object {
        const val FLOOR_ID = "floor-1"
        const val WIDTH = 1081
        const val HEIGHT = 2401
        val CAMERA = Vec3(0.0, 1.4, 0.0)
        val FLOOR_AHEAD = Vec3(0.0, 0.0, -1.4)
        val CEILING_ABOVE = Vec3(0.0, 2.5, -0.3)
        val WALL_AHEAD = Vec3(0.0, 1.5, -1.5)
        val DOOR_BASE = Vec3(-0.5, 0.0, 1.5)
        val project = Project(
            id = "p1",
            name = "House",
            createdAt = 0L,
            updatedAt = 0L,
            floors = listOf(Floor(id = FLOOR_ID, name = "Main Floor")),
        )
    }
}
