package com.oroplex.floorplan.data

import android.util.Log
import com.oroplex.floorplan.core.io.Ids
import com.oroplex.floorplan.core.io.ProjectJson
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Serialization seam so the repository can be unit-tested independently of core's [ProjectJson]. */
interface ProjectCodec {
    fun encode(project: Project): String

    /** Throws IllegalArgumentException with a user-presentable message for malformed input. */
    fun decode(json: String): Project

    companion object {
        val Default: ProjectCodec = object : ProjectCodec {
            override fun encode(project: Project): String = ProjectJson.encode(project)
            override fun decode(json: String): Project = ProjectJson.decode(json)
        }
    }
}

/**
 * Stores each project as filesDir/projects/<id>.json.
 *
 * All projects are loaded into memory once, on a background scope, when the repository is created (a
 * project is a few kB of JSON). Reads are served from that cache; every suspend function first waits for
 * the initial load. Writes are serialized by a [Mutex] and are atomic: the JSON is written and fsync'ed to
 * a temp file which then replaces the target with an atomic rename, so a crash mid-save never leaves a
 * half-written project. Files that cannot be decoded are moved to projects/corrupt/ (kept for manual
 * recovery) and skipped instead of failing the whole list.
 *
 * Project ids double as file names, so ids that are not safe file names (e.g. from an imported backup) are
 * replaced on import; on load, the file name is authoritative.
 */
class FileProjectRepository(
    filesDir: File,
    private val codec: ProjectCodec = ProjectCodec.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val areaOf: (Project) -> Double = Measurements::projectArea,
    private val log: (message: String, error: Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) : ProjectRepository {
    private val dir = File(filesDir, "projects")
    private val corruptDir = File(dir, "corrupt")

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val writeMutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()

    /**
     * Guards [cache], [summaryCache], [flows] and [isLoadedFlag]. Held only for short in-memory updates
     * (never across I/O), so it is safe to take from the non-suspending [observe].
     */
    private val stateLock = Any()
    private val cache = HashMap<String, Project>()
    private val summaryCache = HashMap<String, ProjectSummary>()
    private val flows = HashMap<String, MutableStateFlow<Project?>>()
    private var isLoadedFlag = false

    private val summariesFlow = MutableStateFlow<List<ProjectSummary>>(emptyList())
    private val loadedFlow = MutableStateFlow(false)

    override val summaries: StateFlow<List<ProjectSummary>> = summariesFlow.asStateFlow()

    /** False until the projects on disk have been read; lets the UI tell "loading" from "no projects". */
    val isLoaded: StateFlow<Boolean> = loadedFlow.asStateFlow()

    init {
        scope.launch {
            try {
                loadAll()
            } catch (e: Exception) {
                // e.g. a SecurityException listing the directory: start empty rather than hang forever.
                log("Could not load projects", e)
                finishLoad(emptyMap())
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    override fun observe(projectId: String): StateFlow<Project?> = synchronized(stateLock) {
        flows.getOrPut(projectId) { MutableStateFlow(if (isLoadedFlag) cache[projectId] else null) }
    }.asStateFlow()

    override suspend fun get(projectId: String): Project? {
        loaded.await()
        return synchronized(stateLock) { cache[projectId] }
    }

    override suspend fun create(name: String, address: String): Project {
        val now = clock()
        val project = Project(
            id = Ids.newId(),
            name = name.trim().ifEmpty { DEFAULT_NAME },
            address = address.trim(),
            createdAt = now,
            updatedAt = now,
            floors = listOf(Floor(id = Ids.newId(), name = "Main Floor")),
        )
        return locked { persist(project) }
    }

    override suspend fun save(project: Project): Project {
        require(isSafeId(project.id)) { "Invalid project id." }
        return locked { persist(project.copy(updatedAt = clock())) }
    }

    override suspend fun update(projectId: String, transform: (Project) -> Project): Project? = locked {
        val current = synchronized(stateLock) { cache[projectId] } ?: return@locked null
        persist(transform(current).copy(id = projectId, updatedAt = clock()))
    }

    override suspend fun delete(projectId: String) {
        locked {
            if (isSafeId(projectId)) {
                withContext(ioDispatcher) {
                    val file = fileFor(projectId)
                    if (file.exists() && !file.delete()) throw IOException("Could not delete ${file.name}")
                }
            }
            synchronized(stateLock) {
                cache.remove(projectId)
                summaryCache.remove(projectId)
                flows[projectId]?.value = null
                publishSummariesLocked()
            }
        }
    }

    override suspend fun duplicate(projectId: String): Project? = locked {
        val source = synchronized(stateLock) { cache[projectId] } ?: return@locked null
        val now = clock()
        persist(source.copy(id = Ids.newId(), name = source.name + " (copy)", createdAt = now, updatedAt = now))
    }

    override suspend fun importJson(json: String): Project {
        val decoded = withContext(ioDispatcher) { codec.decode(json) }
        return locked {
            val taken = synchronized(stateLock) { decoded.id in cache }
            val id = if (!isSafeId(decoded.id) || taken) Ids.newId() else decoded.id
            persist(
                decoded.copy(
                    id = id,
                    name = decoded.name.trim().ifEmpty { DEFAULT_NAME },
                    settings = decoded.settings.sanitized(),
                    updatedAt = clock(),
                ),
            )
        }
    }

    override fun exportJson(project: Project): String = codec.encode(project)

    // ---------------------------------------------------------------------------------------------

    private suspend fun <T> locked(block: suspend () -> T): T {
        loaded.await()
        return writeMutex.withLock { block() }
    }

    /** Writes [project] atomically, then publishes it. Must be called with [writeMutex] held. */
    private suspend fun persist(project: Project): Project {
        withContext(ioDispatcher) { writeAtomically(fileFor(project.id), codec.encode(project)) }
        val summary = summarize(project)
        synchronized(stateLock) {
            cache[project.id] = project
            summaryCache[project.id] = summary
            flows[project.id]?.value = project
            publishSummariesLocked()
        }
        return project
    }

    private fun writeAtomically(target: File, text: String) {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create ${dir.path}")
        val tmp = File(dir, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun loadAll() {
        val loadedProjects = HashMap<String, Project>()
        val files = dir.listFiles().orEmpty()
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(TMP_SUFFIX)) {
                file.delete() // leftover of an interrupted write; the previous version is still intact
                continue
            }
            if (!file.name.endsWith(JSON_SUFFIX)) continue
            val id = file.name.removeSuffix(JSON_SUFFIX)
            if (!isSafeId(id)) continue
            val text = try {
                file.readText(Charsets.UTF_8)
            } catch (e: IOException) {
                log("Could not read ${file.name}; skipping it", e)
                continue
            }
            val project = try {
                codec.decode(text)
            } catch (e: Exception) {
                // IllegalArgumentException for malformed JSON; anything else is equally unrecoverable here.
                log("Project file ${file.name} is unreadable; moving it to ${corruptDir.name}/", e)
                quarantine(file)
                continue
            }
            // The file name is authoritative so ids stay unique even if a file was copied by hand.
            loadedProjects[id] = project.copy(id = id, settings = project.settings.sanitized())
        }
        finishLoad(loadedProjects)
    }

    private fun finishLoad(projects: Map<String, Project>) {
        val loadedSummaries = projects.mapValues { summarize(it.value) }
        synchronized(stateLock) {
            cache.putAll(projects)
            summaryCache.putAll(loadedSummaries)
            isLoadedFlag = true
            for ((id, flow) in flows) flow.value = cache[id]
            publishSummariesLocked()
        }
        loadedFlow.value = true
    }

    private fun quarantine(file: File) {
        try {
            if (!corruptDir.isDirectory) corruptDir.mkdirs()
            val dest = File(corruptDir, "${file.nameWithoutExtension}-${clock()}$JSON_SUFFIX")
            if (!file.renameTo(dest)) {
                log("Could not move ${file.name} aside; renaming in place", null)
                file.renameTo(File(dir, file.name + ".corrupt"))
            }
        } catch (e: SecurityException) {
            log("Could not quarantine ${file.name}", e)
        }
    }

    private fun summarize(p: Project): ProjectSummary {
        val area = try {
            areaOf(p).takeIf { it.isFinite() } ?: 0.0
        } catch (e: Exception) {
            log("Could not compute the area of project ${p.id}", e)
            0.0
        }
        return ProjectSummary(
            id = p.id,
            name = p.name,
            address = p.address,
            updatedAt = p.updatedAt,
            roomCount = p.floors.sumOf { it.rooms.size },
            floorCount = p.floors.size,
            totalArea = area,
        )
    }

    private fun publishSummariesLocked() {
        summariesFlow.value = summaryCache.values.sortedWith(
            compareByDescending<ProjectSummary> { it.updatedAt }.thenBy { it.name.lowercase() }.thenBy { it.id },
        )
    }

    private fun fileFor(id: String) = File(dir, id + JSON_SUFFIX)

    private companion object {
        const val TAG = "FileProjectRepository"
        const val JSON_SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"
        const val DEFAULT_NAME = "Untitled project"
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun isSafeId(id: String) = SAFE_ID.matches(id)
    }
}
