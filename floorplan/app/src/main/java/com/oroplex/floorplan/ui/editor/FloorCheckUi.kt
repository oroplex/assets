@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled._3dRotation
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.FloorIssueAction
import com.oroplex.floorplan.core.solve.IssueSeverity
import kotlinx.coroutines.launch

/**
 * "Floor check" bottom sheet: every [FloorChecks][com.oroplex.floorplan.core.solve.FloorChecks] item of the
 * floor, most severe first. Tapping an item about a room closes the sheet and selects that room; an item with a
 * one-tap fix ([FloorIssue.action]) also gets a button that closes the sheet and runs [onAction].
 */
@Composable
fun FloorCheckSheet(
    floor: Floor,
    issues: List<FloorIssue>,
    onSelectRoom: (roomId: String) -> Unit,
    onDismiss: () -> Unit,
    onAction: (FloorIssue) -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val names = floor.rooms.associate { it.id to it.name }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Floor check", style = MaterialTheme.typography.titleLarge)
                Text(
                    checkSummary(floor, issues),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }
            if (issues.isEmpty()) {
                AllClear(floor)
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(issues) { index, issue ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                        val roomId = issue.roomId?.takeIf { it in names }
                        IssueRow(
                            issue = issue,
                            roomName = roomId?.let { names[it] },
                            onClick = roomId?.let { id ->
                                {
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        onSelectRoom(id)
                                        onDismiss()
                                    }
                                }
                            },
                            onAction = issue.action?.takeIf { it != FloorIssueAction.SNAP_ROOM || roomId != null }?.let {
                                {
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        onAction(issue)
                                        onDismiss()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun checkSummary(floor: Floor, issues: List<FloorIssue>): String {
    if (floor.rooms.isEmpty()) return "${floor.name} has no rooms yet."
    val attention = issues.count { it.severity != IssueSeverity.INFO }
    val tips = issues.size - attention
    return buildString {
        append(floor.name)
        append(" · ")
        when {
            issues.isEmpty() -> append("nothing to fix")
            attention == 0 -> append(if (tips == 1) "1 tip" else "$tips tips")
            else -> {
                append(if (attention == 1) "1 item to check" else "$attention items to check")
                if (tips > 0) append(if (tips == 1) ", 1 tip" else ", $tips tips")
            }
        }
    }
}

@Composable
private fun AllClear(floor: Floor) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = AllClearTint, modifier = Modifier.size(48.dp))
        Text(
            if (floor.rooms.isEmpty()) "Nothing to check yet" else "All clear",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (floor.rooms.isEmpty()) "Scan or draw a room first."
            else "No overlaps or measurement problems found. This floor is ready to export.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val AllClearTint = Color(0xFF1E8E3E)

/** Button label of a floor-check item's one-tap fix. */
internal fun FloorIssueAction.label(): String = when (this) {
    FloorIssueAction.STRAIGHTEN_FLOOR -> "Straighten"
    FloorIssueAction.SNAP_ROOM -> "Snap"
}

@Composable
private fun IssueRow(issue: FloorIssue, roomName: String?, onClick: (() -> Unit)?, onAction: (() -> Unit)?) {
    val (icon, tint, label) = severityStyle(issue.severity)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Show ${roomName ?: "room"}", role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            if (roomName != null) {
                Text(
                    roomName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(issue.message, style = MaterialTheme.typography.bodyMedium)
        }
        val action = issue.action
        if (onAction != null && action != null) {
            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier.padding(start = 8.dp).semantics {
                    contentDescription = action.label() + (roomName?.let { " $it" } ?: if (action == FloorIssueAction.STRAIGHTEN_FLOOR) " the floor" else "")
                },
            ) { Text(action.label()) }
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun severityStyle(severity: IssueSeverity): Triple<ImageVector, Color, String> = when (severity) {
    IssueSeverity.ERROR -> Triple(Icons.Filled.Error, MaterialTheme.colorScheme.error, "Problem")
    IssueSeverity.WARNING -> Triple(Icons.Filled.Warning, WarningTint, "Warning")
    IssueSeverity.INFO -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.primary, "Tip")
}

private val WarningTint = Color(0xFFE37400)

/**
 * Inline banner over the top of the plan while rooms overlap (usually a room from a later scan that has not
 * been moved into place yet). "Show" selects the room to fix, "Details" opens the floor check.
 */
@Composable
fun OverlapBanner(
    floor: Floor,
    overlaps: List<Pair<String, String>>,
    onShow: (roomId: String) -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = overlaps.firstOrNull() ?: return
    val names = floor.rooms.associate { it.id to it.name }
    val roomCount = overlaps.flatMap { listOf(it.first, it.second) }.distinct().size
    val title = if (overlaps.size == 1) {
        "${names[first.first] ?: "A room"} and ${names[first.second] ?: "another room"} overlap"
    } else {
        "$roomCount rooms overlap"
    }
    val toFix = roomToFix(floor, first)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Hide this warning") }
            }
            Text(
                "Usually a room from a later scan. Select it, turn it with Square to floor if it's at an angle, then " +
                    "drag it into place with Move or tap Snap to neighbour.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDetails) { Text("Details") }
                if (toFix != null) {
                    TextButton(onClick = { onShow(toFix) }) { Text("Show ${names[toFix] ?: "room"}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/**
 * The room of an overlapping pair the user most likely needs to move: the one added to the floor later
 * (later scans are appended), or null when neither is on the floor any more.
 */
internal fun roomToFix(floor: Floor, pair: Pair<String, String>): String? {
    val a = floor.rooms.indexOfFirst { it.id == pair.first }
    val b = floor.rooms.indexOfFirst { it.id == pair.second }
    return when {
        a < 0 && b < 0 -> null
        a < 0 -> pair.second
        b < 0 -> pair.first
        b >= a -> pair.second
        else -> pair.first
    }
}

/** Floating "zoom to fit" and "3D view" buttons in the top end corner of the plan. */
@Composable
fun CanvasControls(onFit: () -> Unit, on3d: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier) {
        FilledTonalIconButton(
            onClick = onFit,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Icon(Icons.Filled.FitScreen, contentDescription = "Zoom to fit")
        }
        if (on3d != null) {
            FilledTonalButton(onClick = on3d, modifier = Modifier.semantics { contentDescription = "3D view" }) {
                Icon(Icons.Filled._3dRotation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("3D")
            }
        }
    }
}
