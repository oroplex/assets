package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.model.UnitSystem

/**
 * A standard architectural drawing scale: one unit on paper is [denominator] units in the building.
 * [label] is how it is written on a drawing, e.g. `1/4" = 1'-0"` (imperial) or `1:50` (metric).
 */
data class PlanScale(val denominator: Int, val label: String) {
    /** PDF points one plan meter takes on paper when the page is printed at 100 %. */
    val pointsPerMeter: Double get() = PlanScales.POINTS_PER_METER / denominator
}

/** The scale and page orientation shared by every plan sheet of one PDF. */
data class SheetScale(val scale: PlanScale, val landscape: Boolean)

/**
 * Chooses the drawing scale of a PDF (pure, unit-tested).
 *
 * A contractor reads a plan with a scale ruler and compares floors side by side, so all plan sheets of a
 * document share ONE standard scale and ONE orientation instead of each floor being fitted to its page
 * (which gives ratios such as 1:73 that differ from sheet to sheet).
 */
object PlanScales {
    /** Points (1/72 in) in one meter: a drawing at 1:1. */
    const val POINTS_PER_METER = 72.0 / 0.0254

    /** Inches-per-foot scales, largest first: 1:48, 1:64, 1:96, 1:128, 1:192. */
    val IMPERIAL: List<PlanScale> = listOf(
        PlanScale(48, "1/4\" = 1'-0\""),
        PlanScale(64, "3/16\" = 1'-0\""),
        PlanScale(96, "1/8\" = 1'-0\""),
        PlanScale(128, "3/32\" = 1'-0\""),
        PlanScale(192, "1/16\" = 1'-0\""),
    )

    /** Metric scales, largest first. */
    val METRIC: List<PlanScale> = listOf(50, 75, 100, 150, 200).map { PlanScale(it, "1:$it") }

    /** The standard scales for [system], largest (most detailed) first. */
    fun standard(system: UnitSystem): List<PlanScale> = if (system == UnitSystem.IMPERIAL) IMPERIAL else METRIC

    /**
     * True when a drawing with [bounds] (plan meters) printed at [scale] fits the plan area of a [paper]
     * page in the given orientation, leaving [SheetLayout.PLAN_FIT_MARGIN] free on every side.
     */
    fun fits(bounds: Bounds, scale: PlanScale, paper: PaperSize, landscape: Boolean): Boolean {
        if (!isDrawable(bounds)) return false
        val area = SheetLayout.planArea(paper.widthPt(landscape).toDouble(), paper.heightPt(landscape).toDouble())
        val availableWidth = area.width - 2 * SheetLayout.PLAN_FIT_MARGIN
        val availableHeight = area.height - 2 * SheetLayout.PLAN_FIT_MARGIN
        val ppm = scale.pointsPerMeter
        return bounds.width * ppm <= availableWidth + FIT_SLACK_PT && bounds.height * ppm <= availableHeight + FIT_SLACK_PT
    }

    /**
     * The largest standard scale of [system] at which EVERY floor fits its plan area on [paper] in one common
     * orientation, with that orientation; null when even the smallest standard scale is too large (the caller
     * then fits each floor to its page) or there is nothing to draw.
     *
     * [boundsAt] returns the drawing bounds (plan meters) of every floor rendered for a scale. It is asked
     * once per candidate because bounds depend on the scale: the overall dimension lines sit a fixed number
     * of points outside the walls, which is more meters at a smaller scale.
     *
     * Landscape and portrait are both tried; a scale that fits in only one of them decides the orientation.
     * When both fit, the pages follow the shape of the plans (landscape for plans clearly wider than tall,
     * the same [SheetLayout.LANDSCAPE_ASPECT] rule as fit-to-page sheets).
     */
    fun choose(system: UnitSystem, paper: PaperSize, boundsAt: (PlanScale) -> List<Bounds>): SheetScale? {
        for (scale in standard(system)) {
            val bounds = boundsAt(scale).filter(::isDrawable)
            if (bounds.isEmpty()) return null
            val landscapeFits = bounds.all { fits(it, scale, paper, landscape = true) }
            val portraitFits = bounds.all { fits(it, scale, paper, landscape = false) }
            when {
                landscapeFits && portraitFits -> return SheetScale(scale, prefersLandscape(bounds))
                landscapeFits -> return SheetScale(scale, landscape = true)
                portraitFits -> return SheetScale(scale, landscape = false)
            }
        }
        return null
    }

    /** Landscape when the widest floor is clearly wider than the tallest floor is tall. */
    internal fun prefersLandscape(bounds: List<Bounds>): Boolean {
        val width = bounds.maxOfOrNull { it.width } ?: return false
        val height = bounds.maxOfOrNull { it.height } ?: return false
        return height > 1e-9 && width / height > SheetLayout.LANDSCAPE_ASPECT
    }

    private fun isDrawable(b: Bounds): Boolean =
        !b.isEmpty && b.width.isFinite() && b.height.isFinite() && (b.width > 0 || b.height > 0)

    /** Rounding slack (points) so a plan that exactly fills the area still counts as fitting. */
    private const val FIT_SLACK_PT = 1e-6
}
