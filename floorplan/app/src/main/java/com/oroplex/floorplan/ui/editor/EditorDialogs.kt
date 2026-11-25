@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.model.AreaCategory
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomNames
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.ui.common.LengthField
import com.oroplex.floorplan.ui.common.LengthFieldState
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

/**
 * Name, type and "counts as" (area category) of a room. Changing the type renames a room that still has a
 * default name ([nameAfterTypeChange], numbered against [floorRooms]); the area category follows the type
 * unless the user picks another one.
 */
@Composable
fun RoomDetailsDialog(
    room: Room,
    floorRooms: List<Room>,
    onConfirm: (name: String, type: RoomType, areaCategory: AreaCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(room.name) }
    var type by rememberSaveable { mutableStateOf(room.type) }
    // null = follow the type's default category.
    var category by rememberSaveable { mutableStateOf(room.areaCategory?.takeIf { it != room.type.defaultArea }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room details") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RoomTypeDropdown(
                    selected = type,
                    onSelect = { newType ->
                        // Keep auto-generated names in sync with the type ("Room 3" -> "Bedroom 3"), numbered
                        // against the floor so two rooms never share a name.
                        name = nameAfterTypeChange(name, type, newType, floorRooms, room.id)
                        if (category == newType.defaultArea) category = null
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
                AreaCategoryChoice(
                    selected = category ?: type.defaultArea,
                    typeDefault = type.defaultArea,
                    onSelect = { category = it.takeIf { c -> c != type.defaultArea } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name.trim().ifEmpty { RoomNames.unique(type, floorRooms, room.id) }, type, category)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Counts as" radio list: which area total the room's floor area goes into. */
@Composable
private fun AreaCategoryChoice(selected: AreaCategory, typeDefault: AreaCategory, onSelect: (AreaCategory) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text("Counts as", style = MaterialTheme.typography.labelLarge)
        AreaCategory.entries.forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = c == selected, onClick = { onSelect(c) }, role = Role.RadioButton),
            ) {
                RadioButton(selected = c == selected, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(c.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (c == typeDefault) {
                        Text(
                            "Usual for this room type",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Room types grouped for the type picker, in display order. Every [RoomType] appears exactly once; types not
 * listed in a group (added later) go to "Other".
 */
internal object RoomTypeGroups {
    private val GROUPS: List<Pair<String, List<RoomType>>> = listOf(
        "Living" to listOf(RoomType.LIVING, RoomType.FAMILY, RoomType.DEN, RoomType.OFFICE, RoomType.BONUS, RoomType.SUNROOM),
        "Kitchen & dining" to listOf(RoomType.KITCHEN, RoomType.DINING, RoomType.PANTRY),
        "Bedrooms & baths" to listOf(
            RoomType.BEDROOM, RoomType.PRIMARY_BEDROOM, RoomType.BATHROOM, RoomType.HALF_BATH, RoomType.CLOSET,
        ),
        "Halls & entries" to listOf(RoomType.HALLWAY, RoomType.ENTRY, RoomType.MUDROOM, RoomType.STAIRS),
        "Utility & storage" to listOf(
            RoomType.LAUNDRY, RoomType.UTILITY, RoomType.STORAGE, RoomType.GARAGE, RoomType.WORKSHOP, RoomType.BASEMENT,
        ),
        "Outdoor" to listOf(RoomType.PORCH, RoomType.DECK, RoomType.BALCONY, RoomType.PATIO),
    )

    val groups: List<Pair<String, List<RoomType>>> by lazy {
        val listed = GROUPS.flatMap { it.second }.toSet()
        val rest = RoomType.entries.filter { it !in listed }
        GROUPS + ("Other" to rest)
    }
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
        // The menu scrolls; group headings keep the long list easy to scan.
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RoomTypeGroups.groups.forEach { (heading, types) ->
                if (types.isEmpty()) return@forEach
                Text(
                    heading,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 12.dp, bottom = 4.dp)
                        .semantics { heading() },
                )
                types.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName) },
                        onClick = {
                            onSelect(type)
                            expanded = false
                        },
                        trailingIcon = if (type == selected) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                        } else {
                            null
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
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
                    when {
                        room.ceilingHeight == null -> "Not measured yet. Enter the floor-to-ceiling height of ${room.name}."
                        room.ceilingEstimated ->
                            "This is the default height, not a measurement. Enter the real floor-to-ceiling height of " +
                                "${room.name}, or tap Save to confirm it."
                        else -> "Floor-to-ceiling height of ${room.name}."
                    },
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

/**
 * Tape calibration of the floor from one wall of a scanned room. [scannedRooms] / [drawnRooms] count the floor's
 * rooms for the explanation. [onApply] returns an error or null on success.
 */
@Composable
fun CalibrateDialog(
    room: Room,
    wallIndex: Int,
    scannedRooms: Int,
    drawnRooms: Int,
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
                Text(calibrationExplanation(room.name, wallIndex, scannedRooms, drawnRooms), style = MaterialTheme.typography.bodyMedium)
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

/**
 * What tape calibration does, for the dialog: only AR measurements are scaled. Scanned rooms scale; drawn rooms
 * keep their typed sizes and move with the plan; lengths, ceilings and door / window sizes the user typed stay.
 */
internal fun calibrationExplanation(roomName: String, wallIndex: Int, scannedRooms: Int, drawnRooms: Int): String = buildString {
    append("Measure wall ${wallIndex + 1} of $roomName with a tape (inside face, corner to corner) and enter the true length. ")
    val scanned = scannedRooms.coerceAtLeast(1)
    append(
        if (scanned == 1) "The scanned room on this floor is scaled to match, which removes the AR scale error."
        else "The $scanned scanned rooms on this floor are scaled to match, which removes the AR scale error.",
    )
    if (drawnRooms > 0) {
        append(
            if (drawnRooms == 1) " The drawn room keeps its typed size and moves with the plan."
            else " The $drawnRooms drawn rooms keep their typed sizes and move with the plan.",
        )
    }
    append(" Lengths, ceilings and door sizes you typed are kept.")
}

// ---- Project settings ----------------------------------------------------------------------------------

/**
 * The project's own settings. [estimatedCeilingRooms] is the number of rooms whose ceiling is still the current
 * default estimate; when the default ceiling changes, the dialog offers to apply the new default to them
 * ([onConfirm]'s second argument).
 */
@Composable
fun ProjectSettingsDialog(
    settings: PlanSettings,
    estimatedCeilingRooms: Int,
    onConfirm: (PlanSettings, applyCeilingToEstimated: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var units by rememberSaveable { mutableStateOf(settings.unitSystem) }
    var denominator by rememberSaveable { mutableStateOf(settings.imperialDenominator.takeIf { it in DENOMINATORS } ?: 2) }
    var square by rememberSaveable { mutableStateOf(settings.squareAngles) }
    var toleranceText by rememberSaveable { mutableStateOf(formatNumber(settings.squareToleranceDeg)) }
    var showLengths by rememberSaveable { mutableStateOf(settings.showWallLengths) }
    var showAreas by rememberSaveable { mutableStateOf(settings.showRoomAreas) }
    var showOverall by rememberSaveable { mutableStateOf(settings.showOverallDimensions) }
    var applyCeiling by rememberSaveable { mutableStateOf(true) }
    // The length fields follow the unit choice made in this dialog.
    val fieldSettings = settings.copy(unitSystem = units, imperialDenominator = if (units == UnitSystem.IMPERIAL) denominator else settings.imperialDenominator)
    val exteriorStored = settings.exteriorWallThickness?.takeIf { it.isFinite() && it > 0 }
    // Last valid values; they seed the fields again when a unit change recreates them.
    var thickness by rememberSaveable { mutableStateOf(settings.wallThickness) }
    var exterior by rememberSaveable { mutableStateOf(exteriorStored ?: settings.wallThickness) }
    var ceiling by rememberSaveable { mutableStateOf(settings.defaultCeilingHeight) }
    var doorWidth by rememberSaveable { mutableStateOf(settings.defaultDoorWidth) }
    var doorHeight by rememberSaveable { mutableStateOf(settings.defaultDoorHeight) }
    var windowWidth by rememberSaveable { mutableStateOf(settings.defaultWindowWidth) }
    var windowHeight by rememberSaveable { mutableStateOf(settings.defaultWindowHeight) }
    var sill by rememberSaveable { mutableStateOf(settings.defaultWindowSill) }

    val thicknessField = rememberLengthFieldState(thickness, fieldSettings, smallUnits = true)
    val exteriorField = rememberLengthFieldState(exterior, fieldSettings, smallUnits = true)
    val ceilingField = rememberLengthFieldState(ceiling, fieldSettings)
    val doorField = rememberLengthFieldState(doorWidth, fieldSettings, smallUnits = true)
    val doorHeightField = rememberLengthFieldState(doorHeight, fieldSettings, smallUnits = true)
    val windowField = rememberLengthFieldState(windowWidth, fieldSettings, smallUnits = true)
    val windowHeightField = rememberLengthFieldState(windowHeight, fieldSettings, smallUnits = true)
    val sillField = rememberLengthFieldState(sill, fieldSettings, smallUnits = true, allowZero = true)

    // A value outside the range is accepted when it is the stored value itself (e.g. from an imported backup),
    // so the dialog can always be saved without touching unrelated fields.
    fun fieldError(field: LengthFieldState, range: ClosedFloatingPointRange<Double>, stored: Double?): String? =
        field.value?.takeUnless { stored != null && abs(it - stored) < 1e-9 }?.let { rangeError(it, range, fieldSettings) }

    val keepLengths = {
        fun keep(field: LengthFieldState, range: ClosedFloatingPointRange<Double>, set: (Double) -> Unit) {
            field.value?.takeIf { it in range }?.let(set)
        }
        keep(thicknessField, THICKNESS_RANGE) { thickness = it }
        keep(exteriorField, EXTERIOR_THICKNESS_RANGE) { exterior = it }
        keep(ceilingField, DEFAULT_CEILING_RANGE) { ceiling = it }
        keep(doorField, DOOR_WIDTH_RANGE) { doorWidth = it }
        keep(doorHeightField, DOOR_HEIGHT_RANGE) { doorHeight = it }
        keep(windowField, WINDOW_WIDTH_RANGE) { windowWidth = it }
        keep(windowHeightField, WINDOW_HEIGHT_RANGE) { windowHeight = it }
        keep(sillField, SILL_RANGE) { sill = it }
    }
    val thicknessError = fieldError(thicknessField, THICKNESS_RANGE, settings.wallThickness)
    val exteriorError = fieldError(exteriorField, EXTERIOR_THICKNESS_RANGE, exteriorStored)
    val ceilingError = fieldError(ceilingField, DEFAULT_CEILING_RANGE, settings.defaultCeilingHeight)
    val doorError = fieldError(doorField, DOOR_WIDTH_RANGE, settings.defaultDoorWidth)
    val doorHeightError = fieldError(doorHeightField, DOOR_HEIGHT_RANGE, settings.defaultDoorHeight)
    val windowError = fieldError(windowField, WINDOW_WIDTH_RANGE, settings.defaultWindowWidth)
    val windowHeightError = fieldError(windowHeightField, WINDOW_HEIGHT_RANGE, settings.defaultWindowHeight)
    val sillError = fieldError(sillField, SILL_RANGE, settings.defaultWindowSill)
    val lengthFields = listOf(
        thicknessField, exteriorField, ceilingField, doorField, doorHeightField, windowField, windowHeightField, sillField,
    )
    val lengthErrors = listOf(
        thicknessError, exteriorError, ceilingError, doorError, doorHeightError, windowError, windowHeightError, sillError,
    )
    val tolerance = toleranceText.trim().replace(',', '.').toDoubleOrNull()
    val toleranceError = if (tolerance == null || tolerance !in TOLERANCE_RANGE) {
        "Between ${formatDegrees(TOLERANCE_RANGE.start)} and ${formatDegrees(TOLERANCE_RANGE.endInclusive)}"
    } else {
        null
    }
    val valid = lengthFields.all { it.value != null } && lengthErrors.all { it == null } && (!square || toleranceError == null)
    val newCeiling = ceilingField.value
    val ceilingChanged = newCeiling != null && ceilingError == null && abs(newCeiling - settings.defaultCeilingHeight) > 1e-9

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
                        keepLengths()
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
                            keepLengths()
                            denominator = it
                        },
                    )
                }
                HorizontalDivider()
                Text("Walls", style = MaterialTheme.typography.labelLarge)
                LengthField(thicknessField, label = "Interior wall thickness", error = thicknessError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(
                    exteriorField,
                    label = "Exterior wall thickness",
                    error = exteriorError,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Overall dimensions measure to the outside of the exterior walls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text("Ceilings", style = MaterialTheme.typography.labelLarge)
                LengthField(ceilingField, label = "Default ceiling height", error = ceilingError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                Text(
                    "Used when a room's ceiling isn't measured. Such rooms are marked (est.).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ceilingChanged && estimatedCeilingRooms > 0) {
                    CheckboxRow(
                        text = "Apply to $estimatedCeilingRooms ${if (estimatedCeilingRooms == 1) "room" else "rooms"} with estimated ceilings",
                        checked = applyCeiling,
                        onCheckedChange = { applyCeiling = it },
                    )
                }
                HorizontalDivider()
                Text("New doors & windows", style = MaterialTheme.typography.labelLarge)
                LengthField(doorField, label = "Door width", error = doorError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(doorHeightField, label = "Door height", error = doorHeightError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(windowField, label = "Window width", error = windowError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(windowHeightField, label = "Window height", error = windowHeightError, imeAction = ImeAction.Next, modifier = Modifier.fillMaxWidth())
                LengthField(sillField, label = "Window sill height", error = sillError, modifier = Modifier.fillMaxWidth())
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
                SwitchRow(
                    title = "Show overall dimensions",
                    subtitle = "Total width and depth of the floor, drawn around the plan.",
                    checked = showOverall,
                    onCheckedChange = { showOverall = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val values = lengthFields.map { it.value }
                    if (values.all { it != null }) {
                        val exteriorValue = exteriorField.value
                        onConfirm(
                            settings.copy(
                                unitSystem = units,
                                imperialDenominator = denominator,
                                wallThickness = thicknessField.value ?: settings.wallThickness,
                                // Keep "same as interior" (null) unless the user entered a different value
                                // (compared by value, so an edit survives a unit switch that recreates the field).
                                exteriorWallThickness = if (
                                    exteriorStored == null && (exteriorValue == null || abs(exteriorValue - settings.wallThickness) < 1e-9)
                                ) {
                                    settings.exteriorWallThickness
                                } else {
                                    exteriorValue
                                },
                                squareAngles = square,
                                squareToleranceDeg = if (square && tolerance != null) tolerance else settings.squareToleranceDeg,
                                showWallLengths = showLengths,
                                showRoomAreas = showAreas,
                                showOverallDimensions = showOverall,
                                defaultCeilingHeight = ceilingField.value ?: settings.defaultCeilingHeight,
                                defaultDoorWidth = doorField.value ?: settings.defaultDoorWidth,
                                defaultDoorHeight = doorHeightField.value ?: settings.defaultDoorHeight,
                                defaultWindowWidth = windowField.value ?: settings.defaultWindowWidth,
                                defaultWindowHeight = windowHeightField.value ?: settings.defaultWindowHeight,
                                defaultWindowSill = sillField.value ?: settings.defaultWindowSill,
                            ),
                            ceilingChanged && estimatedCeilingRooms > 0 && applyCeiling,
                        )
                        onDismiss()
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Checkbox),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
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

// Same ranges as the app Settings screen for the defaults it also edits.
private val DEFAULT_CEILING_RANGE = 1.5..6.0
private val DOOR_HEIGHT_RANGE = 1.5..3.5
private val WINDOW_HEIGHT_RANGE = 0.2..3.0
private val SILL_RANGE = 0.0..2.5
private val EXTERIOR_THICKNESS_RANGE = 0.05..0.8
private val CEILING_RANGE = 1.5..10.0
private val DRAWN_ROOM_RANGE = 0.3..50.0
private val WALL_RANGE = 0.06..100.0
private val OPENING_HEIGHT_RANGE = 0.1..5.0
private const val SILL_MAX = 3.0
private val THICKNESS_RANGE = 0.02..0.6
private val TOLERANCE_RANGE = 0.5..15.0
// Same ranges as PlanSettings.sanitized(), so a stored default can always be saved back unchanged
// (garage doors are up to ~5 m wide).
private val DOOR_WIDTH_RANGE = 0.2..5.0
private val WINDOW_WIDTH_RANGE = 0.1..6.0
