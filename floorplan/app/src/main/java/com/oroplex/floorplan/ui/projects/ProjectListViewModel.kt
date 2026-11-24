package com.oroplex.floorplan.ui.projects

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.core.io.Ids
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.data.FileProjectRepository
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.data.ProjectSummary
import com.oroplex.floorplan.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** One-off events for the projects screen. */
sealed interface ProjectListEvent {
    /** Open the editor of [projectId]; [issuedAt] (wall-clock ms) lets the screen drop requests that waited too long. */
    data class Open(val projectId: String, val issuedAt: Long = 0L) : ProjectListEvent {
        /** True when the request is older than [STALE_AFTER_MS] at [now]; requests without a time never are. */
        fun isStale(now: Long): Boolean = issuedAt > 0L && now - issuedAt > STALE_AFTER_MS

        companion object {
            /** Creating or importing a project takes milliseconds; anything this old waited while the screen was away. */
            const val STALE_AFTER_MS = 10_000L
        }
    }

    /** [undo] is the deleted project, offered for restoring from the snackbar. */
    data class Message(val text: String, val undo: Project? = null) : ProjectListEvent
}

class ProjectListViewModel(
    private val repository: ProjectRepository,
    private val settings: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val summaries: StateFlow<List<ProjectSummary>> = repository.summaries

    /** False until the repository has read the projects on disk (other implementations: always true). */
    val isLoaded: StateFlow<Boolean> = (repository as? FileProjectRepository)?.isLoaded ?: MutableStateFlow(true)

    private val _events = Channel<ProjectListEvent>(Channel.BUFFERED)
    val events: Flow<ProjectListEvent> = _events.receiveAsFlow()

    /** Live project, used by list cards to format areas in the project's own units. */
    fun observe(projectId: String): StateFlow<Project?> = repository.observe(projectId)

    /** Creates a project with the app-wide default settings and opens it. */
    fun create(name: String, address: String) = launchReporting("Couldn't create the project") {
        val created = repository.create(name, address)
        val defaults = settings.defaults.value
        repository.update(created.id) { it.copy(settings = defaults) }
        _events.send(ProjectListEvent.Open(created.id, issuedAt = clock()))
    }

    fun rename(projectId: String, name: String, address: String) = launchReporting("Couldn't rename the project") {
        repository.update(projectId) { it.copy(name = name.trim().ifEmpty { it.name }, address = address.trim()) }
    }

    fun duplicate(projectId: String) = launchReporting("Couldn't duplicate the project") {
        val copy = repository.duplicate(projectId)
        if (copy != null) message("Created ${copy.name}")
    }

    fun delete(projectId: String) = launchReporting("Couldn't delete the project") {
        val project = repository.get(projectId) ?: return@launchReporting
        repository.delete(projectId)
        _events.send(ProjectListEvent.Message("Deleted ${project.name}", undo = project))
    }

    /** Restores a project deleted a moment ago (snackbar "Undo"). */
    fun restore(project: Project) = launchReporting("Couldn't restore the project") {
        repository.save(project)
    }

    /** Adds the demo house (with a fresh id, so it can be opened any number of times) and opens it. */
    fun openSample() = launchReporting("Couldn't create the sample house") {
        val now = clock()
        val sample = SampleProjects.demo(now)
        val defaults = settings.defaults.value
        // Show the sample in the user's preferred units.
        val localized = sample.settings.copy(unitSystem = defaults.unitSystem, imperialDenominator = defaults.imperialDenominator)
        val saved = repository.save(sample.copy(id = Ids.newId(), createdAt = now, updatedAt = now, settings = localized))
        _events.send(ProjectListEvent.Open(saved.id, issuedAt = clock()))
    }

    /** Writes the project's JSON backup to a document picked with CreateDocument. */
    fun exportBackup(resolver: ContentResolver, uri: Uri, projectId: String) = launchReporting("Couldn't save the backup") {
        val project = repository.get(projectId)
        if (project == null) {
            message("That project no longer exists.")
            return@launchReporting
        }
        val json = repository.exportJson(project)
        withContext(Dispatchers.IO) {
            val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("The file could not be opened.")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        message("Backup of ${project.name} saved")
    }

    /** Imports a JSON backup picked with OpenDocument. */
    fun importBackup(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        val text = try {
            withContext(Dispatchers.IO) {
                val input = resolver.openInputStream(uri) ?: throw IOException("The file could not be opened.")
                input.use { readLimited(it, MAX_IMPORT_BYTES) }.toString(Charsets.UTF_8)
            }
        } catch (e: IOException) {
            message("Couldn't read the file: ${e.message ?: "unknown error"}")
            return@launch
        } catch (e: SecurityException) {
            message("Couldn't read the file: permission denied.")
            return@launch
        }
        try {
            val project = repository.importJson(text)
            message("Imported ${project.name}")
        } catch (e: IllegalArgumentException) {
            message("Couldn't import this file: ${e.message ?: "it is not a FloorPlan Scan backup."}")
        } catch (e: IOException) {
            message("Couldn't save the imported project: ${e.message ?: "unknown error"}")
        }
    }

    private fun launchReporting(failure: String, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: IOException) {
            message("$failure: ${e.message ?: "storage error"}")
        } catch (e: IllegalArgumentException) {
            message("$failure: ${e.message ?: "invalid data"}")
        } catch (e: SecurityException) {
            message("$failure: permission denied")
        }
    }

    private suspend fun message(text: String) = _events.send(ProjectListEvent.Message(text))

    class Factory(
        private val repository: ProjectRepository,
        private val settings: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ProjectListViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return ProjectListViewModel(repository, settings) as T
        }
    }

    companion object {
        /** Backups are a few hundred kB at most; anything far larger is not one of ours. */
        const val MAX_IMPORT_BYTES = 32L * 1024 * 1024

        internal fun readLimited(input: InputStream, limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("The file is too large to be a backup.")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}
