package com.oroplex.floorplan.ui.export

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Architecture
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import com.oroplex.floorplan.data.appContainer
import com.oroplex.floorplan.export.ExportFiles
import com.oroplex.floorplan.export.ExportFormat
import com.oroplex.floorplan.export.PaperSize
import kotlinx.coroutines.launch

private const val TAG = "ExportScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(projectId: String, onBack: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: ExportViewModel = viewModel(key = "export:$projectId") {
        ExportViewModel(projectId, container.projects, container.branding, appContext, createSavedStateHandle())
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // One SAF launcher per format: each needs its own MIME type, and a stable registration means a
    // result delivered after process death still reaches the right handler.
    val saveLaunchers = ExportFormat.entries.associateWith { format ->
        key(format) {
            val contract = remember { ActivityResultContracts.CreateDocument(format.mimeType) }
            rememberLauncherForActivityResult(contract) { uri -> if (uri != null) vm.saveTo(uri) }
        }
    }

    // Events are only consumed while the screen is visible: a share sheet can't be opened from the
    // background, so an export that finishes while the app is away waits in the channel until return.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is ExportEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                    is ExportEvent.PickSaveLocation -> try {
                        saveLaunchers.getValue(event.format).launch(event.fileName)
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "No document picker", e)
                        scope.launch { snackbar.showSnackbar("No file manager is available to save files. Use Share instead.") }
                    }
                    is ExportEvent.Share -> {
                        val problem = runCatchingShare {
                            context.startActivity(
                                ExportFiles.shareIntent(context, event.exported.file, event.exported.format.mimeType, event.subject),
                            )
                        }
                        if (problem != null) scope.launch { snackbar.showSnackbar(problem) }
                    }
                }
            }
        }
    }

    val ready = state as? ExportScreenState.Ready
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Export")
                        if (ready != null && ready.projectName.isNotBlank()) {
                            Text(
                                ready.projectName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (ready != null) {
                ExportActions(
                    state = ready,
                    onShare = { vm.requestExport(ExportAction.SHARE) },
                    onSave = { vm.requestExport(ExportAction.SAVE) },
                )
            }
        },
    ) { padding ->
        when (val s = state) {
            ExportScreenState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            is ExportScreenState.Missing -> MissingProject(s.message, onBack, Modifier.padding(padding))
            is ExportScreenState.Ready -> ExportContent(
                state = s,
                preview = preview,
                padding = padding,
                vm = vm,
                onBackToEditor = onBack,
            )
        }
    }

    ready?.confirmation?.let { confirmation ->
        ExportAnywayDialog(
            confirmation = confirmation,
            onConfirm = vm::confirmExportAnyway,
            onDismiss = vm::dismissConfirmation,
        )
    }
}

/** Runs [block]; returns a user-facing message when no app can handle the share intent. */
private inline fun runCatchingShare(block: () -> Unit): String? =
    try {
        block()
        null
    } catch (e: ActivityNotFoundException) {
        "No app on this device can receive this file."
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "FileProvider rejected the export file", e)
        "Couldn't share the file."
    }

@Composable
private fun ExportContent(
    state: ExportScreenState.Ready,
    preview: ExportPreview,
    padding: PaddingValues,
    vm: ExportViewModel,
    onBackToEditor: () -> Unit,
) {
    val request = state.request
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "preview") {
            ExportPreviewCard(preview, Modifier.padding(horizontal = 16.dp))
        }

        if (state.blockedReason == null) {
            item(key = "checks") {
                PreExportCard(
                    checks = state.checks,
                    acknowledged = state.errorsAcknowledged,
                    onExportAnyway = vm::acknowledgeErrors,
                    onBackToEditor = onBackToEditor,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        if (request.format != ExportFormat.JSON && state.floorChoices.isNotEmpty()) {
            item(key = "floors") {
                Section("Floor") {
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.floorChoices.forEach { option ->
                            val selected = option?.id == state.selectedFloorId
                            FilterChip(
                                selected = selected,
                                onClick = { vm.selectFloor(option?.id) },
                                label = {
                                    Text(if (option == null) "All floors" else "${option.name} (${option.roomCount})")
                                },
                            )
                        }
                    }
                }
            }
        }

        state.hint?.let { hint -> item(key = "hint") { Notice(hint, isError = false) } }
        if (state.showBrandingTip) {
            item(key = "branding-tip") {
                Notice("Tip: add your company name and logo in Settings → Company branding to brand your exports.", isError = false)
            }
        }
        state.blockedReason?.let { reason -> item(key = "blocked") { Notice(reason, isError = true) } }

        item(key = "formats") {
            Section("Format") {
                Column(Modifier.selectableGroup()) {
                    ExportFormat.entries.forEach { format ->
                        FormatRow(format, selected = format == request.format, onSelect = { vm.selectFormat(format) })
                    }
                }
            }
        }

        if (request.format != ExportFormat.JSON) {
            item(key = "options") {
                Section("Options") {
                    if (request.format == ExportFormat.PDF) {
                        PaperSizeRow(request.paper, vm::selectPaper)
                    }
                    if (request.format != ExportFormat.DXF) {
                        SwitchRow("Wall lengths", request.showWallLengths, vm::setShowWallLengths)
                        SwitchRow("Room areas", request.showAreas, vm::setShowAreas)
                        SwitchRow(
                            "Overall dimensions",
                            request.showOverallDimensions,
                            vm::setShowOverallDimensions,
                            supporting = "Total width and depth outside the plan",
                        )
                    } else {
                        Text(
                            "DXF contains walls, doors, windows and room labels on separate layers.",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (request.format == ExportFormat.PDF) {
                        SwitchRow(
                            "Room schedule",
                            request.includeRoomSchedule,
                            vm::setIncludeRoomSchedule,
                            supporting = "A page listing every room's size, area and ceiling height",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

private fun ExportFormat.icon(): ImageVector = when (this) {
    ExportFormat.PDF -> Icons.Outlined.PictureAsPdf
    ExportFormat.PNG -> Icons.Outlined.Image
    ExportFormat.SVG -> Icons.Outlined.Code
    ExportFormat.DXF -> Icons.Outlined.Architecture
    ExportFormat.JSON -> Icons.Outlined.DataObject
}

@Composable
private fun FormatRow(format: ExportFormat, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        leadingContent = { Icon(format.icon(), contentDescription = null) },
        headlineContent = { Text(format.label) },
        supportingContent = { Text(format.description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaperSizeRow(selected: PaperSize, onSelect: (PaperSize) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Paper size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            PaperSize.entries.forEachIndexed { index, paper ->
                SegmentedButton(
                    selected = paper == selected,
                    onClick = { onSelect(paper) },
                    shape = SegmentedButtonDefaults.itemShape(index, PaperSize.entries.size),
                ) { Text(paper.label) }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/** Bottom bar: file name, progress while generating, Save and Share buttons. */
@Composable
private fun ExportActions(state: ExportScreenState.Ready, onShare: () -> Unit, onSave: () -> Unit) {
    val enabled = !state.busy && state.blockedReason == null
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.padding(top = 8.dp))
            }
            Text(
                if (state.busy) "Creating ${state.fileName}…" else state.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save to device", maxLines = 1)
                }
                Button(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MissingProject(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.padding(top = 16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.padding(top = 16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
