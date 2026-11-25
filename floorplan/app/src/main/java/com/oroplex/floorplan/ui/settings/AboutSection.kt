package com.oroplex.floorplan.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.google.ar.core.ArCoreApk
import com.oroplex.floorplan.diagnostics.ScanLog
import com.oroplex.floorplan.export.ExportFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AboutSection"

@Composable
internal fun AboutSection() {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val arStatus = rememberArCoreStatus()
    val scope = rememberCoroutineScope()
    var shareFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
            headlineContent = { Text("FloorPlan Scan") },
            supportingContent = { Text("Version $version") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ListItem(
            leadingContent = { Icon(Icons.Outlined.ViewInAr, contentDescription = null) },
            headlineContent = { Text("ARCore") },
            supportingContent = { Text(arStatus) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        ListItem(
            leadingContent = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
            headlineContent = { Text("Share scan log") },
            supportingContent = {
                Text(
                    if (shareFailed) {
                        "Couldn't prepare the log. Try again."
                    } else {
                        "What the scanner measured in recent scans, and details of any crash. No photos or " +
                            "location. Send it along when something goes wrong."
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Share scan log") {
                scope.launch {
                    shareFailed = !shareScanLog(context, "FloorPlan Scan $version, ARCore: $arStatus")
                }
            },
        )
        Text(
            "Measurements come from your phone's AR tracking and are approximate (typically within " +
                "1–3 % with careful scanning). Verify critical dimensions with a tape measure.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Writes the scan log (with a header naming the app version and the phone) to the shared-exports folder and
 * opens the system share sheet. False when the file could not be written or nothing can receive it.
 */
private suspend fun shareScanLog(context: Context, about: String): Boolean = try {
    val header = "$about\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val file = withContext(Dispatchers.IO) {
        ExportFiles.write(context, "FloorPlanScan-log.txt") { out -> ScanLog.export(out, header) }
    }
    context.startActivity(ExportFiles.shareIntent(context, file, "text/plain", "FloorPlan Scan log"))
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Sharing the scan log failed", e)
    false
}

private fun appVersion(context: Context): String = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    "${info.versionName ?: "unknown"} (build ${PackageInfoCompat.getLongVersionCode(info)})"
} catch (e: PackageManager.NameNotFoundException) {
    "unknown"
}

/**
 * ARCore availability as a sentence. `checkAvailability` answers UNKNOWN_CHECKING while it queries
 * Play Services for AR in the background, so the check is repeated briefly until the answer is final.
 */
@Composable
private fun rememberArCoreStatus(): String {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Checking…") }
    LaunchedEffect(Unit) {
        repeat(MAX_CHECKS) {
            val availability = try {
                ArCoreApk.getInstance().checkAvailability(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ARCore availability check failed", e)
                null
            }
            if (availability == null) {
                status = "Couldn't check ARCore support on this device."
                return@LaunchedEffect
            }
            if (!availability.isTransient) {
                status = describe(availability)
                return@LaunchedEffect
            }
            delay(CHECK_INTERVAL_MS)
        }
        status = "Still checking ARCore support. Reopen Settings to try again."
    }
    return status
}

private const val MAX_CHECKS = 25
private const val CHECK_INTERVAL_MS = 200L

private fun describe(availability: ArCoreApk.Availability): String = when (availability) {
    ArCoreApk.Availability.SUPPORTED_INSTALLED ->
        "Supported and installed. This device is ready to scan."
    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
        "Supported. Google Play Services for AR needs an update; you'll be asked when you start a scan."
    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
        "Supported. Google Play Services for AR will be installed when you start your first scan."
    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
        "Not supported on this device. You can still draw rooms and type dimensions in the editor."
    ArCoreApk.Availability.UNKNOWN_TIMED_OUT ->
        "Couldn't confirm ARCore support (timed out). Check your internet connection."
    ArCoreApk.Availability.UNKNOWN_CHECKING ->
        "Checking…"
    ArCoreApk.Availability.UNKNOWN_ERROR ->
        "Couldn't confirm ARCore support on this device."
}
