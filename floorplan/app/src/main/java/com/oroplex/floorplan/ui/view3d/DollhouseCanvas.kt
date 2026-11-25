package com.oroplex.floorplan.ui.view3d

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the canvas reports from touch input. Pixel deltas; zoom is the pinch scale change (> 1 = apart). */
internal class DollhouseGestureCallbacks(
    val onGestureStart: () -> Unit,
    val onOrbit: (dx: Float, dy: Float) -> Unit,
    val onPinchPan: (zoom: Float, pan: Offset) -> Unit,
    val onDoubleTap: () -> Unit,
)

/**
 * The 3D view itself: a Compose Canvas driven by the software pipeline ([DollhouseProjector]). The mesh is
 * cached by the scene; the projector re-projects only when [camera] (snapshot state) or the size changes, and
 * that state is read during drawing, so camera moves redraw the canvas without recomposition.
 */
@Composable
internal fun DollhouseCanvas(
    scene: DollhouseScene,
    lens: Lens,
    camera: () -> OrbitCamera?,
    showLabels: Boolean,
    onViewport: (width: Int, height: Int, marginPx: Float) -> Unit,
    callbacks: DollhouseGestureCallbacks,
    description: String,
    onResetAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mesh = scene.mesh
    val density = LocalDensity.current
    val projector = remember(mesh, lens) { DollhouseProjector(mesh, lens) }
    val fillPaint = remember {
        // Fill and a hairline stroke of the same color: neighbouring faces overlap by half a pixel, so
        // anti-aliasing never leaves background-colored seams between the pieces of a wall.
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1f
        }
    }
    val linePaint = remember(density) {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = with(density) { OutlineWidth.toPx() }
            color = DollhousePalette.OUTLINE
        }
    }
    val path = remember { Path() }
    val background = remember { Brush.verticalGradient(listOf(BackgroundTop, BackgroundBottom)) }
    val marginPx = with(density) { FitMargin.toPx() }

    val textMeasurer = rememberTextMeasurer()
    val labelLayouts: List<TextLayoutResult> = remember(mesh, textMeasurer, density) {
        val maxWidth = with(density) { LabelMaxWidth.roundToPx() }
        mesh.labels.map { label ->
            textMeasurer.measure(
                text = label.text,
                style = LabelStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxWidth),
            )
        }
    }
    val labelPadH = with(density) { 8.dp.toPx() }
    val labelPadV = with(density) { 3.dp.toPx() }
    val labelGap = with(density) { 4.dp.toPx() }
    val labelBoxes = remember(labelLayouts, labelPadH, labelPadV) {
        LabelBoxes(
            width = FloatArray(labelLayouts.size) { labelLayouts[it].size.width + 2 * labelPadH },
            height = FloatArray(labelLayouts.size) { labelLayouts[it].size.height + 2 * labelPadV },
        )
    }
    val placer = remember(mesh) { LabelPlacer(DoubleArray(mesh.labels.size) { mesh.labels[it].area }) }

    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentReset by rememberUpdatedState(onResetAction)

    Canvas(
        modifier
            // Faces, outlines and label pills are drawn at projected pixel positions that leave the canvas
            // when the model is zoomed in or panned. Compose does not clip drawing by default, so without
            // this they would paint over the navigation-bar inset (and, in a pop-up window, the sides).
            .clipToBounds()
            .onSizeChanged { onViewport(it.width, it.height, marginPx) }
            .pointerInput(Unit) { detectDollhouseGestures { currentCallbacks } }
            .semantics {
                contentDescription = description
                customActions = listOf(CustomAccessibilityAction("Reset view") { currentReset(); true })
            },
    ) {
        drawRect(background)
        val cam = camera() ?: return@Canvas
        projector.update(cam, size.width.toInt(), size.height.toInt())
        drawFaces(projector, path, fillPaint, linePaint)
        if (showLabels) {
            placer.place(projector.labelX, projector.labelY, projector.labelState, labelBoxes.width, labelBoxes.height, labelGap)
            drawLabels(projector, placer, labelLayouts, labelBoxes, labelPadH, labelPadV)
        }
    }
}

/** Pill sizes of the room labels (pixels), measured once per model. */
private class LabelBoxes(val width: FloatArray, val height: FloatArray)

private fun DrawScope.drawFaces(projector: DollhouseProjector, path: Path, fillPaint: Paint, linePaint: Paint) {
    val mesh = projector.mesh
    drawIntoCanvas { c ->
        val canvas = c.nativeCanvas
        val xy = projector.polyXY
        val lines = projector.lineXY
        for (k in 0 until projector.paintCount) {
            val face = projector.order[k]
            val s = projector.polyStart[k]
            val e = projector.polyStart[k + 1]
            if (e - s >= 3) {
                path.rewind()
                path.moveTo(xy[2 * s], xy[2 * s + 1])
                for (v in s + 1 until e) path.lineTo(xy[2 * v], xy[2 * v + 1])
                path.close()
                fillPaint.color = mesh.fillColor[face]
                canvas.drawPath(path, fillPaint)
            }
            val l0 = projector.lineStart[k]
            val l1 = projector.lineStart[k + 1]
            if (l1 > l0) canvas.drawLines(lines, 4 * l0, 4 * (l1 - l0), linePaint)
        }
    }
}

private fun DrawScope.drawLabels(
    projector: DollhouseProjector,
    placer: LabelPlacer,
    layouts: List<TextLayoutResult>,
    boxes: LabelBoxes,
    padH: Float,
    padV: Float,
) {
    for (i in layouts.indices) {
        if (!placer.shown[i]) continue
        val layout = layouts[i]
        val w = boxes.width[i]
        val h = boxes.height[i]
        val topLeft = Offset(projector.labelX[i] - w / 2, projector.labelY[i] + placer.offsetY[i] - h / 2)
        // A label whose room is hidden behind walls fades, so it still says where the room is.
        val alpha = if (projector.labelState[i] == DollhouseProjector.LABEL_OCCLUDED) 0.45f else 1f
        drawRoundRect(LabelBackground, topLeft, Size(w, h), CornerRadius(h / 2), alpha = 0.92f * alpha)
        drawText(layout, topLeft = Offset(topLeft.x + padH, topLeft.y + padV), alpha = alpha)
    }
}

/**
 * One finger drags orbit; two fingers pinch-zoom and pan together; a double tap resets. A finger left down
 * after a pinch does nothing (no accidental orbit when lifting fingers one by one).
 */
private suspend fun PointerInputScope.detectDollhouseGestures(callbacks: () -> DollhouseGestureCallbacks) {
    val doubleTapSlop = DoubleTapSlop.toPx()
    var lastTapUp = Long.MIN_VALUE / 2
    var lastTapPosition = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        callbacks().onGestureStart()
        val isSecondTap = down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastTapPosition).getDistance() < doubleTapSlop
        var dragging = false
        var multiTouch = false
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                if (!dragging && !multiTouch) {
                    if (isSecondTap) {
                        lastTapUp = Long.MIN_VALUE / 2
                        callbacks().onDoubleTap()
                    } else {
                        lastTapUp = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                        lastTapPosition = down.position
                    }
                }
                break
            }
            if (pressed.size >= 2) {
                multiTouch = true
                dragging = true
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) callbacks().onPinchPan(zoom, pan)
                event.changes.forEach { it.consume() }
                continue
            }
            val change = pressed.first()
            if (multiTouch) {
                change.consume()
                continue
            }
            if (!dragging) {
                if ((change.position - down.position).getDistance() <= slop) continue
                dragging = true
            }
            val delta = change.positionChange()
            if (delta != Offset.Zero) callbacks().onOrbit(delta.x, delta.y)
            change.consume()
        }
    }
}

private val OutlineWidth = 0.7.dp
private val FitMargin = 28.dp
private val LabelMaxWidth = 150.dp
private val DoubleTapSlop = 48.dp
private val BackgroundTop = Color(0xFFE9ECF0)
private val BackgroundBottom = Color(0xFFF9FAFB)
private val LabelBackground = Color.White
private val LabelStyle = TextStyle(color = Color(0xFF202327), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
