package com.oroplex.floorplan.ui.export

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
 */
@Composable
internal fun PreExportCard(
    checks: ChecksState,
    acknowledged: Boolean,
    onExportAnyway: () -> Unit,
    onBackToEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (checks) {
        ChecksState.NotApplicable -> return
        ChecksState.Running -> CheckingRow(modifier)
        is ChecksState.Done -> {
            val report = checks.report
            if (report.isClean) AllClearRow(modifier) else IssuesCard(report, acknowledged, onExportAnyway, onBackToEditor, modifier)
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
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Text(
                "Before you export",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                PreExportChecks.summary(report),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 6.dp))
            val shown = if (expanded) report.items else report.items.take(COLLAPSED_ITEMS)
            shown.forEach { item -> IssueRow(item) }
            if (report.items.size > COLLAPSED_ITEMS) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Show all ${report.items.size}")
                }
            }
            if (report.hasErrors) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (needsDecision) {
                    Text(
                        "Fix these in the editor for an accurate plan, or export anyway.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onBackToEditor) { Text("Back to editor") }
                        OutlinedButton(onClick = onExportAnyway) { Text("Export anyway") }
                    }
                } else {
                    Text(
                        "You chose to export anyway. The file will include these problems.",
                        modifier = Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun IssueRow(item: CheckItem) {
    val (icon, tint, label) = severityStyle(item.severity)
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
    }
}

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
