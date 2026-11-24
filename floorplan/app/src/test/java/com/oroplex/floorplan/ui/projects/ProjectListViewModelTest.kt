package com.oroplex.floorplan.ui.projects

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.data.ProjectSummary
import com.oroplex.floorplan.data.SettingsRepository
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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListViewModelTest {

    private class FakeRepository : ProjectRepository {
        val projects = LinkedHashMap<String, Project>()
        var nextId = 1
        var failWrites = false

        override val summaries: StateFlow<List<ProjectSummary>> = MutableStateFlow(emptyList())
        override fun observe(projectId: String): StateFlow<Project?> = MutableStateFlow(projects[projectId])
        override suspend fun get(projectId: String): Project? = projects[projectId]
        override suspend fun create(name: String, address: String): Project {
            if (failWrites) throw IOException("disk full")
            val p = Project(id = "p${nextId++}", name = name, address = address, createdAt = 1, updatedAt = 1, floors = listOf(Floor("f", "Main Floor")))
            projects[p.id] = p
            return p
        }
        override suspend fun save(project: Project): Project {
            if (failWrites) throw IOException("disk full")
            projects[project.id] = project
            return project
        }
        override suspend fun update(projectId: String, transform: (Project) -> Project): Project? =
            projects[projectId]?.let(transform)?.also { projects[projectId] = it }
        override suspend fun delete(projectId: String) {
            projects.remove(projectId)
        }
        override suspend fun duplicate(projectId: String): Project? = projects[projectId]?.let { save(it.copy(id = "p${nextId++}", name = it.name + " (copy)")) }
        override suspend fun importJson(json: String): Project = error("unused")
        override fun exportJson(project: Project): String = error("unused")
    }

    private class FakeSettings(initial: PlanSettings) : SettingsRepository {
        override val defaults = MutableStateFlow(initial)
        override fun update(transform: (PlanSettings) -> PlanSettings) {
            defaults.value = transform(defaults.value)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.eventsOf(vm: ProjectListViewModel): List<ProjectListEvent> {
        val list = mutableListOf<ProjectListEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { list += it } }
        return list
    }

    @Test
    fun createAppliesTheDefaultsAndOpensTheProjectRightAway() = runTest {
        val repo = FakeRepository()
        val defaults = PlanSettings(unitSystem = UnitSystem.METRIC, defaultDoorWidth = 0.9)
        val vm = ProjectListViewModel(repo, FakeSettings(defaults), clock = { 5_000L })
        val events = eventsOf(vm)

        vm.create("12 Oak Street", "Springfield")
        val open = events.single() as ProjectListEvent.Open
        assertEquals("p1", open.projectId)
        assertEquals(5_000L, open.issuedAt)
        assertEquals(defaults, repo.projects["p1"]?.settings)
    }

    @Test
    fun openRequestsGoStaleOnlyAfterTheyWaitedTooLong() {
        val open = ProjectListEvent.Open("p1", issuedAt = 1_000L)
        assertFalse(open.isStale(1_000L))
        assertFalse(open.isStale(1_000L + ProjectListEvent.Open.STALE_AFTER_MS))
        assertTrue(open.isStale(1_001L + ProjectListEvent.Open.STALE_AFTER_MS))
        assertFalse("requests without a time are never dropped", ProjectListEvent.Open("p1").isStale(Long.MAX_VALUE))
    }

    @Test
    fun deleteOffersUndoThatRestoresTheProject() = runTest {
        val repo = FakeRepository()
        val vm = ProjectListViewModel(repo, FakeSettings(PlanSettings()), clock = { 1L })
        val events = eventsOf(vm)
        val created = repo.create("Cottage", "")

        vm.delete(created.id)
        assertNull(repo.projects[created.id])
        val message = events.single() as ProjectListEvent.Message
        assertEquals("Deleted Cottage", message.text)
        assertNotNull(message.undo)

        vm.restore(message.undo!!)
        assertEquals(created, repo.projects[created.id])
    }

    @Test
    fun storageErrorsBecomeMessages() = runTest {
        val repo = FakeRepository().apply { failWrites = true }
        val vm = ProjectListViewModel(repo, FakeSettings(PlanSettings()), clock = { 1L })
        val events = eventsOf(vm)
        vm.create("Cottage", "")
        val message = events.single() as ProjectListEvent.Message
        assertEquals("Couldn't create the project: disk full", message.text)
        assertNull(message.undo)
    }

    @Test
    fun openSampleUsesThePreferredUnitsAndAFreshId() = runTest {
        val repo = FakeRepository()
        val vm = ProjectListViewModel(repo, FakeSettings(PlanSettings(unitSystem = UnitSystem.METRIC)), clock = { 7_000L })
        val events = eventsOf(vm)
        vm.openSample()
        vm.openSample()
        val opens = events.filterIsInstance<ProjectListEvent.Open>()
        assertEquals(2, opens.size)
        assertTrue(opens[0].projectId != opens[1].projectId)
        assertTrue(opens.all { it.issuedAt == 7_000L })
        assertTrue(repo.projects.values.all { it.settings.unitSystem == UnitSystem.METRIC && it.rooms() > 0 })
    }

    @Test
    fun readLimitedRejectsHugeFiles() {
        val data = ByteArray(1000) { 1 }
        assertEquals(1000, ProjectListViewModel.readLimited(data.inputStream(), 1000).size)
        try {
            ProjectListViewModel.readLimited(data.inputStream(), 999)
            throw AssertionError("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    private fun Project.rooms() = floors.sumOf { it.rooms.size }
}
