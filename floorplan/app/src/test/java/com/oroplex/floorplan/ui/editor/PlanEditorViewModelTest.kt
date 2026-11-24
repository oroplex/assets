package com.oroplex.floorplan.ui.editor

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.geometry.Vec2
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

    private val project = Project(
        id = "p1",
        name = "House",
        createdAt = 1,
        updatedAt = 1,
        floors = listOf(Floor("f1", "Main Floor")),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.messagesOf(vm: PlanEditorViewModel): List<String> {
        val list = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { list += it } }
        return list
    }

    @Test
    fun loadsTheProjectAndSelectsTheFirstFloor() = runTest {
        val vm = PlanEditorViewModel(FakeRepository(project), "p1")
        val s = vm.state.value
        assertEquals(EditorStatus.READY, s.status)
        assertEquals("f1", s.floorId)
        assertEquals(project, s.project)
        assertFalse(s.canUndo)
    }

    @Test
    fun missingProjectIsReported() = runTest {
        val vm = PlanEditorViewModel(FakeRepository(null), "p1")
        assertEquals(EditorStatus.MISSING, vm.state.value.status)
    }

    @Test
    fun editsAreSavedAndUndoable() = runTest {
        val repo = FakeRepository(project)
        val vm = PlanEditorViewModel(repo, "p1")

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
        val vm = PlanEditorViewModel(repo, "p1")
        vm.renameProject("House", "")
        vm.renameProject("   ", "x") // blank names are ignored
        assertFalse(vm.state.value.canUndo)
        assertEquals(0, repo.saves)
    }

    @Test
    fun externalChangesAreAdoptedAsAnUndoableStep() = runTest {
        val repo = FakeRepository(project)
        val vm = PlanEditorViewModel(repo, "p1")
        vm.setView("f1", ViewTransform(10.0, Vec2.ZERO, 1.0))

        // e.g. the capture screen saved new data into the project
        repo.update("p1") { it.copy(notes = "scanned") }
        assertEquals("scanned", vm.state.value.project?.notes)
        assertTrue(vm.state.value.canUndo)
        assertNull("the canvas refits after external changes", vm.viewFor("f1"))

        vm.undo()
        assertEquals("", vm.state.value.project?.notes)
        assertEquals("", repo.flow.value?.notes)
    }

    @Test
    fun deletionElsewhereShowsMissing() = runTest {
        val repo = FakeRepository(project)
        val vm = PlanEditorViewModel(repo, "p1")
        repo.delete("p1")
        assertEquals(EditorStatus.MISSING, vm.state.value.status)
    }

    @Test
    fun floorsCanBeAddedRenamedAndDeleted() = runTest {
        val repo = FakeRepository(project)
        val vm = PlanEditorViewModel(repo, "p1")
        val messages = messagesOf(vm)

        vm.deleteFloor("f1")
        assertEquals(1, vm.state.value.project?.floors?.size)
        assertEquals(listOf("A project needs at least one floor."), messages)

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
        val vm = PlanEditorViewModel(FakeRepository(project), "p1")
        vm.select(Selection.Room("nope"))
        assertNull(vm.state.value.selection)
        vm.toggleMoveMode()
        assertFalse(vm.state.value.moveMode)
    }

    @Test
    fun settingsAreSanitizedAndUndoable() = runTest {
        val repo = FakeRepository(project)
        val vm = PlanEditorViewModel(repo, "p1")
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
        val vm = PlanEditorViewModel(repo, "p1")
        val messages = messagesOf(vm)
        vm.renameProject("Cottage", "")
        assertEquals("Cottage", vm.state.value.project?.name)
        assertEquals("House", repo.flow.value?.name)
        assertEquals(1, messages.size)
        assertTrue(messages[0].startsWith("Couldn't save your changes"))

        repo.failSaves = false
        vm.renameProject("Cottage 2", "")
        assertEquals("Cottage 2", repo.flow.value?.name)
    }

    @Test
    fun undoHistoryIsCappedAtFifty() = runTest {
        val vm = PlanEditorViewModel(FakeRepository(project), "p1")
        repeat(60) { vm.renameProject("Name $it", "") }
        var undos = 0
        while (vm.state.value.canUndo) {
            vm.undo()
            undos++
        }
        assertEquals(PlanEditorViewModel.MAX_UNDO, undos)
        assertEquals("Name 9", vm.state.value.project?.name)
    }
}
