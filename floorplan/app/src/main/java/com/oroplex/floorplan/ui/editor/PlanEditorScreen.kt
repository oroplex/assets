package com.oroplex.floorplan.ui.editor

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun PlanEditorScreen(
    projectId: String,
    onScanRoom: (projectId: String, floorId: String) -> Unit,
    onExport: (projectId: String) -> Unit,
    onBack: () -> Unit,
) {
    Text("TODO: PlanEditorScreen")
}
