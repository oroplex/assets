package com.oroplex.floorplan.data

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FileProjectRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Stand-in for core's ProjectJson with the same contract. */
    private val codec = object : ProjectCodec {
        override fun encode(project: Project): String = json.encodeToString(Project.serializer(), project)
        override fun decode(json: String): Project = this@FileProjectRepositoryTest.json.decodeFromString(Project.serializer(), json)
    }

    private var now = 1_000L

    private fun TestScope.repo(dir: File = tmp.root) = FileProjectRepository(
        filesDir = dir,
        codec = codec,
        clock = { now },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        areaOf = { p -> p.floors.sumOf { f -> f.rooms.sumOf { Polygons.area(it.corners) } } },
        log = { _, _ -> },
    )

    private val projectsDir get() = File(tmp.root, "projects")

    private fun square(id: String, size: Double) = Room(
        id = id,
        name = id,
        corners = listOf(Vec2(0.0, 0.0), Vec2(size, 0.0), Vec2(size, size), Vec2(0.0, size)),
    )

    @Test
    fun createPersistsProjectWithMainFloorAndDefaults() = runTest {
        val repo = repo()
        val p = repo.create("  Oak Street  ", "12 Oak St")
        assertEquals("Oak Street", p.name)
        assertEquals("12 Oak St", p.address)
        assertEquals(listOf("Main Floor"), p.floors.map { it.name })
        assertEquals(PlanSettings(), p.settings)
        assertTrue(File(projectsDir, "${p.id}.json").isFile)
        assertEquals(p, repo.get(p.id))
        assertEquals(listOf(p.id), repo.summaries.value.map { it.id })
        assertTrue(repo.isLoaded.value)
    }

    @Test
    fun blankNameGetsDefault() = runTest {
        assertEquals("Untitled project", repo().create("   ").name)
    }

    @Test
    fun summariesAreSortedByUpdatedAtDescendingWithStats() = runTest {
        val repo = repo()
        now = 10
        val a = repo.create("A")
        now = 20
        val b = repo.create("B")
        now = 30
        repo.update(a.id) { it.copy(floors = listOf(Floor("f1", "One", listOf(square("r1", 2.0), square("r2", 3.0))), Floor("f2", "Two"))) }
        val summaries = repo.summaries.value
        assertEquals(listOf(a.id, b.id), summaries.map { it.id })
        val sa = summaries.first()
        assertEquals(2, sa.roomCount)
        assertEquals(2, sa.floorCount)
        assertEquals(13.0, sa.totalArea, 1e-9)
        assertEquals(30L, sa.updatedAt)
    }

    @Test
    fun saveStampsUpdatedAtAndReloadsInNewInstance() = runTest {
        val repo = repo()
        val p = repo.create("House")
        now = 5_000
        val saved = repo.save(p.copy(name = "Renamed"))
        assertEquals(5_000L, saved.updatedAt)
        val reloaded = repo().get(p.id)
        assertEquals(saved, reloaded)
    }

    @Test
    fun observeEmitsNullWhileLoadingThenTheProjectThenNullAfterDelete() = runTest {
        val first = repo()
        val p = first.create("House")

        val repo = repo()
        val flow = repo.observe(p.id)
        assertNull("not loaded yet", flow.value)
        assertFalse(repo.isLoaded.value)
        advanceUntilIdle()
        assertEquals(p, flow.value)

        repo.update(p.id) { it.copy(notes = "n") }
        assertEquals("n", flow.value?.notes)

        repo.delete(p.id)
        assertNull(flow.value)
        assertNull(repo.get(p.id))
        assertFalse(File(projectsDir, "${p.id}.json").exists())
        assertTrue(repo.summaries.value.isEmpty())
    }

    @Test
    fun updateOfMissingProjectReturnsNullAndKeepsTheId() = runTest {
        val repo = repo()
        assertNull(repo.update("nope") { it })
        val p = repo.create("House")
        val updated = repo.update(p.id) { it.copy(id = "other-id", name = "X") }
        assertEquals(p.id, updated?.id)
        assertNull(repo.get("other-id"))
    }

    @Test
    fun duplicateGetsNewIdAndCopySuffix() = runTest {
        val repo = repo()
        val p = repo.create("House")
        val copy = repo.duplicate(p.id)
        assertNotNull(copy)
        assertNotEquals(p.id, copy?.id)
        assertEquals("House (copy)", copy?.name)
        assertEquals(2, repo.summaries.value.size)
        assertNull(repo.duplicate("missing"))
    }

    @Test
    fun corruptFilesAreQuarantinedAndSkipped() = runTest {
        val good = repo().create("Good")
        File(projectsDir, "broken.json").writeText("{ not json")
        File(projectsDir, "${good.id}.json.tmp").writeText("partial")

        val repo = repo()
        assertEquals(good, repo.get(good.id))
        assertEquals(listOf(good.id), repo.summaries.value.map { it.id })
        assertFalse(File(projectsDir, "broken.json").exists())
        assertFalse("leftover temp file removed", File(projectsDir, "${good.id}.json.tmp").exists())
        val quarantined = File(projectsDir, "corrupt").listFiles().orEmpty()
        assertEquals(1, quarantined.size)
        assertTrue(quarantined[0].name.startsWith("broken"))
    }

    @Test
    fun fileNameIsAuthoritativeForTheId() = runTest {
        val p = repo().create("House")
        // A hand-copied file: same content, different file name.
        File(projectsDir, "${p.id}.json").copyTo(File(projectsDir, "copy-1.json"))
        val repo = repo()
        assertEquals("copy-1", repo.get("copy-1")?.id)
        assertEquals(2, repo.summaries.value.size)
    }

    @Test
    fun importAssignsNewIdOnCollisionOrUnsafeId() = runTest {
        val repo = repo()
        val p = repo.create("House")
        val backup = repo.exportJson(p)

        val imported = repo.importJson(backup)
        assertNotEquals(p.id, imported.id)
        assertEquals("House", imported.name)

        val unsafe = repo.importJson(codec.encode(p.copy(id = "../../evil")))
        assertTrue(unsafe.id.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(File(projectsDir, "${unsafe.id}.json").isFile)

        val fresh = repo.importJson(codec.encode(p.copy(id = "fresh-id")))
        assertEquals("fresh-id", fresh.id)
        assertEquals(4, repo.summaries.value.size)
    }

    @Test
    fun importRejectsGarbageWithIllegalArgument() = runTest {
        val repo = repo()
        try {
            repo.importJson("hello")
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertTrue(repo.summaries.value.isEmpty())
    }

    @Test
    fun importSanitizesInvalidSettings() = runTest {
        val repo = repo()
        val p = repo.create("House")
        val bad = p.copy(id = "bad-settings", settings = PlanSettings(wallThickness = -1.0, imperialDenominator = 3))
        val imported = repo.importJson(codec.encode(bad))
        assertEquals(PlanSettings().wallThickness, imported.settings.wallThickness, 0.0)
        assertEquals(PlanSettings().imperialDenominator, imported.settings.imperialDenominator)
    }

    @Test
    fun noTempFilesRemainAfterWrites() = runTest {
        val repo = repo()
        val p = repo.create("House")
        repeat(3) { repo.update(p.id) { it.copy(notes = "v$it") } }
        val names = projectsDir.list().orEmpty().toList()
        assertEquals(listOf("${p.id}.json"), names)
    }
}
