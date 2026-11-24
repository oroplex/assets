package com.oroplex.floorplan.ui.common

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.render.PlanPainter

/**
 * Small label-free drawing of a project's first non-empty floor, for list cards. Shows [placeholder]
 * while the project is loading, when it has no rooms, or when the plan cannot be drawn.
 */
@Composable
fun PlanThumbnail(project: Project?, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val rooms = project?.floors?.firstOrNull { it.rooms.isNotEmpty() }?.rooms.orEmpty()
    val settings = project?.settings
    val drawList = remember(rooms, settings) {
        if (rooms.isEmpty() || settings == null) null else renderThumbnail(rooms, settings)
    }
    if (drawList == null || drawList.bounds.isEmpty) {
        placeholder()
        return
    }
    val density = LocalDensity.current
    val marginPx = with(density) { 4.dp.toPx() }.toDouble()
    // Point sizes are scaled way down so door leaves and window lines stay hairlines at this size.
    val pxPerPt = density.density * 0.35
    Canvas(modifier) {
        val view = ViewTransform.fit(drawList.bounds, size.width.toDouble(), size.height.toDouble(), marginPx, pxPerPt)
        drawIntoCanvas { PlanPainter.draw(it.nativeCanvas, drawList, view) }
    }
}

private fun renderThumbnail(rooms: List<Room>, settings: PlanSettings): DrawList? = try {
    PlanRenderer.render(
        rooms,
        settings,
        RenderOptions(showRoomNames = false, showRoomDimensions = false, showAreas = false, showWallLengths = false),
    )
} catch (e: Exception) {
    Log.w("PlanThumbnail", "Could not render a thumbnail", e)
    null
}
