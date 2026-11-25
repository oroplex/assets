@file:OptIn(ExperimentalMaterial3Api::class)

package com.oroplex.floorplan.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import kotlinx.coroutines.launch

/**
 * "Rooms" bottom sheet: every room of the floor with its type and size; tapping one closes the sheet and selects
 * it (zooming to it). Reaches rooms that are hidden under others or too small to tap, and works with TalkBack.
 */
@Composable
fun RoomsSheet(
    floor: Floor,
    settings: PlanSettings,
    selectedRoomId: String?,
    onSelectRoom: (roomId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Rooms", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (floor.rooms.isEmpty()) "${floor.name} has no rooms yet."
                    else "${floor.name} · tap a room to select it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                // No item keys: rooms from a hand-edited backup could share an id, which keys would crash on.
                itemsIndexed(floor.rooms) { index, room ->
                    if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                    val isSelected = room.id == selectedRoomId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .semantics { selected = isSelected }
                            .clickable(onClickLabel = "Select ${room.name}", role = Role.Button) {
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    onSelectRoom(room.id)
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            when {
                                isSelected -> Icons.Filled.CheckCircle
                                room.captureMethod == CaptureMethod.MANUAL -> Icons.Outlined.Draw
                                else -> Icons.Outlined.ViewInAr
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(room.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                roomSummary(room, settings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
