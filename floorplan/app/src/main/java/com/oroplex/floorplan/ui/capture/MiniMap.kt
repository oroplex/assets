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
private const val HIGHLIGHT_COLOR: Int = 0xFFFFB300.toInt()

/**
 * Static part of the mini-map (changes only when the capture state does): the session's rooms and the
 * draft drawn by [PlanRenderer] once solved, or the raw observation segments / corner taps before that.
 * [focus] is what the view auto-fits to (the draft), before adding the camera.
 *
 * Everything is drawn in ONE frame, the floor as stored: saved session rooms are stored scaled by the floor's
 * tape calibration, so the draft ([savedDraft], the room as it will be saved), the raw captures and the camera
 * ([scale], applied with [mapCamera]) are mapped into that frame too. Wall lengths then read as they will be
 * saved, and earlier rooms sit where they really are next to the live one.
 */
internal data class MiniMapContent(val drawList: DrawList, val focus: Bounds, val scale: AppliedScale?) {
    /** A raw AR plan point (camera, aim) in the mini-map's frame. */
    fun mapCamera(p: Vec2): Vec2 = scale?.map(p) ?: p

    companion object {
        /**
         * [savedDraft] is the draft room as it will be saved (CaptureViewModel.roomAsSaved), null while unsolved.
         * Overall dimension lines are off: on a 150 dp card they are unreadable and look like conflicting sizes.
         */
        fun build(ui: CaptureUiState, savedDraft: Room?): MiniMapContent {
            val draft = ui.draft
            val scale = ui.savedScale
            fun map(p: Vec2): Vec2 = scale?.map(p) ?: p
            val rooms: List<Room> = ui.sessionRooms + listOfNotNull(savedDraft)
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
                            selectedRoomId = savedDraft?.id,
                            showOverallDimensions = false,
                        ),
                    )
                } catch (e: Exception) {
                    null
                }
            }
            val ops = ArrayList<DrawOp>()
            rendered?.let { ops += it.ops }
            if (savedDraft == null || rendered == null) ops += rawDraftOps(draft, savedDraft, ::map)
            ops += recaptureOps(draft, ::map)

            val focusPoints: List<Vec2> = when {
                savedDraft != null -> savedDraft.corners
                draft.method == CaptureMethod.CORNERS -> draft.cornerTaps.map(::map)
                else -> draft.observations.flatMap { listOf(map(it.extentStart), map(it.extentEnd)) }
            }.filter { it.isFinite() }
            val focus = if (focusPoints.isNotEmpty()) Bounds.of(focusPoints) else Bounds.EMPTY
            return MiniMapContent(DrawList(ops, rendered?.bounds ?: focus), focus, scale)
        }

        /** The wall or corner being re-captured, drawn on top in amber so the user sees which one to aim at. */
        private fun recaptureOps(d: RoomDraft, map: (Vec2) -> Vec2): List<DrawOp> {
            val i = d.recaptureIndex ?: return emptyList()
            return if (d.method == CaptureMethod.CORNERS) {
                val p = d.cornerTaps.getOrNull(i)?.takeIf { it.isFinite() } ?: return emptyList()
                listOf(DrawOp.Dot(map(p), radiusPt = 6.0, fillColor = HIGHLIGHT_COLOR, strokeColor = PlanColors.HANDLE_FILL))
            } else {
                val o = d.observations.getOrNull(i) ?: return emptyList()
                if (!o.extentStart.isFinite() || !o.extentEnd.isFinite()) return emptyList()
                listOf(DrawOp.Polyline(listOf(map(o.extentStart), map(o.extentEnd)), closed = false, widthPt = 7.0, color = HIGHLIGHT_COLOR))
            }
        }

        /** Unsolved draft (or renderer failure): observation segments with wall numbers, corner taps. */
        private fun rawDraftOps(d: RoomDraft, savedDraft: Room?, map: (Vec2) -> Vec2): List<DrawOp> {
            val ops = ArrayList<DrawOp>()
            if (savedDraft != null) {
                ops += DrawOp.Polyline(savedDraft.corners, closed = true, widthPt = 3.0, color = PlanColors.WALL)
                return ops
            }
            d.observations.forEachIndexed { i, o ->
                if (!o.extentStart.isFinite() || !o.extentEnd.isFinite()) return@forEachIndexed
                val a = map(o.extentStart)
                val b = map(o.extentEnd)
                ops += DrawOp.Polyline(listOf(a, b), closed = false, widthPt = 4.0, color = OBSERVATION_COLOR)
                val normal = o.line.normal.takeIf { it.isFinite() } ?: Vec2.ZERO
                ops += DrawOp.Text(
                    anchor = Vec2.lerp(a, b, 0.5) + normal * 0.35,
                    text = "${i + 1}",
                    sizePt = 14.0,
                    color = PlanColors.TEXT,
                    bold = true,
                )
            }
            if (d.method == CaptureMethod.CORNERS && d.cornerTaps.isNotEmpty()) {
                val taps = d.cornerTaps.filter { it.isFinite() }.map(map)
                if (taps.size >= 2) {
                    ops += DrawOp.Polyline(taps, closed = false, widthPt = 3.0, color = OBSERVATION_COLOR)
                }
                taps.forEach { ops += DrawOp.Dot(it, radiusPt = 4.0, fillColor = PlanColors.HANDLE_FILL, strokeColor = OBSERVATION_COLOR) }
            }
            return ops
        }
    }
}

/**
 * Top-down mini-map card: the room being captured (and this session's earlier rooms) plus the phone's
 * position and aim, auto-fitted around the draft and the camera. Redrawn every AR frame from [frame]
 * without recomposition. [savedDraft] is the draft as it will be saved (see [MiniMapContent]).
 */
@Composable
internal fun MiniMap(
    ui: CaptureUiState,
    savedDraft: Room?,
    frame: () -> ArFrameState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val pxPerPt = density * 160.0 / 72.0 * MINI_MAP_PT_SCALE
    val content = remember(ui.draft, savedDraft, ui.sessionRooms, ui.settings, ui.sessionId, ui.floorCalibration) {
        MiniMapContent.build(ui, savedDraft)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 6.dp,
    ) {
        Box {
            Canvas(Modifier.fillMaxSize().clipToBounds()) {
                val f = frame()
                val rawCamera = f.cameraPosition?.takeIf { it.isFinite() }?.toPlan()
                val rawAim = f.reticle?.world?.takeIf { it.isFinite() }?.toPlan()
                    ?.takeIf { rawCamera != null && it.distanceTo(rawCamera) <= MAX_AIM_LINE }
                val camera = rawCamera?.let(content::mapCamera)
                val aim = rawAim?.let(content::mapCamera)

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
