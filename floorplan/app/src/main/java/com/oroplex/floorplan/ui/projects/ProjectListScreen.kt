@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.projects

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.data.ProjectSummary
import com.oroplex.floorplan.data.appContainer
import com.oroplex.floorplan.ui.common.ConfirmDialog
import com.oroplex.floorplan.ui.common.PlanThumbnail
import com.oroplex.floorplan.ui.common.ProjectDetailsDialog
import kotlinx.coroutines.launch

/** Dialogs of the projects screen. */
private sealed interface ListDialog {
    data object Create : ListDialog
    data class Rename(val summary: ProjectSummary) : ListDialog
    data class Delete(val summary: ProjectSummary) : ListDialog
}

@Composable
fun ProjectListScreen(onOpenProject: (projectId: String) -> Unit, onOpenSettings: () -> Unit) {
    val container = appContainer()
    val viewModel: ProjectListViewModel = viewModel(factory = ProjectListViewModel.Factory(container.projects, container.settings))
    val summaries by viewModel.summaries.collectAsStateWithLifecycle()
    val loaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    val defaults by container.settings.defaults.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val openProject by rememberUpdatedState(onOpenProject)

    var dialog by remember { mutableStateOf<ListDialog?>(null) }
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = pendingExportId
        pendingExportId = null
        if (uri != null && id != null) viewModel.exportBackup(context.contentResolver, uri, id)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importBackup(context.contentResolver, uri)
    }
    val startImport = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ProjectListEvent.Open -> {
                    // A request that waited while this screen was away (e.g. the user left right after tapping
                    // Create) must not push the editor out of the blue later.
                    if (!event.isStale(System.currentTimeMillis())) {
                        snackbar.currentSnackbarData?.dismiss()
                        openProject(event.projectId)
                    }
                }
                // Shown in its own coroutine: showSnackbar suspends until the snackbar is gone (up to 10 s), and
                // the collector must stay free to open a newly created project right away. SnackbarHostState
                // still shows the messages one after another.
                is ProjectListEvent.Message -> launch {
                    val undo = event.undo
                    val result = snackbar.showSnackbar(
                        message = event.text,
                        actionLabel = if (undo != null) "Undo" else null,
                        withDismissAction = undo != null,
                        duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed && undo != null) viewModel.restore(undo)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            ProjectListTopBar(
                onImport = startImport,
                onOpenSettings = onOpenSettings,
                onOpenSample = viewModel::openSample,
            )
        },
        floatingActionButton = {
            if (loaded && summaries.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { dialog = ListDialog.Create },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New project") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                summaries.isEmpty() -> EmptyProjects(
                    onCreate = { dialog = ListDialog.Create },
                    onOpenSample = viewModel::openSample,
                    onImport = startImport,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(summaries, key = { it.id }) { summary ->
                        val project by remember(summary.id) { viewModel.observe(summary.id) }.collectAsStateWithLifecycle()
                        ProjectCard(
                            summary = summary,
                            project = project,
                            unitSystem = project?.settings?.unitSystem ?: defaults.unitSystem,
                            onOpen = { onOpenProject(summary.id) },
                            onRename = { dialog = ListDialog.Rename(summary) },
                            onDuplicate = { viewModel.duplicate(summary.id) },
                            onExport = {
                                pendingExportId = summary.id
                                exportLauncher.launch(backupFileName(summary.name))
                            },
                            onDelete = { dialog = ListDialog.Delete(summary) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        ListDialog.Create -> ProjectDetailsDialog(
            title = "New project",
            initialName = "",
            initialAddress = "",
            confirmLabel = "Create",
            onConfirm = viewModel::create,
            onDismiss = { dialog = null },
        )
        is ListDialog.Rename -> ProjectDetailsDialog(
            title = "Rename project",
            initialName = d.summary.name,
            initialAddress = d.summary.address,
            confirmLabel = "Save",
            onConfirm = { name, address -> viewModel.rename(d.summary.id, name, address) },
            onDismiss = { dialog = null },
        )
        is ListDialog.Delete -> ConfirmDialog(
            title = "Delete ${d.summary.name}?",
            message = "All floors, rooms and measurements of this project are deleted from this phone. " +
                "Export a backup first if you might need it again.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { viewModel.delete(d.summary.id) },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ProjectListTopBar(onImport: () -> Unit, onOpenSettings: () -> Unit, onOpenSample: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("FloorPlan Scan") },
        actions = {
            IconButton(onClick = onImport) { Icon(Icons.Filled.FileOpen, contentDescription = "Import backup") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Open sample house") },
                        leadingIcon = { Icon(Icons.Outlined.Apartment, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenSample()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Import backup") },
                        leadingIcon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onImport()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun ProjectCard(
    summary: ProjectSummary,
    project: Project?,
    unitSystem: UnitSystem,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp),
            ) {
                PlanThumbnail(project, Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary.address.isNotBlank()) {
                    Text(
                        summary.address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    projectDetails(summary, unitSystem),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${summary.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    CardMenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { menuOpen = false; onRename() }
                    CardMenuItem("Duplicate", Icons.Outlined.ContentCopy) { menuOpen = false; onDuplicate() }
                    CardMenuItem("Export backup", Icons.Outlined.Save) { menuOpen = false; onExport() }
                    CardMenuItem("Delete", Icons.Outlined.Delete, destructive = true) { menuOpen = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun CardMenuItem(label: String, icon: ImageVector, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}

@Composable
private fun EmptyProjects(onCreate: () -> Unit, onOpenSample: () -> Unit, onImport: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.ViewInAr,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
        Text(
            "Measure your home, get a floor plan",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Walk through each room and aim your phone at the walls. FloorPlan Scan measures them with AR and " +
                "builds an accurate, dimensioned plan you can edit and export as PDF, image, SVG or DXF.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onCreate,
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("New project", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onOpenSample, modifier = Modifier.padding(top = 12.dp)) {
            Icon(Icons.Outlined.Apartment, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Open sample house", modifier = Modifier.padding(start = 8.dp))
        }
        TextButton(onClick = onImport, modifier = Modifier.padding(top = 4.dp)) {
            Text("Import a backup")
        }
    }
}

private fun projectDetails(summary: ProjectSummary, unitSystem: UnitSystem): String {
    val parts = ArrayList<String>(4)
    parts += "Updated " + DateUtils.getRelativeTimeSpanString(
        summary.updatedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    parts += if (summary.roomCount == 1) "1 room" else "${summary.roomCount} rooms"
    if (summary.floorCount > 1) parts += "${summary.floorCount} floors"
    if (summary.totalArea > 0 && summary.totalArea.isFinite()) parts += Units.formatArea(summary.totalArea, unitSystem)
    return parts.joinToString(" · ")
}

/** A file-system friendly suggested name for a backup, e.g. "12 Oak Street.floorplan.json". */
private fun backupFileName(projectName: String): String {
    val safe = projectName.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifEmpty { "FloorPlan" }
    return "$safe.floorplan.json"
}
