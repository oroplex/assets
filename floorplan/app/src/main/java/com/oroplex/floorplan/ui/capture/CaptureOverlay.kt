package com.oroplex.floorplan.ui.capture

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oroplex.floorplan.ar.ArFrameState
import com.oroplex.floorplan.ar.SurfaceKind
import com.oroplex.floorplan.ar.TrackingProblem
import com.oroplex.floorplan.ar.TrackingStatus
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.CaptureMethod
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.projection.Projection
import com.oroplex.floorplan.core.solve.RoomOps
import kotlin.math.max

/** Overlay palette: saturated colors with dark outlines so they read on any camera image. */
internal object OverlayColors {
    val Good = Color(0xFF34C759)
    val Caution = Color(0xFFFFB300)
    val Bad = Color(0xFFFF3B30)
    val Accent = Color(0xFF40C4FF)
    val Wall = Color.White
    val Outline = Color(0x73000000)
    val LabelBackground = Color(0xCC1F1F1F)
    val Door = Color(0xFFFF9100)
    val Window = Color(0xFF00B0FF)
    val Opening = Color(0xFF69F0AE)

    fun forOpening(type: OpeningType): Color = when (type) {
        OpeningType.DOOR -> Door
        OpeningType.WINDOW -> Window
        OpeningType.OPENING -> Opening
    }
}

/** Overlay color of a wall's quality grade (green / amber / red). */
internal fun gradeColor(grade: CaptureGrade): Color = when (grade) {
    CaptureGrade.GOOD -> OverlayColors.Good
    CaptureGrade.FAIR -> OverlayColors.Caution
    CaptureGrade.POOR -> OverlayColors.Bad
}

/** Beyond this reticle distance, depth readings get too noisy for accurate walls. */
internal const val MAX_ACCURATE_DISTANCE = 4.0

/** How far a captured wall's floor line extends past its observed part, each side (meters). */
private const val WALL_LINE_EXTENSION = 0.5

/** Floor aim points farther than this from the camera (near the horizon) are not drawn. */
private const val MAX_AIM_DISTANCE = 10.0

/**
 * Plan-space geometry of the overlay, derived from the capture state only (not per frame), with labels
 * already formatted. Everything lies on the locked floor plane.
 */
internal data class OverlayGeometry(
    val floorY: Double?,
    val walls: List<WallLine>,
    val edges: List<EdgeLine>,
    val cornerTaps: List<Vec2>,
    /** Corner being re-captured (index into [cornerTaps]). */
    val highlightedCorner: Int?,
    val openings: List<OpeningLine>,
    /** First mark of a baseline wall or of an opening, waiting for its second mark. */
    val pendingMark: Vec2?,
    /** The pending mark is on a wall surface (window) rather than on the floor. */
    val pendingOnSurface: Boolean,
    /** Draw a live line from the last corner tap to the aim point. */
    val rubberBandFromLastCorner: Boolean,
    val fadedWalls: Boolean,
    val showReticle: Boolean,
    /** Color the distance readout when the reticle is beyond accurate range (capture steps only). */
    val warnRange: Boolean,
) {
    /** A captured wall, numbered in capture order; [highlighted] while it is being re-captured. */
    data class WallLine(
        val a: Vec2,
        val b: Vec2,
        val labelAt: Vec2,
        val label: String,
        val grade: CaptureGrade,
        val highlighted: Boolean,
    )

    data class EdgeLine(val a: Vec2, val b: Vec2, val labelAt: Vec2, val label: String)

    /** [parked]: kept while the room is reopened; drawn dimmed until it is placed on a wall again. */
    data class OpeningLine(val a: Vec2, val b: Vec2, val color: Color, val parked: Boolean = false)

    companion object {
        fun from(ui: CaptureUiState): OverlayGeometry {
            val d = ui.draft
            val settings = ui.settings
            val walls = d.observations.mapIndexedNotNull { i, o ->
                val dir = o.line.direction
                if (!dir.isFinite() || dir.length < 0.5) return@mapIndexedNotNull null
                val a = o.extentStart - dir * WALL_LINE_EXTENSION
                val b = o.extentEnd + dir * WALL_LINE_EXTENSION
                // line.normal points into the room: put the number on the interior side of the line.
                val labelAt = Vec2.lerp(o.extentStart, o.extentEnd, 0.5) + o.line.normal * 0.3
                WallLine(a, b, labelAt, "${i + 1}", wallGrade(o), highlighted = d.recaptureIndex == i)
            }
            val room = ui.draftRoom
            val edges = room?.let { r ->
                Polygons.edges(r.corners).filter { it.length > 1e-6 }.map { e ->
                    // CCW outline: the left normal of an edge points into the room.
                    val inward = e.direction.perp()
                    EdgeLine(e.a, e.b, e.midpoint + inward * 0.25, formatLengthSafe(e.length, settings))
                }
            }.orEmpty()
            val placed = room?.let { r ->
                r.openings.mapNotNull { o ->
                    val seg = try {
                        RoomOps.openingSegment(r, o)
                    } catch (e: Exception) {
                        null
                    } ?: return@mapNotNull null
                    OpeningLine(seg.first, seg.second, OverlayColors.forOpening(o.type))
                }
            }.orEmpty()
            val parked = d.parkedOpenings.map { p ->
                OpeningLine(p.start, p.end, OverlayColors.forOpening(p.opening.type), parked = true)
            }
            val pendingMark = when (ui.step) {
                CaptureStep.WALLS -> d.baseline?.firstTap?.toPlan()
                CaptureStep.OPENINGS -> d.pendingOpeningEdge
                else -> null
            }
            return OverlayGeometry(
                floorY = ui.floorY,
                walls = walls,
                edges = edges,
                cornerTaps = if (d.method == CaptureMethod.CORNERS) d.cornerTaps else emptyList(),
                highlightedCorner = if (d.method == CaptureMethod.CORNERS) d.recaptureIndex else null,
                openings = placed + parked,
                pendingMark = pendingMark,
                pendingOnSurface = ui.step == CaptureStep.OPENINGS && d.openingType == OpeningType.WINDOW,
                rubberBandFromLastCorner = ui.step == CaptureStep.CORNERS && d.cornerTaps.isNotEmpty() && d.recaptureIndex == null,
                fadedWalls = ui.step == CaptureStep.OPENINGS || ui.step == CaptureStep.REVIEW,
                showReticle = ui.step != CaptureStep.REVIEW,
                warnRange = ui.step == CaptureStep.WALLS || ui.step == CaptureStep.CORNERS || ui.step == CaptureStep.OPENINGS,
            )
        }
    }
}

/**
 * Full-screen AR overlay drawn over the camera feed: captured walls (numbered, ringed in their quality
 * color), the live solved outline with lengths, corner taps, openings, pending marks and the center
 * reticle with its distance readout, measurement progress ring and success / failure animation ([fx]).
 * [frame] and [fx] are read only inside the draw phase, so new AR frames and animation ticks redraw the
 * canvas without recomposing.
 */
@Composable
internal fun CaptureOverlay(
    ui: CaptureUiState,
    frame: () -> ArFrameState,
    fx: ReticleFx,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 48)
    val geometry = remember(ui.draft, ui.settings, ui.step, ui.floorY, ui.sessionId) { OverlayGeometry.from(ui) }
    val settings = ui.settings
    Canvas(modifier) {
        val f = frame()
        val projector = FloorProjector.create(f, geometry.floorY, size)
        if (projector != null) drawWorld(geometry, projector, f, textMeasurer, settings)
        if (geometry.showReticle) drawReticle(f, textMeasurer, settings, geometry.warnRange, fx)
    }
}

/**
 * Projects plan points on the floor plane (world Y = [floorY]) into canvas pixels. The AR viewport and
 * the canvas normally have the same size; scaling covers any mismatch. Segments are near-plane clipped
 * by [Projection.projectSegment] and then clipped to a generous canvas rectangle so huge off-screen
 * coordinates never reach the renderer.
 */
private class FloorProjector(
    private val viewProj: FloatArray,
    private val floorY: Double,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val canvasSize: Size,
    val cameraPosition: Vec3?,
) {
    private val sx = canvasSize.width / viewportWidth
    private val sy = canvasSize.height / viewportHeight
    private val margin = max(canvasSize.width, canvasSize.height) * 0.5f
    private val clipRect = Rect(-margin, -margin, canvasSize.width + margin, canvasSize.height + margin)

    fun point(p: Vec2): Offset? = point(Vec3.fromPlan(p, floorY))

    fun point(p: Vec3): Offset? {
        val s = Projection.worldToScreen(viewProj, p, viewportWidth, viewportHeight) ?: return null
        val o = Offset((s.x * sx).toFloat(), (s.y * sy).toFloat())
        return o.takeIf { it.x.isFinite() && it.y.isFinite() && clipRect.contains(it) }
    }

    fun segment(a: Vec2, b: Vec2): Pair<Offset, Offset>? = segment(Vec3.fromPlan(a, floorY), Vec3.fromPlan(b, floorY))

    fun segment(a: Vec3, b: Vec3): Pair<Offset, Offset>? {
        val (p, q) = Projection.projectSegment(viewProj, a, b, viewportWidth, viewportHeight) ?: return null
        return clipSegment(
            Offset((p.x * sx).toFloat(), (p.y * sy).toFloat()),
            Offset((q.x * sx).toFloat(), (q.y * sy).toFloat()),
            clipRect,
        )
    }

    /** Where the screen-center ray meets the floor (what a FloorPoint measurement would return), in plan. */
    fun floorAimPoint(): Vec2? {
        val inv = Projection.invert(viewProj) ?: return null
        val ray = Projection.screenRay(
            inv,
            viewportWidth / 2.0,
            viewportHeight / 2.0,
            viewportWidth,
            viewportHeight,
        )
        val hit = Projection.intersectHorizontalPlane(ray, floorY)?.takeIf { it.isFinite() } ?: return null
        val camera = cameraPosition
        if (camera != null && hit.toPlan().distanceTo(camera.toPlan()) > MAX_AIM_DISTANCE) return null
        return hit.toPlan()
    }

    companion object {
        fun create(f: ArFrameState, floorY: Double?, canvasSize: Size): FloorProjector? {
            val vp = f.viewProjection ?: return null
            if (floorY == null || !floorY.isFinite() || vp.size < 16) return null
            if (f.tracking != TrackingStatus.TRACKING || f.viewportWidth <= 0 || f.viewportHeight <= 0) return null
            if (canvasSize.width <= 0f || canvasSize.height <= 0f) return null
            return FloorProjector(vp, floorY, f.viewportWidth, f.viewportHeight, canvasSize, f.cameraPosition)
        }
    }
}

private fun DrawScope.drawWorld(
    g: OverlayGeometry,
    projector: FloorProjector,
    f: ArFrameState,
    textMeasurer: TextMeasurer,
    settings: PlanSettings,
) {
    val wallWidth = 3.dp.toPx()
    val wallAlpha = if (g.fadedWalls) 0.35f else 0.95f

    // Captured walls (observed extent ± 0.5 m) as lines on the floor; the one being re-captured in amber.
    val wallBadges = ArrayList<Pair<Offset, OverlayGeometry.WallLine>>(g.walls.size)
    for (w in g.walls) {
        projector.segment(w.a, w.b)?.let { (a, b) ->
            if (w.highlighted) {
                outlinedLine(OverlayColors.Caution, a, b, wallWidth * 2f)
            } else {
                outlinedLine(OverlayColors.Wall.copy(alpha = wallAlpha), a, b, wallWidth)
            }
        }
        if (!g.fadedWalls) projector.point(w.labelAt)?.let { wallBadges += it to w }
    }

    // Solved outline preview, in the accent color.
    val edgeLabels = ArrayList<Pair<Offset, String>>(g.edges.size)
    for (e in g.edges) {
        projector.segment(e.a, e.b)?.let { (a, b) -> outlinedLine(OverlayColors.Accent, a, b, 4.dp.toPx()) }
        projector.point(e.labelAt)?.let { edgeLabels += it to e.label }
    }

    // Openings as thick colored segments along their wall (parked ones dimmed).
    for (o in g.openings) {
        projector.segment(o.a, o.b)?.let { (a, b) ->
            outlinedLine(if (o.parked) o.color.copy(alpha = 0.55f) else o.color, a, b, 8.dp.toPx())
        }
    }

    // Corner taps: dots joined in capture order.
    val tapPoints = g.cornerTaps.map { projector.point(it) }
    for (i in 1 until g.cornerTaps.size) {
        projector.segment(g.cornerTaps[i - 1], g.cornerTaps[i])?.let { (a, b) ->
            outlinedLine(OverlayColors.Wall, a, b, 2.5.dp.toPx())
        }
    }
    tapPoints.forEachIndexed { i, p ->
        if (p != null) markerDot(p, if (i == g.highlightedCorner) OverlayColors.Caution else OverlayColors.Accent)
    }

    // Live line from the pending mark / last corner to where the reticle meets the floor.
    val aim: Vec2? = when {
        g.pendingMark != null && g.pendingOnSurface -> f.reticle?.world?.takeIf { it.isFinite() }?.toPlan()
        g.pendingMark != null || g.rubberBandFromLastCorner -> projector.floorAimPoint()
        else -> null
    }
    val from = g.pendingMark ?: if (g.rubberBandFromLastCorner) g.cornerTaps.lastOrNull() else null
    if (from != null) {
        if (aim != null) {
            projector.segment(from, aim)?.let { (a, b) -> dashedLine(OverlayColors.Caution, a, b) }
            val live = formatLengthSafe(from.distanceTo(aim), settings)
            projector.point(Vec2.lerp(from, aim, 0.5))?.let { label(textMeasurer, live, it, Color.Black, OverlayColors.Caution) }
        }
        if (g.pendingMark != null) projector.point(g.pendingMark)?.let { markerDot(it, OverlayColors.Caution) }
    }

    // Labels last so they stay on top of the lines.
    for ((p, text) in edgeLabels) label(textMeasurer, text, p, Color.White, OverlayColors.LabelBackground)
    for ((p, w) in wallBadges) badge(textMeasurer, w.label, p, gradeColor(w.grade), w.highlighted)
}

/**
 * Center reticle (ring + crosshair) colored by what it hits, with the distance readout (amber beyond
 * accurate range), the measurement progress ring around it, and a check mark / red flash when a capture
 * finishes.
 */
private fun DrawScope.drawReticle(
    f: ArFrameState,
    textMeasurer: TextMeasurer,
    settings: PlanSettings,
    warnRange: Boolean,
    fx: ReticleFx,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val hit = f.reticle
    val trackingProblem = f.tracking != TrackingStatus.TRACKING || f.problem != TrackingProblem.NONE
    val color = when {
        trackingProblem || hit == null -> OverlayColors.Bad
        hit.surface == SurfaceKind.FLOOR || hit.surface == SurfaceKind.WALL_PLANE ||
            hit.surface == SurfaceKind.DEPTH || hit.surface == SurfaceKind.CEILING -> OverlayColors.Good
        else -> OverlayColors.Caution
    }
    val radius = 24.dp.toPx()
    val check = fx.check.value.coerceIn(0f, 1f)
    val crosshair = 1f - check
    drawCircle(OverlayColors.Outline, radius, center, style = Stroke(width = 6.dp.toPx()))
    drawCircle(color, radius, center, style = Stroke(width = 3.dp.toPx()))
    if (crosshair > 0.01f) {
        val inner = radius * 0.35f
        val outer = radius * 0.75f
        val outline = OverlayColors.Outline.copy(alpha = OverlayColors.Outline.alpha * crosshair)
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            val a = center + Offset(dx * inner, dy * inner)
            val b = center + Offset(dx * outer, dy * outer)
            drawLine(outline, a, b, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color.copy(alpha = crosshair), a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(color.copy(alpha = crosshair), 2.5.dp.toPx(), center)
    }

    // Measurement progress: an arc filling clockwise from 12 o'clock just outside the reticle.
    val progress = fx.ring.value.coerceIn(0f, 1f)
    val fail = fx.fail.value.coerceIn(0f, 1f)
    if (progress > 0.001f || fail > 0.001f) {
        val ringRadius = radius + 9.dp.toPx()
        val stroke = 5.dp.toPx()
        drawCircle(OverlayColors.Outline, ringRadius, center, style = Stroke(width = stroke + 3.dp.toPx()))
        if (fail > 0.001f) {
            drawCircle(OverlayColors.Bad.copy(alpha = fail), ringRadius, center, style = Stroke(width = stroke))
        } else {
            drawArc(
                color = if (progress >= 0.999f) OverlayColors.Good else OverlayColors.Accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = center - Offset(ringRadius, ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
    if (check > 0.01f) drawCheck(center, radius * (0.55f + 0.45f * check), check)

    val distance = hit?.distance?.takeIf { it.isFinite() && it >= 0.0 } ?: return
    if (trackingProblem) return
    val outOfRange = warnRange && distance > MAX_ACCURATE_DISTANCE
    label(
        textMeasurer,
        formatLengthSafe(distance, settings),
        center + Offset(0f, radius + 26.dp.toPx()),
        if (outOfRange) Color.Black else Color.White,
        if (outOfRange) OverlayColors.Caution else OverlayColors.LabelBackground,
    )
}

/** Green disc with a white check mark, of [radius], faded by [alpha]. */
private fun DrawScope.drawCheck(center: Offset, radius: Float, alpha: Float) {
    drawCircle(OverlayColors.Outline.copy(alpha = OverlayColors.Outline.alpha * alpha), radius + 2.dp.toPx(), center)
    drawCircle(OverlayColors.Good.copy(alpha = alpha), radius, center)
    val path = Path().apply {
        moveTo(center.x - radius * 0.45f, center.y + radius * 0.02f)
        lineTo(center.x - radius * 0.12f, center.y + radius * 0.34f)
        lineTo(center.x + radius * 0.48f, center.y - radius * 0.30f)
    }
    drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(width = radius * 0.18f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.outlinedLine(color: Color, a: Offset, b: Offset, width: Float) {
    drawLine(OverlayColors.Outline, a, b, strokeWidth = width + 3.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, a, b, strokeWidth = width, cap = StrokeCap.Round)
}

private fun DrawScope.dashedLine(color: Color, a: Offset, b: Offset) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
    drawLine(OverlayColors.Outline, a, b, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
    drawLine(color, a, b, strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
}

private fun DrawScope.markerDot(p: Offset, ring: Color) {
    drawCircle(OverlayColors.Outline, 8.dp.toPx(), p)
    drawCircle(Color.White, 6.dp.toPx(), p)
    drawCircle(ring, 6.dp.toPx(), p, style = Stroke(width = 2.5.dp.toPx()))
}

/** Text in a rounded pill centered on [center]. */
private fun DrawScope.label(textMeasurer: TextMeasurer, text: String, center: Offset, fg: Color, bg: Color) {
    val layout = textMeasurer.measure(text, TextStyle(color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    val padH = 8.dp.toPx()
    val padV = 3.dp.toPx()
    val w = layout.size.width + 2 * padH
    val h = layout.size.height + 2 * padV
    drawRoundRect(bg, topLeft = Offset(center.x - w / 2, center.y - h / 2), size = Size(w, h), cornerRadius = CornerRadius(h / 2, h / 2))
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/** Wall number in a white disc ringed in its quality color ([ring]); amber-filled while [highlighted]. */
private fun DrawScope.badge(textMeasurer: TextMeasurer, text: String, center: Offset, ring: Color, highlighted: Boolean) {
    val layout = textMeasurer.measure(text, TextStyle(color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    val r = max(layout.size.width, layout.size.height) / 2f + 5.dp.toPx()
    drawCircle(OverlayColors.Outline, r + 4.dp.toPx(), center)
    drawCircle(ring, r + 2.5.dp.toPx(), center)
    drawCircle(if (highlighted) OverlayColors.Caution else Color.White, r, center)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

/**
 * Liang–Barsky clip of segment a→b to [r]. Returns null when the segment lies completely outside.
 */
internal fun clipSegment(a: Offset, b: Offset, r: Rect): Pair<Offset, Offset>? {
    if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return null
    var t0 = 0f
    var t1 = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
    for (i in 0 until 4) {
        if (p[i] == 0f) {
            if (q[i] < 0f) return null
        } else {
            val t = q[i] / p[i]
            if (p[i] < 0f) {
                if (t > t1) return null
                if (t > t0) t0 = t
            } else {
                if (t < t0) return null
                if (t < t1) t1 = t
            }
        }
    }
    return Offset(a.x + t0 * dx, a.y + t0 * dy) to Offset(a.x + t1 * dx, a.y + t1 * dy)
}

/** [Units.formatLength] with the project's settings; never throws on odd input. */
internal fun formatLengthSafe(meters: Double, settings: PlanSettings): String = try {
    Units.formatLength(meters, settings.unitSystem, settings.imperialDenominator)
} catch (e: Exception) {
    String.format(java.util.Locale.US, "%.2f m", meters)
}
