package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.ui.view3d.DollhouseProjector.Companion.LABEL_HIDDEN
import com.oroplex.floorplan.ui.view3d.DollhouseProjector.Companion.LABEL_OCCLUDED
import com.oroplex.floorplan.ui.view3d.DollhouseProjector.Companion.LABEL_VISIBLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelPlacerTest {
    private val w = floatArrayOf(100f, 100f, 100f)
    private val h = floatArrayOf(20f, 20f, 20f)

    @Test fun separateLabelsStayAtTheirAnchors() {
        val placer = LabelPlacer(doubleArrayOf(10.0, 20.0, 5.0))
        placer.place(floatArrayOf(0f, 300f, 600f), floatArrayOf(0f, 0f, 0f), intArrayOf(LABEL_VISIBLE, LABEL_VISIBLE, LABEL_VISIBLE), w, h, 4f)
        assertTrue(placer.shown.all { it })
        assertTrue(placer.offsetY.all { it == 0f })
    }

    @Test fun collidingLabelIsNudgedAndTheLargerRoomKeepsItsSpot() {
        val placer = LabelPlacer(doubleArrayOf(5.0, 20.0, 1.0))
        // Labels 0 and 1 at the same spot; 1 is the larger room. Label 2 is far away.
        placer.place(floatArrayOf(0f, 10f, 600f), floatArrayOf(0f, 0f, 0f), intArrayOf(LABEL_VISIBLE, LABEL_VISIBLE, LABEL_VISIBLE), w, h, 4f)
        assertTrue(placer.shown[0] && placer.shown[1] && placer.shown[2])
        assertEquals(0f, placer.offsetY[1])
        assertEquals(-24f, placer.offsetY[0]) // one label height + gap up
    }

    @Test fun labelThatFitsNowhereIsLeftOut() {
        val placer = LabelPlacer(doubleArrayOf(3.0, 2.0, 1.0))
        val x = floatArrayOf(0f, 0f, 0f)
        val y = floatArrayOf(0f, 0f, 0f)
        placer.place(x, y, intArrayOf(LABEL_VISIBLE, LABEL_VISIBLE, LABEL_VISIBLE), w, h, 4f)
        assertTrue(placer.shown[0])
        assertTrue(placer.shown[1]) // above
        assertTrue(placer.shown[2]) // below
        assertEquals(setOf(0f, -24f, 24f), placer.offsetY.toSet())
        // A fourth crowded label would have nowhere to go.
        val four = LabelPlacer(doubleArrayOf(4.0, 3.0, 2.0, 1.0))
        four.place(FloatArray(4), FloatArray(4), IntArray(4) { LABEL_VISIBLE }, FloatArray(4) { 100f }, FloatArray(4) { 20f }, 4f)
        assertEquals(3, four.shown.count { it })
        assertFalse(four.shown[3])
    }

    @Test fun roomsInSightWinOverHiddenOnesAndHiddenLabelsNeverShow() {
        val placer = LabelPlacer(doubleArrayOf(50.0, 1.0, 9.0))
        // The big room is behind a wall; the small one is in sight at the same spot.
        placer.place(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f), intArrayOf(LABEL_OCCLUDED, LABEL_VISIBLE, LABEL_HIDDEN), w, h, 4f)
        assertTrue(placer.shown[1])
        assertEquals(0f, placer.offsetY[1])
        assertTrue(placer.shown[0])
        assertTrue(placer.offsetY[0] != 0f)
        assertFalse(placer.shown[2])
    }

    @Test fun repeatedCallsStartFresh() {
        val placer = LabelPlacer(doubleArrayOf(1.0, 2.0))
        val state = intArrayOf(LABEL_VISIBLE, LABEL_VISIBLE)
        placer.place(floatArrayOf(0f, 0f), floatArrayOf(0f, 0f), state, w, h, 4f)
        placer.place(floatArrayOf(0f, 500f), floatArrayOf(0f, 0f), state, w, h, 4f)
        assertTrue(placer.shown.all { it })
        assertTrue(placer.offsetY.all { it == 0f })
        LabelPlacer(DoubleArray(0)).place(FloatArray(0), FloatArray(0), IntArray(0), FloatArray(0), FloatArray(0), 4f)
    }
}
