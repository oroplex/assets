package com.oroplex.floorplan.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.render.PlanPainter
import kotlin.math.max

/** DrawList point sizes are shrunk on the small card so labels and strokes stay proportionate. */
private const val MINI_MAP_PT_SCALE = 0.5

/** Smallest plan span (meters) the card zooms to, so the first wall isn't blown up to fill it. */
private const val MIN_SPAN = 3.0

/** The aim line is only drawn for reticle hits this close (meters). */
private const val MAX_AIM_LINE = 8.0

private const val CAMERA_COLOR: Int = 0xFF1A73E8.toInt()
private const val OBSERVATION_COLOR: Int = 0xFF1A73E8.toInt()

/**
 * Static part of the mini-map (changes only when the capture state does): the session's rooms and the
 * draft drawn by [PlanRenderer] once solved, or the raw observation segments / corner taps before that.
 * [focus] is what the view auto-fits to (the draft), before adding the camera.
 */
internal data class MiniMapContent(val drawList: DrawList, val focus: Bounds) {
    companion object {
        fun build(ui: CaptureUiState): MiniMapContent {
            val draft = ui.draft
            val draftRoom = ui.draftRoom
            val rooms: List<Room> = ui.sessionRooms + listOfNotNull(draftRoom)
            val rendered = if (rooms.isEmpty()) {
                null
            } else {
                try {
                    PlanRenderer.render(
                        rooms,
                        ui.settings,
                        RenderOptions(
                            showRoomNames = false,
                            showRoomDimensions = false,
                            showAreas = false,
                            showWallLengths = true,
                            selectedRoomId = draftRoom?.id,
                        ),
                    )
                } catch (e: Exception) {
                    null
                }
            }
            val ops = ArrayList<DrawOp>()
            rendered?.let { ops += it.ops }
            if (draftRoom == null || rendered == null) ops += rawDraftOps(ui, draftRoom)

            val focusPoints: List<Vec2> = when {
                draftRoom != null -> draftRoom.corners
                draft.method == CaptureMethod.CORNERS -> draft.cornerTaps
                else -> draft.observations.flatMap { listOf(it.extentStart, it.extentEnd) }
            }.filter { it.isFinite() }
            val focus = if (focusPoints.isNotEmpty()) Bounds.of(focusPoints) else Bounds.EMPTY
            return MiniMapContent(DrawList(ops, rendered?.bounds ?: focus), focus)
        }

        /** Unsolved draft (or renderer failure): observation segments with wall numbers, corner taps. */
        private fun rawDraftOps(ui: CaptureUiState, draftRoom: Room?): List<DrawOp> {
            val ops = ArrayList<DrawOp>()
            val d = ui.draft
            if (draftRoom != null) {
                ops += DrawOp.Polyline(draftRoom.corners, closed = true, widthPt = 3.0, color = PlanColors.WALL)
                return ops
            }
            d.observations.forEachIndexed { i, o ->
                if (!o.extentStart.isFinite() || !o.extentEnd.isFinite()) return@forEachIndexed
                ops += DrawOp.Polyline(listOf(o.extentStart, o.extentEnd), closed = false, widthPt = 4.0, color = OBSERVATION_COLOR)
                ops += DrawOp.Text(
                    anchor = Vec2.lerp(o.extentStart, o.extentEnd, 0.5) + o.line.normal * 0.35,
                    text = "${i + 1}",
                    sizePt = 14.0,
                    color = PlanColors.TEXT,
                    bold = true,
                )
            }
            if (d.method == CaptureMethod.CORNERS && d.cornerTaps.isNotEmpty()) {
                if (d.cornerTaps.size >= 2) {
                    ops += DrawOp.Polyline(d.cornerTaps, closed = false, widthPt = 3.0, color = OBSERVATION_COLOR)
                }
                d.cornerTaps.forEach { ops += DrawOp.Dot(it, radiusPt = 4.0, fillColor = PlanColors.HANDLE_FILL, strokeColor = OBSERVATION_COLOR) }
            }
            return ops
        }
    }
}

/**
 * Top-down mini-map card: the room being captured (and this session's earlier rooms) plus the phone's
 * position and aim, auto-fitted around the draft and the camera. Redrawn every AR frame from [frame]
 * without recomposition.
 */
@Composable
internal fun MiniMap(
    ui: CaptureUiState,
    frame: () -> ArFrameState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val pxPerPt = density * 160.0 / 72.0 * MINI_MAP_PT_SCALE
    val content = remember(ui.draft, ui.sessionRooms, ui.settings, ui.sessionId) { MiniMapContent.build(ui) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 6.dp,
    ) {
        Box {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val f = frame()
                val camera = f.cameraPosition?.takeIf { it.isFinite() }?.toPlan()
                val aim = f.reticle?.world?.takeIf { it.isFinite() }?.toPlan()
                    ?.takeIf { camera != null && it.distanceTo(camera) <= MAX_AIM_LINE }

                var bounds = content.focus
                if (camera != null) bounds = bounds.include(camera)
                val view = ViewTransform.fit(
                    withMinSpan(bounds, MIN_SPAN),
                    size.width.toDouble(),
                    size.height.toDouble(),
                    marginPx = 12.dp.toPx().toDouble(),
                    pxPerPt = pxPerPt,
                )
                val dynamicOps = ArrayList<DrawOp>(3)
                if (camera != null) {
                    if (aim != null) {
                        dynamicOps += DrawOp.Polyline(listOf(camera, aim), closed = false, widthPt = 2.0, color = CAMERA_COLOR, dashPt = 4.0)
                    }
                    dynamicOps += DrawOp.Dot(camera, radiusPt = 6.0, fillColor = CAMERA_COLOR, strokeColor = PlanColors.HANDLE_FILL)
                }
                drawIntoCanvas { canvas ->
                    if (content.drawList.ops.isNotEmpty()) PlanPainter.draw(canvas.nativeCanvas, content.drawList, view)
                    if (dynamicOps.isNotEmpty()) {
                        PlanPainter.draw(canvas.nativeCanvas, DrawList(dynamicOps, Bounds.of(listOfNotNull(camera, aim))), view)
                    }
                }
            }
            Text(
                text = "Top view",
                style = MaterialTheme.typography.labelSmall,
                color = Color(PlanColors.TEXT_SECONDARY),
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Grow [b] symmetrically so both sides span at least [minSpan] meters (empty stays empty). */
internal fun withMinSpan(b: Bounds, minSpan: Double): Bounds {
    if (b.isEmpty) return b
    val c = b.center
    val halfW = max(b.width, minSpan) / 2
    val halfH = max(b.height, minSpan) / 2
    return Bounds(c.x - halfW, c.y - halfH, c.x + halfW, c.y + halfH)
}
