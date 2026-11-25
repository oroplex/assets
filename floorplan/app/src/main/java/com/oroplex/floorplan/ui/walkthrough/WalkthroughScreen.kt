package com.oroplex.floorplan.ui.walkthrough

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oroplex.floorplan.ar.ArCaptureHost
import com.oroplex.floorplan.ar.ArSessionMode
import com.oroplex.floorplan.export.ExportFiles
import com.oroplex.floorplan.recording.RecordingFile
import com.oroplex.floorplan.recording.RecordingInfo
import com.oroplex.floorplan.recording.WalkthroughRecording
import com.oroplex.floorplan.recording.WalkthroughStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Records a walk-through: the user walks through the rooms while the phone saves camera frames and its own
 * motion tracking; the recording is then sent to a computer, which makes the floor plan. [onDone] leaves.
 */
@Composable
fun WalkthroughScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { WalkthroughStore(walkthroughRoot(context)) }
    val vm: WalkthroughViewModel = viewModel(
        key = "walkthrough",
        factory = WalkthroughViewModel.Factory(store) { name -> recordingInfo(context, name) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)
    val scope = rememberCoroutineScope()
    var recorder by remember { mutableStateOf<WalkthroughRecording?>(null) }

    LaunchedEffect(state.leave) {
        if (state.leave) currentOnDone()
    }

    val actions = WalkthroughActions(
        onStart = { vm.start(recorder) },
        onStop = { vm.stop(recorder) },
        onDiscard = { vm.discard(recorder) },
        onClose = vm::requestLeave,
        onShare = { recording ->
            try {
                context.startActivity(ExportFiles.shareIntent(context, recording.file, "application/zip", recording.name))
            } catch (e: ActivityNotFoundException) {
                vm.showMessage("No app on this phone can receive the file.")
            } catch (e: IllegalArgumentException) {
                vm.showMessage("Couldn't share the file.")
            }
        },
        onSaveToDownloads = { recording ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) { saveToDownloads(context, recording.file) }
                vm.showMessage(
                    if (saved) "Saved to Download/$DOWNLOADS_FOLDER on this phone." else "Couldn't save to Downloads. Use Share instead.",
                )
            }
        },
        onDelete = vm::delete,
        onRecover = vm::recover,
        onRecordAnother = vm::recordAnother,
        onDismissMessage = vm::dismissMessage,
    )

    ArCaptureHost(modifier = Modifier.fillMaxSize(), mode = ArSessionMode.RECORD, onExit = vm::requestLeave) { controller ->
        DisposableEffect(controller) {
            recorder = controller.walkthrough
            onDispose { recorder = null }
        }
        WalkthroughContent(state, actions)
    }

    if (state.confirmLeave) {
        StopRecordingDialog(
            onSave = { vm.stopAndLeave(recorder, discard = false) },
            onDiscard = { vm.stopAndLeave(recorder, discard = true) },
            onKeepRecording = vm::dismissLeave,
        )
    }
}

internal class WalkthroughActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onDiscard: () -> Unit,
    val onClose: () -> Unit,
    val onShare: (RecordingFile) -> Unit,
    val onSaveToDownloads: (RecordingFile) -> Unit,
    val onDelete: (RecordingFile) -> Unit,
    val onRecover: () -> Unit,
    val onRecordAnother: () -> Unit,
    val onDismissMessage: () -> Unit,
)

/** Everything drawn above the camera picture. Stateless, so it can be shown without a camera in tests. */
@Composable
internal fun WalkthroughContent(state: WalkthroughUiState, actions: WalkthroughActions) {
    BackHandler(onBack = actions.onClose)
    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.55f)) {
                IconButton(onClick = actions.onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            if (state.phase == WalkthroughPhase.RECORDING) RecordingBadge(state)
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.72f).dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.phase) {
                    WalkthroughPhase.IDLE -> IdlePanel(state, actions)
                    WalkthroughPhase.RECORDING -> RecordingPanel(state, actions)
                    WalkthroughPhase.SAVING -> SavingPanel(state)
                    WalkthroughPhase.DONE -> DonePanel(state, actions)
                }
                state.message?.let { Notice(it, actions.onDismissMessage) }
            }
        }
    }
}

@Composable
private fun RecordingBadge(state: WalkthroughUiState) {
    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.6f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color(0xFFE53935), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                "REC ${WalkthroughCoach.clock(state.elapsedSeconds)}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun IdlePanel(state: WalkthroughUiState, actions: WalkthroughActions) {
    Text("Record a walk-through", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Walk through the rooms while the phone records what the camera sees and how it moves. A computer turns " +
            "the recording into a floor plan afterwards.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tip in WalkthroughCoach.TIPS) {
            Text("•  $tip", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Button(onClick = actions.onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.Filled.Videocam, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Start recording")
    }
    if (state.unfinished > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.unfinished == 1) "1 recording was cut off before it was saved." else "${state.unfinished} recordings were cut off before they were saved.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onRecover) { Text("Recover") }
        }
    }
    if (state.recordings.isNotEmpty()) {
        HorizontalDivider()
        Text("On this phone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (recording in state.recordings) RecordingRow(recording, actions)
    }
}

@Composable
private fun RecordingRow(recording: RecordingFile, actions: WalkthroughActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(recording.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${WalkthroughCoach.size(recording.sizeBytes)} · ${formatDate(recording.modifiedMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.onShare(recording) }) {
            Icon(Icons.Outlined.Share, contentDescription = "Share ${recording.name}")
        }
        if (canSaveToDownloads()) {
            IconButton(onClick = { actions.onSaveToDownloads(recording) }) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = "Save ${recording.name} to Downloads")
            }
        }
        IconButton(onClick = { actions.onDelete(recording) }) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${recording.name}")
        }
    }
}

@Composable
private fun RecordingPanel(state: WalkthroughUiState, actions: WalkthroughActions) {
    val hint = WalkthroughCoach.hint(state.progress, state.elapsedSeconds)
    Text(
        hint.text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (hint.warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    val p = state.progress
    val counts = buildString {
        append(if (p.frames == 1) "1 frame" else "${p.frames} frames")
        append(" · ").append(WalkthroughCoach.size(p.bytes))
        if (p.imageWidth > 0) append(" · ").append(p.imageWidth).append(" × ").append(p.imageHeight)
        if (p.dropped > 0) append(" · ").append(p.dropped).append(" skipped")
    }
    Text(counts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(
        onClick = actions.onStop,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Stop and save")
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = actions.onDiscard) { Text("Discard this recording") }
    }
}

@Composable
private fun SavingPanel(state: WalkthroughUiState) {
    Text("Saving the recording…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(progress = { state.packProgress }, modifier = Modifier.fillMaxWidth())
    Text(
        "Keep the app open: this takes a few seconds.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DonePanel(state: WalkthroughUiState, actions: WalkthroughActions) {
    val saved = state.saved ?: return
    Text("Recording saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "${saved.name} · ${state.savedFrames} frames · ${WalkthroughCoach.size(saved.sizeBytes)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Send it to the computer: Share offers Quick Share, Drive and the like. Save to Downloads puts it in " +
            "Download/$DOWNLOADS_FOLDER, where a computer finds it over a USB cable.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { actions.onShare(saved) }, modifier = Modifier.weight(1f).height(48.dp)) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
        if (canSaveToDownloads()) {
            FilledTonalButton(onClick = { actions.onSaveToDownloads(saved) }, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Downloads")
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = actions.onRecordAnother, modifier = Modifier.weight(1f)) { Text("Record another") }
        OutlinedButton(onClick = actions.onClose, modifier = Modifier.weight(1f)) { Text("Done") }
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss message", tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
internal fun StopRecordingDialog(onSave: () -> Unit, onDiscard: () -> Unit, onKeepRecording: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepRecording,
        title = { Text("Stop recording?") },
        text = { Text("Leaving ends the recording. Save what you have recorded so far, or throw it away.") },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save and leave") }
                OutlinedButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Discard and leave") }
                TextButton(onClick = onKeepRecording, modifier = Modifier.fillMaxWidth()) { Text("Keep recording") }
            }
        },
    )
}

private fun formatDate(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

// ---------------------------------------------------------------------------------------------------
// Files
// ---------------------------------------------------------------------------------------------------

internal const val DOWNLOADS_FOLDER = "FloorPlanScan"

/** App-private external storage (large files, no permission); internal storage when the phone has none. */
internal fun walkthroughRoot(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, WalkthroughStore.DIRECTORY)

private fun canSaveToDownloads(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/**
 * Copies [file] into the phone's public Download/[DOWNLOADS_FOLDER] folder (Android 10 and later; needs no
 * permission). Blocking. False when it could not be written.
 */
internal fun saveToDownloads(context: Context, file: File): Boolean {
    if (!canSaveToDownloads()) return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_FOLDER)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        null
    } ?: return false
    return try {
        val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("no stream")
        out.use { stream -> file.inputStream().use { it.copyTo(stream, 256 * 1024) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        try {
            resolver.delete(uri, null, null)
        } catch (ignored: Exception) {
            // Nothing more to clean up.
        }
        false
    }
}

private fun recordingInfo(context: Context, name: String): RecordingInfo {
    val version = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName ?: "unknown"} (build ${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (e: Exception) {
        "unknown"
    }
    return RecordingInfo(
        name = name,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        app = "FloorPlan Scan $version",
    )
}
