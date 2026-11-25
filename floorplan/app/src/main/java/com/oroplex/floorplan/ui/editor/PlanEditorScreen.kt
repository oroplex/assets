@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SquareFoot
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oroplex.floorplan.core.measure.AreaSummary
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.data.appContainer
import com.oroplex.floorplan.ui.common.ConfirmDialog
import com.oroplex.floorplan.ui.common.ProjectDetailsDialog
import com.oroplex.floorplan.ui.common.TextInputDialog
import com.oroplex.floorplan.ui.common.formatArea
import kotlinx.coroutines.launch

@Composable
fun PlanEditorScreen(
    projectId: String,
    onScanRoom: (projectId: String, floorId: String) -> Unit,
    onExport: (projectId: String) -> Unit,
    onBack: () -> Unit,
    /** Open the 3D dollhouse view of a floor (route view3d/{projectId}/{floorId}). */
    onOpen3d: (projectId: String, floorId: String) -> Unit = { _, _ -> },
    /**
     * A room to select and zoom to (e.g. tapped in the export pre-flight list); switches floors as needed (a
     * floor never shown before fits first, then focuses the room). [onFocusRoomConsumed] is called once the
     * request was handled, so it is not repeated.
     */
    focusRoomId: String? = null,
    onFocusRoomConsumed: () -> Unit = {},
) {
    val container = appContainer()
    val viewModel: PlanEditorViewModel = viewModel(
        key = "plan-editor:$projectId",
        factory = PlanEditorViewModel.Factory(container.projects, projectId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var showFloorCheck by remember { mutableStateOf(false) }
    var showRooms by remember { mutableStateOf(false) }
    // The overlap set the user hid the banner for; it comes back when the overlaps change.
    var dismissedOverlaps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Each snackbar runs in its own coroutine so the collector never waits for one to time out, and a
            // newer message replaces the one on screen instead of queueing behind it.
            snackbar.currentSnackbarData?.dismiss()
            launch {
                val undo = message.undoRevision
                val result = snackbar.showSnackbar(
                    message = message.text,
                    actionLabel = if (undo != null) "Undo" else null,
                    duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed && undo != null) viewModel.undoRevision(undo)
            }
        }
    }
    BackHandler(enabled = state.selection != null) { viewModel.clearSelection() }

    // A room another screen asked to show: handled once the project is loaded, then consumed.
    val consumeFocus by rememberUpdatedState(onFocusRoomConsumed)
    val ready = state.status != EditorStatus.LOADING
    LaunchedEffect(focusRoomId, ready) {
        val id = focusRoomId ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        viewModel.showRequestedRoom(id)
        consumeFocus()
    }

    val project = state.project
    val floor = state.floor
    val settings = state.settings
    val open3d: (() -> Unit)? = floor?.takeIf { it.rooms.isNotEmpty() }?.let { f -> { onOpen3d(projectId, f.id) } }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = project?.name ?: "",
                subtitle = floor?.let { floorSummary(it, settings) },
                ready = state.status == EditorStatus.READY,
                canUndo = state.canUndo,
                canRedo = state.canRedo,
                onBack = onBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onExport = { onExport(projectId) },
                onOpen3d = open3d,
                onProjectSettings = { dialog = EditorDialog.ProjectSettings },
                onRenameProject = { dialog = EditorDialog.RenameProject },
            )
        },
        bottomBar = {
            SelectionPanel(state, viewModel, openDialog = { dialog = it })
        },
        floatingActionButton = {
            if (state.status == EditorStatus.READY && floor != null && state.selection == null) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { dialog = EditorDialog.DrawRoom },
                        icon = { Icon(Icons.Filled.Draw, contentDescription = null) },
                        text = { Text("Draw room") },
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                    ExtendedFloatingActionButton(
                        onClick = { onScanRoom(projectId, floor.id) },
                        icon = { Icon(Icons.Filled.ViewInAr, contentDescription = null) },
                        text = { Text("Scan room") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.status) {
                EditorStatus.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                EditorStatus.MISSING -> MissingProject(onBack, Modifier.align(Alignment.Center))
                EditorStatus.READY -> if (project != null) {
                    Column(Modifier.fillMaxSize()) {
                        FloorTabs(
                            floors = project.floors,
                            selectedFloorId = floor?.id,
                            hasRooms = floor?.rooms?.isNotEmpty() == true,
                            attentionCount = state.insights.attentionCount,
                            onSelect = viewModel::selectFloor,
                            onAdd = { dialog = EditorDialog.AddFloor },
                            onCheck = { showFloorCheck = true },
                            onRooms = { showRooms = true },
                            onMove = viewModel::moveFloor,
                            onStraighten = viewModel::straightenFloor,
                            onRotate = { viewModel.rotateFloor(clockwise = true) },
                            onRename = { id -> dialog = EditorDialog.RenameFloor(id) },
                            onDelete = { id -> dialog = EditorDialog.DeleteFloor(id) },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (floor != null) {
                                PlanCanvas(
                                    viewModel = viewModel,
                                    floorId = floor.id,
                                    rooms = state.displayRooms,
                                    settings = settings,
                                    selection = state.selection,
                                    moveMode = state.moveMode,
                                    highlightRoomIds = state.highlightRoomIds,
                                    guides = state.dragGuides,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (floor.rooms.isEmpty()) {
                                    EmptyFloor(Modifier.align(Alignment.Center).padding(24.dp))
                                }
                                Column(
                                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val overlaps = state.insights.overlaps
                                    AnimatedVisibility(
                                        visible = overlaps.isNotEmpty() && overlaps != dismissedOverlaps && state.previewRooms.isEmpty(),
                                        enter = fadeIn() + slideInVertically(),
                                        exit = fadeOut() + slideOutVertically(),
                                    ) {
                                        OverlapBanner(
                                            floor = floor,
                                            overlaps = overlaps,
                                            onShow = { viewModel.focusRoom(it) },
                                            onDetails = { showFloorCheck = true },
                                            onDismiss = { dismissedOverlaps = overlaps },
                                        )
                                    }
                                    if (floor.rooms.isNotEmpty()) {
                                        CanvasControls(
                                            onFit = { viewModel.requestFit(floor.id) },
                                            on3d = open3d,
                                            modifier = Modifier.align(Alignment.End),
                                        )
                                    }
                                }
                            } else {
                                NoFloors(onAdd = { dialog = EditorDialog.AddFloor }, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFloorCheck) {
        val checked = floor
        if (checked == null) {
            LaunchedEffect(Unit) { showFloorCheck = false }
        } else {
            FloorCheckSheet(
                floor = checked,
                issues = state.insights.issues,
                onSelectRoom = { viewModel.focusRoom(it) },
                onDismiss = { showFloorCheck = false },
                onAction = viewModel::runIssueAction,
            )
        }
    }

    if (showRooms) {
        val shown = floor
        if (shown == null) {
            LaunchedEffect(Unit) { showRooms = false }
        } else {
            RoomsSheet(
                floor = shown,
                settings = settings,
                selectedRoomId = state.selection?.roomId,
                onSelectRoom = { viewModel.focusRoom(it) },
                onDismiss = { showRooms = false },
            )
        }
    }

    EditorDialogHost(
        dialog = dialog,
        state = state,
        viewModel = viewModel,
        onDismiss = { dialog = null },
    )
}

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String?,
    ready: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onOpen3d: (() -> Unit)?,
    onProjectSettings: () -> Unit,
    onRenameProject: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = {
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
            IconButton(onClick = onExport, enabled = ready) { Icon(Icons.Filled.IosShare, contentDescription = "Export") }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = ready) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("3D view", Icons.Filled._3dRotation, enabled = onOpen3d != null) {
                        menuOpen = false
                        onOpen3d?.invoke()
                    }
                    MenuItem("Project settings", Icons.Outlined.Settings) {
                        menuOpen = false
                        onProjectSettings()
                    }
                    MenuItem("Rename project", Icons.Outlined.DriveFileRenameOutline) {
                        menuOpen = false
                        onRenameProject()
                    }
                }
            }
        },
    )
}

@Composable
private fun FloorTabs(
    floors: List<Floor>,
    selectedFloorId: String?,
    hasRooms: Boolean,
    attentionCount: Int,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onCheck: () -> Unit,
    onRooms: () -> Unit,
    onMove: (floorId: String, delta: Int) -> Unit,
    onStraighten: () -> Unit,
    onRotate: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selectedIndex = floors.indexOfFirst { it.id == selectedFloorId }.coerceAtLeast(0)
    Surface(tonalElevation = 1.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (floors.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        edgePadding = 8.dp,
                        divider = {},
                        modifier = Modifier.weight(1f),
                    ) {
                        floors.forEach { floor ->
                            Tab(
                                selected = floor.id == selectedFloorId,
                                onClick = { onSelect(floor.id) },
                                text = { Text(floor.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                } else {
                    Box(Modifier.weight(1f))
                }
                val checkDescription = when (attentionCount) {
                    0 -> "Floor check"
                    1 -> "Floor check, 1 item to review"
                    else -> "Floor check, $attentionCount items to review"
                }
                IconButton(
                    onClick = onCheck,
                    enabled = selectedFloorId != null,
                    modifier = Modifier.semantics { contentDescription = checkDescription },
                ) {
                    BadgedBox(badge = { if (attentionCount > 0) Badge { Text(if (attentionCount > 9) "9+" else "$attentionCount") } }) {
                        Icon(Icons.AutoMirrored.Outlined.FactCheck, contentDescription = null)
                    }
                }
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add floor") }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = selectedFloorId != null) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Floor options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuItem("Rooms", Icons.AutoMirrored.Outlined.List, enabled = hasRooms) {
                            menuOpen = false
                            onRooms()
                        }
                        MenuItem("Floor check", Icons.AutoMirrored.Outlined.FactCheck) {
                            menuOpen = false
                            onCheck()
                        }
                        MenuItem("Straighten floor", Icons.Outlined.CropRotate, enabled = hasRooms) {
                            menuOpen = false
                            onStraighten()
                        }
                        MenuItem("Rotate floor 90°", Icons.Outlined.Rotate90DegreesCw, enabled = hasRooms) {
                            menuOpen = false
                            onRotate()
                        }
                        MenuItem("Rename floor", Icons.Outlined.DriveFileRenameOutline) {
                            menuOpen = false
                            selectedFloorId?.let(onRename)
                        }
                        if (floors.size > 1) {
                            MenuItem("Move left", Icons.AutoMirrored.Outlined.ArrowBack, enabled = selectedIndex > 0) {
                                menuOpen = false
                                selectedFloorId?.let { onMove(it, -1) }
                            }
                            MenuItem("Move right", Icons.AutoMirrored.Outlined.ArrowForward, enabled = selectedIndex < floors.lastIndex) {
                                menuOpen = false
                                selectedFloorId?.let { onMove(it, 1) }
                            }
                        }
                        HorizontalDivider()
                        MenuItem("Delete floor", Icons.Outlined.Delete, enabled = floors.size > 1, destructive = true) {
                            menuOpen = false
                            selectedFloorId?.let(onDelete)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive && enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = if (enabled) color else color.copy(alpha = 0.38f)) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = if (enabled) color else color.copy(alpha = 0.38f)) },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Bottom panel for the current selection (nothing when no room is selected). */
@Composable
private fun SelectionPanel(state: EditorUiState, viewModel: PlanEditorViewModel, openDialog: (EditorDialog) -> Unit) {
    val selection = state.selection ?: return
    val room = state.selectedRoom ?: return
    val settings = state.settings
    EditorPanelSurface {
        when (selection) {
            is Selection.Room -> {
                // Graded from the committed room, so a drag does not re-grade it on every frame.
                val committed = state.floor?.rooms?.firstOrNull { it.id == room.id } ?: room
                val quality = remember(committed, settings.unitSystem) { viewModel.quality(committed) }
                val scanSize = state.scanGroupIds.size
                RoomPanel(
                    room = room,
                    settings = settings,
                    moveMode = state.moveMode,
                    quality = quality,
                    snapTarget = state.dragGuides?.snapTargetName,
                    wholeScan = state.wholeScan,
                    scanSize = scanSize,
                    canSquare = (state.floor?.rooms?.size ?: 0) > (if (state.wholeScan) scanSize else 1),
                    actions = RoomPanelActions(
                        onEditDetails = { openDialog(EditorDialog.RoomDetails(room.id)) },
                        onEditCeiling = { openDialog(EditorDialog.CeilingHeight(room.id)) },
                        onOpenOpening = { openDialog(EditorDialog.EditOpening(room.id, it.id)) },
                        onToggleMove = viewModel::toggleMoveMode,
                        onSnap = { viewModel.snapToNeighbour(room.id) },
                        onRotate = { viewModel.rotateRoom(room.id, it) },
                        onResolve = { openDialog(EditorDialog.Resolve(room.id)) },
                        onDelete = { openDialog(EditorDialog.DeleteRoom(room.id)) },
                        onClose = viewModel::clearSelection,
                        onWholeScan = viewModel::setWholeScan,
                        onSquare = { viewModel.squareToFloor(room.id) },
                    ),
                )
            }
            is Selection.Wall -> WallPanel(
                room = room,
                wallIndex = selection.wallIndex,
                settings = settings,
                calibration = state.floor?.calibration,
                actions = WallPanelActions(
                    onSetLength = { openDialog(EditorDialog.SetWallLength(room.id, selection.wallIndex)) },
                    onAddOpening = { type ->
                        viewModel.addOpening(room.id, selection.wallIndex, type)?.let {
                            openDialog(EditorDialog.EditOpening(room.id, it.id))
                        }
                    },
                    onOpenOpening = { openDialog(EditorDialog.EditOpening(room.id, it.id)) },
                    onInsertCorner = { viewModel.insertCorner(room.id, selection.wallIndex) },
                    onCalibrate = { openDialog(EditorDialog.Calibrate(room.id, selection.wallIndex)) },
                    onBackToRoom = { viewModel.select(Selection.Room(room.id)) },
                    onClose = viewModel::clearSelection,
                ),
            )
            is Selection.Corner -> CornerPanel(
                room = room,
                cornerIndex = selection.cornerIndex,
                onDelete = { viewModel.deleteCorner(room.id, selection.cornerIndex) },
                onBackToRoom = { viewModel.select(Selection.Room(room.id)) },
                onClose = viewModel::clearSelection,
            )
        }
    }
}

@Composable
private fun EditorDialogHost(
    dialog: EditorDialog?,
    state: EditorUiState,
    viewModel: PlanEditorViewModel,
    onDismiss: () -> Unit,
) {
    if (dialog == null) return
    val project = state.project
    val settings = state.settings
    fun room(id: String) = project?.floors?.firstNotNullOfOrNull { f -> f.rooms.firstOrNull { it.id == id } }

    // A dialog whose subject disappeared (undo, deletion elsewhere) simply closes.
    var shown = false
    when (dialog) {
        EditorDialog.ProjectSettings -> if (project != null) {
            shown = true
            ProjectSettingsDialog(
                settings = settings,
                estimatedCeilingRooms = estimatedCeilingRooms(project, settings.defaultCeilingHeight),
                onConfirm = { newSettings, apply -> viewModel.updateSettings(newSettings, applyCeilingToEstimated = apply) },
                onDismiss = onDismiss,
            )
        }
        EditorDialog.RenameProject -> if (project != null) {
            shown = true
            ProjectDetailsDialog(
                title = "Rename project",
                initialName = project.name,
                initialAddress = project.address,
                confirmLabel = "Save",
                onConfirm = viewModel::renameProject,
                onDismiss = onDismiss,
            )
        }
        EditorDialog.AddFloor -> if (project != null) {
            shown = true
            TextInputDialog(
                title = "Add floor",
                label = "Floor name",
                initialValue = defaultFloorName(project.floors.size),
                confirmLabel = "Add",
                onConfirm = viewModel::addFloor,
                onDismiss = onDismiss,
            )
        }
        is EditorDialog.RenameFloor -> project?.floors?.firstOrNull { it.id == dialog.floorId }?.let { floor ->
            shown = true
            TextInputDialog(
                title = "Rename floor",
                label = "Floor name",
                initialValue = floor.name,
                confirmLabel = "Save",
                onConfirm = { viewModel.renameFloor(floor.id, it) },
                onDismiss = onDismiss,
            )
        }
        is EditorDialog.DeleteFloor -> project?.floors?.firstOrNull { it.id == dialog.floorId }?.let { floor ->
            shown = true
            val rooms = floor.rooms.size
            ConfirmDialog(
                title = "Delete ${floor.name}?",
                message = if (rooms == 0) "This floor is empty." else "Its $rooms ${if (rooms == 1) "room" else "rooms"} will be deleted too. You can undo this.",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = { viewModel.deleteFloor(floor.id) },
                onDismiss = onDismiss,
            )
        }
        EditorDialog.DrawRoom -> if (state.floor != null) {
            shown = true
            DrawRoomDialog(settings, onConfirm = viewModel::drawRoom, onDismiss = onDismiss)
        }
        is EditorDialog.RoomDetails -> room(dialog.roomId)?.let { room ->
            shown = true
            val floorRooms = project?.floors?.firstOrNull { f -> f.rooms.any { it.id == room.id } }?.rooms.orEmpty()
            RoomDetailsDialog(
                room = room,
                floorRooms = floorRooms,
                onConfirm = { name, type, category -> viewModel.updateRoomDetails(room.id, name, type, category) },
                onDismiss = onDismiss,
            )
        }
        is EditorDialog.CeilingHeight -> room(dialog.roomId)?.let { room ->
            shown = true
            CeilingHeightDialog(room, settings, onConfirm = { viewModel.setCeilingHeight(room.id, it) }, onDismiss = onDismiss)
        }
        is EditorDialog.DeleteRoom -> room(dialog.roomId)?.let { room ->
            shown = true
            ConfirmDialog(
                title = "Delete ${room.name}?",
                message = "The room and its doors and windows are removed from the plan. You can undo this.",
                confirmLabel = "Delete",
                destructive = true,
                onConfirm = { viewModel.deleteRoom(room.id) },
                onDismiss = onDismiss,
            )
        }
        is EditorDialog.Resolve -> room(dialog.roomId)?.let { room ->
            shown = true
            ResolveDialog(room, settings, onConfirm = { viewModel.resolveRoom(room.id, it) }, onDismiss = onDismiss)
        }
        is EditorDialog.EditOpening -> room(dialog.roomId)?.let { room ->
            room.openings.firstOrNull { it.id == dialog.openingId }?.let { opening ->
                shown = true
                OpeningEditorDialog(
                    room = room,
                    opening = opening,
                    settings = settings,
                    onSave = { viewModel.saveOpening(room.id, it) },
                    onDelete = { viewModel.deleteOpening(room.id, opening.id) },
                    onDismiss = onDismiss,
                )
            }
        }
        is EditorDialog.SetWallLength -> room(dialog.roomId)?.takeIf { dialog.wallIndex in it.corners.indices }?.let { room ->
            shown = true
            SetWallLengthDialog(
                room = room,
                wallIndex = dialog.wallIndex,
                settings = settings,
                onApply = { viewModel.setWallLength(room.id, dialog.wallIndex, it) },
                onDismiss = onDismiss,
            )
        }
        is EditorDialog.Calibrate -> room(dialog.roomId)?.takeIf { dialog.wallIndex in it.corners.indices }?.let { room ->
            val floor = project?.floors?.firstOrNull { f -> f.rooms.any { it.id == room.id } }
            shown = true
            CalibrateDialog(
                room = room,
                wallIndex = dialog.wallIndex,
                scannedRooms = floor?.rooms?.count { it.captureMethod != CaptureMethod.MANUAL } ?: 1,
                drawnRooms = floor?.rooms?.count { it.captureMethod == CaptureMethod.MANUAL } ?: 0,
                settings = settings,
                previous = floor?.calibration,
                onApply = { viewModel.calibrate(room.id, dialog.wallIndex, it) },
                onDismiss = onDismiss,
            )
        }
    }
    if (!shown) LaunchedEffect(dialog) { onDismiss() }
}

/**
 * Guidance on an empty floor. It sits on its own themed card: the plan paper behind it is always white, so
 * theme text colours (light in dark mode) would be unreadable directly on it.
 */
@Composable
private fun EmptyFloor(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
        modifier = modifier.widthIn(max = 480.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.SquareFoot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Text(
                "No rooms on this floor yet",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Tap Scan room to measure a room with your camera, or Draw room to type in its size. " +
                    "Scan the whole floor in one go and the rooms line up by themselves.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NoFloors(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This project has no floors yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "Add a floor to start scanning or drawing rooms.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAdd) { Text("Add a floor") }
    }
}

@Composable
private fun MissingProject(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This project is no longer available.", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "It may have been deleted, or its file could not be read.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onBack) { Text("Back to projects") }
    }
}

/**
 * Top bar subtitle: "Main Floor · 5 rooms · 1,450 sq ft", or "· Living area 1,450 sq ft" when some rooms do not
 * count as living area (garage, porch...), so the headline figure never includes them.
 */
internal fun floorSummary(floor: Floor, settings: PlanSettings): String {
    val rooms = floor.rooms.size
    val roomText = "$rooms ${if (rooms == 1) "room" else "rooms"}"
    if (rooms == 0) return "${floor.name} · no rooms"
    val summary = try {
        Measurements.areaSummary(floor.rooms)
    } catch (e: Exception) {
        return "${floor.name} · $roomText"
    }
    return "${floor.name} · $roomText · ${areaHeadline(summary, settings)}"
}

/** The headline area: the plain net total when every room is living space, else "Living area …". */
internal fun areaHeadline(summary: AreaSummary, settings: PlanSettings): String {
    val others = summary.nonLiving + summary.exterior + summary.excluded
    return if (others > 1e-9) "Living area ${settings.formatArea(summary.living)}" else settings.formatArea(summary.living)
}

private fun defaultFloorName(existingFloors: Int): String = when (existingFloors) {
    0 -> "Main Floor"
    1 -> "Second Floor"
    2 -> "Third Floor"
    else -> "Floor ${existingFloors + 1}"
}
