package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DollhouseViewModelTest {
    private class FakeRepository(initial: Project?) : ProjectRepository {
        val flow = MutableStateFlow(initial)
        override val summaries: StateFlow<List<ProjectSummary>> = MutableStateFlow(emptyList())
        override fun observe(projectId: String): StateFlow<Project?> = flow
        override suspend fun get(projectId: String): Project? = flow.value
        override suspend fun create(name: String, address: String): Project = error("unused")
        override suspend fun save(project: Project): Project = error("unused")
        override suspend fun update(projectId: String, transform: (Project) -> Project): Project? = error("unused")
        override suspend fun delete(projectId: String) {
            error("unused")
        }
        override suspend fun duplicate(projectId: String): Project? = error("unused")
        override suspend fun importJson(json: String): Project = error("unused")
        override fun exportJson(project: Project): String = error("unused")
    }

    private fun rect(id: String, x: Double, w: Double = 4.0) = Room(
        id = id,
        name = id,
        corners = listOf(Vec2(x, 0.0), Vec2(x + w, 0.0), Vec2(x + w, 3.0), Vec2(x, 3.0)),
        ceilingHeight = 2.5,
    )

    private val broken = Room(id = "broken", name = "Broken", corners = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0)))

    private fun project(vararg rooms: Room) = Project(
        id = "p",
        name = "House",
        createdAt = 1,
        updatedAt = 1,
        floors = listOf(Floor("f", "Main Floor", rooms.toList())),
    )

    private val dispatcher = UnconfinedTestDispatcher()

    private fun viewModel(repo: ProjectRepository, floorId: String = "f") =
        DollhouseViewModel(repo, "p", floorId, buildDispatcher = dispatcher, log = { _, _ -> })

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun missingProject() {
        val vm = viewModel(FakeRepository(null))
        assertEquals(DollhouseStatus.MISSING_PROJECT, vm.state.value.status)
        assertNull(vm.camera)
    }

    @Test fun missingFloor() {
        val vm = viewModel(FakeRepository(project(rect("a", 0.0))), floorId = "nope")
        assertEquals(DollhouseStatus.MISSING_FLOOR, vm.state.value.status)
        assertEquals("House", vm.state.value.projectName)
    }

    @Test fun buildsTheSceneAndFramesItOnceTheViewportIsKnown() {
        val vm = viewModel(FakeRepository(project(rect("a", 0.0), rect("b", 4.1), broken)))
        val s = vm.state.value
        assertEquals(DollhouseStatus.READY, s.status)
        assertEquals("Main Floor", s.floorName)
        assertEquals(3, s.roomCount)
        val scene = checkNotNull(s.scene)
        assertEquals(1, scene.skippedRooms)
        assertFalse(scene.isEmpty)
        assertEquals(2, scene.mesh.labels.size)
        assertNull(vm.camera) // no viewport yet
        vm.onViewport(1080, 1900, 60f)
        val cam = checkNotNull(vm.camera)
        assertEquals(OrbitCamera.DEFAULT_PITCH, cam.pitch, 1e-12)
        assertEquals(scene.mesh.bounds.center, cam.target)
    }

    @Test fun gesturesMoveTheCameraAndSurviveModelUpdates() {
        val repo = FakeRepository(project(rect("a", 0.0)))
        val vm = viewModel(repo)
        vm.onViewport(1080, 1900, 60f)
        val framed = checkNotNull(vm.camera)
        vm.orbit(200f, 0f, 2.75f)
        val moved = checkNotNull(vm.camera)
        assertNotEquals(framed.yaw, moved.yaw, 1e-9)
        vm.zoom(2f)
        assertTrue(checkNotNull(vm.camera).distance < moved.distance)
        vm.pan(30f, 0f)
        val user = checkNotNull(vm.camera)
        // A live edit (another room) rebuilds the model but keeps the user's view.
        repo.flow.value = project(rect("a", 0.0), rect("b", 4.1))
        assertEquals(2, vm.state.value.scene?.mesh?.labels?.size)
        assertEquals(user.yaw, checkNotNull(vm.camera).yaw, 1e-12)
        // A reset that settles makes the view "framed" again, so the next model change re-frames it.
        vm.setCamera(checkNotNull(vm.framedCamera()), settled = true)
        repo.flow.value = project(rect("a", 0.0), rect("b", 4.1), rect("c", 8.2))
        assertEquals(checkNotNull(vm.state.value.scene).mesh.bounds.center, checkNotNull(vm.camera).target)
    }

    @Test fun pitchStaysInRange() {
        val vm = viewModel(FakeRepository(project(rect("a", 0.0))))
        vm.onViewport(1080, 1900, 60f)
        vm.orbit(0f, 1e6f, 3f)
        assertEquals(Math.toRadians(85.0), checkNotNull(vm.camera).pitch, 1e-9)
        vm.orbit(0f, -1e6f, 3f)
        assertEquals(Math.toRadians(15.0), checkNotNull(vm.camera).pitch, 1e-9)
    }

    @Test fun emptyFloorHasNoModelToFrame() {
        val vm = viewModel(FakeRepository(project()))
        assertEquals(0, vm.state.value.roomCount)
        assertTrue(checkNotNull(vm.state.value.scene).isEmpty)
        vm.onViewport(1080, 1900, 60f)
        assertNull(vm.camera)
        vm.orbit(10f, 10f, 3f) // no camera: ignored, no crash
        assertNull(vm.framedCamera())
    }

    @Test fun deletedProjectWhileViewing() {
        val repo = FakeRepository(project(rect("a", 0.0)))
        val vm = viewModel(repo)
        repo.flow.value = null
        assertEquals(DollhouseStatus.MISSING_PROJECT, vm.state.value.status)
        assertNull(vm.state.value.scene)
    }

    @Test fun sceneBuildCountsUnusableRooms() {
        val scene = DollhouseScene.build(listOf(rect("a", 0.0), broken), com.oroplex.floorplan.core.model.PlanSettings())
        assertEquals(2, scene.roomCount)
        assertEquals(1, scene.skippedRooms)
        val none = DollhouseScene.build(listOf(broken), com.oroplex.floorplan.core.model.PlanSettings())
        assertTrue(none.isEmpty)
        assertEquals(1, none.skippedRooms)
    }
}
