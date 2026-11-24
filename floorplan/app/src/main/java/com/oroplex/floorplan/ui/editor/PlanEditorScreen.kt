@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SquareFoot
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.data.appContainer
import com.oroplex.floorplan.ui.common.ConfirmDialog
import com.oroplex.floorplan.ui.common.ProjectDetailsDialog
import com.oroplex.floorplan.ui.common.TextInputDialog
import com.oroplex.floorplan.ui.common.formatArea

@Composable
fun PlanEditorScreen(
    projectId: String,
    onScanRoom: (projectId: String, floorId: String) -> Unit,
    onExport: (projectId: String) -> Unit,
    onBack: () -> Unit,
) {
    val container = appContainer()
    val viewModel: PlanEditorViewModel = viewModel(
        key = "plan-editor:$projectId",
        factory = PlanEditorViewModel.Factory(container.projects, projectId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    BackHandler(enabled = state.selection != null) { viewModel.clearSelection() }

    val project = state.project
    val floor = state.floor
    val settings = state.settings

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
                            onSelect = viewModel::selectFloor,
                            onAdd = { dialog = EditorDialog.AddFloor },
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
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (floor.rooms.isEmpty()) EmptyFloor(Modifier.align(Alignment.Center))
                            } else {
                                NoFloors(onAdd = { dialog = EditorDialog.AddFloor }, modifier = Modifier.align(Alignment.Center))
                            }
                        }
                    }
                }
            }
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
                    DropdownMenuItem(text = { Text("Project settings") }, onClick = {
                        menuOpen = false
                        onProjectSettings()
                    })
                    DropdownMenuItem(text = { Text("Rename project") }, onClick = {
                        menuOpen = false
                        onRenameProject()
                    })
                }
            }
        },
    )
}

@Composable
private fun FloorTabs(
    floors: List<Floor>,
    selectedFloorId: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
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
                IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add floor") }
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = selectedFloorId != null) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Floor options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename floor") }, onClick = {
                            menuOpen = false
                            selectedFloorId?.let(onRename)
                        })
                        DropdownMenuItem(
                            text = { Text("Delete floor") },
                            enabled = floors.size > 1,
                            onClick = {
                                menuOpen = false
                                selectedFloorId?.let(onDelete)
                            },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

/** Bottom panel for the current selection (nothing when no room is selected). */
@Composable
private fun SelectionPanel(state: EditorUiState, viewModel: PlanEditorViewModel, openDialog: (EditorDialog) -> Unit) {
    val selection = state.selection ?: return
    val room = state.selectedRoom ?: return
    val settings = state.settings
    EditorPanelSurface {
        when (selection) {
            is Selection.Room -> RoomPanel(
                room = room,
                settings = settings,
                moveMode = state.moveMode,
                actions = RoomPanelActions(
                    onEditDetails = { openDialog(EditorDialog.RoomDetails(room.id)) },
                    onEditCeiling = { openDialog(EditorDialog.CeilingHeight(room.id)) },
                    onOpenOpening = { openDialog(EditorDialog.EditOpening(room.id, it.id)) },
                    onToggleMove = viewModel::toggleMoveMode,
                    onRotate = { viewModel.rotateRoom(room.id, it) },
                    onResolve = { openDialog(EditorDialog.Resolve(room.id)) },
                    onDelete = { openDialog(EditorDialog.DeleteRoom(room.id)) },
                    onClose = viewModel::clearSelection,
                ),
            )
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
            ProjectSettingsDialog(settings, onConfirm = viewModel::updateSettings, onDismiss = onDismiss)
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
            RoomDetailsDialog(room, onConfirm = { name, type -> viewModel.renameRoom(room.id, name, type) }, onDismiss = onDismiss)
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
                roomCount = floor?.rooms?.size ?: 1,
                settings = settings,
                previous = floor?.calibration,
                onApply = { viewModel.calibrate(room.id, dialog.wallIndex, it) },
                onDismiss = onDismiss,
            )
        }
    }
    if (!shown) LaunchedEffect(dialog) { onDismiss() }
}

@Composable
private fun EmptyFloor(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.SquareFoot,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text("No rooms on this floor yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            "Tap Scan room to measure a room with your camera, or Draw room to type in its dimensions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoFloors(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This project has no floors.", style = MaterialTheme.typography.titleMedium)
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

private fun floorSummary(floor: Floor, settings: PlanSettings): String {
    val rooms = floor.rooms.size
    val roomText = "$rooms ${if (rooms == 1) "room" else "rooms"}"
    if (rooms == 0) return "${floor.name} · no rooms"
    val area = try {
        Measurements.floorArea(floor)
    } catch (e: Exception) {
        return "${floor.name} · $roomText"
    }
    return "${floor.name} · $roomText · ${settings.formatArea(area)}"
}

private fun defaultFloorName(existingFloors: Int): String = when (existingFloors) {
    0 -> "Main Floor"
    1 -> "Second Floor"
    2 -> "Third Floor"
    else -> "Floor ${existingFloors + 1}"
}
