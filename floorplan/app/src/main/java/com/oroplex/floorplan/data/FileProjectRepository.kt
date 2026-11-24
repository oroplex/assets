package com.oroplex.floorplan.data

import com.oroplex.floorplan.core.model.Project
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Stores each project as filesDir/projects/<id>.json. */
class FileProjectRepository(filesDir: File) : ProjectRepository {
    private val dir = File(filesDir, "projects")

    override val summaries: StateFlow<List<ProjectSummary>> get() = TODO("implement")
    override fun observe(projectId: String): StateFlow<Project?> = TODO("implement")
    override suspend fun get(projectId: String): Project? = TODO("implement")
    override suspend fun create(name: String, address: String): Project = TODO("implement")
    override suspend fun save(project: Project): Project = TODO("implement")
    override suspend fun update(projectId: String, transform: (Project) -> Project): Project? = TODO("implement")
    override suspend fun delete(projectId: String) = TODO("implement")
    override suspend fun duplicate(projectId: String): Project? = TODO("implement")
    override suspend fun importJson(json: String): Project = TODO("implement")
    override fun exportJson(project: Project): String = TODO("implement")
}
