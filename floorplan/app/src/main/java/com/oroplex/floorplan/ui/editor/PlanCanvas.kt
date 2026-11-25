package com.oroplex.floorplan.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.ln
import kotlin.math.pow

private const val TAG = "PlanCanvas"

/** Finger targets (dp). */
private val CornerHandleRadius = 24.dp
private val WallTolerance = 16.dp
private val LabelSlop = 8.dp
private val FitMargin = 32.dp

/** Corner drags snap onto square / alignment guides within this distance (secondary guides: 3/4 of it). */
private val CornerSnapTolerance = 8.dp

/** A moved room released within this distance of a neighbour's wall snaps against it. */
private val MoveSnapTolerance = 24.dp
private val DoubleTapSlop = 48.dp
private const val DOUBLE_TAP_MS = 300L

/** Duration of the animated zoom-to-fit / focus moves. */
private const val VIEW_ANIMATION_MS = 360.0

/** Zoom limits in px per meter, per unit of screen density. */
private const val MIN_PX_PER_METER_PER_DENSITY = 2.0
private const val MAX_PX_PER_METER_PER_DENSITY = 2000.0

/** Snap guide styling (points). */
private const val GUIDE_PT = 1.0
private const val GUIDE_DASH_PT = 3.0
private const val GHOST_PT = 1.5
private const val GHOST_DASH_PT = 4.0
private const val GHOST_FILL = 0x221A73E8
private val GuideOvershoot = 20.dp
private val RightAngleSize = 10.dp

/**
 * The interactive plan of one floor.
 *
 * Gestures (one custom detector so they never fight each other):
 *  * two fingers: pinch-zoom and pan, always;
 *  * one finger starting on a corner handle of the selected room: drag that corner (live preview, snapped onto
 *    square and alignment guides with a haptic tick, committed on release as one undo step);
 *  * one finger in Move mode with a room selected: translate the room; near a neighbour a dashed outline shows
 *    where it will snap when released;
 *  * any other one-finger drag: pan;
 *  * tap: select corner handle > wall-length label > wall > room > nothing; double tap: zoom to fit.
 *
 * Drawing is clipped to the canvas, so neither the white background nor panned walls ever paint over the
 * floor tabs above it. While a whole scan is moved every room of it is previewed (and its snap landing ghosted).
 * For TalkBack and Switch Access the canvas describes every room with its size and offers actions to select a
 * room, then its walls and corners. View changes requested by the ViewModel (fit, focus a room) are animated unless the
 * system's animations are turned off; any gesture takes over immediately.
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
    highlightRoomIds: Set<String> = emptySet(),
    guides: DragGuides? = null,
) {
    val density = LocalDensity.current
    val pxPerPt = density.density * 160.0 / 72.0
    // Overall dimensions are sized for the current zoom. Derived (and bucketed) so pan/zoom only recompose
    // the canvas when the zoom moves to another step, not on every frame of a pinch.
    val pointsPerMeter by remember(viewModel, floorId) {
        derivedStateOf { viewModel.viewFor(floorId)?.let { quantizedPointsPerMeter(it) } }
    }
    val options = RenderOptions(
        selectedRoomId = selection?.roomId,
        showCornerHandles = selection != null,
        selectedWall = (selection as? Selection.Wall)?.wallIndex,
        highlightRoomIds = highlightRoomIds,
        pointsPerMeter = pointsPerMeter,
    )
    val render = remember(rooms, settings, options) { renderSafely(rooms, settings, options) }
    val drawList = render ?: EMPTY_DRAW_LIST

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val fitMarginPx = with(density) { FitMargin.toPx() }.toDouble()
    val overshootPx = with(density) { GuideOvershoot.toPx() }.toDouble()
    val rightAnglePx = with(density) { RightAngleSize.toPx() }.toDouble()

    // Fit when this floor has no transform yet (first layout, new floor...). Derived so that pan/zoom
    // updates (which only change the transform) redraw the canvas without recomposing it.
    val needsFit by remember(viewModel, floorId) { derivedStateOf { viewModel.viewFor(floorId) == null } }
    LaunchedEffect(needsFit, canvasSize, floorId, drawList.bounds) {
        // Checked again here: a focus request may already have placed the view in this frame.
        if (needsFit && viewModel.viewFor(floorId) == null && canvasSize.width > 0 && canvasSize.height > 0) {
            viewModel.setView(floorId, settledFit(rooms, settings, options, drawList.bounds, canvasSize, fitMarginPx, pxPerPt))
        }
    }

    // Animated view requests (zoom to fit, bring a room into view). The effect restarts from wherever the view
    // is when the canvas size or the plan's bounds change mid-way (the bottom panel opening, the rooms arriving
    // a frame after the request), and is cancelled when a pinch or pan drops the request.
    val context = LocalContext.current
    val animationsEnabled = remember(context) { animatorScale(context) > 0f }
    val request = viewModel.viewRequest(floorId)
    LaunchedEffect(request, canvasSize, drawList.bounds) {
        val r = request ?: return@LaunchedEffect
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@LaunchedEffect
        val w = canvasSize.width.toDouble()
        val h = canvasSize.height.toDouble()
        val fit = settledFit(rooms, settings, options, drawList.bounds, canvasSize, fitMarginPx, pxPerPt)
        val from = viewModel.viewFor(floorId)
        val target = when (r) {
            is ViewRequest.Fit -> fit
            is ViewRequest.Focus -> ViewAnimation.focus(from ?: fit, r.bounds, w, h, fitMarginPx)
        }
        if (from == null || !animationsEnabled || from == target) {
            viewModel.setView(floorId, target)
        } else {
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // A gesture dropped the request since the last frame: never overwrite the user's own pan.
                if (viewModel.viewRequest(floorId) != r) return@LaunchedEffect
                val t = ((now - start) / 1e6 / VIEW_ANIMATION_MS).coerceIn(0.0, 1.0)
                val eased = FastOutSlowInEasing.transform(t.toFloat()).toDouble()
                viewModel.setView(floorId, if (t >= 1.0) target else ViewAnimation.interpolate(from, target, eased, w, h))
                if (t >= 1.0) break
            }
        }
        viewModel.consumeViewRequest(floorId, r)
    }

    // When a selection opens the bottom panel the canvas gets shorter; nudge the plan up so the selected
    // room is not left hidden below the new bottom edge. A pending fit / focus takes care of that itself.
    val latestRooms by rememberUpdatedState(rooms)
    val selectedRoomId = selection?.roomId
    LaunchedEffect(selectedRoomId, canvasSize, floorId) {
        val id = selectedRoomId ?: return@LaunchedEffect
        // Never pan under a finger that is dragging, and leave a pending fit / focus to do its own job.
        if (viewModel.state.value.previewRooms.isNotEmpty() || viewModel.viewRequest(floorId) != null) return@LaunchedEffect
        val view = viewModel.viewFor(floorId) ?: return@LaunchedEffect
        val room = latestRooms.firstOrNull { it.id == id } ?: return@LaunchedEffect
        val dy = revealShift(room, view, canvasSize.height.toDouble(), fitMarginPx)
        if (dy < 0) viewModel.setView(floorId, view.panned(Vec2(0.0, dy)))
    }

    // A light tick whenever a drag snaps onto a new guide (square corner, alignment, neighbour wall).
    val haptics = LocalHapticFeedback.current
    val snapKey = guides?.snapKey
    LaunchedEffect(snapKey) {
        if (snapKey != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    val gestureState = rememberUpdatedState(
        GestureInputs(viewModel, floorId, rooms, drawList, settings, selection, moveMode, canvasSize, pxPerPt, fitMarginPx),
    )
    val textMeasurer = remember { LabelMeasurer() }
    val description = remember(rooms, selection, settings) { canvasDescription(rooms, selection, settings) }
    // TalkBack / Switch Access: select any room, then its walls and corners, without precise pointing.
    val a11yActions = remember(rooms, selection, settings, viewModel) {
        canvasActions(rooms, selection, settings).map { action ->
            CustomAccessibilityAction(action.label) {
                when (action) {
                    is CanvasAction.FocusRoom -> viewModel.focusRoom(action.roomId)
                    is CanvasAction.Select -> viewModel.select(action.selection)
                    is CanvasAction.Clear -> viewModel.clearSelection()
                }
                true
            }
        }
    }

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .semantics {
                    contentDescription = description
                    customActions = a11yActions
                }
                .pointerInput(floorId) { detectPlanGestures(gestureState::value, textMeasurer) },
        ) {
            val view = viewModel.viewFor(floorId) ?: return@Canvas
            drawIntoCanvas { canvas ->
                PlanPainter.draw(canvas.nativeCanvas, drawList, view, background = PlanColors.BACKGROUND)
                if (guides != null) {
                    val ops = guideOps(guides, view, overshootPx, rightAnglePx)
                    if (ops.isNotEmpty()) PlanPainter.draw(canvas.nativeCanvas, DrawList(ops, Bounds.EMPTY), view)
                }
            }
        }
        if (render == null && rooms.isNotEmpty()) {
            // On its own themed card: the plan paper is always white, so theme text colours need a backing.
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Text(
                    "This floor couldn't be drawn. Its data may be damaged; try undoing the last change.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
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

/**
 * Fit to [bounds], then once more to the bounds the plan has when rendered for that zoom: overall dimension
 * lines are sized in points (RenderOptions.pointsPerMeter), so their extent in meters depends on the zoom.
 */
private fun settledFit(
    rooms: List<Room>,
    settings: PlanSettings,
    options: RenderOptions,
    bounds: Bounds,
    size: IntSize,
    marginPx: Double,
    pxPerPt: Double,
): ViewTransform {
    val first = fitView(bounds, size, marginPx, pxPerPt)
    if (rooms.isEmpty()) return first
    val ppm = quantizedPointsPerMeter(first) ?: return first
    if (ppm == options.pointsPerMeter) return first
    val again = renderSafely(rooms, settings, options.copy(pointsPerMeter = ppm)) ?: return first
    return if (again.bounds.isEmpty) first else fitView(again.bounds, size, marginPx, pxPerPt)
}

private fun fitView(bounds: Bounds, size: IntSize, marginPx: Double, pxPerPt: Double): ViewTransform =
    ViewTransform.fit(bounds, size.width.toDouble(), size.height.toDouble(), marginPx, pxPerPt)

/** The system "animator duration scale" (0 when the user turned animations off). */
private fun animatorScale(context: android.content.Context): Float = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (e: RuntimeException) {
    1f
}

/**
 * Zoom steps per doubling of the scale. Labels are fitted to the rooms at the rounded scale, so the rounding
 * error (here at most about 4 %) has to stay inside the padding the fit leaves around a label.
 */
private const val ZOOM_STEPS_PER_OCTAVE = 8.0

/**
 * Points per plan meter of [view] (what RenderOptions.pointsPerMeter expects), rounded to eighth steps on a
 * log2 scale (about 9 % apart) so the plan is only re-rendered when the zoom changes noticeably. Null for an
 * unusable view.
 */
internal fun quantizedPointsPerMeter(view: ViewTransform): Double? {
    val ppm = view.pxPerMeter / view.pxPerPt
    if (!ppm.isFinite() || ppm <= 0.0) return null
    val step = Math.round(ln(ppm) / ln(2.0) * ZOOM_STEPS_PER_OCTAVE) / ZOOM_STEPS_PER_OCTAVE
    return 2.0.pow(step)
}

/**
 * Draw ops for the snap guides: dashed guide lines from the aligned corner through the dragged one (with a
 * short overshoot), small right-angle marks at square corners and a dashed "ghost" of the snap landing spot.
 * Screen-sized parts are converted to plan meters with the current [view].
 */
internal fun guideOps(guides: DragGuides, view: ViewTransform, overshootPx: Double, rightAnglePx: Double): List<DrawOp> {
    if (!(view.pxPerMeter > 0) || !view.pxPerMeter.isFinite()) return emptyList()
    val ops = ArrayList<DrawOp>()
    for (outline in guides.snapOutlines) {
        if (outline.size < 3 || !outline.all { p -> p.isFinite() }) continue
        ops += DrawOp.FillPolygon(outline, GHOST_FILL)
        ops += DrawOp.Polyline(outline, closed = true, widthPt = GHOST_PT, color = PlanColors.SELECTION, dashPt = GHOST_DASH_PT)
    }
    val overshoot = overshootPx / view.pxPerMeter
    for (line in guides.lines) {
        val dir = (line.b - line.a).normalized()
        if (dir == Vec2.ZERO || !line.a.isFinite() || !line.b.isFinite()) continue
        ops += DrawOp.Polyline(
            listOf(line.a - dir * overshoot, line.b + dir * overshoot),
            closed = false,
            widthPt = GUIDE_PT,
            color = PlanColors.SELECTION,
            dashPt = GUIDE_DASH_PT,
        )
    }
    val size = rightAnglePx / view.pxPerMeter
    for (mark in guides.rightAngles) {
        val p1 = mark.vertex + mark.a * size
        val p2 = p1 + mark.b * size
        val p3 = mark.vertex + mark.b * size
        ops += DrawOp.Polyline(listOf(p1, p2, p3), closed = false, widthPt = GUIDE_PT * 1.25, color = PlanColors.SELECTION)
    }
    return ops
}

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
    val cornerSnapPx = CornerSnapTolerance.toPx().toDouble()
    val moveSnapPx = MoveSnapTolerance.toPx().toDouble()
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
                if (next != view) userMovesView(current, next)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            if (transforming) {
                // A finger lifted after a pinch: keep panning with the remaining one.
                val delta = change.positionChange()
                if (delta != Offset.Zero) userMovesView(current, view.panned(delta.toVec2()))
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
                    if (delta != Offset.Zero) userMovesView(current, view.panned(delta.toVec2()))
                }
                DragKind.CORNER -> current.viewModel.updateCornerDrag(plan + grabOffset, cornerSnapPx / view.pxPerMeter)
                DragKind.MOVE -> if (downPlan != null) {
                    current.viewModel.updateMove(plan - downPlan, moveSnapPx / view.pxPerMeter)
                }
            }
            change.consume()
        }
    }
}

/** A pan or pinch by the user: it wins over any running zoom-to-fit / focus animation. */
private fun userMovesView(inputs: GestureInputs, next: ViewTransform) {
    inputs.viewModel.cancelViewRequest(inputs.floorId)
    inputs.viewModel.setView(inputs.floorId, next)
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
