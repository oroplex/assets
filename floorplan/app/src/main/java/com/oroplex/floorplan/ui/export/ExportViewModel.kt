package com.oroplex.floorplan.ui.export

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.solve.FloorChecks
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.data.BrandingRepository
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
import com.oroplex.floorplan.export.SheetBranding
import com.oroplex.floorplan.export.SheetBrandings
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
import kotlinx.coroutines.flow.transformLatest
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
    val showOverallDimensions: Boolean? = null,
) {
    /** Resolves defaults against [project] (paper from its unit system, labels from its settings). */
    fun resolve(project: Project): ExportRequest = ExportRequest(
        format = format,
        floorId = floorId?.takeIf { id -> project.floors.any { it.id == id } },
        paper = paper ?: if (project.settings.unitSystem == UnitSystem.METRIC) PaperSize.A4 else PaperSize.LETTER,
        showWallLengths = showWallLengths ?: project.settings.showWallLengths,
        showAreas = showAreas ?: project.settings.showRoomAreas,
        includeRoomSchedule = includeRoomSchedule,
        showOverallDimensions = showOverallDimensions ?: project.settings.showOverallDimensions,
    )
}

data class FloorOption(val id: String, val name: String, val roomCount: Int)

/** Share or save: the two ways a file leaves the export screen. */
enum class ExportAction { SHARE, SAVE }

/** The "Export anyway?" question, open while the user decides. */
data class ExportConfirmation(val action: ExportAction, val errors: List<CheckItem>)

/** Progress of the pre-export checks for the current choice. */
sealed interface ChecksState {
    /** Nothing to check (JSON backup, project without floors). */
    data object NotApplicable : ChecksState
    data object Running : ChecksState
    data class Done(val report: PreExportReport) : ChecksState
}

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
        val checks: ChecksState = ChecksState.NotApplicable,
        /** Whether Share / Save must first ask "Export anyway?". */
        val gate: ExportGate = ExportGate.Clear,
        /** True once the user chose "Export anyway" for the current errors. */
        val errorsAcknowledged: Boolean = false,
        /** The open "Export anyway?" dialog, if any. */
        val confirmation: ExportConfirmation? = null,
        /** Suggest adding company branding (PDF / PNG without any branding set). */
        val showBrandingTip: Boolean = false,
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

    /** Open the system "save as" picker for [format] with [fileName] suggested. */
    data class PickSaveLocation(val format: ExportFormat, val fileName: String) : ExportEvent
}

/**
 * State and work for the export screen: loads the project, keeps the user's choices (surviving
 * process death through [SavedStateHandle]), runs the pre-export checks and renders the live preview
 * off the main thread, and runs one export at a time. The last export is reused when nothing changed
 * (Share then Save).
 *
 * Errors found by the checks gate Share / Save: [requestExport] opens an "Export anyway?" confirmation
 * until the user acknowledges exactly the current set of errors ([PreExportReport.errorKey]).
 *
 * @param checker the floor checks; [FloorChecks.check] in the app, a fake in tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModel(
    private val projectId: String,
    projects: ProjectRepository,
    branding: BrandingRepository,
    context: Context,
    private val saved: SavedStateHandle,
    private val checker: (Floor, PlanSettings) -> List<FloorIssue> = FloorChecks::check,
) : ViewModel() {

    private sealed interface Load {
        data object Loading : Load
        data class Ready(val project: Project) : Load
        data class Missing(val message: String) : Load
    }

    private sealed interface BrandingLoad {
        data object Loading : BrandingLoad
        data class Ready(val value: SheetBranding?) : BrandingLoad
    }

    /** What the checks depend on: the covered floors and the project settings. */
    private data class CheckInput(val floors: List<Floor>, val settings: PlanSettings)

    private class CachedExport(
        val project: Project,
        val request: ExportRequest,
        val branding: SheetBranding?,
        val exported: ExportedFile,
    )

    private val appContext = context.applicationContext
    private val exporter = ProjectExporter(appContext, projects::exportJson)
    private val dateText = FloorSheets.dateText(System.currentTimeMillis())

    private val load = MutableStateFlow<Load>(Load.Loading)
    private val selection = MutableStateFlow(restoreSelection())
    private val busy = MutableStateFlow(false)
    private val acknowledgedKey = MutableStateFlow(saved.get<String>(KEY_ACK))
    private val confirmation = MutableStateFlow<ExportAction?>(null)
    private var lastExport: CachedExport? = null

    private val eventChannel = Channel<ExportEvent>(Channel.BUFFERED)
    val events: Flow<ExportEvent> = eventChannel.receiveAsFlow()

    /** The company block decoded for printing (logo bitmap included), reloaded when branding changes. */
    private val sheetBranding: StateFlow<BrandingLoad> = branding.branding
        .mapLatest { b -> BrandingLoad.Ready(withContext(Dispatchers.IO) { SheetBrandings.load(b) }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrandingLoad.Loading)

    private val checks: StateFlow<ChecksState> =
        combine(load, selection) { l, s ->
            val project = (l as? Load.Ready)?.project ?: return@combine null
            val request = s.resolve(project)
            if (request.format == ExportFormat.JSON) return@combine null
            CheckInput(ProjectExporter.floorsFor(project, request), project.settings)
        }
            .distinctUntilChanged()
            .transformLatest { input ->
                if (input == null || input.floors.isEmpty()) {
                    emit(ChecksState.NotApplicable)
                } else {
                    val report = withContext(Dispatchers.Default) {
                        PreExportChecks.build(input.floors, input.settings, checker) { floor, e ->
                            Log.w(TAG, "Checks failed for floor ${floor.id}", e)
                        }
                    }
                    emit(ChecksState.Done(report))
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChecksState.Running)

    val state: StateFlow<ExportScreenState> =
        combine(
            combine(load, selection, busy) { l, s, b -> Triple(l, s, b) },
            checks,
            acknowledgedKey,
            confirmation,
            sheetBranding,
        ) { (l, s, b), c, ack, pending, brand -> screenState(l, s, b, c, ack, pending, brand) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ExportScreenState.Loading)

    val preview: StateFlow<ExportPreview> =
        combine(load, selection, sheetBranding) { l, s, b ->
            val project = (l as? Load.Ready)?.project
            val brand = b as? BrandingLoad.Ready
            if (project == null || brand == null) null else PreviewInput(project, s.resolve(project), brand.value)
        }
            .distinctUntilChanged()
            .mapLatest { input ->
                if (input == null) ExportPreview.Loading
                else withContext(Dispatchers.Default) { buildPreview(input.project, input.request, input.branding) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportPreview.Loading)

    private data class PreviewInput(val project: Project, val request: ExportRequest, val branding: SheetBranding?)

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

    fun setShowOverallDimensions(show: Boolean) = edit { it.copy(showOverallDimensions = show) }

    fun setIncludeRoomSchedule(include: Boolean) = edit { it.copy(includeRoomSchedule = include) }

    /**
     * Share or save the current choice. When the checks found errors the user has not acknowledged,
     * this opens the "Export anyway?" confirmation instead (see [confirmExportAnyway]).
     */
    fun requestExport(action: ExportAction) {
        val ready = state.value as? ExportScreenState.Ready ?: return
        if (ready.busy || ready.blockedReason != null) return
        if (ready.gate is ExportGate.NeedsConfirmation) {
            confirmation.value = action
            return
        }
        perform(action, ready)
    }

    /** The user chose "Export anyway": remember the errors they accepted and carry on with the action. */
    fun confirmExportAnyway() {
        val action = confirmation.value ?: return
        confirmation.value = null
        acknowledgeErrors()
        val ready = state.value as? ExportScreenState.Ready ?: return
        perform(action, ready)
    }

    fun dismissConfirmation() {
        confirmation.value = null
    }

    /** Accepts the current errors without exporting right away (the card's "Export anyway" button). */
    fun acknowledgeErrors() {
        val report = (checks.value as? ChecksState.Done)?.report ?: return
        if (!report.hasErrors) return
        acknowledgedKey.value = report.errorKey
        saved[KEY_ACK] = report.errorKey
    }

    private fun perform(action: ExportAction, ready: ExportScreenState.Ready) {
        when (action) {
            ExportAction.SHARE -> share()
            ExportAction.SAVE -> viewModelScope.launch {
                eventChannel.send(ExportEvent.PickSaveLocation(ready.request.format, ready.fileName))
            }
        }
    }

    /** Generates (or reuses) the file and asks the screen to open the share sheet. */
    private fun share() = runExport { exported, project ->
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
            var format: ExportFormat? = null
            try {
                // A save result can arrive right after process death, before the project is reloaded.
                val project = (load.first { it !is Load.Loading } as? Load.Ready)?.project
                if (project == null) {
                    eventChannel.send(ExportEvent.Message("This project is no longer available."))
                    return@launch
                }
                val brand = (sheetBranding.first { it is BrandingLoad.Ready } as BrandingLoad.Ready).value
                val request = selection.value.resolve(project)
                format = request.format
                val exported = lastExport
                    ?.takeIf { it.project == project && it.request == request && it.branding == brand && it.exported.file.isFile }
                    ?.exported
                    ?: exporter.export(project, request, brand).also { lastExport = CachedExport(project, request, brand, it) }
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
                eventChannel.send(ExportEvent.Message(outOfMemoryMessage(format)))
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                eventChannel.send(ExportEvent.Message("Export failed: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                busy.value = false
            }
        }
    }

    private fun screenState(
        l: Load,
        s: ExportSelection,
        isBusy: Boolean,
        checksState: ChecksState,
        ackKey: String?,
        pending: ExportAction?,
        brand: BrandingLoad,
    ): ExportScreenState = when (l) {
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
            val report = (checksState as? ChecksState.Done)?.report
            val gate = report?.let { PreExportChecks.gate(it, ackKey) } ?: ExportGate.Clear
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
                checks = checksState,
                gate = gate,
                errorsAcknowledged = report?.hasErrors == true && gate == ExportGate.Clear,
                confirmation = pending?.let { action ->
                    (gate as? ExportGate.NeedsConfirmation)?.let { ExportConfirmation(action, it.errors) }
                },
                showBrandingTip = brand is BrandingLoad.Ready && brand.value == null &&
                    (request.format == ExportFormat.PDF || request.format == ExportFormat.PNG),
            )
        }
    }

    /** Runs on Dispatchers.Default. Never throws: problems become a [ExportPreview.Message]. */
    private fun buildPreview(project: Project, request: ExportRequest, branding: SheetBranding?): ExportPreview {
        val floors = ProjectExporter.floorsFor(project, request)
        val floor = floors.firstOrNull() ?: return ExportPreview.Message("This project has no floors yet.")
        return try {
            when (request.format) {
                ExportFormat.PDF -> {
                    val sheet = FloorSheets.build(project, floor, request.labels, dateText, branding)
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
                    val sheet = FloorSheets.build(project, floor, request.labels, dateText, branding)
                    val (w, h) = PngExporter.sizeFor(sheet)
                    ExportPreview.Sheet(
                        sheet = sheet,
                        widthPt = PngExporter.SHEET_WIDTH_PT,
                        heightPt = h / PngExporter.PX_PER_PT,
                        caption = "${floor.name} · $w × $h px",
                    )
                }
                ExportFormat.SVG, ExportFormat.DXF -> {
                    // DXF has no overall dimension lines; the preview shows what the file contains.
                    val labels = if (request.format == ExportFormat.DXF) request.labels.copy(showOverallDimensions = false) else request.labels
                    val drawList = FloorSheets.renderFloor(project, floor, labels, 1.0)
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
        showOverallDimensions = saved.get<Boolean>(KEY_OVERALL),
    )

    private fun persist(s: ExportSelection) {
        saved[KEY_FORMAT] = s.format.name
        saved[KEY_FLOOR] = s.floorId
        saved[KEY_PAPER] = s.paper?.name
        saved[KEY_WALLS] = s.showWallLengths
        saved[KEY_AREAS] = s.showAreas
        saved[KEY_SCHEDULE] = s.includeRoomSchedule
        saved[KEY_OVERALL] = s.showOverallDimensions
    }

    companion object {
        private const val TAG = "ExportViewModel"
        private const val KEY_FORMAT = "export.format"
        private const val KEY_FLOOR = "export.floorId"
        private const val KEY_PAPER = "export.paper"
        private const val KEY_WALLS = "export.showWallLengths"
        private const val KEY_AREAS = "export.showAreas"
        private const val KEY_SCHEDULE = "export.includeSchedule"
        private const val KEY_OVERALL = "export.showOverallDimensions"
        private const val KEY_ACK = "export.acknowledgedErrors"

        /** What to tell the user when [format]'s export ran out of memory (null = unknown format). */
        fun outOfMemoryMessage(format: ExportFormat?): String = when (format) {
            ExportFormat.PDF -> "Not enough memory to create this PDF. Close other apps, or export one floor at a time."
            ExportFormat.PNG -> "Not enough memory to create the image. Close other apps, or export a PDF or SVG instead."
            else -> "Not enough memory for this export. Close other apps and try again."
        }
    }
}
