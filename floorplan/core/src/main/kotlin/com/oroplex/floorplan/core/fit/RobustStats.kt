package com.oroplex.floorplan.core.fit

object RobustStats {
    /** Median of the values; NaN for an empty list. */
    fun median(values: List<Double>): Double = TODO("implement")

    /** Median absolute deviation (unscaled); NaN for an empty list. */
    fun mad(values: List<Double>): Double = TODO("implement")

    /** Mean of values within [k] * 1.4826 * MAD of the median (falls back to median if MAD == 0). */
    fun trimmedMean(values: List<Double>, k: Double = 2.5): Double = TODO("implement")
}
