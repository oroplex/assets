package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Level of detail for plan labels at a KNOWN output scale ([RenderOptions.pointsPerMeter]).
 *
 * Label sizes are in points, so on a small output a label can be larger than the room it names: a phone
 * screen is only about 185 pt wide, a third of the page the nominal layout assumes, and at "fit the whole
 * floor" every name, size and wall length of a small room lands on top of its neighbours. With the scale
 * known, a label is only drawn when it fits the space it belongs to:
 *
 * * Room block, in priority order: name (shrunk down to [MIN_SCALE], wrapped onto two lines, then shortened
 *   with an ellipsis, then dropped), "L × W", area. Each line must fit the free width of the room through the label anchor and the
 *   whole block its free height, with [PAD_PT] to spare.
 * * Wall lengths: only on walls long enough for the text plus [WALL_MARGIN_PT] at each end, only where the
 *   text lies inside the room, and never over the room block or another wall length (longer walls first).
 *
 * Zooming in raises the scale, so everything reappears. Widths are estimated from per-character
 * advances ([textWidthPt]) with a small safety margin, so real text does not overflow.
 */
internal object LabelFit {
    /**
     * Smallest factor a room name may be shrunk by before it is shortened or dropped: 5.5 pt of 10, which is
     * about 12 dp on a phone, the size of small UI text.
     */
    const val MIN_SCALE = 0.55

    /** Smallest factor a detail line ("L × W", area) may be shrunk by before it is dropped (5.6 pt of 8). */
    const val MIN_DETAIL_SCALE = 0.7

    /** A name is wrapped onto two lines when that lets it be at least this much bigger than on one line. */
    const val WRAP_GAIN = 0.15

    /** Clear space kept between a room label block and the walls, points. */
    const val PAD_PT = 2.0

    /** Clear space kept at each end of a wall-length label, points. */
    const val WALL_MARGIN_PT = 4.0

    /** Bold text is about this much wider than regular text. */
    const val BOLD_FACTOR = 1.03

    /** Estimates are padded by this factor, so real text is never wider than the space reserved for it. */
    const val WIDTH_SAFETY = 1.04

    /** A shortened name keeps at least this many characters before the ellipsis; otherwise it is dropped. */
    const val MIN_NAME_CHARS = 3

    private const val EPS = 1e-9

    /**
     * Estimated width of [text] in points. Per-character advances approximate a humanist sans-serif such as
     * Roboto (the Android and PDF text font): dimension strings are mostly narrow characters (quotes, spaces,
     * "1", "/"), so a single average width would over-estimate them by a third and hide labels that fit.
     */
    fun textWidthPt(text: String, sizePt: Double, bold: Boolean): Double {
        var em = 0.0
        for (ch in text) em += advance(ch)
        return em * sizePt * (if (bold) BOLD_FACTOR else 1.0) * WIDTH_SAFETY
    }

    /** Advance of one character in em (fraction of the text size). */
    private fun advance(ch: Char): Double = when (ch) {
        ' ' -> 0.25
        '\'', '’', '′' -> 0.18
        '"', '”', '″' -> 0.33
        '.', ',', ':', ';', '!', '|' -> 0.26
        'i', 'j', 'l', 'I' -> 0.26
        'f', 't', 'r' -> 0.36
        '-', '(', ')', '/', '·' -> 0.35
        'm', 'M', 'W' -> 0.88
        'w' -> 0.76
        '…' -> 0.68
        '×', '+', '=' -> 0.56
        '²', '³', '°' -> 0.38
        in '0'..'9' -> 0.57
        in 'a'..'z' -> 0.55
        in 'A'..'Z' -> 0.65
        else -> 0.62
    }

    /** An oriented rectangle in plan meters: [center] plus / minus the half vectors [along] and [across]. */
    class Box(val center: Vec2, val along: Vec2, val across: Vec2) {
        fun corners(): List<Vec2> = listOf(
            center + along + across, center + along - across, center - along - across, center - along + across,
        )

        /** True when the two rectangles overlap (separating-axis test; touching counts as apart). */
        fun overlaps(other: Box): Boolean {
            val axes = listOf(along, across, other.along, other.across)
            for (axis in axes) {
                val n = axis.normalized()
                if (n == Vec2.ZERO) continue
                val d = abs((other.center - center) dot n)
                val r1 = abs(along dot n) + abs(across dot n)
                val r2 = abs(other.along dot n) + abs(other.across dot n)
                if (d >= r1 + r2 - EPS) return false
            }
            return true
        }
    }

    /**
     * Free length (meters) inside the outline for something centered on [p] and running along [dir]: twice
     * the shorter of the two distances from [p] to the outline along +[dir] and -[dir]. 0 when [p] is not
     * inside or the outline is unusable.
     */
    fun clearSpan(corners: List<Vec2>, p: Vec2, dir: Vec2): Double {
        val d = dir.normalized()
        if (d == Vec2.ZERO || !p.isFinite() || !Polygons.contains(corners, p)) return 0.0
        var forward = Double.POSITIVE_INFINITY
        var backward = Double.POSITIVE_INFINITY
        for (i in corners.indices) {
            val a = corners[i]
            val e = corners[(i + 1) % corners.size] - a
            val denom = d cross e
            if (abs(denom) < EPS) continue
            val ap = a - p
            val t = (ap cross e) / denom // distance along d
            val u = (ap cross d) / denom // position along the edge
            if (u < -EPS || u > 1 + EPS) continue
            if (t >= 0) forward = min(forward, t) else backward = min(backward, -t)
        }
        val half = min(forward, backward)
        return if (half.isFinite()) 2 * half else 0.0
    }

    /** A fitted room label and the box it occupies (padded), for keeping wall lengths off it. */
    class RoomLabel(val layout: LabelLayout, val box: Box)

    /**
     * Fit a room's label block at [metersPerPt]. [name] is the bold first line (null to omit), [details] the
     * secondary lines in priority order. [angle] is the preferred baseline direction and [alternativeAngle]
     * (e.g. the room's long axis) is used instead when it shows more. Null when nothing fits.
     */
    fun roomLabel(
        corners: List<Vec2>,
        anchor: Vec2,
        angle: Double,
        alternativeAngle: Double?,
        name: String?,
        details: List<String>,
        nameSizePt: Double,
        detailSizePt: Double,
        nameColor: Int,
        detailColor: Int,
        metersPerPt: Double,
    ): RoomLabel? {
        if (!metersPerPt.isFinite() || metersPerPt <= 0.0 || (name == null && details.isEmpty())) return null
        val first = attempt(corners, anchor, angle, name, details, nameSizePt, detailSizePt, nameColor, detailColor, metersPerPt)
        val second = alternativeAngle?.takeIf { abs(it - angle) > 1e-6 }?.let {
            attempt(corners, anchor, it, name, details, nameSizePt, detailSizePt, nameColor, detailColor, metersPerPt)
        }
        return when {
            first == null -> second?.label
            second == null -> first.label
            second.rank > first.rank -> second.label
            else -> first.label
        }
    }

    private class Attempt(val label: RoomLabel, val rank: Double)

    private fun attempt(
        corners: List<Vec2>,
        anchor: Vec2,
        angle: Double,
        name: String?,
        details: List<String>,
        nameSizePt: Double,
        detailSizePt: Double,
        nameColor: Int,
        detailColor: Int,
        metersPerPt: Double,
    ): Attempt? {
        val along = Vec2.fromAngle(angle)
        val across = along.perp()
        // Free width through the anchor and half a name line above / below it (the block is taller than a line).
        val probe = 0.5 * nameSizePt * PlanGeometry.LINE_SPACING * metersPerPt
        var spanAlong = clearSpan(corners, anchor, along)
        for (k in listOf(-probe, probe)) {
            val p = anchor + across * k
            if (Polygons.contains(corners, p)) spanAlong = min(spanAlong, clearSpan(corners, p, along))
        }
        val availW = spanAlong / metersPerPt - 2 * PAD_PT
        val availH = clearSpan(corners, anchor, across) / metersPerPt - 2 * PAD_PT
        if (availW <= 0.0 || availH <= 0.0) return null

        val lines = ArrayList<LabelLine>()
        var height = 0.0
        var widest = 0.0
        var nameScale = 1.0
        var shortened = false
        if (name != null) {
            // One line must fit the free height at all; then the name shrinks to the free width, down to MIN_SCALE.
            val lineHeight = nameSizePt * PlanGeometry.LINE_SPACING
            if (availH / lineHeight < MIN_SCALE) return null
            val oneLine = min(1.0, min(availH / lineHeight, availW / textWidthPt(name, nameSizePt, true)))
            // Two lines ("Primary" / "Bedroom") when one line would be small and wrapping is clearly bigger.
            val wrapped = if (oneLine < 1.0) wrap(name) else null
            val twoLines = wrapped?.let { (a, b) ->
                val widest2 = max(textWidthPt(a, nameSizePt, true), textWidthPt(b, nameSizePt, true))
                min(1.0, min(availH / (2 * lineHeight), availW / widest2))
            } ?: 0.0
            val texts: List<String>
            when {
                wrapped != null && twoLines >= MIN_SCALE && twoLines >= oneLine + WRAP_GAIN -> {
                    nameScale = twoLines
                    texts = wrapped.toList()
                }
                oneLine >= MIN_SCALE -> {
                    nameScale = oneLine
                    texts = listOf(name)
                }
                else -> {
                    nameScale = MIN_SCALE
                    texts = listOf(shorten(name, availW, nameSizePt * MIN_SCALE) ?: return null)
                    shortened = true
                }
            }
            val size = nameSizePt * nameScale
            for (text in texts) {
                lines += LabelLine(text, size, true, nameColor)
                height += size * PlanGeometry.LINE_SPACING
                widest = max(widest, textWidthPt(text, size, true))
            }
        }
        var shownDetails = 0
        for (detail in details) {
            // Details never look bigger than the name was allowed to be, and each must fit on its own.
            val scale = min(nameScale, availW / textWidthPt(detail, detailSizePt, false))
            if (scale < MIN_DETAIL_SCALE) continue
            val size = detailSizePt * scale
            if (height + size * PlanGeometry.LINE_SPACING > availH) continue
            lines += LabelLine(detail, size, false, detailColor)
            height += size * PlanGeometry.LINE_SPACING
            widest = max(widest, textWidthPt(detail, size, false))
            shownDetails++
        }
        if (lines.isEmpty()) return null

        // Stack the lines top to bottom, centering the whole block on the anchor (as the nominal layout does).
        var top = height / 2
        val offsets = lines.map { line ->
            val h = line.sizePt * PlanGeometry.LINE_SPACING
            (top - h / 2).also { top -= h }
        }
        val box = Box(
            center = anchor,
            along = along * ((widest / 2 + PAD_PT) * metersPerPt),
            across = across * ((height / 2 + PAD_PT) * metersPerPt),
        )
        // More detail lines beat fewer; a whole name beats a shortened one; then the bigger name.
        val rank = shownDetails + (if (name != null && !shortened) 1.0 else 0.0) + 0.5 * nameScale
        return Attempt(RoomLabel(LabelLayout(anchor, angle, lines, offsets), box), rank)
    }

    /**
     * [name] split at the space that gives the most even two lines, or null when it has no usable space. A
     * short trailing number is never put on a line of its own ("Bedroom 2" stays together).
     */
    internal fun wrap(name: String): Pair<String, String>? {
        var best: Pair<String, String>? = null
        var bestWidth = Double.POSITIVE_INFINITY
        for (i in name.indices) {
            if (name[i] != ' ') continue
            val a = name.substring(0, i).trim()
            val b = name.substring(i + 1).trim()
            if (a.isEmpty() || b.isEmpty()) continue
            if (b.length <= 3 && b.all(Char::isDigit)) continue
            val width = max(textWidthPt(a, 1.0, true), textWidthPt(b, 1.0, true))
            if (width < bestWidth) {
                bestWidth = width
                best = a to b
            }
        }
        return best
    }

    /**
     * [name] cut to fit [availW] points at bold [sizePt], ending in an ellipsis; a trailing number is kept
     * ("Bedroom 2" becomes "Bed… 2", never "Bedroom…" twice). Null when fewer than [MIN_NAME_CHARS] letters fit.
     */
    internal fun shorten(name: String, availW: Double, sizePt: Double): String? {
        val lastSpace = name.lastIndexOf(' ')
        val number = name.substring(lastSpace + 1).takeIf { lastSpace > 0 && it.length in 1..3 && it.all(Char::isDigit) }
        val head = if (number != null) name.substring(0, lastSpace).trimEnd() else name
        val tail = if (number != null) "… $number" else "…"
        for (keep in head.length - 1 downTo MIN_NAME_CHARS) {
            val candidate = head.take(keep).trimEnd() + tail
            if (textWidthPt(candidate, sizePt, true) <= availW) return candidate
        }
        return null
    }

    /** A wall-length label candidate: [index] in the caller's list, the wall's [length] (m) and its text [box]. */
    class WallLabel(val index: Int, val length: Double, val widthPt: Double, val box: Box)

    /**
     * Indices of the wall-length labels to draw: walls long enough for their text, whose text lies inside the
     * room [corners], clear of the room's label [roomBox] and of every label already kept (longest walls win).
     */
    fun wallLabels(corners: List<Vec2>, candidates: List<WallLabel>, roomBox: Box?, metersPerPt: Double): Set<Int> {
        if (!metersPerPt.isFinite() || metersPerPt <= 0.0) return candidates.map { it.index }.toSet()
        val kept = ArrayList<WallLabel>()
        for (c in candidates.sortedWith(compareByDescending<WallLabel> { it.length }.thenBy { it.index })) {
            if (c.length / metersPerPt < c.widthPt + 2 * WALL_MARGIN_PT) continue
            if (c.box.corners().any { !Polygons.contains(corners, it) }) continue
            if (roomBox != null && c.box.overlaps(roomBox)) continue
            if (kept.any { it.box.overlaps(c.box) }) continue
            kept += c
        }
        return kept.map { it.index }.toSet()
    }
}
