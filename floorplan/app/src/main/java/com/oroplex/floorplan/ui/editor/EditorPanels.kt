package com.oroplex.floorplan.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Window
import androidx.compose.material.icons.outlined.SensorDoor
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.RoomStats
import com.oroplex.floorplan.core.model.Calibration
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.ui.common.formatArea
import com.oroplex.floorplan.ui.common.formatLength
import java.util.Locale

/** Bottom sheet-like surface hosting the selection panels; scrolls when its content is tall. */
@Composable
fun EditorPanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            content = content,
        )
    }
}

/** Callbacks of [RoomPanel]. */
class RoomPanelActions(
    val onEditDetails: () -> Unit,
    val onEditCeiling: () -> Unit,
    val onOpenOpening: (Opening) -> Unit,
    val onToggleMove: () -> Unit,
    val onRotate: (degrees: Double) -> Unit,
    val onResolve: () -> Unit,
    val onDelete: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun RoomPanel(room: Room, settings: PlanSettings, moveMode: Boolean, actions: RoomPanelActions) {
    val stats = remember(room) { safeStats(room) }
    PanelHeader(
        title = room.name,
        subtitle = buildString {
            append(room.type.displayName)
            append(" · ")
            append(
                when (room.captureMethod) {
                    CaptureMethod.WALLS -> "scanned (walls)"
                    CaptureMethod.CORNERS -> "scanned (corners)"
                    CaptureMethod.MANUAL -> "drawn"
                },
            )
        },
        onClose = actions.onClose,
        leading = null,
        trailing = {
            IconButton(onClick = actions.onEditDetails) { Icon(Icons.Filled.Edit, contentDescription = "Rename room") }
        },
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        StatCell("Size", stats?.let { "${settings.formatLength(it.length)} × ${settings.formatLength(it.width)}" } ?: "—")
        StatCell("Area", stats?.let { settings.formatArea(it.area) } ?: "—")
        StatCell(
            label = "Ceiling",
            value = room.ceilingHeight?.let { settings.formatLength(it) } ?: "Not set",
            modifier = Modifier.clickable(onClickLabel = "Edit ceiling height", onClick = actions.onEditCeiling),
            editable = true,
        )
    }
    if (moveMode) {
        Text(
            "Drag anywhere on the plan to move this room. Two fingers still zoom and pan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    Warnings(room.warnings)
    ActionRow {
        FilterChip(
            selected = moveMode,
            onClick = actions.onToggleMove,
            label = { Text("Move") },
            leadingIcon = { Icon(Icons.Filled.OpenWith, contentDescription = null, Modifier.size(18.dp)) },
        )
        ActionChip("90°", Icons.Filled.Rotate90DegreesCw, "Rotate 90° clockwise") { actions.onRotate(-90.0) }
        ActionChip("1°", null, "Rotate 1° clockwise", prefix = "−") { actions.onRotate(-1.0) }
        ActionChip("1°", null, "Rotate 1° counter-clockwise", prefix = "+") { actions.onRotate(1.0) }
        ActionChip("90°", Icons.Filled.Rotate90DegreesCcw, "Rotate 90° counter-clockwise") { actions.onRotate(90.0) }
        ActionChip("Re-solve", Icons.Filled.AutoFixHigh, null, onClick = actions.onResolve)
        ActionChip("Delete", Icons.Filled.Delete, null, destructive = true, onClick = actions.onDelete)
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Doors & windows", style = MaterialTheme.typography.titleSmall)
    if (room.openings.isEmpty()) {
        Text(
            "None yet. Tap a wall (or its length label) to add a door or window.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    } else {
        room.openings.sortedWith(compareBy({ it.wallIndex }, { it.offset })).forEach { opening ->
            OpeningRow(opening, settings, onClick = { actions.onOpenOpening(opening) })
        }
    }
}

/** Callbacks of [WallPanel]. */
class WallPanelActions(
    val onSetLength: () -> Unit,
    val onAddOpening: (OpeningType) -> Unit,
    val onOpenOpening: (Opening) -> Unit,
    val onInsertCorner: () -> Unit,
    val onCalibrate: () -> Unit,
    val onBackToRoom: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
fun WallPanel(room: Room, wallIndex: Int, settings: PlanSettings, calibration: Calibration?, actions: WallPanelActions) {
    val length = edgeLength(room, wallIndex)
    PanelHeader(
        title = "Wall ${wallIndex + 1} of ${room.corners.size}",
        subtitle = room.name,
        onClose = actions.onClose,
        leading = {
            IconButton(onClick = actions.onBackToRoom) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to room")
            }
        },
        trailing = null,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Length (interior)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(settings.formatLength(length), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        AssistChip(
            onClick = actions.onSetLength,
            label = { Text("Set length") },
            leadingIcon = { Icon(Icons.Filled.Straighten, contentDescription = null, Modifier.size(18.dp)) },
        )
        Spacer(Modifier.width(8.dp))
    }
    ActionRow {
        ActionChip("Add door", Icons.Filled.DoorFront, null) { actions.onAddOpening(OpeningType.DOOR) }
        ActionChip("Add window", Icons.Filled.Window, null) { actions.onAddOpening(OpeningType.WINDOW) }
        ActionChip("Add opening", Icons.Outlined.SensorDoor, null) { actions.onAddOpening(OpeningType.OPENING) }
        ActionChip("Insert corner", Icons.Filled.AddCircleOutline, null, onClick = actions.onInsertCorner)
        ActionChip("Calibrate floor", Icons.Filled.SquareFoot, null, onClick = actions.onCalibrate)
    }
    val onWall = room.openings.filter { it.wallIndex == wallIndex }.sortedBy { it.offset }
    if (onWall.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        onWall.forEach { OpeningRow(it, settings, onClick = { actions.onOpenOpening(it) }) }
    }
    calibration?.let {
        Text(
            "Floor calibrated ×${"%.4f".format(Locale.US, it.factor)} from a tape measurement.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun CornerPanel(room: Room, cornerIndex: Int, onDelete: () -> Unit, onBackToRoom: () -> Unit, onClose: () -> Unit) {
    val angle = remember(room, cornerIndex) {
        Polygons.interiorAngles(room.corners).getOrNull(cornerIndex)?.let { Math.toDegrees(it) }
    }
    PanelHeader(
        title = "Corner ${cornerIndex + 1} of ${room.corners.size}",
        subtitle = room.name,
        onClose = onClose,
        leading = {
            IconButton(onClick = onBackToRoom) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to room") }
        },
        trailing = null,
    )
    if (angle != null && angle.isFinite()) {
        Text("Interior angle ${"%.1f".format(Locale.US, angle)}°", style = MaterialTheme.typography.bodyLarge)
    }
    Text(
        "Drag the blue handle to move this corner. It snaps square to its neighbours.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
    ActionRow {
        ActionChip("Delete corner", Icons.Filled.Delete, null, destructive = true, enabled = room.corners.size > 3, onClick = onDelete)
    }
}

// ---- Building blocks ------------------------------------------------------------------------------------

@Composable
private fun PanelHeader(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        leading?.invoke()
        Column(Modifier.weight(1f).padding(start = if (leading == null) 0.dp else 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier, editable: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (editable) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun Warnings(warnings: List<String>) {
    if (warnings.isEmpty()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        warnings.forEach { w ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(
                    Icons.Filled.Warning,
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
    }
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector?,
    description: String?,
    prefix: String = "",
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(prefix + label) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = description, Modifier.size(18.dp)) } },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = color,
            leadingIconContentColor = if (destructive) color else MaterialTheme.colorScheme.primary,
        ),
        modifier = if (icon == null && description != null) {
            Modifier.semantics { contentDescription = description }
        } else {
            Modifier
        },
    )
}

@Composable
private fun OpeningRow(opening: Opening, settings: PlanSettings, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        Icon(
            when (opening.type) {
                OpeningType.DOOR -> Icons.Filled.DoorFront
                OpeningType.WINDOW -> Icons.Filled.Window
                OpeningType.OPENING -> Icons.Outlined.SensorDoor
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                "${opening.type.label()} · ${settings.formatLength(opening.width)} wide",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                buildString {
                    append("Wall ${opening.wallIndex + 1} · ")
                    append("${settings.formatLength(opening.height)} high")
                    if (opening.type == OpeningType.WINDOW) append(" · sill ${settings.formatLength(opening.sillHeight)}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Measurements can only fail on corrupt geometry; the panel then shows dashes instead of crashing. */
private fun safeStats(room: Room): RoomStats? = try {
    Measurements.room(room)
} catch (e: Exception) {
    null
}
