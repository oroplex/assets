package com.oroplex.floorplan.core.fit

import kotlin.math.abs

/**
 * Outlier-resistant summary statistics used by the AR measurement code (e.g. median of repeated
 * point samples, noise estimates of wall fits).
 *
 * NaN entries are ignored by every function (they carry no information and would otherwise poison
 * the ordering); infinities are kept and sort to the ends as usual.
 */
object RobustStats {
    /**
     * Scale factor that turns a MAD into a consistent estimator of the standard deviation for
     * normally distributed data (1 / Φ⁻¹(3/4)).
     */
    const val MAD_TO_SIGMA = 1.4826

    /** Median of the values; NaN for an empty list. Even counts average the two middle values. */
    fun median(values: List<Double>): Double = medianOfSorted(sortedFinite(values))

    /** Median absolute deviation (unscaled); NaN for an empty list. */
    fun mad(values: List<Double>): Double {
        val sorted = sortedFinite(values)
        if (sorted.isEmpty()) return Double.NaN
        val m = medianOfSorted(sorted)
        val deviations = DoubleArray(sorted.size) { abs(sorted[it] - m) }
        deviations.sort()
        return medianOfSorted(deviations)
    }

    /** Mean of values within [k] * 1.4826 * MAD of the median (falls back to median if MAD == 0). */
    fun trimmedMean(values: List<Double>, k: Double = 2.5): Double {
        val sorted = sortedFinite(values)
        if (sorted.isEmpty()) return Double.NaN
        val m = medianOfSorted(sorted)
        val deviations = DoubleArray(sorted.size) { abs(sorted[it] - m) }
        deviations.sort()
        val madValue = medianOfSorted(deviations)
        if (!(madValue > 0.0) || madValue.isInfinite()) return m
        val window = k * MAD_TO_SIGMA * madValue
        var sum = 0.0
        var count = 0
        for (v in sorted) {
            if (abs(v - m) <= window) {
                sum += v
                count++
            }
        }
        // An empty window is only possible for a tiny / negative / NaN k: the median is the best answer.
        return if (count == 0) m else sum / count
    }

    private fun sortedFinite(values: List<Double>): DoubleArray {
        val out = DoubleArray(values.size)
        var n = 0
        for (v in values) if (!v.isNaN()) out[n++] = v
        val trimmed = if (n == out.size) out else out.copyOf(n)
        trimmed.sort()
        return trimmed
    }

    private fun medianOfSorted(sorted: DoubleArray): Double {
        val n = sorted.size
        if (n == 0) return Double.NaN
        val mid = n / 2
        return if (n % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
