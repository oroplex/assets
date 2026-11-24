package com.oroplex.floorplan.ui.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oroplex.floorplan.ar.ArCaptureController
import com.oroplex.floorplan.ar.ArCaptureHost
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.data.appContainer

/** Scan one or more rooms into floor [floorId] of project [projectId]; [onDone] returns to the editor. */
@Composable
fun CaptureScreen(projectId: String, floorId: String, onDone: () -> Unit) {
    val container = appContainer()
    val vm: CaptureViewModel = viewModel(
        key = "capture:$projectId:$floorId",
        factory = CaptureViewModel.Factory(container.projects, projectId, floorId),
    )
    val state = vm.state.collectAsStateWithLifecycle()
    val finished by remember { derivedStateOf { state.value.finished } }
    val loadError by remember { derivedStateOf { state.value.loadError } }
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(finished) {
        if (finished) currentOnDone()
    }

    val error = loadError
    if (error != null) {
        LoadErrorContent(error, onBack = onDone)
        return
    }
    ArCaptureHost(modifier = Modifier.fillMaxSize(), onExit = onDone) { controller ->
        CaptureContent(vm, state, controller)
    }
}

/** Everything layered over the live camera once the AR session runs. */
@Composable
private fun CaptureContent(
    vm: CaptureViewModel,
    state: State<CaptureUiState>,
    controller: ArCaptureController,
) {
    val ui = state.value
    val frameState = controller.frameState.collectAsStateWithLifecycle()
    // Read inside draw lambdas only, so per-frame updates redraw the canvases without recomposing.
    val frame: () -> ArFrameState = remember(frameState) { { frameState.value } }
    val bannerText by remember(frameState) {
        derivedStateOf { frameState.value.let { trackingBannerText(it.tracking, it.problem) } }
    }
    val floorStatus by remember(frameState) {
        derivedStateOf {
            val f = frameState.value
            when {
                f.floorY == null -> FloorStatus.SEARCHING
                f.floorArea < CaptureViewModel.AUTO_LOCK_MIN_AREA -> FloorStatus.FOUND
                else -> FloorStatus.CONFIDENT
            }
        }
    }

    LaunchedEffect(vm, controller) {
        vm.attach(controller)
        controller.frameState.collect { vm.onFrame(it, controller) }
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(ui.hapticPending) {
        if (ui.hapticPending) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            vm.onHapticPlayed()
        }
    }

    // Show the newest message; anything older is dropped once it is replaced or times out.
    val snackbarHostState = remember { SnackbarHostState() }
    val latestMessage = ui.messages.lastOrNull()
    LaunchedEffect(latestMessage?.id) {
        val message = latestMessage ?: return@LaunchedEffect
        try {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                message.text,
                withDismissAction = true,
                duration = if (message.text.length > 70) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        } finally {
            vm.onMessageShown(message.id)
        }
    }

    val actions = remember(vm, controller) { BoundCaptureActions(vm, controller) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestExit: () -> Unit = {
        val s = state.value
        if (!s.saving && !s.finished) {
            if (s.hasRoomInProgress) confirmDiscard = true else vm.exit(controller)
        }
    }
    BackHandler(enabled = !ui.finished, onBack = requestExit)

    Box(Modifier.fillMaxSize()) {
        CaptureOverlay(ui, frame, Modifier.fillMaxSize())

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloseCaptureButton(onClick = requestExit)
                bannerText?.let { TrackingBanner(it) }
            }
            val showMiniMap = ui.step == CaptureStep.WALLS || ui.step == CaptureStep.CORNERS || ui.step == CaptureStep.OPENINGS
            if (showMiniMap) {
                Spacer(Modifier.width(12.dp))
                MiniMap(ui, frame, Modifier.size(MiniMapSize))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()) {
            SnackbarHost(snackbarHostState, Modifier.fillMaxWidth())
            if (ui.step == CaptureStep.REVIEW) {
                ReviewPanel(ui, actions)
            } else {
                CaptureControls(ui, floorStatus, actions)
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this room?") },
            text = { Text("What you captured for this room will be lost. Rooms you already saved are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.exit(controller)
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep scanning") }
            },
        )
    }
}

/** Binds the UI's actions to the ViewModel and the live AR controller. */
private class BoundCaptureActions(
    private val vm: CaptureViewModel,
    private val controller: ArCaptureController,
) : CaptureActions, ReviewActions {
    override fun lockFloor() = vm.lockFloor(controller)
    override fun relockFloor() = vm.relockFloor()
    override fun measureCeiling() = vm.measureCeiling(controller)
    override fun skipCeiling() = vm.skipCeiling(controller)
    override fun setMethod(method: CaptureMethod) = vm.setMethod(method)
    override fun captureWall() = vm.captureWall(controller)
    override fun startBaseline() = vm.startBaseline()
    override fun cancelBaseline() = vm.cancelBaseline()
    override fun markBaselinePoint() = vm.markBaselinePoint(controller)
    override fun addCorner() = vm.addCorner(controller)
    override fun undo() = vm.undo()
    override fun closeRoom() = vm.closeRoom()
    override fun setOpeningType(type: OpeningType) = vm.setOpeningType(type)
    override fun markOpeningEdge() = vm.markOpeningEdge(controller)
    override fun deleteOpening(id: String) = vm.deleteOpening(id)
    override fun finishOpenings() = vm.finishOpenings()
    override fun setName(name: String) = vm.setName(name)
    override fun setType(type: RoomType) = vm.setType(type)
    override fun saveAndScanNext() = vm.saveAndScanNext(controller)
    override fun saveAndFinish() = vm.saveAndFinish(controller)
    override fun redoRoom() = vm.redoRoom()
}

@Composable
private fun LoadErrorContent(message: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(24.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}
