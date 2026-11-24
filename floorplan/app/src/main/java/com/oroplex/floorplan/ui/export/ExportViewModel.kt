package com.oroplex.floorplan.ui.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.data.ProjectRepository
import com.oroplex.floorplan.export.ExportException
import com.oroplex.floorplan.export.ExportFiles
import com.oroplex.floorplan.export.ExportFormat
import com.oroplex.floorplan.export.ExportRequest
import com.oroplex.floorplan.export.ExportedFile
import com.oroplex.floorplan.export.FloorSheet
import com.oroplex.floorplan.export.FloorSheets
import com.oroplex.floorplan.export.PaperSize
import com.oroplex.floorplan.export.PngExporter
import com.oroplex.floorplan.export.ProjectExporter
import com.oroplex.floorplan.export.SheetLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the user picked. Null option values mean "use the project's own default". */
data class ExportSelection(
    val format: ExportFormat = ExportFormat.PDF,
    /** Null = all floors. */
    val floorId: String? = null,
    val paper: PaperSize? = null,
    val showWallLengths: Boolean? = null,
    val showAreas: Boolean? = null,
    val includeRoomSchedule: Boolean = true,
) {
    /** Resolves defaults against [project] (paper from its unit system, labels from its settings). */
    fun resolve(project: Project): ExportRequest = ExportRequest(
        format = format,
        floorId = floorId?.takeIf { id -> project.floors.any { it.id == id } },
        paper = paper ?: if (project.settings.unitSystem == UnitSystem.METRIC) PaperSize.A4 else PaperSize.LETTER,
        showWallLengths = showWallLengths ?: project.settings.showWallLengths,
        showAreas = showAreas ?: project.settings.showRoomAreas,
        includeRoomSchedule = includeRoomSchedule,
    )
}

data class FloorOption(val id: String, val name: String, val roomCount: Int)

sealed interface ExportScreenState {
    data object Loading : ExportScreenState
    data class Missing(val message: String) : ExportScreenState
    data class Ready(
        val projectName: String,
        val floors: List<FloorOption>,
        val request: ExportRequest,
        /** Floors chips: null entry = "All floors" (only offered for multi-floor formats). */
        val floorChoices: List<FloorOption?>,
        /** The chip shown as selected (null = "All floors"). */
        val selectedFloorId: String?,
        val fileName: String,
        /** Why export is impossible with the current choices, or null when it can proceed. */
        val blockedReason: String?,
        /** Extra information about the current choice (e.g. which floor a single-file format uses). */
        val hint: String?,
        val busy: Boolean,
    ) : ExportScreenState
}

/** What the preview card shows. */
sealed interface ExportPreview {
    data object Loading : ExportPreview
    data class Message(val text: String) : ExportPreview

    /** A full sheet (PDF page 1 / PNG image) [widthPt] x [heightPt]. */
    data class Sheet(val sheet: FloorSheet, val widthPt: Double, val heightPt: Double, val caption: String) : ExportPreview

    /** Just the plan drawing (SVG / DXF). */
    data class Plan(val drawList: DrawList, val caption: String) : ExportPreview
}

sealed interface ExportEvent {
    data class Share(val exported: ExportedFile, val subject: String) : ExportEvent
    data class Message(val text: String) : ExportEvent
}

/**
 * State and work for the export screen: loads the project, keeps the user's choices (surviving
 * process death through [SavedStateHandle]), renders the live preview off the main thread, and runs
 * one export at a time. The last export is reused when nothing changed (Share then Save).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModel(
    private val projectId: String,
    projects: ProjectRepository,
    context: Context,
    private val saved: SavedStateHandle,
) : ViewModel() {

    private sealed interface Load {
        data object Loading : Load
        data class Ready(val project: Project) : Load
        data class Missing(val message: String) : Load
    }

    private class CachedExport(val project: Project, val request: ExportRequest, val exported: ExportedFile)

    private val appContext = context.applicationContext
    private val exporter = ProjectExporter(appContext, projects::exportJson)
    private val dateText = FloorSheets.dateText(System.currentTimeMillis())

    private val load = MutableStateFlow<Load>(Load.Loading)
    private val selection = MutableStateFlow(restoreSelection())
    private val busy = MutableStateFlow(false)
    private var lastExport: CachedExport? = null

    private val eventChannel = Channel<ExportEvent>(Channel.BUFFERED)
    val events: Flow<ExportEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<ExportScreenState> =
        combine(load, selection, busy) { l, s, b -> screenState(l, s, b) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ExportScreenState.Loading)

    val preview: StateFlow<ExportPreview> =
        combine(load, selection) { l, s -> (l as? Load.Ready)?.project?.let { it to s.resolve(it) } }
            .distinctUntilChanged()
            .mapLatest { input ->
                if (input == null) ExportPreview.Loading
                else withContext(Dispatchers.Default) { buildPreview(input.first, input.second) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportPreview.Loading)

    init {
        viewModelScope.launch {
            val initial = try {
                projects.get(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Loading project $projectId failed", e)
                load.value = Load.Missing("This project couldn't be opened.")
                return@launch
            }
            if (initial == null) {
                load.value = Load.Missing("This project no longer exists.")
                return@launch
            }
            load.value = Load.Ready(initial)
            // observe() starts with null while the repository loads; only a null after a real value
            // means the project was deleted while this screen was open.
            var seen = false
            projects.observe(projectId).collect { p ->
                if (p != null) {
                    seen = true
                    load.value = Load.Ready(p)
                } else if (seen) {
                    load.value = Load.Missing("This project was deleted.")
                }
            }
        }
    }

    fun selectFormat(format: ExportFormat) = edit { it.copy(format = format) }

    fun selectFloor(floorId: String?) = edit { it.copy(floorId = floorId) }

    fun selectPaper(paper: PaperSize) = edit { it.copy(paper = paper) }

    fun setShowWallLengths(show: Boolean) = edit { it.copy(showWallLengths = show) }

    fun setShowAreas(show: Boolean) = edit { it.copy(showAreas = show) }

    fun setIncludeRoomSchedule(include: Boolean) = edit { it.copy(includeRoomSchedule = include) }

    /** Generates (or reuses) the file and asks the screen to open the share sheet. */
    fun share() = runExport { exported, project ->
        eventChannel.send(ExportEvent.Share(exported, subject = "${project.name.ifBlank { "Floor plan" }} — floor plan"))
    }

    /** Generates (or reuses) the file and copies it into the document the user created. */
    fun saveTo(destination: Uri) = runExport { exported, _ ->
        try {
            withContext(Dispatchers.IO) { ExportFiles.copyToDocument(appContext, exported.file, destination) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException, SecurityException (grant revoked), IllegalArgumentException (bad provider).
            throw ExportException("Couldn't save to the selected location. Try another folder.", e)
        }
        eventChannel.send(ExportEvent.Message("Saved ${exported.file.name}"))
    }

    private fun runExport(action: suspend (ExportedFile, Project) -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                // A save result can arrive right after process death, before the project is reloaded.
                val project = (load.first { it !is Load.Loading } as? Load.Ready)?.project
                if (project == null) {
                    eventChannel.send(ExportEvent.Message("This project is no longer available."))
                    return@launch
                }
                val request = selection.value.resolve(project)
                val exported = lastExport
                    ?.takeIf { it.project == project && it.request == request && it.exported.file.isFile }
                    ?.exported
                    ?: exporter.export(project, request).also { lastExport = CachedExport(project, request, it) }
                action(exported, project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                Log.w(TAG, "Export failed: ${e.message}", e.cause)
                eventChannel.send(ExportEvent.Message(e.message ?: "Export failed."))
            } catch (e: IOException) {
                Log.e(TAG, "Export I/O failure", e)
                eventChannel.send(ExportEvent.Message("Couldn't write the file. Check that storage isn't full and try again."))
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Export ran out of memory", e)
                eventChannel.send(ExportEvent.Message("Not enough memory for this export. Try a PDF or SVG instead."))
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                eventChannel.send(ExportEvent.Message("Export failed: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                busy.value = false
            }
        }
    }

    private fun screenState(l: Load, s: ExportSelection, isBusy: Boolean): ExportScreenState = when (l) {
        Load.Loading -> ExportScreenState.Loading
        is Load.Missing -> ExportScreenState.Missing(l.message)
        is Load.Ready -> {
            val project = l.project
            val request = s.resolve(project)
            val floors = project.floors.map { FloorOption(it.id, it.name.ifBlank { "Floor" }, it.rooms.size) }
            val covered = ProjectExporter.floorsFor(project, request)
            val multi = request.format.supportsAllFloors
            val blocked = when {
                request.format == ExportFormat.JSON -> null
                project.floors.isEmpty() -> "This project has no floors yet."
                request.format.isDrawing && covered.firstOrNull()?.rooms.isNullOrEmpty() ->
                    covered.firstOrNull()?.let { ProjectExporter.emptyFloorMessage(it) }
                else -> null
            }
            val hint = when {
                request.format == ExportFormat.JSON -> "The backup always contains the whole project (all floors)."
                !multi && s.floorId == null && project.floors.size > 1 ->
                    "${request.format.name} holds one floor — exporting “${covered.firstOrNull()?.name.orEmpty()}”. " +
                        "Pick another floor above."
                else -> null
            }
            val offerAllFloors = multi && floors.size > 1
            ExportScreenState.Ready(
                projectName = project.name,
                floors = floors,
                request = request,
                floorChoices = if (offerAllFloors) listOf<FloorOption?>(null) + floors else floors,
                selectedFloorId = if (offerAllFloors) request.floorId else covered.firstOrNull()?.id,
                fileName = ProjectExporter.fileName(project, request),
                blockedReason = blocked,
                hint = hint,
                busy = isBusy,
            )
        }
    }

    /** Runs on Dispatchers.Default. Never throws: problems become a [ExportPreview.Message]. */
    private fun buildPreview(project: Project, request: ExportRequest): ExportPreview {
        val floors = ProjectExporter.floorsFor(project, request)
        val floor = floors.firstOrNull() ?: return ExportPreview.Message("This project has no floors yet.")
        return try {
            when (request.format) {
                ExportFormat.PDF -> {
                    val sheet = FloorSheets.build(project, floor, request.showWallLengths, request.showAreas, dateText)
                        .copy(showScaleRatio = true)
                    val landscape = SheetLayout.isLandscape(sheet.drawList?.bounds)
                    val pages = if (floors.size == 1) "1 plan page" else "${floors.size} plan pages"
                    val schedule = if (request.includeRoomSchedule) " + room schedule" else ""
                    ExportPreview.Sheet(
                        sheet = sheet,
                        widthPt = request.paper.widthPt(landscape).toDouble(),
                        heightPt = request.paper.heightPt(landscape).toDouble(),
                        caption = "${request.paper.label} ${if (landscape) "landscape" else "portrait"} · $pages$schedule",
                    )
                }
                ExportFormat.PNG -> {
                    val sheet = FloorSheets.build(project, floor, request.showWallLengths, request.showAreas, dateText)
                    val (w, h) = PngExporter.sizeFor(sheet)
                    ExportPreview.Sheet(
                        sheet = sheet,
                        widthPt = PngExporter.SHEET_WIDTH_PT,
                        heightPt = h / PngExporter.PX_PER_PT,
                        caption = "${floor.name} · $w × $h px",
                    )
                }
                ExportFormat.SVG, ExportFormat.DXF -> {
                    val drawList = FloorSheets.renderFloor(project, floor, request.showWallLengths, request.showAreas, 1.0)
                        ?: return ExportPreview.Message(FloorSheets.EMPTY_FLOOR_MESSAGE)
                    val caption = if (request.format == ExportFormat.DXF) {
                        val units = if (project.settings.unitSystem == UnitSystem.IMPERIAL) "inches" else "millimeters"
                        "${floor.name} · drawing units: $units"
                    } else {
                        "${floor.name} · vector drawing"
                    }
                    ExportPreview.Plan(drawList, caption)
                }
                ExportFormat.JSON -> {
                    val rooms = project.floors.sumOf { it.rooms.size }
                    val floorsText = if (project.floors.size == 1) "1 floor" else "${project.floors.size} floors"
                    ExportPreview.Message("Project backup · $floorsText · ${FloorSheets.roomCountText(rooms)}")
                }
            }
        } catch (e: ExportException) {
            ExportPreview.Message(e.message ?: "Preview unavailable.")
        } catch (e: Exception) {
            Log.w(TAG, "Preview failed", e)
            ExportPreview.Message("Preview unavailable.")
        }
    }

    private fun edit(transform: (ExportSelection) -> ExportSelection) {
        selection.update(transform)
        persist(selection.value)
    }

    private fun restoreSelection(): ExportSelection = ExportSelection(
        format = saved.get<String>(KEY_FORMAT)?.let { name -> ExportFormat.entries.firstOrNull { it.name == name } }
            ?: ExportFormat.PDF,
        floorId = saved.get<String>(KEY_FLOOR),
        paper = saved.get<String>(KEY_PAPER)?.let { name -> PaperSize.entries.firstOrNull { it.name == name } },
        showWallLengths = saved.get<Boolean>(KEY_WALLS),
        showAreas = saved.get<Boolean>(KEY_AREAS),
        includeRoomSchedule = saved.get<Boolean>(KEY_SCHEDULE) ?: true,
    )

    private fun persist(s: ExportSelection) {
        saved[KEY_FORMAT] = s.format.name
        saved[KEY_FLOOR] = s.floorId
        saved[KEY_PAPER] = s.paper?.name
        saved[KEY_WALLS] = s.showWallLengths
        saved[KEY_AREAS] = s.showAreas
        saved[KEY_SCHEDULE] = s.includeRoomSchedule
    }

    private companion object {
        const val TAG = "ExportViewModel"
        const val KEY_FORMAT = "export.format"
        const val KEY_FLOOR = "export.floorId"
        const val KEY_PAPER = "export.paper"
        const val KEY_WALLS = "export.showWallLengths"
        const val KEY_AREAS = "export.showAreas"
        const val KEY_SCHEDULE = "export.includeSchedule"
    }
}
