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
import androidx.compose.material.icons.automirrored.filled.AlignHorizontalLeft
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Window
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import com.oroplex.floorplan.core.solve.QualityLevel
import com.oroplex.floorplan.core.solve.RoomQualityReport
import com.oroplex.floorplan.ui.common.formatArea
import com.oroplex.floorplan.ui.common.formatLength
import com.oroplex.floorplan.ui.common.formatVolume
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
    val onSnap: () -> Unit,
    val onRotate: (degrees: Double) -> Unit,
    val onResolve: () -> Unit,
    val onDelete: () -> Unit,
    val onClose: () -> Unit,
    /** Switch "Whole scan" on or off (only offered when the room shares its scan with other rooms). */
    val onWholeScan: (Boolean) -> Unit = {},
    /** "Square to floor": turn the room (or its whole scan) to run like the rest of the floor. */
    val onSquare: () -> Unit = {},
)

/**
 * Details and actions of the selected room. [quality] is its trust grade (null when it could not be graded);
 * [snapTarget] names the room a move in progress would snap to. [scanSize] is the number of rooms sharing the
 * room's scan (0 or 1: no "Whole scan" choice); with [wholeScan] on, Move, the turn buttons, Square to floor and
 * Snap act on all of them. [canSquare]: the floor has other rooms to square to.
 */
@Composable
fun RoomPanel(
    room: Room,
    settings: PlanSettings,
    moveMode: Boolean,
    actions: RoomPanelActions,
    quality: RoomQualityReport? = null,
    snapTarget: String? = null,
    wholeScan: Boolean = false,
    scanSize: Int = 0,
    canSquare: Boolean = true,
) {
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell("Size", stats?.let { "${settings.formatLength(it.length)} × ${settings.formatLength(it.width)}" } ?: "—")
        StatCell("Area", stats?.let { settings.formatArea(it.area) } ?: "—")
        StatCell(
            label = "Ceiling",
            value = ceilingText(room, settings),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Edit ceiling height", role = Role.Button, onClick = actions.onEditCeiling),
            editable = true,
        )
    }
    val figures = roomFigures(room, stats, settings)
    // Equal columns that wrap, so "1,234 sq ft (est.)" never runs off a narrow phone.
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val style = MaterialTheme.typography.titleSmall
        StatCell("Perimeter", figures.perimeter, Modifier.weight(1f), valueStyle = style)
        StatCell("Wall area", figures.wallArea, Modifier.weight(1f), valueStyle = style)
        StatCell("Volume", figures.volume, Modifier.weight(1f), valueStyle = style)
    }
    // Drawn rooms have no scan evidence to grade (the subtitle already says "drawn").
    if (quality != null && quality.level != QualityLevel.MANUAL) QualitySummary(quality, room.warnings)
    val block = wholeScan && scanSize > 1
    if (moveMode) {
        // Two lines either way, so the panel (and with it the plan) does not change height mid-drag.
        Text(
            when {
                snapTarget != null -> "Let go to snap to $snapTarget.\nThe dashed outline shows where it lands."
                block -> "Drag anywhere to move all $scanSize rooms of this scan. Let go next to another room and they snap into place."
                else -> "Drag anywhere on the plan to move this room. Let go next to another room and it snaps into place."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            minLines = 2,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
    Warnings(room.warnings)
    if (scanSize > 1) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            FilterChip(
                selected = wholeScan,
                onClick = { actions.onWholeScan(!wholeScan) },
                label = { Text("Whole scan ($scanSize rooms)") },
                leadingIcon = {
                    Icon(
                        if (wholeScan) Icons.Filled.Check else Icons.Filled.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Whole scan, $scanSize rooms, ${if (wholeScan) "on" else "off"}"
                },
            )
            Text(
                if (wholeScan) "Move, turn and snap act on every room of this scan." else "Only ${room.name} moves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
            )
        }
    }
    val what = if (block) "this scan" else room.name
    ActionRow {
        FilterChip(
            selected = moveMode,
            onClick = actions.onToggleMove,
            label = { Text("Move") },
            leadingIcon = { Icon(Icons.Filled.OpenWith, contentDescription = null, Modifier.size(18.dp)) },
        )
        ActionChip("Snap to neighbour", Icons.AutoMirrored.Filled.AlignHorizontalLeft, null, onClick = actions.onSnap)
        if (canSquare) ActionChip("Square to floor", Icons.Filled.CropRotate, null, onClick = actions.onSquare)
        ActionChip("90°", Icons.Filled.Rotate90DegreesCw, "Rotate $what 90° clockwise") { actions.onRotate(-90.0) }
        ActionChip("1°", null, "Rotate $what 1° clockwise", prefix = "−") { actions.onRotate(-1.0) }
        ActionChip("1°", null, "Rotate $what 1° counter-clockwise", prefix = "+") { actions.onRotate(1.0) }
        ActionChip("90°", Icons.Filled.Rotate90DegreesCcw, "Rotate $what 90° counter-clockwise") { actions.onRotate(90.0) }
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

/**
 * The selected wall: its length (and whether it was set by hand), its doors and windows, and wall actions.
 * Calibration applies to scanned rooms only, so drawn rooms say their sizes are as typed instead.
 */
@Composable
fun WallPanel(room: Room, wallIndex: Int, settings: PlanSettings, calibration: Calibration?, actions: WallPanelActions) {
    val length = edgeLength(room, wallIndex)
    val handSet = remember(room, wallIndex) { handSetWall(room, wallIndex) }
    val drawn = room.captureMethod == CaptureMethod.MANUAL
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
            if (handSet != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        Icons.Filled.Straighten,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        handSet.typedLength?.takeIf { it.isFinite() && it > 0 }
                            ?.let { "Set by hand (typed ${settings.formatLength(it)})" } ?: "Set by hand",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
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
        // Calibration corrects AR scale error, so it is measured from a wall of a scanned room.
        if (!drawn) ActionChip("Calibrate floor", Icons.Filled.SquareFoot, null, onClick = actions.onCalibrate)
    }
    val onWall = room.openings.filter { it.wallIndex == wallIndex }.sortedBy { it.offset }
    if (onWall.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        onWall.forEach { OpeningRow(it, settings, onClick = { actions.onOpenOpening(it) }) }
    }
    wallNote(drawn, calibration)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Note under a wall's details: drawn rooms keep their typed sizes ("Drawn room — sizes are as typed."); a
 * scanned room on a tape-calibrated floor shows the calibration; null otherwise.
 */
internal fun wallNote(drawn: Boolean, calibration: Calibration?): String? = when {
    drawn -> "Drawn room — sizes are as typed."
    calibration != null && calibration.factor.isFinite() ->
        "Floor calibrated ×${"%.4f".format(Locale.US, calibration.factor)} from a tape measurement."
    else -> null
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
        "Drag the blue handle to move this corner. It clicks into square corners and lines up with the other corners.",
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
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    valueStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = valueStyle)
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

/**
 * Scan quality badge plus the reasons behind a grade below "good" (reasons that repeat a solver warning shown
 * right below are left out).
 */
@Composable
private fun QualitySummary(report: RoomQualityReport, warnings: List<String>) {
    val style = qualityStyle(report.level)
    Column(Modifier.padding(vertical = 4.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = style.container,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Scan quality: ${style.label}" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size(16.dp))
                Text(
                    style.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = style.content,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        if (report.level != QualityLevel.GOOD) {
            report.reasons.filter { it.isNotBlank() && it !in warnings }.forEach { reason ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp),
                    )
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

private class QualityStyle(val label: String, val icon: ImageVector, val container: Color, val content: Color)

@Composable
private fun qualityStyle(level: QualityLevel): QualityStyle = when (level) {
    QualityLevel.GOOD -> QualityStyle("Good scan", Icons.Filled.Verified, GoodContainer, GoodContent)
    QualityLevel.FAIR -> QualityStyle("Fair scan: worth a quick check", Icons.Outlined.Info, FairContainer, FairContent)
    QualityLevel.POOR -> QualityStyle(
        "Check this room",
        Icons.Filled.Warning,
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    QualityLevel.MANUAL -> QualityStyle(
        "Drawn by hand",
        Icons.Filled.Draw,
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val GoodContainer = Color(0xFFE6F4EA)
private val GoodContent = Color(0xFF0D652D)
private val FairContainer = Color(0xFFFEF7E0)
private val FairContent = Color(0xFF7A4F01)

/** "8' 0\"", "8' 0\" (est.)" for an assumed default, or "Not set". */
internal fun ceilingText(room: Room, settings: PlanSettings): String {
    val height = room.ceilingHeight?.takeIf { it.isFinite() && it > 0 } ?: return "Not set"
    val text = settings.formatLength(height)
    return if (room.ceilingEstimated) "$text (est.)" else text
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

/** The room panel's second row of figures, formatted ("—" when unknown). */
internal data class RoomFigures(val perimeter: String, val wallArea: String, val volume: String)

/**
 * Perimeter, wall area (net of doors and windows) and volume of [room] from [stats]. Wall area and volume need a
 * ceiling height ("—" without one) and are marked "(est.)" when the ceiling is an assumed default.
 */
internal fun roomFigures(room: Room, stats: RoomStats?, settings: PlanSettings): RoomFigures {
    val est = if (room.ceilingEstimated) " (est.)" else ""
    val perimeter = stats?.perimeter?.takeIf { it.isFinite() && it > 0 }?.let { settings.formatLength(it) } ?: "—"
    val wallArea = stats?.wallArea?.takeIf { it.isFinite() }?.let { settings.formatArea(it) + est } ?: "—"
    val volume = stats?.volume?.takeIf { it.isFinite() }?.let { settings.formatVolume(it) + est } ?: "—"
    return RoomFigures(perimeter, wallArea, volume)
}

/** Measurements can only fail on corrupt geometry; the panel then shows dashes instead of crashing. */
internal fun safeStats(room: Room): RoomStats? = try {
    Measurements.room(room)
} catch (e: Exception) {
    null
}
