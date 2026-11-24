package com.oroplex.floorplan.ar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MobileOff
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen message used for every AR setup problem: centered icon, title, explanation, an optional
 * primary action and Back. System back also calls [onBack] unless [handleSystemBack] is false (then the
 * screen underneath keeps handling it). Scrolls when the text does not fit (large font scales, landscape).
 */
@Composable
internal fun ArMessageScreen(
    icon: ImageVector,
    title: String,
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    handleSystemBack: Boolean = true,
) {
    BackHandler(enabled = handleSystemBack, onBack = onBack)
    ArScreenFrame(modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (primaryLabel != null && onPrimary != null) {
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/** Full-screen "working on it" state (checking ARCore, waiting for an install or a permission dialog). */
@Composable
internal fun ArProgressScreen(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    ArScreenFrame(modifier) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** [ArMessageScreen] for an [ArSetupError], with the icon and primary action that fit it. */
@Composable
internal fun ArSetupErrorScreen(
    error: ArSetupError,
    onPrimary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    handleSystemBack: Boolean = true,
) {
    val icon = when (error) {
        ArSetupError.DEVICE_NOT_SUPPORTED -> Icons.Outlined.MobileOff
        ArSetupError.ARCORE_NOT_INSTALLED, ArSetupError.INSTALL_DECLINED -> Icons.Outlined.Download
        ArSetupError.ARCORE_TOO_OLD, ArSetupError.APP_TOO_OLD -> Icons.Outlined.SystemUpdate
        ArSetupError.CHECK_FAILED -> Icons.Outlined.CloudOff
        ArSetupError.CAMERA_UNAVAILABLE -> Icons.Outlined.VideocamOff
        ArSetupError.SESSION_FAILED -> Icons.Outlined.ErrorOutline
    }
    val primaryLabel = when (error.action) {
        ArSetupAction.RETRY -> "Retry"
        ArSetupAction.INSTALL -> if (error == ArSetupError.ARCORE_TOO_OLD) "Update" else "Install"
        ArSetupAction.NONE -> null
    }
    ArMessageScreen(
        icon = icon,
        title = error.title,
        message = error.message,
        onBack = onBack,
        modifier = modifier,
        primaryLabel = primaryLabel,
        onPrimary = onPrimary.takeIf { primaryLabel != null },
        handleSystemBack = handleSystemBack,
    )
}

/** Camera permission not granted yet (or denied once): explain why and offer the system prompt. */
@Composable
internal fun CameraRationaleScreen(onGrant: () -> Unit, onBack: () -> Unit) {
    ArMessageScreen(
        icon = Icons.Outlined.PhotoCamera,
        title = "Allow camera access",
        message = "FloorPlan Scan uses the camera to track the phone's movement and measure your rooms. " +
            "The camera feed is processed on this device only.",
        onBack = onBack,
        primaryLabel = "Grant access",
        onPrimary = onGrant,
    )
}

/** Camera permission permanently denied: the system will not prompt again, so point to Settings. */
@Composable
internal fun CameraBlockedScreen(onOpenSettings: () -> Unit, onBack: () -> Unit) {
    ArMessageScreen(
        icon = Icons.Outlined.NoPhotography,
        title = "Camera access is turned off",
        message = "Room scanning needs the camera. Open Settings, tap Permissions, and allow Camera for " +
            "FloorPlan Scan. Then come back here.",
        onBack = onBack,
        primaryLabel = "Open settings",
        onPrimary = onOpenSettings,
    )
}

@Composable
private fun ArScreenFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                content()
            }
        }
    }
}
