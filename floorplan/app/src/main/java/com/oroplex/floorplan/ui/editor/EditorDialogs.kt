@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.ui.common.LengthField
import com.oroplex.floorplan.ui.common.formatLength
import com.oroplex.floorplan.ui.common.rememberLengthFieldState
import java.util.Locale
import kotlin.math.abs

/** Which editor dialog is open. Dialogs referencing a room close themselves if the room disappears. */
sealed interface EditorDialog {
    data object ProjectSettings : EditorDialog
    data object RenameProject : EditorDialog
    data object AddFloor : EditorDialog
    data class RenameFloor(val floorId: String) : EditorDialog
    data class DeleteFloor(val floorId: String) : EditorDialog
    data object DrawRoom : EditorDialog
    data class RoomDetails(val roomId: String) : EditorDialog
    data class CeilingHeight(val roomId: String) : EditorDialog
    data class DeleteRoom(val roomId: String) : EditorDialog
    data class Resolve(val roomId: String) : EditorDialog
    data class EditOpening(val roomId: String, val openingId: String) : EditorDialog
    data class SetWallLength(val roomId: String, val wallIndex: Int) : EditorDialog
    data class Calibrate(val roomId: String, val wallIndex: Int) : EditorDialog
}

// ---- Room ----------------------------------------------------------------------------------------------

@Composable
fun RoomDetailsDialog(room: Room, onConfirm: (name: String, type: RoomType) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(room.name) }
    var type by rememberSaveable { mutableStateOf(room.type) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomTypeDropdown(
                    selected = type,
                    onSelect = { newType ->
                        // Keep auto-generated names in sync with the type ("Bedroom 2" -> "Office").
                        if (name.isBlank() || RoomPlacement.isDefaultName(name, type)) name = newType.displayName
                        type = newType
                    },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name.trim().ifEmpty { type.displayName }, type)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RoomTypeDropdown(selected: RoomType, onSelect: (RoomType) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Room type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

@Composable
fun CeilingHeightDialog(room: Room, settings: PlanSettings, onConfirm: (Double?) -> Unit, onDismiss: () -> Unit) {
    val field = rememberLengthFieldState(room.ceilingHeight ?: settings.defaultCeilingHeight, settings)
    val value = field.value
    val error = value?.let { if (it !in CEILING_RANGE) "Enter a height between ${settings.formatLength(CEILING_RANGE.start)} and ${settings.formatLength(CEILING_RANGE.endInclusive)}." else null }
    val valid = value != null && error == null
    val confirm = {
        if (valid) {
            onConfirm(value)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ceiling height") },
        text = {
            Column {
                Text(
                    if (room.ceilingHeight == null) "Not measured yet. Enter the floor-to-ceiling height of ${room.name}."
                    else "Floor-to-ceiling height of ${room.name}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(4.dp))
                LengthField(field, label = "Height", error = error, onImeAction = confirm, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = valid) { Text("Save") } },
        dismissButton = {
            Row {
                if (room.ceilingHeight != null) {
                    TextButton(onClick = {
                        onConfirm(null)
                        onDismiss()
                    }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun ResolveDialog(room: Room, settings: PlanSettings, onConfirm: (squareAngles: Boolean) -> Unit, onDismiss: () -> Unit) {
    var square by rememberSaveable { mutableStateOf(settings.squareAngles) }
    val explanation = when {
        room.captureMethod == CaptureMethod.WALLS && room.observations.size >= 3 ->
            "Rebuilds the outline from the ${room.observations.size} walls measured during the scan. Manual corner edits to this room are replaced."
        room.captureMethod == CaptureMethod.CORNERS && room.cornerTaps.size >= 3 ->
            "Rebuilds the outline from the ${room.cornerTaps.size} corners tapped during the scan. Manual corner edits to this room are replaced."
        else -> "This room has no raw scan data, so re-solving can only square its corners."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-solve ${room.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                SwitchRow(
                    title = "Square the corners",
                    subtitle = "Snap walls within ${formatDegrees(settings.squareToleranceDeg)} of the room's main direction to exactly 90°.",
                    checked = square,
                    onCheckedChange = { square = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(square)
                onDismiss()
            }) { Text("Re-solve") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DrawRoomDialog(settings: PlanSettings, onConfirm: (RoomType, width: Double, length: Double) -> Unit, onDismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf(RoomType.OTHER) }
    val width = rememberLengthFieldState(null, settings)
    val length = rememberLengthFieldState(null, settings)
    val widthError = rangeError(width.value, DRAWN_ROOM_RANGE, settings)
    val lengthError = rangeError(length.value, DRAWN_ROOM_RANGE, settings)
    val valid = width.value != null && length.value != null && widthError == null && lengthError == null
    val confirm = {
        val w = width.value
        val l = length.value
        if (valid && w != null && l != null) {
            onConfirm(type, w, l)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw a room") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Enter the interior dimensions. The room is placed to the right of the plan; select it and use Move to put it in place.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(2.dp))
                RoomTypeDropdown(selected = type, onSelect = { type = it })
                LengthField(width, label = "Width (left to right)", error = widthError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(length, label = "Length (top to bottom)", error = lengthError, onImeAction = confirm, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = valid) { Text("Add room") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Openings ------------------------------------------------------------------------------------------

@Composable
fun OpeningEditorDialog(
    room: Room,
    opening: Opening,
    settings: PlanSettings,
    onSave: (Opening) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val wallLength = edgeLength(room, opening.wallIndex)
    var type by rememberSaveable { mutableStateOf(opening.type) }
    var swing by rememberSaveable { mutableStateOf(opening.swing) }
    val width = rememberLengthFieldState(opening.width, settings, smallUnits = true)
    val height = rememberLengthFieldState(opening.height, settings, smallUnits = true)
    val sill = rememberLengthFieldState(opening.sillHeight, settings, smallUnits = true, allowZero = true)
    val offset = rememberLengthFieldState(opening.offset, settings, allowZero = true)

    val w = width.value
    val h = height.value
    val sillValue = if (type == OpeningType.WINDOW) sill.value else 0.0
    val o = offset.value
    val widthError = w?.let {
        when {
            it < PlanEditorViewModel.MIN_OPENING_WIDTH -> "At least ${settings.formatLength(PlanEditorViewModel.MIN_OPENING_WIDTH)}."
            it > wallLength + 1e-9 -> "The wall is only ${settings.formatLength(wallLength)} long."
            else -> null
        }
    }
    val heightError = h?.let { if (it !in OPENING_HEIGHT_RANGE) "Between ${settings.formatLength(OPENING_HEIGHT_RANGE.start)} and ${settings.formatLength(OPENING_HEIGHT_RANGE.endInclusive)}." else null }
    val sillError = if (type == OpeningType.WINDOW) sillValue?.let { if (it > SILL_MAX) "At most ${settings.formatLength(SILL_MAX)}." else null } else null
    val maxOffset = (wallLength - (w ?: 0.0)).coerceAtLeast(0.0)
    val offsetError = o?.let { if (it > maxOffset + 1e-9) "At most ${settings.formatLength(maxOffset)} for this width." else null }
    val valid = w != null && h != null && sillValue != null && o != null &&
        widthError == null && heightError == null && sillError == null && offsetError == null

    fun applyTypeDefaults(newType: OpeningType) {
        if (newType == type) return
        // Switching between door-like and window resets the vertical dimensions to the project defaults.
        if (newType == OpeningType.WINDOW) {
            height.text = settings.formatLength(settings.defaultWindowHeight)
            sill.text = settings.formatLength(settings.defaultWindowSill)
        } else if (type == OpeningType.WINDOW) {
            height.text = settings.formatLength(settings.defaultDoorHeight)
        }
        type = newType
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${type.label()} on wall ${opening.wallIndex + 1}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SegmentedChoice(
                    options = OpeningType.entries,
                    selected = type,
                    label = { it.label() },
                    onSelect = ::applyTypeDefaults,
                )
                Spacer(Modifier.padding(2.dp))
                LengthField(width, label = "Width", error = widthError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(height, label = "Height", error = heightError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                if (type == OpeningType.WINDOW) {
                    LengthField(sill, label = "Sill height", error = sillError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                }
                LengthField(
                    offset,
                    label = "Distance from corner ${opening.wallIndex + 1}",
                    error = offsetError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type == OpeningType.DOOR) {
                    Text("Hinge side (walking into the room)", style = MaterialTheme.typography.labelLarge)
                    SegmentedChoice(
                        options = listOf(true, false),
                        selected = swing.isLeft(),
                        label = { if (it) "Left" else "Right" },
                        onSelect = { left -> swing = swingOf(left, swing.isIn()) },
                    )
                    Text("Opens", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    SegmentedChoice(
                        options = listOf(true, false),
                        selected = swing.isIn(),
                        label = { if (it) "Into the room" else "Outward" },
                        onSelect = { inward -> swing = swingOf(swing.isLeft(), inward) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (w != null && h != null && sillValue != null && o != null) {
                        onSave(opening.copy(type = type, width = w, height = h, sillHeight = sillValue, offset = o, swing = swing))
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun DoorSwing.isLeft() = this == DoorSwing.LEFT_IN || this == DoorSwing.LEFT_OUT
private fun DoorSwing.isIn() = this == DoorSwing.LEFT_IN || this == DoorSwing.RIGHT_IN
private fun swingOf(left: Boolean, inward: Boolean) = when {
    left && inward -> DoorSwing.LEFT_IN
    left -> DoorSwing.LEFT_OUT
    inward -> DoorSwing.RIGHT_IN
    else -> DoorSwing.RIGHT_OUT
}

// ---- Walls ---------------------------------------------------------------------------------------------

/** [onApply] returns an error to show, or null when the length was applied (the dialog then closes). */
@Composable
fun SetWallLengthDialog(
    room: Room,
    wallIndex: Int,
    settings: PlanSettings,
    onApply: (Double) -> String?,
    onDismiss: () -> Unit,
) {
    val current = edgeLength(room, wallIndex)
    val field = rememberLengthFieldState(current, settings)
    // Error returned by the last apply, shown until the text is edited: (text it was for, message).
    var failure by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val value = field.value
    val error = rangeError(value, WALL_RANGE, settings) ?: failure?.takeIf { it.first == field.text }?.second
    val confirm = {
        if (value != null && rangeError(value, WALL_RANGE, settings) == null) {
            val result = onApply(value)
            if (result == null) onDismiss() else failure = field.text to result
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wall ${wallIndex + 1} length") },
        text = {
            Column {
                Text(
                    "Currently ${settings.formatLength(current)}. The wall's start corner stays put; the room stretches along the wall so a rectangle stays square.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(4.dp))
                LengthField(
                    field,
                    label = "New length",
                    error = error,
                    onImeAction = confirm,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = value != null && rangeError(value, WALL_RANGE, settings) == null) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tape calibration of the whole floor from one wall. [onApply] returns an error or null on success. */
@Composable
fun CalibrateDialog(
    room: Room,
    wallIndex: Int,
    roomCount: Int,
    settings: PlanSettings,
    previous: Calibration?,
    onApply: (Double) -> String?,
    onDismiss: () -> Unit,
) {
    val measured = edgeLength(room, wallIndex)
    val field = rememberLengthFieldState(null, settings)
    var failure by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val value = field.value
    val factor = if (value != null && measured > 1e-6) value / measured else null
    val confirm = {
        if (value != null) {
            val result = onApply(value)
            if (result == null) onDismiss() else failure = field.text to result
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrate floor") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Measure wall ${wallIndex + 1} of ${room.name} with a tape (inside face, corner to corner) and enter " +
                        "the true length. All $roomCount ${if (roomCount == 1) "room" else "rooms"} on this floor are scaled to match, " +
                        "which removes the AR scale error.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.padding(4.dp))
                Text("Measured by the app", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(settings.formatLength(measured), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.padding(4.dp))
                LengthField(
                    field,
                    label = "Tape-measured length",
                    error = failure?.takeIf { it.first == field.text }?.second,
                    onImeAction = confirm,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (factor != null && factor.isFinite()) {
                    val percent = (factor - 1) * 100
                    Text(
                        "Scale factor ×${"%.4f".format(Locale.US, factor)} (${if (percent >= 0) "+" else "−"}${"%.2f".format(Locale.US, abs(percent))} %)",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (abs(percent) > 5) {
                        Text(
                            "That is a large correction for AR measurements (usually 1–3 %). Double-check the number and its units.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (previous != null) {
                    Text(
                        "This floor was already calibrated (×${"%.4f".format(Locale.US, previous.factor)}); the new correction is applied on top.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = value != null) { Text("Calibrate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Project settings ----------------------------------------------------------------------------------

@Composable
fun ProjectSettingsDialog(settings: PlanSettings, onConfirm: (PlanSettings) -> Unit, onDismiss: () -> Unit) {
    var units by rememberSaveable { mutableStateOf(settings.unitSystem) }
    var denominator by rememberSaveable { mutableStateOf(settings.imperialDenominator.takeIf { it in DENOMINATORS } ?: 2) }
    var square by rememberSaveable { mutableStateOf(settings.squareAngles) }
    var toleranceText by rememberSaveable { mutableStateOf(formatNumber(settings.squareToleranceDeg)) }
    var showLengths by rememberSaveable { mutableStateOf(settings.showWallLengths) }
    var showAreas by rememberSaveable { mutableStateOf(settings.showRoomAreas) }
    // The thickness field follows the unit choice made in this dialog.
    val fieldSettings = settings.copy(unitSystem = units, imperialDenominator = if (units == UnitSystem.IMPERIAL) denominator else settings.imperialDenominator)
    // Last valid thickness; seeds the field again when a unit change recreates it.
    var thickness by rememberSaveable { mutableStateOf(settings.wallThickness) }
    val thicknessField = rememberLengthFieldState(thickness, fieldSettings, smallUnits = true)
    val keepThickness = { thicknessField.value?.takeIf { it in THICKNESS_RANGE }?.let { thickness = it } }
    val thicknessValue = thicknessField.value
    val thicknessError = rangeError(thicknessValue, THICKNESS_RANGE, fieldSettings)
    val tolerance = toleranceText.trim().replace(',', '.').toDoubleOrNull()
    val toleranceError = if (tolerance == null || tolerance !in TOLERANCE_RANGE) {
        "Between ${formatDegrees(TOLERANCE_RANGE.start)} and ${formatDegrees(TOLERANCE_RANGE.endInclusive)}"
    } else {
        null
    }
    val valid = thicknessValue != null && thicknessError == null && (!square || toleranceError == null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Units", style = MaterialTheme.typography.labelLarge)
                SegmentedChoice(
                    options = UnitSystem.entries,
                    selected = units,
                    label = { if (it == UnitSystem.IMPERIAL) "Feet & inches" else "Metric" },
                    onSelect = {
                        keepThickness()
                        units = it
                    },
                )
                if (units == UnitSystem.IMPERIAL) {
                    Text("Precision", style = MaterialTheme.typography.labelLarge)
                    SegmentedChoice(
                        options = DENOMINATORS,
                        selected = denominator,
                        label = { if (it == 1) "1\"" else "1/$it\"" },
                        onSelect = {
                            keepThickness()
                            denominator = it
                        },
                    )
                }
                LengthField(thicknessField, label = "Wall thickness", error = thicknessError, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                SwitchRow(
                    title = "Square corners",
                    subtitle = "When rooms are solved, snap nearly-square corners to exactly 90°.",
                    checked = square,
                    onCheckedChange = { square = it },
                )
                if (square) {
                    OutlinedTextField(
                        value = toleranceText,
                        onValueChange = { toleranceText = it },
                        label = { Text("Squaring tolerance (degrees)") },
                        singleLine = true,
                        isError = toleranceError != null,
                        supportingText = { Text(toleranceError ?: "Walls within this angle of the room's main direction are snapped.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
                SwitchRow("Show wall lengths", null, showLengths) { showLengths = it }
                SwitchRow("Show room areas", null, showAreas) { showAreas = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (thicknessValue != null) {
                        onConfirm(
                            settings.copy(
                                unitSystem = units,
                                imperialDenominator = denominator,
                                wallThickness = thicknessValue,
                                squareAngles = square,
                                squareToleranceDeg = if (square && tolerance != null) tolerance else settings.squareToleranceDeg,
                                showWallLengths = showLengths,
                                showRoomAreas = showAreas,
                            ),
                        )
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Building blocks -----------------------------------------------------------------------------------

@Composable
fun <T> SegmentedChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun rangeError(value: Double?, range: ClosedFloatingPointRange<Double>, settings: PlanSettings): String? {
    if (value == null || value in range) return null
    return "Enter a length between ${settings.formatLength(range.start)} and ${settings.formatLength(range.endInclusive)}."
}

private fun formatNumber(v: Double): String =
    if (v == Math.rint(v)) v.toLong().toString() else "%.1f".format(Locale.US, v)

private fun formatDegrees(v: Double): String = formatNumber(v) + "°"

private val DENOMINATORS = listOf(1, 2, 4, 8, 16)
private val CEILING_RANGE = 1.5..10.0
private val DRAWN_ROOM_RANGE = 0.3..50.0
private val WALL_RANGE = 0.06..100.0
private val OPENING_HEIGHT_RANGE = 0.1..5.0
private const val SILL_MAX = 3.0
private val THICKNESS_RANGE = 0.02..0.6
private val TOLERANCE_RANGE = 0.5..15.0
