@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.view3d

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.LabelOff
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined._3dRotation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oroplex.floorplan.data.appContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Interactive 3D "dollhouse" view of one floor (orbit/zoom with gestures). */
@Composable
fun DollhouseScreen(projectId: String, floorId: String, onBack: () -> Unit) {
    val container = appContainer()
    val viewModel: DollhouseViewModel = viewModel(
        key = "dollhouse:$projectId:$floorId",
        factory = DollhouseViewModel.Factory(container.projects, projectId, floorId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val branding = container.branding
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    var showLabels by rememberSaveable { mutableStateOf(true) }
    var hintVisible by rememberSaveable { mutableStateOf(!branding.isFlagSet(GESTURE_HINT_FLAG)) }
    val reset = remember { ResetAnimation() }
    val canInteract = state.status == DollhouseStatus.READY && state.scene?.isEmpty == false && state.roomCount > 0

    LaunchedEffect(hintVisible, canInteract) {
        // The countdown starts once there is a model to try the gestures on.
        if (hintVisible && canInteract) {
            delay(HINT_DURATION_MS)
            hintVisible = false // shown again next time, until the user has tried a gesture
        }
    }

    fun resetView() {
        val from = viewModel.camera ?: return
        val to = viewModel.framedCamera() ?: return
        reset.job?.cancel()
        reset.job = scope.launch {
            animate(0f, 1f, animationSpec = tween(RESET_DURATION_MS, easing = FastOutSlowInEasing)) { v, _ ->
                viewModel.setCamera(OrbitCamera.lerp(from, to, v.toDouble()), settled = false)
            }
            viewModel.setCamera(to, settled = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (state.floorName.isBlank()) "3D view" else "3D view — ${state.floorName}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.projectName.isNotBlank()) {
                            Text(
                                state.projectName,
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
                    IconButton(onClick = { showLabels = !showLabels }, enabled = canInteract) {
                        Icon(
                            if (showLabels) Icons.AutoMirrored.Outlined.Label else Icons.AutoMirrored.Outlined.LabelOff,
                            contentDescription = if (showLabels) "Hide room names" else "Show room names",
                        )
                    }
                    IconButton(onClick = ::resetView, enabled = canInteract) {
                        Icon(Icons.Outlined.CenterFocusStrong, contentDescription = "Reset view")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val center = Modifier.align(Alignment.Center)
            when (state.status) {
                DollhouseStatus.LOADING -> CircularProgressIndicator(center)
                DollhouseStatus.MISSING_PROJECT -> MessageState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "This project is no longer available",
                    body = "It may have been deleted, or its file could not be read.",
                    action = "Back" to onBack,
                    modifier = center,
                )
                DollhouseStatus.MISSING_FLOOR -> MessageState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "This floor is no longer available",
                    body = "It may have been deleted. Go back and pick another floor.",
                    action = "Back to plan" to onBack,
                    modifier = center,
                )
                DollhouseStatus.READY -> {
                    val scene = state.scene
                    when {
                        state.roomCount == 0 -> MessageState(
                            icon = Icons.Outlined.ViewInAr,
                            title = "Nothing to show in 3D yet",
                            body = "Scan or draw a room on this floor, then come back to see it in 3D.",
                            action = "Back to plan" to onBack,
                            modifier = center,
                        )
                        scene == null && state.failed -> MessageState(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "This floor couldn't be shown in 3D",
                            body = "Some of its data may be damaged. Check the rooms in the plan editor.",
                            action = "Back to plan" to onBack,
                            modifier = center,
                        )
                        scene == null -> CircularProgressIndicator(center)
                        scene.isEmpty -> MessageState(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "These rooms can't be shown in 3D",
                            body = "Their outlines need fixing. In the plan, select a room and redraw or re-solve it.",
                            action = "Back to plan" to onBack,
                            modifier = center,
                        )
                        else -> {
                            DollhouseCanvas(
                                scene = scene,
                                lens = viewModel.lens,
                                camera = { viewModel.camera },
                                showLabels = showLabels,
                                onViewport = viewModel::onViewport,
                                callbacks = DollhouseGestureCallbacks(
                                    onGestureStart = {
                                        reset.job?.cancel() // touching the model stops a reset in progress
                                        if (hintVisible) {
                                            hintVisible = false
                                            branding.setFlag(GESTURE_HINT_FLAG)
                                        }
                                    },
                                    onOrbit = { dx, dy -> viewModel.orbit(dx, dy, density) },
                                    onPinchPan = { zoom, pan ->
                                        viewModel.zoom(zoom)
                                        viewModel.pan(pan.x, pan.y)
                                    },
                                    onDoubleTap = ::resetView,
                                ),
                                description = describe(state.floorName, scene),
                                onResetAction = ::resetView,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (scene.skippedRooms > 0) {
                                Notice(
                                    text = if (scene.skippedRooms == 1) "1 room couldn't be shown in 3D. Check its outline in the plan."
                                    else "${scene.skippedRooms} rooms couldn't be shown in 3D. Check their outlines in the plan.",
                                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                                )
                            }
                            AnimatedVisibility(
                                visible = hintVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            ) {
                                GestureHint()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The running "reset view" animation, if any (plain holder: changing it needs no recomposition). */
private class ResetAnimation {
    var job: Job? = null
}

private fun describe(floorName: String, scene: DollhouseScene): String {
    val shown = scene.roomCount - scene.skippedRooms
    val rooms = if (shown == 1) "1 room" else "$shown rooms"
    val name = floorName.ifBlank { "this floor" }
    return "3D model of $name with $rooms. Drag to rotate, pinch to zoom, drag with two fingers to move, double-tap to reset."
}

@Composable
private fun GestureHint() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 420.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined._3dRotation, contentDescription = null)
            Text(
                "Drag to rotate · Pinch to zoom · Two fingers to move · Double-tap to reset",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp).widthIn(max = 420.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageState(
    icon: ImageVector,
    title: String,
    body: String,
    action: Pair<String, () -> Unit>?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Button(onClick = action.second, modifier = Modifier.padding(top = 8.dp)) { Text(action.first) }
        }
    }
}

/** One-time flag (BrandingRepository): set once the user has used a gesture, so the hint stops appearing. */
private const val GESTURE_HINT_FLAG = "view3d.gestureHintSeen"
private const val HINT_DURATION_MS = 7_000L
private const val RESET_DURATION_MS = 450
