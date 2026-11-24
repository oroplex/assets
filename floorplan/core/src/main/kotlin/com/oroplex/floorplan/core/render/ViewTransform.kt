package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import kotlin.math.min

/**
 * Maps plan meters (y up) to an output surface in pixels (y down).
 * [pxPerPt] converts DrawList point sizes to pixels (screen: density * 160/72; PDF: 1.0; SVG: 1.0).
 */
data class ViewTransform(val pxPerMeter: Double, val originPx: Vec2, val pxPerPt: Double) {

    fun toScreen(p: Vec2): Vec2 = Vec2(originPx.x + p.x * pxPerMeter, originPx.y - p.y * pxPerMeter)

    fun toPlan(s: Vec2): Vec2 = Vec2((s.x - originPx.x) / pxPerMeter, (originPx.y - s.y) / pxPerMeter)

    /** Screen rotation angle in degrees (clockwise-positive, as android.graphics.Canvas.rotate expects). */
    fun screenDegrees(planAngle: Double): Double = -Math.toDegrees(planAngle)

    /** Zoom by [factor] keeping the plan point under screen pixel [focusPx] fixed. */
    fun zoomedAround(focusPx: Vec2, factor: Double): ViewTransform {
        val newOrigin = focusPx + (originPx - focusPx) * factor
        return copy(pxPerMeter = pxPerMeter * factor, originPx = newOrigin)
    }

    fun panned(deltaPx: Vec2): ViewTransform = copy(originPx = originPx + deltaPx)

    companion object {
        /** Fit [bounds] centered inside a width x height surface with [marginPx] on every side. */
        fun fit(bounds: Bounds, widthPx: Double, heightPx: Double, marginPx: Double, pxPerPt: Double): ViewTransform {
            if (bounds.isEmpty || bounds.width <= 0 && bounds.height <= 0) {
                return ViewTransform(100.0, Vec2(widthPx / 2, heightPx / 2), pxPerPt)
            }
            val availW = (widthPx - 2 * marginPx).coerceAtLeast(1.0)
            val availH = (heightPx - 2 * marginPx).coerceAtLeast(1.0)
            val scale = min(
                if (bounds.width > 0) availW / bounds.width else Double.POSITIVE_INFINITY,
                if (bounds.height > 0) availH / bounds.height else Double.POSITIVE_INFINITY,
            )
            val c = bounds.center
            // screen center = origin + (c.x*s, -c.y*s)  =>  origin = center - (c.x*s, -c.y*s)
            val origin = Vec2(widthPx / 2 - c.x * scale, heightPx / 2 + c.y * scale)
            return ViewTransform(scale, origin, pxPerPt)
        }
    }
}
