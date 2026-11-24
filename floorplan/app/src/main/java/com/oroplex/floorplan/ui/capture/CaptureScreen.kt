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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.oroplex.floorplan.data.BrandingRepository
import com.oroplex.floorplan.data.appContainer

/** Scan one or more rooms into floor [floorId] of project [projectId]; [onDone] returns to the editor. */
@Composable
fun CaptureScreen(projectId: String, floorId: String, onDone: () -> Unit) {
    val container = appContainer()
    val tipsFlag = remember(container) { BrandingFlag(container.branding, CAPTURE_TIPS_FLAG) }
    val vm: CaptureViewModel = viewModel(
        key = "capture:$projectId:$floorId",
        factory = CaptureViewModel.Factory(container.projects, projectId, floorId, tipsFlag),
    )
    val state = vm.state.collectAsStateWithLifecycle()
    val finished by remember { derivedStateOf { state.value.finished } }
    val loadError by remember { derivedStateOf { state.value.loadError } }
    val confirmDiscard by remember { derivedStateOf { state.value.confirmDiscard } }
    val showTips by remember { derivedStateOf { state.value.showTips } }
    val tipsFromHelp by remember { derivedStateOf { state.value.tipsFromHelp } }
    val currentOnDone by rememberUpdatedState(onDone)

    // The AR controller last handed to the capture UI. Back on the AR host's own screens (e.g. "camera in
    // use by another app" mid-scan) still exits through the ViewModel: discard prompt + anchor corrections.
    var lastController by remember { mutableStateOf<ArCaptureController?>(null) }

    LaunchedEffect(finished) {
        if (finished) currentOnDone()
    }

    val error = loadError
    if (error != null) {
        LoadErrorContent(error, onBack = onDone)
        return
    }
    ArCaptureHost(modifier = Modifier.fillMaxSize(), onExit = { vm.requestExit(lastController) }) { controller ->
        DisposableEffect(controller) {
            lastController = controller
            // Runs while the controller can still answer (before the host closes the session).
            onDispose { vm.onSessionEnded(controller) }
        }
        CaptureContent(vm, state, controller)
    }

    // Composed outside the AR host so they also show above its error screens.
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = vm::dismissDiscard,
            title = { Text("Discard this room?") },
            text = { Text("What you captured for this room will be lost. Rooms you already saved are kept.") },
            confirmButton = {
                TextButton(onClick = { vm.discardAndExit(lastController) }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDiscard) { Text("Keep scanning") }
            },
        )
    }
    if (showTips) HowToScanSheet(doneLabel = if (tipsFromHelp) "Got it" else "Start scanning", onDismiss = vm::dismissTips)
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
    val floorStatus by remember(frameState) { derivedStateOf { floorStatus(frameState.value) } }
    val coaching by vm.coaching.collectAsStateWithLifecycle()

    LaunchedEffect(vm, controller) {
        vm.attach(controller)
        controller.frameState.collect { vm.onFrame(it, controller) }
    }

    // Reticle feedback: the progress ring fills while measuring; outcomes play a haptic and an animation.
    val fx = remember { ReticleFx() }
    val measuring = ui.measuring
    val expectedMs = expectedMeasureMillis(ui.step, ui.draft)
    LaunchedEffect(measuring) {
        if (measuring) fx.measuring(expectedMs) else fx.idle()
    }
    val haptics = LocalHapticFeedback.current
    val feedback = ui.feedback
    LaunchedEffect(feedback?.id) {
        val f = feedback ?: return@LaunchedEffect
        if (f.id <= state.value.feedbackPlayedId) return@LaunchedEffect // already played (e.g. before rotation)
        vm.onFeedbackPlayed(f.id)
        haptics.performHapticFeedback(if (f.kind == FeedbackKind.FAILED) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
        fx.play(f.kind)
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
    // System Back first leaves a transient mode or steps back out of REVIEW (nothing is lost); otherwise it
    // means "leave the scan" (with the discard prompt when a room is in progress).
    BackHandler(enabled = !ui.finished) { vm.onBack(controller) }

    Box(Modifier.fillMaxSize()) {
        CaptureOverlay(ui, frame, fx, Modifier.fillMaxSize())

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloseCaptureButton(onClick = { vm.requestExit(controller) })
                    HelpButton(onClick = vm::openTips)
                }
                CoachingBanner(coaching)
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
    override fun useLastCeiling() = vm.useLastCeiling(controller)
    override fun setMethod(method: CaptureMethod) = vm.setMethod(method)
    override fun captureWall() = vm.captureWall(controller)
    override fun startBaseline() = vm.startBaseline()
    override fun cancelBaseline() = vm.cancelBaseline()
    override fun markBaselinePoint() = vm.markBaselinePoint(controller)
    override fun addCorner() = vm.addCorner(controller)
    override fun startRecapture(index: Int) = vm.startRecapture(index)
    override fun cancelRecapture() = vm.cancelRecapture()
    override fun deleteCapture(index: Int) = vm.deleteCapture(index)
    override fun undo() = vm.undo()
    override fun closeRoom() = vm.closeRoom()
    override fun backToWalls() = vm.backToWalls()
    override fun setOpeningType(type: OpeningType) = vm.setOpeningType(type)
    override fun markOpeningEdge() = vm.markOpeningEdge(controller)
    override fun deleteOpening(id: String) = vm.deleteOpening(id)
    override fun finishOpenings() = vm.finishOpenings()
    override fun setName(name: String) = vm.setName(name)
    override fun setType(type: RoomType) = vm.setType(type)
    override fun saveAndScanNext() = vm.saveAndScanNext(controller)
    override fun saveAndFinish() = vm.saveAndFinish(controller)
    override fun redoRoom() = vm.redoRoom()
    override fun backToOpenings() = vm.backToOpenings()
}

/**
 * [OneTimeFlag] stored in the app's [BrandingRepository] one-time flags. The tips are a convenience, so a
 * failing preference store must never block scanning: a failed read counts as "not seen", a failed write
 * is ignored.
 */
internal class BrandingFlag(private val repository: BrandingRepository, private val key: String) : OneTimeFlag {
    override fun isSet(): Boolean = runCatching { repository.isFlagSet(key) }.getOrDefault(false)

    override fun set() {
        runCatching { repository.setFlag(key, true) }
    }
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
