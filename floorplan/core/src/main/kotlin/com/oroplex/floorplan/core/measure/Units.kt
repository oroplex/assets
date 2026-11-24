package com.oroplex.floorplan.core.measure

import com.oroplex.floorplan.core.model.UnitSystem

object Units {
    const val METERS_PER_INCH = 0.0254
    const val METERS_PER_FOOT = 0.3048
    const val SQFT_PER_SQM = 10.763910416709722

    /**
     * IMPERIAL: feet-inches rounded to 1/[imperialDenominator] inch, e.g. 12' 4 1/2", 0' 11", 10' 0".
     *   Rounding carries correctly (11 31/32" at 1/2" precision -> 1' 0"). Fractions are reduced (2/4 -> 1/2).
     * METRIC: meters with 2 decimals, e.g. "3.76 m"; below 1 m use centimeters, e.g. "85 cm".
     * Negative values are formatted with a leading "-".
     */
    fun formatLength(meters: Double, system: UnitSystem, imperialDenominator: Int = 2): String = TODO("implement")

    /** "152 sq ft" (rounded to whole sq ft) or "14.1 m²" (1 decimal). */
    fun formatArea(squareMeters: Double, system: UnitSystem): String = TODO("implement")

    /**
     * Parse user input into meters. Accepts e.g. 12'4", 12' 4 1/2", 12ft 4in, 148", 148 in, 12.5' ,
     * 3.76m, 376cm, 3760mm, and a bare number (feet for IMPERIAL, meters for METRIC).
     * Returns null for unparseable, negative, or zero input.
     */
    fun parseLength(text: String, system: UnitSystem): Double? = TODO("implement")
}
