package com.oroplex.floorplan.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.render.PlanPainter

/** Review-step actions. */
internal interface ReviewActions {
    fun setName(name: String)
    fun setType(type: RoomType)
    fun saveAndScanNext()
    fun saveAndFinish()
    fun redoRoom()
}

/** Formatted room statistics for the review (null fields are not shown). */
internal data class ReviewStats(
    val size: String?,
    val area: String,
    val perimeter: String,
    val ceiling: String,
)

internal fun reviewStats(room: Room, ceilingMeasured: Boolean, settings: PlanSettings): ReviewStats {
    val system = settings.unitSystem
    fun len(m: Double) = formatLengthSafe(m, settings)
    val stats = try {
        Measurements.room(room)
    } catch (e: Exception) {
        null
    }
    val area = stats?.area ?: Polygons.area(room.corners)
    val perimeter = stats?.perimeter ?: Polygons.perimeter(room.corners)
    val size = stats?.let { "${len(it.length)} × ${len(it.width)}" }
    val areaText = try {
        Units.formatArea(area, system)
    } catch (e: Exception) {
        String.format(java.util.Locale.US, "%.1f m²", area)
    }
    val ceilingHeight = room.ceilingHeight ?: settings.defaultCeilingHeight
    val ceiling = len(ceilingHeight) + if (ceilingMeasured) "" else " (estimated)"
    return ReviewStats(size, areaText, len(perimeter), ceiling)
}

/**
 * REVIEW step: a plan preview of the room, its stats and warnings, name and type, and the save actions.
 * Scrolls within ~60 % of the screen height so the camera stays partly visible and the keyboard fits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewPanel(
    ui: CaptureUiState,
    actions: ReviewActions,
    modifier: Modifier = Modifier,
) {
    val room = ui.draftRoom
    val d = ui.draft
    val enabled = !ui.busy && !ui.finished
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Review room", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (room == null) {
                Text(
                    "This room has no valid outline. Tap Redo room to capture it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                RoomPreview(room, ui.settings, Modifier.fillMaxWidth().height(170.dp))
                val stats = remember(room, d.ceilingMeasured, ui.settings) { reviewStats(room, d.ceilingMeasured, ui.settings) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.size?.let { StatRow("Size (L × W)", it) }
                    StatRow("Area", stats.area)
                    StatRow("Perimeter", stats.perimeter)
                    StatRow("Ceiling height", stats.ceiling)
                    StatRow("Doors & windows", if (room.openings.isEmpty()) "None" else room.openings.size.toString())
                }
                if (room.warnings.isNotEmpty()) WarningLines(room.warnings)
            }
            if (ui.otherSessionRoomCount > 0) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        "Rooms from other scan sessions may need to be moved into place in the editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            OutlinedTextField(
                value = d.name,
                onValueChange = actions::setName,
                label = { Text("Room name") },
                placeholder = { Text(d.type.displayName) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            RoomTypeDropdown(d.type, enabled, actions::setType)

            if (ui.saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
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
            TextButton(onClick = actions::redoRoom, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Redo room")
            }
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
            PlanRenderer.render(listOf(room), settings, RenderOptions(showRoomNames = false, showWallLengths = true))
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
