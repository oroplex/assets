package com.oroplex.floorplan.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.DrawOp
import com.oroplex.floorplan.core.render.TextAlign
import com.oroplex.floorplan.core.render.ViewTransform

/**
 * Draws a core DrawList onto an android.graphics.Canvas (screen via Compose nativeCanvas, PDF pages,
 * bitmaps). Shared by the editor, the capture review preview, and PDF/PNG export so they look identical.
 */
object PlanPainter {
    /** Halo stroke width around text, in points. */
    private const val HALO_PT = 3.0

    /** Outline width of [DrawOp.Dot] markers that have a stroke color, in points. */
    private const val DOT_STROKE_PT = 1.5

    /** Pixel coordinates beyond this are treated as garbage (corrupt data / absurd zoom) and skipped. */
    private const val MAX_COORD = 1e7

    /**
     * Paints and scratch objects are cached per thread: the editor draws on the main thread while an
     * export may render a PDF/PNG on a background thread at the same time.
     */
    private val tools = ThreadLocal.withInitial { Tools() }

    fun draw(canvas: Canvas, drawList: DrawList, view: ViewTransform, background: Int? = null) {
        if (background != null) canvas.drawColor(background)
        if (!view.pxPerMeter.isFinite() || view.pxPerMeter <= 0 || !view.pxPerPt.isFinite() || !view.originPx.isFinite()) return
        val t = tools.get() ?: Tools().also { tools.set(it) }
        for (op in drawList.ops) {
            when (op) {
                is DrawOp.FillPolygon -> drawFill(canvas, op, view, t)
                is DrawOp.Polyline -> drawPolyline(canvas, op, view, t)
                is DrawOp.Arc -> drawArc(canvas, op, view, t)
                is DrawOp.Dot -> drawDot(canvas, op, view, t)
                is DrawOp.Text -> drawText(canvas, op, view, t)
            }
        }
    }

    private fun drawFill(canvas: Canvas, op: DrawOp.FillPolygon, view: ViewTransform, t: Tools) {
        if (op.points.size < 3 || !t.buildPath(op.points, closed = true, view = view)) return
        t.fill.color = op.color
        canvas.drawPath(t.path, t.fill)
    }

    private fun drawPolyline(canvas: Canvas, op: DrawOp.Polyline, view: ViewTransform, t: Tools) {
        if (op.points.size < 2 || !t.buildPath(op.points, op.closed, view)) return
        canvas.drawPath(t.path, t.stroke(op.color, op.widthPt * view.pxPerPt, op.dashPt * view.pxPerPt))
    }

    /** Plan angles are CCW with y up; screen angles are clockwise with y down, hence the sign flips. */
    private fun drawArc(canvas: Canvas, op: DrawOp.Arc, view: ViewTransform, t: Tools) {
        if (!op.center.isFinite() || !op.radius.isFinite() || op.radius <= 0 ||
            !op.startAngle.isFinite() || !op.sweepAngle.isFinite()
        ) return
        val c = view.toScreen(op.center)
        val r = op.radius * view.pxPerMeter
        if (!c.withinLimits() || r > MAX_COORD) return
        t.rect.set((c.x - r).toFloat(), (c.y - r).toFloat(), (c.x + r).toFloat(), (c.y + r).toFloat())
        t.path.reset()
        val sweepDeg = -Math.toDegrees(op.sweepAngle)
        if (kotlin.math.abs(sweepDeg) >= 360.0) {
            // arcTo treats a full turn as an empty sweep; draw the whole circle instead.
            t.path.addOval(t.rect, Path.Direction.CW)
        } else {
            t.path.arcTo(t.rect, (-Math.toDegrees(op.startAngle)).toFloat(), sweepDeg.toFloat(), true)
        }
        canvas.drawPath(t.path, t.stroke(op.color, op.widthPt * view.pxPerPt, op.dashPt * view.pxPerPt))
    }

    private fun drawDot(canvas: Canvas, op: DrawOp.Dot, view: ViewTransform, t: Tools) {
        if (!op.center.isFinite() || !op.radiusPt.isFinite() || op.radiusPt <= 0) return
        val c = view.toScreen(op.center)
        if (!c.withinLimits()) return
        val r = (op.radiusPt * view.pxPerPt).toFloat()
        t.fill.color = op.fillColor
        canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), r, t.fill)
        val strokeColor = op.strokeColor ?: return
        canvas.drawCircle(c.x.toFloat(), c.y.toFloat(), r, t.stroke(strokeColor, DOT_STROKE_PT * view.pxPerPt, 0.0))
    }

    /**
     * Text is drawn in its own frame: the canvas is translated to the anchor and rotated so +x runs along
     * the baseline. [DrawOp.Text.offsetPt] is applied in that rotated frame, with +y meaning "toward the
     * text's up", i.e. negative screen y. Each line is vertically centered on the anchor using the font
     * metrics; multi-line text is centered as a block. The halo (a wide stroke) is drawn under the fill.
     */
    private fun drawText(canvas: Canvas, op: DrawOp.Text, view: ViewTransform, t: Tools) {
        if (op.text.isBlank() || !op.anchor.isFinite() || !op.sizePt.isFinite() || op.sizePt <= 0 || !op.angle.isFinite()) return
        val a = view.toScreen(op.anchor)
        if (!a.withinLimits()) return
        val sizePx = (op.sizePt * view.pxPerPt).toFloat()
        if (sizePx < 0.5f || sizePx > 10_000f) return

        val paint = t.text
        paint.textSize = sizePx
        paint.typeface = if (op.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = when (op.align) {
            TextAlign.START -> Paint.Align.LEFT
            TextAlign.CENTER -> Paint.Align.CENTER
            TextAlign.END -> Paint.Align.RIGHT
        }
        paint.color = op.color
        paint.getFontMetrics(t.metrics)
        val lineHeight = t.metrics.descent - t.metrics.ascent
        // Baseline offset that puts the middle of [ascent, descent] on y = 0.
        val centerShift = -(t.metrics.ascent + t.metrics.descent) / 2f

        val lines = op.text.split('\n')
        val ox = (op.offsetPt.x.takeIf { it.isFinite() } ?: 0.0) * view.pxPerPt
        val oy = -(op.offsetPt.y.takeIf { it.isFinite() } ?: 0.0) * view.pxPerPt

        val halo = op.haloColor?.let { color ->
            t.haloText.set(paint)
            t.haloText.style = Paint.Style.STROKE
            t.haloText.strokeWidth = (HALO_PT * view.pxPerPt).toFloat()
            t.haloText.strokeJoin = Paint.Join.ROUND
            t.haloText.color = color
            t.haloText
        }

        canvas.save()
        try {
            canvas.translate(a.x.toFloat(), a.y.toFloat())
            val degrees = view.screenDegrees(op.angle)
            if (degrees != 0.0) canvas.rotate(degrees.toFloat())
            val firstCenter = oy - (lines.size - 1) * lineHeight / 2.0
            lines.forEachIndexed { i, line ->
                if (line.isEmpty()) return@forEachIndexed
                val x = ox.toFloat()
                val y = (firstCenter + i * lineHeight).toFloat() + centerShift
                if (halo != null) canvas.drawText(line, x, y, halo)
                canvas.drawText(line, x, y, paint)
            }
        } finally {
            canvas.restore()
        }
    }

    private fun Vec2.withinLimits() = isFinite() && kotlin.math.abs(x) < MAX_COORD && kotlin.math.abs(y) < MAX_COORD

    private class Tools {
        val path = Path()
        val rect = RectF()
        val metrics = Paint.FontMetrics()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { style = Paint.Style.FILL }
        val haloText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.BUTT
        }
        private var dashPx = 0f
        private var dashEffect: DashPathEffect? = null

        /** The shared stroke paint configured for one op. Dash = gap = [dashPx]; 0 means solid. */
        fun stroke(color: Int, widthPx: Double, dashPx: Double): Paint {
            strokePaint.color = color
            strokePaint.strokeWidth = if (widthPx.isFinite() && widthPx > 0) widthPx.toFloat() else 0f
            val dash = if (dashPx.isFinite() && dashPx > 0.0) dashPx.toFloat() else 0f
            strokePaint.pathEffect = if (dash > 0f) {
                if (dash != this.dashPx || dashEffect == null) {
                    dashEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
                    this.dashPx = dash
                }
                dashEffect
            } else {
                null
            }
            return strokePaint
        }

        /** Fills [path] with the screen-space polyline; false (nothing to draw) if any point is unusable. */
        fun buildPath(points: List<Vec2>, closed: Boolean, view: ViewTransform): Boolean {
            path.reset()
            points.forEachIndexed { i, p ->
                if (!p.isFinite()) return false
                val s = view.toScreen(p)
                if (!s.isFinite() || kotlin.math.abs(s.x) > MAX_COORD || kotlin.math.abs(s.y) > MAX_COORD) return false
                if (i == 0) path.moveTo(s.x.toFloat(), s.y.toFloat()) else path.lineTo(s.x.toFloat(), s.y.toFloat())
            }
            if (closed) path.close()
            return true
        }
    }
}
