package com.oroplex.floorplan.ui.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.OpeningType

/** User actions of the capture controls; bound to the ViewModel + live AR controller by the screen. */
internal interface CaptureActions {
    fun lockFloor()
    fun relockFloor()
    fun measureCeiling()
    fun skipCeiling()
    fun setMethod(method: CaptureMethod)
    fun captureWall()
    fun startBaseline()
    fun cancelBaseline()
    fun markBaselinePoint()
    fun addCorner()
    fun undo()
    fun closeRoom()
    fun setOpeningType(type: OpeningType)
    fun markOpeningEdge()
    fun deleteOpening(id: String)
    fun finishOpenings()
}

/** Floor detection progress shown during FIND_FLOOR (derived from the AR frames). */
internal enum class FloorStatus { SEARCHING, FOUND, CONFIDENT }

internal fun stepTitle(step: CaptureStep): String = when (step) {
    CaptureStep.FIND_FLOOR -> "Find the floor"
    CaptureStep.CEILING -> "Ceiling height"
    CaptureStep.WALLS -> "Capture walls"
    CaptureStep.CORNERS -> "Capture corners"
    CaptureStep.OPENINGS -> "Doors & windows"
    CaptureStep.REVIEW -> "Review room"
}

/** One short instruction for the current step. */
internal fun stepInstruction(ui: CaptureUiState, floorStatus: FloorStatus): String {
    val d = ui.draft
    return when (ui.step) {
        CaptureStep.FIND_FLOOR -> when (floorStatus) {
            FloorStatus.SEARCHING -> "Point at the floor and slowly move the phone."
            FloorStatus.FOUND -> "Floor found. Keep moving slowly to confirm it."
            FloorStatus.CONFIDENT -> "Floor found. Hold still for a moment, or tap Lock floor."
        }
        CaptureStep.CEILING ->
            "Aim straight up at a clear spot on the ceiling, or Skip to use ${formatLengthSafe(ui.settings.defaultCeilingHeight, ui.settings)}."
        CaptureStep.WALLS -> {
            val baseline = d.baseline
            val n = d.observations.size + 1
            when {
                baseline?.firstTap == null && baseline != null ->
                    "Aim where wall $n meets the floor, near one end, and tap Mark."
                baseline != null -> "Now aim at the base of the same wall near its other end and tap Mark."
                d.observations.isEmpty() ->
                    "Aim at wall 1 above furniture, hold steady, tap Capture wall. Then go around the room in order."
                d.observations.size >= 3 -> "Aim at wall $n and tap Capture wall, or Close room if all walls are done."
                else -> "Aim at wall $n above furniture, hold steady, tap Capture wall."
            }
        }
        CaptureStep.CORNERS -> {
            val n = d.cornerTaps.size + 1
            if (d.cornerTaps.size >= 3) {
                "Aim at corner $n where the walls meet the floor, or Close room if all corners are done."
            } else {
                "Aim at corner $n where the walls meet the floor, tap Add corner. Go around in order."
            }
        }
        CaptureStep.OPENINGS -> {
            val waitingForRight = d.pendingOpeningEdge != null
            when (d.openingType) {
                OpeningType.WINDOW ->
                    if (waitingForRight) "Now aim at the right side of the window frame." else "Aim at the left side of a window frame and tap Mark left edge."
                OpeningType.DOOR ->
                    if (waitingForRight) "Now aim at the floor at the door's right edge." else "Aim at the floor at a door's left edge. Tap Done when finished."
                OpeningType.OPENING ->
                    if (waitingForRight) "Now aim at the floor at the opening's right edge." else "Aim at the floor at an opening's left edge. Tap Done when finished."
            }
        }
        CaptureStep.REVIEW -> "Check the room, name it and save."
    }
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/**
 * Bottom panel for every step but REVIEW: title, instruction, progress, step options (method switch,
 * opening types and list, warnings), the primary action and secondary actions.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureControls(
    ui: CaptureUiState,
    floorStatus: FloorStatus,
    actions: CaptureActions,
    modifier: Modifier = Modifier,
) {
    val d = ui.draft
    val enabled = !ui.busy && !ui.finished
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stepTitle(ui.step),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val counter = when (ui.step) {
                    CaptureStep.WALLS -> plural(d.observations.size, "wall", "walls")
                    CaptureStep.CORNERS -> plural(d.cornerTaps.size, "corner", "corners")
                    CaptureStep.OPENINGS -> plural(d.openings.size, "opening", "openings")
                    else -> null
                }
                if (counter != null) CounterBadge(counter)
            }
            Text(
                text = stepInstruction(ui, floorStatus),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                ui.measuring -> BusyRow("Hold steady…")
                ui.saving -> BusyRow("Saving…")
            }

            // Method switch: only before the first capture of the room.
            if ((ui.step == CaptureStep.WALLS || ui.step == CaptureStep.CORNERS) && d.captureCount == 0 && d.baseline == null) {
                val methods = listOf(CaptureMethod.WALLS to "Walls", CaptureMethod.CORNERS to "Corners")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    methods.forEachIndexed { index, (method, label) ->
                        SegmentedButton(
                            selected = d.method == method,
                            onClick = { actions.setMethod(method) },
                            shape = SegmentedButtonDefaults.itemShape(index, methods.size),
                            enabled = enabled,
                        ) { Text(label) }
                    }
                }
            }

            if (ui.step == CaptureStep.OPENINGS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpeningType.entries.forEach { type ->
                        FilterChip(
                            selected = d.openingType == type,
                            onClick = { actions.setOpeningType(type) },
                            label = { Text(openingLabel(type)) },
                            enabled = enabled,
                        )
                    }
                }
                if (d.openings.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.openings.forEach { o ->
                            InputChip(
                                selected = false,
                                onClick = { actions.deleteOpening(o.id) },
                                label = { Text("${openingLabel(o.type)} ${formatLengthSafe(o.width, ui.settings)}") },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                                enabled = enabled,
                            )
                        }
                    }
                }
                d.solve?.warnings?.takeIf { it.isNotEmpty() }?.let { WarningLines(it, maxShown = 2) }
            }

            if (ui.step == CaptureStep.WALLS && d.offerBaseline && d.baseline == null) {
                FilledTonalButton(onClick = actions::startBaseline, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Tap the wall base instead")
                }
            }

            primaryAction(ui, actions)?.let { (label, onClick) ->
                Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
            }
            SecondaryActions(ui, actions, enabled)
        }
    }
}

private fun primaryAction(ui: CaptureUiState, actions: CaptureActions): Pair<String, () -> Unit>? {
    val d = ui.draft
    return when (ui.step) {
        CaptureStep.FIND_FLOOR -> "Lock floor" to actions::lockFloor
        CaptureStep.CEILING -> "Measure ceiling" to actions::measureCeiling
        CaptureStep.WALLS -> when {
            d.baseline == null -> "Capture wall" to actions::captureWall
            d.baseline.firstTap == null -> "Mark wall base (1 of 2)" to actions::markBaselinePoint
            else -> "Mark wall base (2 of 2)" to actions::markBaselinePoint
        }
        CaptureStep.CORNERS -> "Add corner" to actions::addCorner
        CaptureStep.OPENINGS ->
            (if (d.pendingOpeningEdge == null) "Mark left edge" else "Mark right edge") to actions::markOpeningEdge
        CaptureStep.REVIEW -> null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryActions(ui: CaptureUiState, actions: CaptureActions, enabled: Boolean) {
    val d = ui.draft
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (ui.step) {
            CaptureStep.FIND_FLOOR, CaptureStep.REVIEW -> Unit
            CaptureStep.CEILING -> {
                OutlinedButton(onClick = actions::skipCeiling, enabled = enabled) { Text("Skip") }
                if (!d.hasCaptures) TextButton(onClick = actions::relockFloor, enabled = enabled) { Text("Re-lock floor") }
            }
            CaptureStep.WALLS -> {
                val canUndo = d.observations.isNotEmpty() || d.baseline?.firstTap != null
                UndoButton("Undo", canUndo && enabled, actions::undo)
                if (d.baseline != null) {
                    OutlinedButton(onClick = actions::cancelBaseline, enabled = enabled) { Text("Cancel") }
                } else if (!d.offerBaseline) {
                    OutlinedButton(onClick = actions::startBaseline, enabled = enabled) { Text("Tap wall base") }
                }
                CloseRoomButton(d.observations.size >= 3 && enabled, actions::closeRoom)
            }
            CaptureStep.CORNERS -> {
                UndoButton("Undo", d.cornerTaps.isNotEmpty() && enabled, actions::undo)
                CloseRoomButton(d.cornerTaps.size >= 3 && enabled, actions::closeRoom)
            }
            CaptureStep.OPENINGS -> {
                val hasSomething = d.pendingOpeningEdge != null || d.openings.isNotEmpty()
                UndoButton(if (hasSomething) "Undo" else "Reopen room", enabled, actions::undo)
                FilledTonalButton(onClick = actions::finishOpenings, enabled = enabled) { Text("Done") }
            }
        }
    }
}

@Composable
private fun UndoButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun CloseRoomButton(enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) { Text("Close room") }
}

@Composable
private fun CounterBadge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun BusyRow(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
        }
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Solver / quality warnings as compact lines with a warning icon. */
@Composable
internal fun WarningLines(warnings: List<String>, maxShown: Int = Int.MAX_VALUE) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        warnings.take(maxShown).forEach { w ->
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Text(
                    w,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (warnings.size > maxShown) {
            Text(
                "+${warnings.size - maxShown} more in the review",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Text of the tracking banner, or null when tracking is fine. */
internal fun trackingBannerText(tracking: TrackingStatus, problem: TrackingProblem): String? = when {
    problem != TrackingProblem.NONE -> problem.message.ifBlank { "AR tracking is limited. Move the phone slowly." }
    tracking == TrackingStatus.PAUSED || tracking == TrackingStatus.STOPPED -> "Tracking paused. Hold the phone steady and move slowly."
    else -> null
}

@Composable
internal fun TrackingBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xE6B3261E),
        contentColor = Color.White,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Round translucent close button for the top-left corner of the camera view. */
@Composable
internal fun CloseCaptureButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x99000000), contentColor = Color.White),
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Close scan")
    }
}

/** Fixed width used for the mini-map card; the top-left column leaves room for it. */
internal val MiniMapSize = 150.dp
