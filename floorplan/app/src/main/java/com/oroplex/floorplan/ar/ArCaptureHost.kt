package com.oroplex.floorplan.ar

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Full-screen AR host. Handles camera permission, ARCore availability / install, session creation and
 * configuration, lifecycle (resume/pause/close), and renders the camera feed. Once the session runs it
 * calls [content] with the live controller, layered above the camera view. Shows friendly full-screen
 * messages (with retry / back) for every failure (no permission, unsupported device, install declined,
 * camera unavailable). [onExit] is invoked when the user backs out of an error screen.
 */
@Composable
fun ArCaptureHost(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    content: @Composable (controller: ArCaptureController) -> Unit,
) {
    Text("TODO: ArCaptureHost", modifier)
}
