package com.oroplex.floorplan.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.PlanRenderer
import com.oroplex.floorplan.core.render.RenderOptions
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.render.PlanPainter

private const val TAG = "PlanCanvas"

/** Finger targets (dp). */
private val CornerHandleRadius = 24.dp
private val WallTolerance = 16.dp
private val LabelSlop = 8.dp
private val FitMargin = 32.dp
private val SnapTolerance = 8.dp
private val DoubleTapSlop = 48.dp
private const val DOUBLE_TAP_MS = 300L

/** Zoom limits in px per meter, per unit of screen density. */
private const val MIN_PX_PER_METER_PER_DENSITY = 2.0
private const val MAX_PX_PER_METER_PER_DENSITY = 2000.0

/**
 * The interactive plan of one floor.
 *
 * Gestures (one custom detector so they never fight each other):
 *  * two fingers: pinch-zoom and pan, always;
 *  * one finger starting on a corner handle of the selected room: drag that corner (live preview, snapped
 *    square to its neighbours, committed on release as one undo step);
 *  * one finger in Move mode with a room selected: translate the room;
 *  * any other one-finger drag: pan;
 *  * tap: select corner handle > wall-length label > wall > room > nothing; double tap: fit the plan.
 */
@Composable
fun PlanCanvas(
    viewModel: PlanEditorViewModel,
    floorId: String,
    rooms: List<Room>,
    settings: PlanSettings,
    selection: Selection?,
    moveMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerPt = density.density * 160.0 / 72.0
    val options = RenderOptions(
        selectedRoomId = selection?.roomId,
        showCornerHandles = selection != null,
        selectedWall = (selection as? Selection.Wall)?.wallIndex,
    )
    val render = remember(rooms, settings, options) { renderSafely(rooms, settings, options) }
    val drawList = render ?: EMPTY_DRAW_LIST

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val fitMarginPx = with(density) { FitMargin.toPx() }.toDouble()

    // Fit when this floor has no transform yet (first layout, double tap, new rooms...). Derived so that
    // pan/zoom updates (which only change the transform) redraw the canvas without recomposing it.
    val needsFit by remember(viewModel, floorId) { derivedStateOf { viewModel.viewFor(floorId) == null } }
    LaunchedEffect(needsFit, canvasSize, floorId, drawList.bounds) {
        if (needsFit && canvasSize.width > 0 && canvasSize.height > 0) {
            viewModel.setView(floorId, fitView(drawList.bounds, canvasSize, fitMarginPx, pxPerPt))
        }
    }

    // When a selection opens the bottom panel the canvas gets shorter; nudge the plan up so the selected
    // room is not left hidden below the new bottom edge.
    val latestRooms by rememberUpdatedState(rooms)
    val selectedRoomId = selection?.roomId
    LaunchedEffect(selectedRoomId, canvasSize, floorId) {
        val id = selectedRoomId ?: return@LaunchedEffect
        val view = viewModel.viewFor(floorId) ?: return@LaunchedEffect
        val room = latestRooms.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val dy = revealShift(room, view, canvasSize.height.toDouble(), fitMarginPx)
        if (dy < 0) viewModel.setView(floorId, view.panned(Vec2(0.0, dy)))
    }

    val gestureState = rememberUpdatedState(
        GestureInputs(viewModel, floorId, rooms, drawList, settings, selection, moveMode, canvasSize, pxPerPt, fitMarginPx),
    )
    val textMeasurer = remember { LabelMeasurer() }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(floorId) { detectPlanGestures(gestureState::value, textMeasurer) },
        ) {
            val view = viewModel.viewFor(floorId) ?: return@Canvas
            drawIntoCanvas { canvas ->
                PlanPainter.draw(canvas.nativeCanvas, drawList, view, background = PlanColors.BACKGROUND)
            }
        }
        if (render == null && rooms.isNotEmpty()) {
            Text(
                "This floor couldn't be drawn. Its data may be damaged; try undoing the last change.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
    }
}

private val EMPTY_DRAW_LIST = DrawList(emptyList(), Bounds.EMPTY)

private fun renderSafely(rooms: List<Room>, settings: PlanSettings, options: RenderOptions): DrawList? = try {
    PlanRenderer.render(rooms, settings, options)
} catch (e: Exception) {
    Log.e(TAG, "Rendering the floor failed", e)
    null
}

private fun fitView(bounds: Bounds, size: IntSize, marginPx: Double, pxPerPt: Double): ViewTransform =
    ViewTransform.fit(bounds, size.width.toDouble(), size.height.toDouble(), marginPx, pxPerPt)

/**
 * Vertical screen shift (pixels, <= 0) that brings the bottom of [room] above the canvas bottom minus
 * [marginPx], without pushing its top above [marginPx]. 0 when nothing needs to move.
 */
internal fun revealShift(room: Room, view: ViewTransform, heightPx: Double, marginPx: Double): Double {
    if (heightPx <= 0) return 0.0
    val screen = room.corners.filter { it.isFinite() }.map { view.toScreen(it) }
    if (screen.isEmpty()) return 0.0
    val top = screen.minOf { it.y }
    val bottom = screen.maxOf { it.y }
    val overflow = bottom - (heightPx - marginPx)
    if (overflow <= 0) return 0.0
    val maxShift = (top - marginPx).coerceAtLeast(0.0)
    return -minOf(overflow, maxShift)
}

/** Everything the gesture detector needs, refreshed every composition. */
private data class GestureInputs(
    val viewModel: PlanEditorViewModel,
    val floorId: String,
    val rooms: List<Room>,
    val drawList: DrawList,
    val settings: PlanSettings,
    val selection: Selection?,
    val moveMode: Boolean,
    val canvasSize: IntSize,
    val pxPerPt: Double,
    val fitMarginPx: Double,
)

private enum class DragKind { PAN, CORNER, MOVE }

private suspend fun PointerInputScope.detectPlanGestures(inputs: () -> GestureInputs, measurer: LabelMeasurer) {
    val cornerRadiusPx = CornerHandleRadius.toPx().toDouble()
    val wallTolPx = WallTolerance.toPx().toDouble()
    val labelSlopPx = LabelSlop.toPx().toDouble()
    val snapPx = SnapTolerance.toPx().toDouble()
    val doubleTapSlopPx = DoubleTapSlop.toPx()
    val minPxPerMeter = MIN_PX_PER_METER_PER_DENSITY * density
    val maxPxPerMeter = MAX_PX_PER_METER_PER_DENSITY * density
    var lastTapTime = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = inputs()
        val startView = start.viewModel.viewFor(start.floorId)
        val downPlan = startView?.toPlan(down.position.toVec2())

        // What a one-finger drag from here would do, decided at touch-down.
        var kind = DragKind.PAN
        var cornerIndex = -1
        var grabOffset = Vec2.ZERO
        val selectedRoom = start.selection?.let { s -> start.rooms.firstOrNull { it.id == s.roomId } }
        if (startView != null && selectedRoom != null && downPlan != null) {
            val corner = EditorHitTest.cornerAt(selectedRoom, down.position.toVec2(), startView, cornerRadiusPx)
            if (corner != null) {
                kind = DragKind.CORNER
                cornerIndex = corner
                grabOffset = selectedRoom.corners[corner] - downPlan
            } else if (start.moveMode) {
                kind = DragKind.MOVE
            }
        }

        var dragging = false
        var transforming = false
        var multiTouch = false
        val slop = viewConfiguration.touchSlop

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (dragging && !transforming) {
                    if (kind != DragKind.PAN) start.viewModel.endDrag()
                } else if (!dragging && !multiTouch) {
                    val now = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                    val isDouble = now - lastTapTime < DOUBLE_TAP_MS && (down.position - lastTapPosition).getDistance() < doubleTapSlopPx
                    if (isDouble) {
                        lastTapTime = 0L
                        start.viewModel.requestFit(start.floorId)
                    } else {
                        lastTapTime = now
                        lastTapPosition = down.position
                        handleTap(inputs(), down.position.toVec2(), cornerRadiusPx, wallTolPx, labelSlopPx, measurer)
                    }
                }
                break
            }
            val current = inputs()
            val view = current.viewModel.viewFor(current.floorId)
            if (view == null) {
                event.changes.forEach { it.consume() }
                continue
            }
            if (pressed.size >= 2) {
                multiTouch = true
                if (dragging && !transforming && kind != DragKind.PAN) current.viewModel.cancelDrag()
                transforming = true
                dragging = true
                val zoom = event.calculateZoom().toDouble()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                var next = view
                if (zoom.isFinite() && zoom > 0 && zoom != 1.0) {
                    val target = (view.pxPerMeter * zoom).coerceIn(minPxPerMeter, maxPxPerMeter)
                    next = next.zoomedAround(centroid.toVec2(), target / view.pxPerMeter)
                }
                if (pan != Offset.Zero) next = next.panned(pan.toVec2())
                if (next != view) current.viewModel.setView(current.floorId, next)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            if (transforming) {
                // A finger lifted after a pinch: keep panning with the remaining one.
                val delta = change.positionChange()
                if (delta != Offset.Zero) current.viewModel.setView(current.floorId, view.panned(delta.toVec2()))
                change.consume()
                continue
            }
            if (!dragging) {
                if ((change.position - down.position).getDistance() <= slop) continue
                dragging = true
                val started = when (kind) {
                    DragKind.CORNER -> selectedRoom != null && current.viewModel.beginCornerDrag(selectedRoom.id, cornerIndex)
                    DragKind.MOVE -> selectedRoom != null && current.viewModel.beginMove(selectedRoom.id)
                    DragKind.PAN -> true
                }
                if (!started) kind = DragKind.PAN
            }
            val plan = view.toPlan(change.position.toVec2())
            when (kind) {
                DragKind.PAN -> {
                    val delta = change.positionChange()
                    if (delta != Offset.Zero) current.viewModel.setView(current.floorId, view.panned(delta.toVec2()))
                }
                DragKind.CORNER -> {
                    val base = selectedRoom
                    if (base != null) {
                        val target = plan + grabOffset
                        val snapped = CornerSnap.snap(
                            base.corners, cornerIndex, target, CornerSnap.axisAngle(base.corners), snapPx / view.pxPerMeter,
                        )
                        current.viewModel.updateCornerDrag(snapped)
                    }
                }
                DragKind.MOVE -> if (downPlan != null) current.viewModel.updateMove(plan - downPlan)
            }
            change.consume()
        }
    }
}

private fun handleTap(
    inputs: GestureInputs,
    screen: Vec2,
    cornerRadiusPx: Double,
    wallTolPx: Double,
    labelSlopPx: Double,
    measurer: LabelMeasurer,
) {
    val view = inputs.viewModel.viewFor(inputs.floorId) ?: return
    val plan = view.toPlan(screen)
    val selectedRoom = inputs.selection?.let { s -> inputs.rooms.firstOrNull { it.id == s.roomId } }
    val selection: Selection? = run {
        if (selectedRoom != null) {
            EditorHitTest.cornerAt(selectedRoom, screen, view, cornerRadiusPx)?.let { return@run Selection.Corner(selectedRoom.id, it) }
        }
        EditorHitTest.wallLabelAt(inputs.drawList, screen, view, labelSlopPx, measurer::widthPx)
            ?.let { (roomId, index) -> return@run Selection.Wall(roomId, index) }
        EditorHitTest.wallAt(inputs.rooms, plan, wallTolPx / view.pxPerMeter, inputs.settings.wallThickness, selectedRoom?.id)
            ?.let { (roomId, index) -> return@run Selection.Wall(roomId, index) }
        EditorHitTest.roomAt(inputs.rooms, plan)?.let { return@run Selection.Room(it) }
        null
    }
    inputs.viewModel.select(selection)
}

/** Measures label widths with the same typeface choice PlanPainter uses (main thread only). */
private class LabelMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun widthPx(op: DrawOp.Text, sizePx: Double): Double {
        if (!sizePx.isFinite() || sizePx <= 0) return 0.0
        paint.textSize = sizePx.toFloat()
        paint.typeface = if (op.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return op.text.split('\n').maxOf { paint.measureText(it).toDouble() }
    }
}

private fun Offset.toVec2() = Vec2(x.toDouble(), y.toDouble())
