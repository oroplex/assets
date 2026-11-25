package com.oroplex.floorplan.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.core.solve.QualityLevel
import com.oroplex.floorplan.render.PlanPainter

/** Review-step actions. */
internal interface ReviewActions {
    fun setName(name: String)
    fun setType(type: RoomType)
    fun saveAndScanNext()
    fun saveAndFinish()
    fun redoRoom()
    fun backToWalls()
    fun backToOpenings()
}

/**
 * REVIEW step: a plan preview of the room, its quality grade and reasons, stats (L × W, area, perimeter,
 * ceiling, openings) and warnings, name and type, the save actions with what they mean, and the ways back
 * (walls, doors & windows, redo). Scrolls within ~62 % of the screen height so the camera stays partly
 * visible and the keyboard fits.
 *
 * [room] is the draft exactly as it will be saved (CaptureViewModel.roomAsSaved: scaled to the floor's tape
 * calibration, if any), so every size shown here is the size that ends up in the plan. Quality reasons number
 * walls and corners by capture, like the wall chips.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReviewPanel(
    ui: CaptureUiState,
    room: Room?,
    actions: ReviewActions,
    modifier: Modifier = Modifier,
) {
    val d = ui.draft
    val enabled = !ui.busy && !ui.finished
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    val focusManager = LocalFocusManager.current
    val units = ui.settings.unitSystem
    val quality = remember(room, units) { room?.let { reviewQuality(safeAssess(it, units)) } }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        // The details scroll; the two Save buttons stay pinned below them, always on screen: saving is what
        // the user does at the end of every room, often one-handed on a job site.
        Column(modifier = Modifier.navigationBarsPadding().heightIn(max = maxHeight)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Review room",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                quality?.let { QualityBadge(it) }
            }
            if (room == null) {
                Text(
                    "This room has no valid outline. Tap Back to walls to fix it, or Redo room.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                RoomPreview(room, ui.settings, Modifier.fillMaxWidth().height(170.dp))
                val stats = remember(room, ui.settings) { reviewStats(room, ui.settings) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.size?.let { StatRow("Size (L × W)", it) }
                    StatRow("Area", stats.area)
                    StatRow("Perimeter", stats.perimeter)
                    StatRow("Ceiling height", stats.ceiling)
                    StatRow("Doors & windows", if (room.openings.isEmpty()) "None" else room.openings.size.toString())
                }
                quality?.takeIf { it.reasons.isNotEmpty() }?.let { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        q.reasons.forEach { NoteLine(it, isWarning = q.level == QualityLevel.POOR) }
                    }
                }
                // Solver warnings the quality reasons don't already say.
                val warnings = room.warnings.filter { w -> quality?.reasons?.none { it.contains(w, ignoreCase = true) } != false }
                if (warnings.isNotEmpty()) WarningLines(warnings)
            }
            calibrationNote(ui.floorCalibration)?.let { NoteLine(it) }
            if (ui.floorCalibration == null && ui.sessionRoomIds.isEmpty()) {
                NoteLine("Tip: tape-measure one long wall later and calibrate the floor in the editor for the best accuracy.")
            }
            if (ui.otherSessionRoomCount > 0) {
                NoteLine("Rooms from other scan sessions may need to be moved into place in the editor.")
            }

            // Local text state so fast typing never races the ViewModel's async state emissions. The only
            // ViewModel-side rename (picking a type while the name isn't user-edited) is adopted here.
            var nameText by remember(d.id) { mutableStateOf(d.name) }
            LaunchedEffect(d.type) { if (!d.nameEdited) nameText = d.name }
            OutlinedTextField(
                value = nameText,
                onValueChange = {
                    val text = it.take(CaptureViewModel.MAX_NAME_LENGTH)
                    nameText = text
                    actions.setName(text)
                },
                label = { Text("Room name") },
                placeholder = { Text(d.type.displayName) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            RoomTypeDropdown(d.type, enabled, actions::setType)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val wallsLabel = if (d.method == CaptureMethod.CORNERS) "Back to corners" else "Back to walls"
                EditButton(wallsLabel, Icons.AutoMirrored.Filled.ArrowBack, enabled, actions::backToWalls)
                EditButton("Doors & windows", Icons.Outlined.DoorFront, enabled, actions::backToOpenings)
                EditButton("Redo room", Icons.Outlined.Replay, enabled, actions::redoRoom)
            }
            Text(
                "Scan every room of this floor before you finish: rooms scanned later won't line up automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Saving…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
            }
            val canSave = enabled && room != null && ui.loadError == null
            Button(
                onClick = { focusManager.clearFocus(); actions.saveAndScanNext() },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save & scan next room") }
            OutlinedButton(
                onClick = { focusManager.clearFocus(); actions.saveAndFinish() },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("Save & finish") }
        }
        }
    }
}

@Composable
private fun EditButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Text(label, Modifier.padding(start = 6.dp))
    }
}

/** Colored pill with the room's quality grade ("Good", "Fair", "Needs a check"). */
@Composable
private fun QualityBadge(q: ReviewQuality) {
    val (color, icon) = when (q.level) {
        QualityLevel.GOOD -> gradeUiColor(CaptureGrade.GOOD) to Icons.Filled.CheckCircle
        QualityLevel.FAIR -> gradeUiColor(CaptureGrade.FAIR) to Icons.Filled.WarningAmber
        QualityLevel.POOR -> gradeUiColor(CaptureGrade.POOR) to Icons.Filled.ErrorOutline
        QualityLevel.MANUAL -> MaterialTheme.colorScheme.secondary to Icons.Outlined.EditNote
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f), contentColor = color) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                q.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomTypeDropdown(selected: RoomType, enabled: Boolean, onSelect: (RoomType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Room type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            RoomType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** The room drawn exactly as it will appear on the plan (same renderer and painter as the editor). */
@Composable
private fun RoomPreview(room: Room, settings: PlanSettings, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val pxPerPt = density * 160.0 / 72.0 * 0.7
    val drawList: DrawList? = remember(room, settings) {
        try {
            // No overall dimension lines: measured to the outside of assumed wall bands, they would sit next
            // to the interior wall lengths and the "Size" row as a second, conflicting size.
            PlanRenderer.render(
                listOf(room),
                settings,
                RenderOptions(showRoomNames = false, showWallLengths = true, showOverallDimensions = false),
            )
        } catch (e: Exception) {
            null
        }
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color(PlanColors.BACKGROUND), tonalElevation = 1.dp) {
        Box(Modifier.fillMaxSize()) {
            if (drawList == null) {
                Text(
                    "Preview unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val bounds = if (drawList.bounds.isEmpty) Polygons.bounds(room.corners) else drawList.bounds
                    val view = ViewTransform.fit(
                        bounds,
                        size.width.toDouble(),
                        size.height.toDouble(),
                        marginPx = 16.dp.toPx().toDouble(),
                        pxPerPt = pxPerPt,
                    )
                    drawIntoCanvas { PlanPainter.draw(it.nativeCanvas, drawList, view) }
                }
            }
        }
    }
}
