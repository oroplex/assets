package com.oroplex.floorplan.ui.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.ar.ArFrameState
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
    fun useLastCeiling()
    fun setMethod(method: CaptureMethod)
    fun captureWall()
    fun startBaseline()
    fun cancelBaseline()
    fun markBaselinePoint()
    fun addCorner()
    fun startRecapture(index: Int)
    fun cancelRecapture()
    fun deleteCapture(index: Int)
    fun undo()
    fun closeRoom()
    fun backToWalls()
    fun setOpeningType(type: OpeningType)
    fun markOpeningEdge()
    fun deleteOpening(id: String)
    fun finishOpenings()
}

/** Floor detection progress shown during FIND_FLOOR (derived from the AR frames). */
internal enum class FloorStatus { SEARCHING, FOUND, CONFIDENT }

internal fun floorStatus(frame: ArFrameState): FloorStatus = when {
    frame.floorY?.isFinite() != true -> FloorStatus.SEARCHING
    frame.floorArea < CaptureViewModel.AUTO_LOCK_MIN_AREA -> FloorStatus.FOUND
    else -> FloorStatus.CONFIDENT
}

internal fun stepTitle(step: CaptureStep): String = when (step) {
    CaptureStep.FIND_FLOOR -> "Find the floor"
    CaptureStep.CEILING -> "Ceiling height"
    CaptureStep.WALLS -> "Capture walls"
    CaptureStep.CORNERS -> "Capture corners"
    CaptureStep.OPENINGS -> "Doors & windows"
    CaptureStep.REVIEW -> "Review room"
}

/** One short instruction for the current step: what to aim at and which button to tap. */
internal fun stepInstruction(ui: CaptureUiState, floorStatus: FloorStatus): String {
    val d = ui.draft
    return when (ui.step) {
        CaptureStep.FIND_FLOOR -> when {
            floorStatus == FloorStatus.SEARCHING -> "Point the phone at the floor and move it slowly."
            !ui.autoLockFloor -> "Aim at open floor, away from furniture, and tap Lock floor."
            floorStatus == FloorStatus.FOUND -> "Floor found. Keep moving slowly to confirm it."
            else -> "Floor found. Hold still for a moment, or tap Lock floor."
        }
        CaptureStep.CEILING ->
            "Aim straight up at a clear spot on the ceiling, or Skip to use ${formatLengthSafe(ui.settings.defaultCeilingHeight, ui.settings)} (est.)."
        CaptureStep.WALLS -> {
            val baseline = d.baseline
            val recapture = d.recaptureIndex?.takeIf { it in d.observations.indices }
            val n = (recapture ?: d.observations.size) + 1
            when {
                baseline != null && baseline.firstTap == null ->
                    "Aim where wall $n meets the floor, near one end, and tap Mark."
                baseline != null -> "Now aim at the base of the same wall near its other end and tap Mark."
                recapture != null -> "Aim at wall $n again, above furniture, and tap Re-capture."
                d.observations.isEmpty() ->
                    "Aim at wall 1 above furniture and hold still while the ring fills. Then go around the room in order."
                d.observations.size >= 3 -> "Aim at wall $n, or tap Close room when every wall is captured."
                else -> "Aim at wall $n above furniture and tap Capture wall."
            }
        }
        CaptureStep.CORNERS -> {
            val recapture = d.recaptureIndex?.takeIf { it in d.cornerTaps.indices }
            val n = (recapture ?: d.cornerTaps.size) + 1
            when {
                recapture != null -> "Aim at corner $n where the walls meet the floor and tap Re-capture."
                d.cornerTaps.size >= 3 -> "Aim at corner $n where the walls meet the floor, or tap Close room when done."
                else -> "Aim at corner $n where the walls meet the floor and tap Add corner. Go around in order."
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

/** True when the Undo button of the current step has something to undo (OPENINGS always: it can reopen). */
internal fun canUndo(ui: CaptureUiState): Boolean {
    val d = ui.draft
    return when (ui.step) {
        CaptureStep.WALLS -> d.baseline?.firstTap != null || d.recaptureIndex != null || d.history.isNotEmpty()
        CaptureStep.CORNERS -> d.recaptureIndex != null || d.history.isNotEmpty()
        CaptureStep.OPENINGS -> true
        else -> false
    }
}

private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

/**
 * Bottom panel for every step but REVIEW: title, instruction, progress, step options (method switch,
 * wall / corner list, opening types and list, warnings), the primary action and secondary actions.
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
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .animateContentSize(),
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
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                ui.measuring -> BusyRow("Hold steady…")
                ui.saving -> BusyRow("Saving…")
            }

            // Method switch: only before the first capture of the room.
            if (ui.step.isCaptureStep && d.captureCount == 0 && d.baseline == null) {
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

            if (ui.step.isCaptureStep) {
                CaptureList(ui, actions, enabled)
                if (d.captureCount >= 3 && d.solve == null) {
                    val noun = if (d.method == CaptureMethod.CORNERS) "corners" else "walls"
                    NoteLine(
                        "These $noun don't close into a room yet. Capture the missing ones in order, or delete the odd one out.",
                        isWarning = true,
                    )
                }
                if (d.parkedOpenings.isNotEmpty()) {
                    val n = d.parkedOpenings.size
                    NoteLine("Your ${plural(n, "door or window", "doors and windows")} will be put back when you close the room.")
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
            d.baseline == null && d.recaptureIndex != null -> "Re-capture wall ${d.recaptureIndex + 1}" to actions::captureWall
            d.baseline == null -> "Capture wall" to actions::captureWall
            d.baseline.firstTap == null -> "Mark wall base (1 of 2)" to actions::markBaselinePoint
            else -> "Mark wall base (2 of 2)" to actions::markBaselinePoint
        }
        CaptureStep.CORNERS ->
            (if (d.recaptureIndex != null) "Re-capture corner ${d.recaptureIndex + 1}" else "Add corner") to actions::addCorner
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
                ui.lastMeasuredCeiling?.let { h ->
                    OutlinedButton(onClick = actions::useLastCeiling, enabled = enabled) {
                        Text("Same as last room (${formatLengthSafe(h, ui.settings)})")
                    }
                }
                if (ui.canRelockFloor) TextButton(onClick = actions::relockFloor, enabled = enabled) { Text("Re-lock floor") }
            }
            CaptureStep.WALLS -> {
                UndoButton("Undo", canUndo(ui) && enabled, actions::undo)
                if (d.baseline != null) {
                    OutlinedButton(onClick = actions::cancelBaseline, enabled = enabled) { Text("Cancel") }
                } else if (!d.offerBaseline) {
                    OutlinedButton(onClick = actions::startBaseline, enabled = enabled) { Text("Tap wall base") }
                }
                CloseRoomButton(enabled, actions::closeRoom)
            }
            CaptureStep.CORNERS -> {
                UndoButton("Undo", canUndo(ui) && enabled, actions::undo)
                CloseRoomButton(enabled, actions::closeRoom)
            }
            CaptureStep.OPENINGS -> {
                val hasSomething = d.pendingOpeningEdge != null || d.openings.isNotEmpty()
                UndoButton(if (hasSomething) "Undo" else "Reopen room", enabled, actions::undo)
                if (hasSomething) {
                    TextButton(onClick = actions::backToWalls, enabled = enabled) {
                        Text(if (d.method == CaptureMethod.CORNERS) "Edit corners" else "Edit walls")
                    }
                }
                FilledTonalButton(onClick = actions::finishOpenings, enabled = enabled) { Text("Done") }
            }
        }
    }
}

/**
 * The captured walls (or corners) as numbered chips, each wall marked with its quality color. Tapping one
 * opens a small menu to re-capture it in place or delete it.
 */
@Composable
private fun CaptureList(ui: CaptureUiState, actions: CaptureActions, enabled: Boolean) {
    val d = ui.draft
    val corners = d.method == CaptureMethod.CORNERS
    val count = d.captureCount
    if (count == 0) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (corners) "Corners" else "Walls",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (i in 0 until count) {
            val n = i + 1
            val observation = if (corners) null else d.observations[i]
            val grade = observation?.let(::wallGrade)
            val title = if (corners) "Corner $n" else "Wall $n"
            val detail = observation?.let { wallQualityText(it, ui.settings) } ?: "Marked on the floor where the walls meet."
            CaptureChip(
                label = "$n",
                description = if (grade != null) "$title, ${gradeHeadline(grade).lowercase()}" else title,
                grade = grade,
                selected = d.recaptureIndex == i,
                enabled = enabled,
                menuTitle = title,
                menuDetail = detail,
                onRecapture = { actions.startRecapture(i) },
                onDelete = { actions.deleteCapture(i) },
            )
        }
        if (d.recaptureIndex != null) {
            TextButton(onClick = actions::cancelRecapture, enabled = enabled) { Text("Cancel re-capture") }
        }
    }
}

@Composable
private fun CaptureChip(
    label: String,
    description: String,
    grade: CaptureGrade?,
    selected: Boolean,
    enabled: Boolean,
    menuTitle: String,
    menuDetail: String,
    onRecapture: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            enabled = enabled,
            label = { Text(label, fontWeight = FontWeight.SemiBold) },
            leadingIcon = grade?.let { g -> { GradeDot(g) } },
            modifier = Modifier.semantics { contentDescription = "$description. Tap to re-capture or delete." },
        )
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (grade != null) {
                        GradeDot(grade)
                        Text(" ", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(menuTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(menuDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (selected) "Cancel re-capture" else "Re-capture") },
                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                onClick = {
                    open = false
                    onRecapture()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** Chip / text color of a quality grade (darker than the overlay colors, for white surfaces). */
internal fun gradeUiColor(grade: CaptureGrade): Color = when (grade) {
    CaptureGrade.GOOD -> Color(0xFF1E8E3E)
    CaptureGrade.FAIR -> Color(0xFFF29900)
    CaptureGrade.POOR -> Color(0xFFD93025)
}

@Composable
private fun GradeDot(grade: CaptureGrade) {
    Box(Modifier.size(10.dp).background(gradeUiColor(grade), CircleShape))
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

/** A small icon + text line (info, or a warning in the error color). */
@Composable
internal fun NoteLine(text: String, isWarning: Boolean = false) {
    val color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (isWarning) Icons.Filled.WarningAmber else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Solver / quality warnings as compact lines with a warning icon. */
@Composable
internal fun WarningLines(warnings: List<String>, maxShown: Int = Int.MAX_VALUE) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        warnings.take(maxShown).forEach { NoteLine(it, isWarning = true) }
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
    tracking == TrackingStatus.NOT_STARTED -> TrackingProblem.INITIALIZING.message
    else -> null
}

private fun coachIcon(hint: CoachingHint): ImageVector = when (hint.kind) {
    CoachKind.TRACKING -> if (hint.tone == CoachTone.CRITICAL) Icons.Filled.WarningAmber else Icons.Outlined.Info
    CoachKind.HOLD_STILL -> Icons.Filled.PanTool
    CoachKind.TOO_FAST -> Icons.Filled.Speed
    CoachKind.LOWER_FLOOR -> Icons.Filled.Layers
    CoachKind.TOO_FAR -> Icons.AutoMirrored.Filled.DirectionsWalk
    CoachKind.TOO_CLOSE -> Icons.Filled.ZoomOut
    CoachKind.AIM_HIGHER, CoachKind.AIM_UP -> Icons.Filled.ArrowUpward
    CoachKind.AIM_LOWER -> Icons.Filled.ArrowDownward
    CoachKind.NO_SURFACE -> Icons.Filled.CenterFocusWeak
    CoachKind.FLOOR_SEARCH -> Icons.Filled.Search
}

/**
 * Live coaching banner over the camera (one hint at a time, see [CaptureCoach]): red for problems that
 * block accurate capture, amber for cautions, dark for advice. Fades in and out; keeps the last hint's
 * text while fading out.
 */
@Composable
internal fun CoachingBanner(hint: CoachingHint?, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(hint) }
    LaunchedEffect(hint) { if (hint != null) last = hint }
    AnimatedVisibility(
        visible = hint != null,
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val h = hint ?: last ?: return@AnimatedVisibility
        val (background, content) = when (h.tone) {
            CoachTone.CRITICAL -> Color(0xE6B3261E) to Color.White
            CoachTone.WARNING -> Color(0xF2FFB300) to Color(0xFF1F1F1F)
            CoachTone.INFO -> Color(0xD91F1F1F) to Color.White
        }
        Surface(shape = RoundedCornerShape(12.dp), color = background, contentColor = content) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(coachIcon(h), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(h.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Round translucent button for the top corners of the camera view. */
@Composable
private fun CameraIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x99000000), contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
internal fun CloseCaptureButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    CameraIconButton(Icons.Filled.Close, "Close scan", onClick, modifier)

@Composable
internal fun HelpButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    CameraIconButton(Icons.AutoMirrored.Outlined.HelpOutline, "How to scan", onClick, modifier)

/** Fixed width used for the mini-map card; the top-left column leaves room for it. */
internal val MiniMapSize = 150.dp
