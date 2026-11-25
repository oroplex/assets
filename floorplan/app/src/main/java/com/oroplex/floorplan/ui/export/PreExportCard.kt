package com.oroplex.floorplan.ui.export

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.solve.IssueSeverity
import com.oroplex.floorplan.ui.theme.extendedColors

/** Items shown before "Show all". */
private const val COLLAPSED_ITEMS = 4

/**
 * "Before you export": the pre-flight checks for the floors being exported. Errors (e.g. overlapping
 * rooms) outline the card in the error color and need an explicit "Export anyway" before Share / Save
 * go ahead; warnings and notes are informational.
 *
 * Nothing here changes the plan. A row about a room is a button that shows that room in the editor
 * ([onOpenRoom]); a floor-wide row with a fix (straighten the floor) goes back to the editor
 * ([onBackToEditor]), where the floor check offers the fix. See [CheckItem.target].
 */
@Composable
internal fun PreExportCard(
    checks: ChecksState,
    acknowledged: Boolean,
    onExportAnyway: () -> Unit,
    onBackToEditor: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenRoom: (roomId: String) -> Unit = {},
) {
    when (checks) {
        ChecksState.NotApplicable -> return
        ChecksState.Running -> CheckingRow(modifier)
        is ChecksState.Done -> {
            val report = checks.report
            if (report.isClean) {
                AllClearRow(modifier)
            } else {
                IssuesCard(report, acknowledged, onExportAnyway, onBackToEditor, onOpenRoom, modifier)
            }
        }
    }
}

@Composable
private fun CheckingRow(modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Checking the plan…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AllClearRow(modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.extendedColors.success,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            PreExportChecks.summary(PreExportReport.EMPTY),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IssuesCard(
    report: PreExportReport,
    acknowledged: Boolean,
    onExportAnyway: () -> Unit,
    onBackToEditor: () -> Unit,
    onOpenRoom: (roomId: String) -> Unit,
    modifier: Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val needsDecision = report.hasErrors && !acknowledged
    val border = if (needsDecision) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    OutlinedCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        border = border,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // Rows span the card so a tappable row highlights edge to edge; everything else is inset.
        val inset = Modifier.padding(horizontal = CARD_INSET)
        Column(Modifier.padding(top = 14.dp, bottom = 8.dp)) {
            Text(
                "Before you export",
                style = MaterialTheme.typography.titleSmall,
                modifier = inset.semantics { heading() },
            )
            Text(
                PreExportChecks.summary(report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = inset,
            )
            Spacer(Modifier.padding(top = 6.dp))
            val shown = if (expanded) report.items else report.items.take(COLLAPSED_ITEMS)
            shown.forEach { item -> IssueRow(item, onOpenRoom, onBackToEditor) }
            if (report.items.size > COLLAPSED_ITEMS) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text(if (expanded) "Show less" else "Show all ${report.items.size}")
                }
            }
            if (report.hasErrors) {
                HorizontalDivider(inset.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (needsDecision) {
                    Text(
                        "Fix these in the editor for an accurate plan, or export anyway.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = inset,
                    )
                    Row(
                        inset.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Lands on the first problem's room when there is one, not just on the last view.
                        val firstRoom = report.firstErrorRoomId
                        TextButton(onClick = { if (firstRoom != null) onOpenRoom(firstRoom) else onBackToEditor() }) {
                            Text("Back to editor")
                        }
                        OutlinedButton(onClick = onExportAnyway) { Text("Export anyway") }
                    }
                } else {
                    Text(
                        "You chose to export anyway. The file will include these problems.",
                        modifier = inset.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One check result. A row that leads somewhere ([CheckItem.target]) is a 48 dp button with a chevron:
 * "Show in editor" for a room, "Back to editor" for a floor-wide fix. Other rows are plain text.
 */
@Composable
private fun IssueRow(item: CheckItem, onOpenRoom: (roomId: String) -> Unit, onBackToEditor: () -> Unit) {
    val (icon, tint, label) = severityStyle(item.severity)
    val target = item.target
    val tappable = target != CheckTarget.None
    val rowModifier = when (target) {
        is CheckTarget.Room -> Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Show in editor") { onOpenRoom(target.roomId) }
        CheckTarget.Editor -> Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Back to editor", onClick = onBackToEditor)
        CheckTarget.None -> Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .padding(horizontal = CARD_INSET, vertical = 5.dp),
        verticalAlignment = if (tappable) Alignment.CenterVertically else Alignment.Top,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (tappable) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Horizontal inset of the card's content. */
private val CARD_INSET = 16.dp

@Composable
private fun severityStyle(severity: IssueSeverity): Triple<ImageVector, Color, String> = when (severity) {
    IssueSeverity.ERROR -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "Problem")
    IssueSeverity.WARNING -> Triple(Icons.Outlined.WarningAmber, MaterialTheme.extendedColors.warning, "Warning")
    IssueSeverity.INFO -> Triple(Icons.Outlined.Info, MaterialTheme.colorScheme.onSurfaceVariant, "Note")
}

/** "Export anyway?" asked when Share / Save is tapped while errors are unacknowledged. */
@Composable
internal fun ExportAnywayDialog(confirmation: ExportConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = confirmation.errors.size
    val verb = if (confirmation.action == ExportAction.SHARE) "sharing" else "saving"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (count == 1) "1 problem found" else "$count problems found") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("These can make the plan wrong. Check them before $verb:")
                confirmation.errors.take(3).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                if (count > 3) Text("…and ${count - 3} more", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Export anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
