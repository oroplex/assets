package com.oroplex.floorplan.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Key of the one-time flag that the first-scan "How to scan" sheet was seen. */
internal const val CAPTURE_TIPS_FLAG = "capture_tips_seen"

/** One step of the "How to scan" sheet. */
internal data class ScanTip(val title: String, val detail: String, val art: TipArt)

internal enum class TipArt { FLOOR, CEILING, WALLS, CLOSE }

/** The four steps of a scan, in order. Short on purpose: people read this standing in a room. */
internal val SCAN_TIPS = listOf(
    ScanTip(
        "Find the floor",
        "Point the phone at open floor and move it slowly. It locks by itself, or tap Lock floor.",
        TipArt.FLOOR,
    ),
    ScanTip(
        "Measure the ceiling",
        "Aim straight up at a clear spot and tap Measure ceiling. Short on time? Skip it.",
        TipArt.CEILING,
    ),
    ScanTip(
        "Capture each wall in order",
        "Aim at bare wall above furniture, beside doors, pictures and TVs rather than on them, and hold still " +
            "until the ring fills. Go around the room one wall at a time.",
        TipArt.WALLS,
    ),
    ScanTip(
        "Close the room, add doors & windows",
        "Tap Close room, then mark the left and right edge of each door and window.",
        TipArt.CLOSE,
    ),
)

/**
 * "How to scan": four short illustrated steps, shown automatically on the very first scan and from the
 * help button. Closing it in any way calls [onDismiss]; [doneLabel] is the closing button's text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HowToScanSheet(doneLabel: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "How to scan a room",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            SCAN_TIPS.forEachIndexed { i, tip -> TipRow(i + 1, tip) }
            Text(
                "Scan every room of a floor in one session so they line up. Afterwards, tape-measure one long wall " +
                    "and calibrate the floor in the editor.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(doneLabel) }
        }
    }
}

@Composable
private fun TipRow(number: Int, tip: ScanTip) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            val primary = MaterialTheme.colorScheme.primary
            val ink = MaterialTheme.colorScheme.onPrimaryContainer
            Canvas(Modifier.fillMaxSize().padding(10.dp)) { drawTipArt(tip.art, primary, ink) }
        }
        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$number. ${tip.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(tip.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Simple line illustrations of each step, drawn in a square of the current size. */
private fun DrawScope.drawTipArt(art: TipArt, accent: Color, ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = w * 0.03f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (art) {
        TipArt.FLOOR -> {
            // A floor grid in perspective with a tilted phone above it, aiming down.
            val dash = PathEffect.dashPathEffect(floatArrayOf(w * 0.06f, w * 0.05f))
            for (i in 0..3) {
                val y = h * (0.62f + i * 0.12f)
                val inset = w * (0.18f - i * 0.05f)
                drawLine(accent.copy(alpha = 0.6f), Offset(inset, y), Offset(w - inset, y), strokeWidth = thin.width, pathEffect = dash)
            }
            drawPhone(Offset(w * 0.5f, h * 0.28f), w * 0.26f, h * 0.36f, 35f, ink, stroke)
            drawLine(accent, Offset(w * 0.56f, h * 0.42f), Offset(w * 0.64f, h * 0.7f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawCircle(accent, w * 0.05f, Offset(w * 0.65f, h * 0.74f))
        }
        TipArt.CEILING -> {
            drawLine(ink, Offset(w * 0.08f, h * 0.12f), Offset(w * 0.92f, h * 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawPhone(Offset(w * 0.5f, h * 0.72f), w * 0.36f, h * 0.24f, 0f, ink, stroke)
            arrow(Offset(w * 0.5f, h * 0.56f), Offset(w * 0.5f, h * 0.2f), accent, stroke)
        }
        TipArt.WALLS -> {
            // Top view of a room with numbered walls and the walking direction.
            val left = w * 0.12f
            val top = h * 0.12f
            val right = w * 0.88f
            val bottom = h * 0.88f
            drawRect(ink, Offset(left, top), Size(right - left, bottom - top), style = stroke)
            val r = w * 0.07f
            listOf(
                Offset(w * 0.5f, top),
                Offset(right, h * 0.5f),
                Offset(w * 0.5f, bottom),
                Offset(left, h * 0.5f),
            ).forEachIndexed { i, c ->
                drawCircle(Color.White, r, c)
                drawCircle(if (i == 0) accent else ink, r, c, style = thin)
            }
            val arc = Path().apply {
                addArc(Rect(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f), 200f, 250f)
            }
            drawPath(arc, accent, style = thin)
            arrowHead(Offset(w * 0.3f, h * 0.43f), -100f, accent, w * 0.08f, thin)
        }
        TipArt.CLOSE -> {
            val path = Path().apply {
                moveTo(w * 0.12f, h * 0.12f)
                lineTo(w * 0.88f, h * 0.12f)
                lineTo(w * 0.88f, h * 0.88f)
                lineTo(w * 0.62f, h * 0.88f)
                moveTo(w * 0.32f, h * 0.88f)
                lineTo(w * 0.12f, h * 0.88f)
                close()
            }
            drawPath(path, ink, style = stroke)
            // Door leaf and swing.
            drawLine(accent, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.32f, h * 0.6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawArc(
                accent,
                startAngle = -90f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.32f - w * 0.28f, h * 0.6f),
                size = Size(w * 0.56f, h * 0.56f),
                style = Stroke(width = thin.width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.04f, w * 0.04f))),
            )
            // Window on the right wall.
            drawLine(accent, Offset(w * 0.88f, h * 0.32f), Offset(w * 0.88f, h * 0.58f), strokeWidth = stroke.width * 2f, cap = StrokeCap.Butt)
        }
    }
}

private fun DrawScope.drawPhone(center: Offset, width: Float, height: Float, degrees: Float, color: Color, stroke: Stroke) {
    rotate(degrees, center) {
        drawRoundRect(
            color,
            topLeft = Offset(center.x - width / 2, center.y - height / 2),
            size = Size(width, height),
            cornerRadius = CornerRadius(width * 0.18f, width * 0.18f),
            style = stroke,
        )
    }
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, stroke: Stroke) {
    drawLine(color, from, to, strokeWidth = stroke.width, cap = StrokeCap.Round)
    val angle = Math.toDegrees(atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())).toFloat()
    arrowHead(to, angle, color, size.width * 0.12f, stroke)
}

/** Two short strokes forming an arrow head at [tip], pointing along [degrees] (screen angle). */
private fun DrawScope.arrowHead(tip: Offset, degrees: Float, color: Color, length: Float, stroke: Stroke) {
    val a = Math.toRadians((degrees + 150f).toDouble())
    val b = Math.toRadians((degrees - 150f).toDouble())
    drawLine(color, tip, tip + Offset((cos(a) * length).toFloat(), (sin(a) * length).toFloat()), strokeWidth = stroke.width, cap = StrokeCap.Round)
    drawLine(color, tip, tip + Offset((cos(b) * length).toFloat(), (sin(b) * length).toFloat()), strokeWidth = stroke.width, cap = StrokeCap.Round)
}
