package com.oroplex.floorplan.data

import com.oroplex.floorplan.core.model.Project
import kotlinx.coroutines.flow.StateFlow

data class ProjectSummary(
    val id: String,
    val name: String,
    val address: String,
    val updatedAt: Long,
    val roomCount: Int,
    val floorCount: Int,
    /** Net interior area in square meters. */
    val totalArea: Double,
)

/**
 * Persistent project store. Thread-safe; all suspend functions may be called from the main thread
 * (implementations switch to Dispatchers.IO). Writes are atomic (write temp file + rename) so a crash
 * mid-save never corrupts a project.
 */
interface ProjectRepository {
    /** All projects, most recently updated first. Emits after every change. */
    val summaries: StateFlow<List<ProjectSummary>>

    /** Live view of one project (null while loading or if it does not exist / was deleted). */
    fun observe(projectId: String): StateFlow<Project?>

    suspend fun get(projectId: String): Project?

    /** Create and persist a new project with one empty floor named "Main Floor". */
    suspend fun create(name: String, address: String = ""): Project

    /** Persist [project] as-is, stamping updatedAt = now. */
    suspend fun save(project: Project): Project

    /** Atomically read-modify-write. Returns the saved project, or null if it does not exist. */
    suspend fun update(projectId: String, transform: (Project) -> Project): Project?

    suspend fun delete(projectId: String)

    /** Copy with a new id and " (copy)" name suffix. */
    suspend fun duplicate(projectId: String): Project?

    /** Import a JSON backup; assigns a new id if one with the same id already exists. */
    suspend fun importJson(json: String): Project

    fun exportJson(project: Project): String
}
