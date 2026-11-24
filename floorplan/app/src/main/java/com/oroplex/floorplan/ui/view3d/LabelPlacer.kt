package com.oroplex.floorplan.ui.view3d

import kotlin.math.abs

/**
 * Keeps room labels from piling on top of each other. Greedy, in priority order: labels whose room is in
 * sight before hidden ones, larger rooms before smaller ones. Each label tries its anchor, then one label
 * height above, then one below, and takes the first spot that does not overlap a label already placed;
 * a label that fits nowhere is left out for this frame.
 *
 * Pure and allocation-free per call (sized once for [areas].size labels), so it can run on every frame.
 */
class LabelPlacer(areas: DoubleArray) {
    private val count = areas.size

    /** Label indices by area, largest first (ties by index, so the order is stable). */
    private val byArea: IntArray = areas.indices.sortedWith(compareByDescending<Int> { areas[it] }.thenBy { it }).toIntArray()

    /** Per label after [place]: whether to draw it, and its vertical offset from the anchor in pixels. */
    val shown = BooleanArray(count)
    val offsetY = FloatArray(count)

    private val placed = IntArray(count)
    private var placedCount = 0

    /**
     * Place labels centered at ([x], [y]) with sizes [width] × [height] (pixels). [state] uses the
     * DollhouseProjector label states: hidden labels are never shown, occluded ones only after visible ones.
     * [gap] is the minimum free space kept between two labels.
     */
    fun place(x: FloatArray, y: FloatArray, state: IntArray, width: FloatArray, height: FloatArray, gap: Float) {
        placedCount = 0
        shown.fill(false)
        offsetY.fill(0f)
        for (pass in 0..1) {
            val wanted = if (pass == 0) DollhouseProjector.LABEL_VISIBLE else DollhouseProjector.LABEL_OCCLUDED
            for (i in byArea) {
                if (state[i] != wanted) continue
                for (candidate in 0 until 3) {
                    val dy = when (candidate) {
                        0 -> 0f
                        1 -> -(height[i] + gap)
                        else -> height[i] + gap
                    }
                    if (fits(i, x[i], y[i] + dy, x, y, width, height, gap)) {
                        shown[i] = true
                        offsetY[i] = dy
                        placed[placedCount++] = i
                        break
                    }
                }
            }
        }
    }

    private fun fits(i: Int, cx: Float, cy: Float, x: FloatArray, y: FloatArray, width: FloatArray, height: FloatArray, gap: Float): Boolean {
        for (k in 0 until placedCount) {
            val j = placed[k]
            val jy = y[j] + offsetY[j]
            val overlapX = abs(cx - x[j]) < (width[i] + width[j]) / 2 + gap
            val overlapY = abs(cy - jy) < (height[i] + height[j]) / 2 + gap
            if (overlapX && overlapY) return false
        }
        return true
    }
}
