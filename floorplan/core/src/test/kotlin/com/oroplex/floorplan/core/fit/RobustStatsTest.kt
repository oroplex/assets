package com.oroplex.floorplan.core.fit

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobustStatsTest {

    @Test fun medianOddEvenEmpty() {
        assertEquals(3.0, RobustStats.median(listOf(5.0, 1.0, 3.0)))
        assertEquals(2.5, RobustStats.median(listOf(4.0, 1.0, 3.0, 2.0)))
        assertEquals(7.0, RobustStats.median(listOf(7.0)))
        assertTrue(RobustStats.median(emptyList()).isNaN())
    }

    @Test fun medianIgnoresNanAndToleratesInfinity() {
        assertEquals(2.0, RobustStats.median(listOf(Double.NaN, 1.0, 2.0, 3.0)))
        assertTrue(RobustStats.median(listOf(Double.NaN)).isNaN())
        assertEquals(2.0, RobustStats.median(listOf(1.0, 2.0, Double.POSITIVE_INFINITY)))
    }

    @Test fun medianDoesNotMutateInput() {
        val input = mutableListOf(3.0, 1.0, 2.0)
        RobustStats.median(input)
        assertEquals(listOf(3.0, 1.0, 2.0), input)
    }

    @Test fun madBasics() {
        // median 3; |dev| = 2,1,0,1,97 -> median 1
        assertEquals(1.0, RobustStats.mad(listOf(1.0, 2.0, 3.0, 4.0, 100.0)))
        assertEquals(0.0, RobustStats.mad(listOf(2.0, 2.0, 2.0, 9.0)))
        assertEquals(0.0, RobustStats.mad(listOf(5.0)))
        assertTrue(RobustStats.mad(emptyList()).isNaN())
    }

    @Test fun madEstimatesGaussianSigma() {
        val rnd = Random(1)
        val values = List(20000) { 10.0 + rnd.nextGaussian() * 0.02 }
        assertEquals(0.02, RobustStats.MAD_TO_SIGMA * RobustStats.mad(values), 0.001)
    }

    @Test fun trimmedMeanRejectsOutliers() {
        val rnd = Random(2)
        val values = List(500) { 1.5 + rnd.nextGaussian() * 0.01 } + List(50) { 40.0 + it } + List(20) { -30.0 }
        val plainMean = values.average()
        val robust = RobustStats.trimmedMean(values)
        assertTrue(kotlin.math.abs(plainMean - 1.5) > 1.0)
        assertEquals(1.5, robust, 0.002)
    }

    @Test fun trimmedMeanFallsBackToMedian() {
        // MAD == 0 -> median, even though the mean would be pulled by the 100.
        assertEquals(2.0, RobustStats.trimmedMean(listOf(2.0, 2.0, 2.0, 100.0)))
        assertEquals(4.0, RobustStats.trimmedMean(listOf(4.0)))
        assertTrue(RobustStats.trimmedMean(emptyList()).isNaN())
        // A degenerate k still returns a sensible value.
        assertEquals(3.0, RobustStats.trimmedMean(listOf(1.0, 2.0, 3.0, 4.0, 5.0), k = -1.0))
    }

    @Test fun trimmedMeanWindowUsesScaledMad() {
        // median 0, MAD 1 -> window k * 1.4826 = 1.4826 for k = 1: keeps -1, 0, 1, drops +-3.
        val values = listOf(-3.0, -1.0, 0.0, 1.0, 3.0, 1.0, -1.0)
        assertEquals(0.0, RobustStats.median(values))
        assertEquals(1.0, RobustStats.mad(values))
        assertEquals(0.0, RobustStats.trimmedMean(values, k = 1.0), 1e-12)
        val skewed = listOf(-1.0, 0.0, 1.0, 1.4, 2.0)
        // median 1, |dev| = 2, 1, 0, 0.4, 1 -> MAD 1; window 1.4826: keeps 0, 1, 1.4, 2
        assertEquals((0.0 + 1.0 + 1.4 + 2.0) / 4, RobustStats.trimmedMean(skewed, k = 1.0), 1e-12)
    }
}
