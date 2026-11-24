package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Vec2
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

object SvgExporter {
    private const val DEFAULT_WIDTH_PT = 612.0
    private const val DEFAULT_HEIGHT_PT = 792.0
    private const val DEFAULT_MARGIN_PT = 36.0
    private const val TITLE_PT = 14.0

    /** Height reserved above the plan for the title (the title itself plus breathing room). */
    private const val TITLE_BAND_PT = 2 * TITLE_PT

    /** Same halo / dot outline widths and line spacing as the Android PlanPainter, so exports match the screen. */
    private const val HALO_PT = 3.0
    private const val DOT_STROKE_PT = 1.5
    private const val LINE_HEIGHT = 1.17

    private const val FONT_FAMILY = "Roboto, Helvetica, Arial, sans-serif"

    /**
     * Standalone SVG document of [drawList] fitted into a widthPt x heightPt page with [marginPt] margins
     * (1 SVG user unit = 1 pt). Optional [title] is drawn at the top-left and in <title>.
     * Text must be XML-escaped. Output must be valid, well-formed XML.
     *
     * The plan is fitted below a title band when a title is given. Plan arcs are converted to SVG elliptical
     * arc commands in y-down page space (the y flip mirrors the sweep direction). Ops with non-finite
     * geometry are skipped; invalid page sizes or margins fall back to Letter portrait / 0.5".
     */
    fun export(drawList: DrawList, widthPt: Double, heightPt: Double, marginPt: Double = 36.0, title: String? = null): String {
        val width = widthPt.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_WIDTH_PT
        val height = heightPt.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_HEIGHT_PT
        val margin = marginPt.takeIf { it.isFinite() && it >= 0.0 } ?: DEFAULT_MARGIN_PT
        val cleanTitle = title?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        val band = if (cleanTitle != null) TITLE_BAND_PT else 0.0
        val fitted = ViewTransform.fit(drawList.bounds, width, (height - band).coerceAtLeast(1.0), margin, 1.0)
        val view = fitted.copy(originPx = fitted.originPx + Vec2(0.0, band))

        val sb = StringBuilder(4096 + drawList.ops.size * 160)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"").append(n(width))
            .append("pt\" height=\"").append(n(height)).append("pt\" viewBox=\"0 0 ").append(n(width)).append(' ')
            .append(n(height)).append("\">\n")
        sb.append("<title>").append(escape(cleanTitle ?: "Floor plan")).append("</title>\n")
        sb.append("<rect x=\"0\" y=\"0\" width=\"").append(n(width)).append("\" height=\"").append(n(height))
            .append("\"").append(fill(PlanColors.BACKGROUND)).append("/>\n")
        sb.append("<g id=\"plan\">\n")
        for (op in drawList.ops) {
            when (op) {
                is DrawOp.FillPolygon -> polygon(sb, op, view)
                is DrawOp.Polyline -> polyline(sb, op, view)
                is DrawOp.Arc -> arc(sb, op, view)
                is DrawOp.Dot -> dot(sb, op, view)
                is DrawOp.Text -> text(sb, op, view)
            }
        }
        sb.append("</g>\n")
        if (cleanTitle != null) {
            sb.append("<text x=\"").append(n(margin)).append("\" y=\"").append(n(margin + TITLE_PT / 2))
                .append("\" font-family=\"").append(FONT_FAMILY).append("\" font-size=\"").append(n(TITLE_PT))
                .append("\" font-weight=\"bold\" text-anchor=\"start\" dominant-baseline=\"central\"")
                .append(fill(PlanColors.TEXT)).append('>').append(escape(cleanTitle)).append("</text>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun polygon(sb: StringBuilder, op: DrawOp.FillPolygon, view: ViewTransform) {
        if (op.points.size < 3) return
        val pts = points(op.points, view) ?: return
        sb.append("<polygon points=\"").append(pts).append('"').append(fill(op.color)).append(" stroke=\"none\"/>\n")
    }

    private fun polyline(sb: StringBuilder, op: DrawOp.Polyline, view: ViewTransform) {
        if (op.points.size < 2 || !validStroke(op.widthPt)) return
        if (op.closed) {
            val screen = op.points.map { view.toScreen(it) }
            if (screen.any { !it.isFinite() }) return
            sb.append("<path d=\"")
            screen.forEachIndexed { i, p -> sb.append(if (i == 0) "M" else " L").append(n(p.x)).append(' ').append(n(p.y)) }
            sb.append(" Z\"")
        } else {
            val pts = points(op.points, view) ?: return
            sb.append("<polyline points=\"").append(pts).append('"')
        }
        sb.append(" fill=\"none\"").append(stroke(op.color, op.widthPt, op.dashPt)).append("/>\n")
    }

    /**
     * Plan arcs are CCW-positive with y up; on the y-down page the same arc runs the other way, so a positive
     * plan sweep becomes SVG sweep-flag 0. Sweeps over 180 degrees set the large-arc flag; a full circle is
     * split into two half arcs (an SVG arc with coincident end points draws nothing).
     */
    private fun arc(sb: StringBuilder, op: DrawOp.Arc, view: ViewTransform) {
        if (!op.center.isFinite() || !op.radius.isFinite() || op.radius <= 0.0 || !op.startAngle.isFinite() ||
            !op.sweepAngle.isFinite() || op.sweepAngle == 0.0 || !validStroke(op.widthPt)
        ) return
        val sweep = op.sweepAngle.coerceIn(-2 * PI, 2 * PI)
        val pieces = if (abs(sweep) > 2 * PI - 1e-9) 2 else 1
        val step = sweep / pieces
        val r = op.radius * view.pxPerMeter
        if (!r.isFinite()) return
        val start = view.toScreen(op.center + Vec2.fromAngle(op.startAngle, op.radius))
        if (!start.isFinite()) return
        sb.append("<path d=\"M").append(n(start.x)).append(' ').append(n(start.y))
        val largeArc = if (abs(step) > PI) 1 else 0
        val sweepFlag = if (step > 0) 0 else 1
        for (k in 1..pieces) {
            val end = view.toScreen(op.center + Vec2.fromAngle(op.startAngle + step * k, op.radius))
            sb.append(" A").append(n(r)).append(' ').append(n(r)).append(" 0 ").append(largeArc).append(' ')
                .append(sweepFlag).append(' ').append(n(end.x)).append(' ').append(n(end.y))
        }
        sb.append("\" fill=\"none\"").append(stroke(op.color, op.widthPt, op.dashPt)).append("/>\n")
    }

    private fun dot(sb: StringBuilder, op: DrawOp.Dot, view: ViewTransform) {
        if (!op.center.isFinite() || !op.radiusPt.isFinite() || op.radiusPt <= 0.0) return
        val c = view.toScreen(op.center)
        if (!c.isFinite()) return
        sb.append("<circle cx=\"").append(n(c.x)).append("\" cy=\"").append(n(c.y)).append("\" r=\"")
            .append(n(op.radiusPt * view.pxPerPt)).append('"').append(fill(op.fillColor))
        op.strokeColor?.let { sb.append(stroke(it, DOT_STROKE_PT * view.pxPerPt, 0.0)) }
        sb.append("/>\n")
    }

    /**
     * Text is placed in its own frame: translated to the anchor and rotated so +x runs along the baseline
     * (SVG rotate() is clockwise-positive on the y-down page, as [ViewTransform.screenDegrees] returns).
     * offsetPt.y points toward the text's up, i.e. negative frame y. Multi-line text is centered as a block.
     */
    private fun text(sb: StringBuilder, op: DrawOp.Text, view: ViewTransform) {
        if (op.text.isBlank() || !op.anchor.isFinite() || !op.sizePt.isFinite() || op.sizePt <= 0.0 || !op.angle.isFinite()) return
        val a = view.toScreen(op.anchor)
        if (!a.isFinite()) return
        val size = op.sizePt * view.pxPerPt
        val ox = (op.offsetPt.x.takeIf { it.isFinite() } ?: 0.0) * view.pxPerPt
        val oy = -(op.offsetPt.y.takeIf { it.isFinite() } ?: 0.0) * view.pxPerPt
        val anchor = when (op.align) {
            TextAlign.START -> "start"
            TextAlign.CENTER -> "middle"
            TextAlign.END -> "end"
        }
        val degrees = view.screenDegrees(op.angle)
        sb.append("<text transform=\"translate(").append(n(a.x)).append(' ').append(n(a.y)).append(')')
        if (abs(degrees) > 1e-9) sb.append(" rotate(").append(n(degrees)).append(')')
        sb.append("\" font-family=\"").append(FONT_FAMILY).append("\" font-size=\"").append(n(size)).append('"')
        if (op.bold) sb.append(" font-weight=\"bold\"")
        sb.append(" text-anchor=\"").append(anchor).append("\" dominant-baseline=\"central\"").append(fill(op.color))
        op.haloColor?.let {
            sb.append(stroke(it, HALO_PT * view.pxPerPt, 0.0)).append(" paint-order=\"stroke\"")
        }
        val lines = op.text.split('\n').map { it.replace('\t', ' ') }
        if (lines.size == 1) {
            sb.append(" x=\"").append(n(ox)).append("\" y=\"").append(n(oy)).append("\">")
                .append(escape(lines[0])).append("</text>\n")
            return
        }
        sb.append('>')
        val lineHeight = size * LINE_HEIGHT
        val first = oy - (lines.size - 1) * lineHeight / 2
        lines.forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            sb.append("<tspan x=\"").append(n(ox)).append("\" y=\"").append(n(first + i * lineHeight))
                .append("\" dominant-baseline=\"central\">").append(escape(line)).append("</tspan>")
        }
        sb.append("</text>\n")
    }

    /** "x,y x,y ..." in page coordinates, or null if any point is not finite. */
    private fun points(pts: List<Vec2>, view: ViewTransform): String? {
        val sb = StringBuilder(pts.size * 16)
        for ((i, p) in pts.withIndex()) {
            val s = view.toScreen(p)
            if (!s.isFinite()) return null
            if (i > 0) sb.append(' ')
            sb.append(n(s.x)).append(',').append(n(s.y))
        }
        return sb.toString()
    }

    private fun validStroke(widthPt: Double) = widthPt.isFinite() && widthPt > 0.0

    private fun fill(argb: Int): String {
        val alpha = (argb ushr 24) and 0xFF
        val base = " fill=\"${hex(argb)}\""
        return if (alpha == 255) base else "$base fill-opacity=\"${n(alpha / 255.0, 3)}\""
    }

    private fun stroke(argb: Int, widthPt: Double, dashPt: Double): String {
        val alpha = (argb ushr 24) and 0xFF
        val sb = StringBuilder(" stroke=\"").append(hex(argb)).append('"')
        if (alpha != 255) sb.append(" stroke-opacity=\"").append(n(alpha / 255.0, 3)).append('"')
        sb.append(" stroke-width=\"").append(n(widthPt)).append("\" stroke-linejoin=\"round\"")
        if (dashPt.isFinite() && dashPt > 0.0) sb.append(" stroke-dasharray=\"").append(n(dashPt)).append(' ').append(n(dashPt)).append('"')
        return sb.toString()
    }

    private fun hex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    /** Locale-independent number with at most [decimals] decimals and no trailing zeros. */
    internal fun n(v: Double, decimals: Int = 2): String {
        if (!v.isFinite()) return "0"
        var s = String.format(Locale.ROOT, "%.${decimals}f", v)
        if ('.' in s) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }

    /**
     * XML-escape [text] for element content and attribute values, dropping characters XML 1.0 cannot
     * represent at all (control characters, unpaired surrogates, U+FFFE / U+FFFF).
     */
    internal fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit
                Character.isHighSurrogate(ch) -> {
                    if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                        sb.append(ch).append(text[i + 1])
                        i++
                    }
                }
                Character.isLowSurrogate(ch) -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }
}
