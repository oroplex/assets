package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelFitTest {
    private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) =
        listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))

    private val room4x3 = rect(0.0, 0.0, 4.0, 3.0)

    private fun fit(metersPerPt: Double, name: String? = "Bedroom", corners: List<Vec2> = room4x3, alt: Double? = null) =
        LabelFit.roomLabel(
            corners, Vec2(corners.map { it.x }.average(), corners.map { it.y }.average()), 0.0, alt, name,
            listOf("13' 1 1/2\" × 9' 10\"", "129 sq ft"), 10.0, 8.0, 1, 2, metersPerPt,
        )

    @Test
    fun clearSpanIsTwiceTheShorterDistanceToTheOutline() {
        assertEquals(4.0, LabelFit.clearSpan(room4x3, Vec2(2.0, 1.5), Vec2(1.0, 0.0)), 1e-9)
        assertEquals(3.0, LabelFit.clearSpan(room4x3, Vec2(2.0, 1.5), Vec2(0.0, 1.0)), 1e-9)
        assertEquals(2.0, LabelFit.clearSpan(room4x3, Vec2(1.0, 1.5), Vec2(1.0, 0.0)), 1e-9) // off-centre
        assertEquals(0.0, LabelFit.clearSpan(room4x3, Vec2(9.0, 9.0), Vec2(1.0, 0.0)), 1e-9) // outside
        assertEquals(0.0, LabelFit.clearSpan(room4x3, Vec2(2.0, 1.5), Vec2.ZERO), 1e-9)
    }

    @Test
    fun atAPageScaleTheWholeBlockIsShownAtFullSize() {
        // 4 m over 200 pt: the name and both details fit easily.
        val label = assertNotNull(fit(metersPerPt = 0.02))
        assertEquals(listOf("Bedroom", "13' 1 1/2\" × 9' 10\"", "129 sq ft"), label.layout.lines.map { it.text })
        assertEquals(listOf(10.0, 8.0, 8.0), label.layout.lines.map { it.sizePt })
    }

    @Test
    fun atAPhoneScaleLowPriorityLinesGoFirst() {
        // 4 m over 44 pt: the name fits, the long "L × W" line does not, the short area line does.
        val label = assertNotNull(fit(metersPerPt = 0.09))
        assertEquals(listOf("Bedroom", "129 sq ft"), label.layout.lines.map { it.text })
        // A lower room: the name still fits, but there is no height left for a second line.
        val low = assertNotNull(fit(metersPerPt = 0.09, corners = rect(0.0, 0.0, 4.0, 1.6)))
        assertEquals(listOf("Bedroom"), low.layout.lines.map { it.text })
    }

    @Test
    fun theNameShrinksThenWrapsThenIsShortenedThenDropped() {
        // 35 pt free width: "Bedroom" (about 45 pt at 10 pt) shrinks to fit.
        val shrunk = assertNotNull(fit(metersPerPt = 0.1))
        assertEquals("Bedroom", shrunk.layout.lines.first().text)
        assertTrue(shrunk.layout.lines.first().sizePt in 5.5..9.99)
        // A two-word name that would be tiny on one line is wrapped instead, at a bigger size.
        val wrapped = assertNotNull(fit(metersPerPt = 0.1, name = "Primary Bedroom"))
        assertEquals(listOf("Primary", "Bedroom"), wrapped.layout.lines.filter { it.bold }.map { it.text })
        assertTrue(wrapped.layout.lines.first().sizePt > 7.0)
        // A long single word cannot wrap: it is shortened with an ellipsis at the minimum size.
        val shortened = assertNotNull(fit(metersPerPt = 0.14, name = "Conservatory"))
        assertTrue(shortened.layout.lines.first().text.endsWith("…"))
        assertEquals(5.5, shortened.layout.lines.first().sizePt, 1e-9)
        // Thumbnail scale: nothing readable fits.
        assertNull(fit(metersPerPt = 0.4))
    }

    @Test
    fun namesWrapAtTheMostEvenSpaceAndKeepNumbersTogether() {
        assertEquals("Primary" to "Bedroom", LabelFit.wrap("Primary Bedroom"))
        assertEquals("Living /" to "Kitchen", LabelFit.wrap("Living / Kitchen"))
        assertNull(LabelFit.wrap("Bedroom 2"))
        assertNull(LabelFit.wrap("Hallway"))
        assertEquals("Guest" to "Bedroom 2", LabelFit.wrap("Guest Bedroom 2"))
    }

    @Test
    fun aShortenedNameKeepsItsNumber() {
        assertEquals("Bed… 2", LabelFit.shorten("Bedroom 2", LabelFit.textWidthPt("Bed… 2", 6.0, true) + 0.01, 6.0))
        assertEquals("Bedr… 12", LabelFit.shorten("Bedroom 12", LabelFit.textWidthPt("Bedr… 12", 6.0, true) + 0.01, 6.0))
        assertEquals("Prim…", LabelFit.shorten("Primary Bedroom", LabelFit.textWidthPt("Prim…", 6.0, true) + 0.01, 6.0))
        assertNull(LabelFit.shorten("Bedroom 2", 10.0, 6.0)) // not even "Bed… 2" fits
        assertNull(LabelFit.shorten("Den", 100.0, 6.0)) // nothing shorter than the name itself is worth an ellipsis
    }

    @Test
    fun dimensionTextIsNarrowerThanLetters() {
        // 19 characters of quotes, spaces and digits are narrower than 19 letters.
        val dims = LabelFit.textWidthPt("13' 1 1/2\" × 9' 10\"", 8.0, false)
        val letters = LabelFit.textWidthPt("abcdefghknopqsuvxyz", 8.0, false)
        assertTrue(dims < 0.8 * letters, "$dims vs $letters")
        assertTrue(LabelFit.textWidthPt("Room", 10.0, true) > LabelFit.textWidthPt("Room", 10.0, false))
    }

    @Test
    fun theBlockNeverLeavesTheRoom() {
        for (mpp in listOf(0.01, 0.02, 0.04, 0.06, 0.08, 0.1, 0.15, 0.2)) {
            val label = fit(mpp, name = "Primary Bedroom") ?: continue
            for (corner in label.box.corners()) {
                assertTrue(corner.x in -1e-9..4.0 + 1e-9 && corner.y in -1e-9..3.0 + 1e-9, "box leaves the room at $mpp m/pt: $corner")
            }
        }
    }

    @Test
    fun anElongatedRoomTurnsItsLabelWhenThatShowsMore() {
        val closet = rect(0.0, 0.0, 0.9, 3.0)
        // Horizontal: 0.9 m at 0.03 m/pt = 30 pt minus padding: the name wraps and shrinks, and "L × W" does not fit.
        val horizontal = assertNotNull(fit(0.03, name = "Linen Closet", corners = closet))
        assertEquals(0.0, horizontal.layout.angle, 1e-9)
        assertEquals(listOf("Linen", "Closet"), horizontal.layout.lines.filter { it.bold }.map { it.text })
        assertTrue(horizontal.layout.lines.none { "×" in it.text })
        // Allowed to turn along the 3 m axis: the whole name on one line, at full size, with its "L × W" line.
        val turned = assertNotNull(fit(0.03, name = "Linen Closet", corners = closet, alt = PI / 2))
        assertEquals(PI / 2, turned.layout.angle, 1e-9)
        assertEquals("Linen Closet", turned.layout.lines.first().text)
        assertEquals(10.0, turned.layout.lines.first().sizePt, 1e-9)
        assertTrue(turned.layout.lines.any { "×" in it.text })
    }

    @Test
    fun boxesOverlapOnlyWhenTheyReallyDo() {
        val a = LabelFit.Box(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 0.5))
        assertTrue(a.overlaps(LabelFit.Box(Vec2(1.5, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 0.5))))
        assertFalse(a.overlaps(LabelFit.Box(Vec2(2.5, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 0.5))))
        assertFalse(a.overlaps(LabelFit.Box(Vec2(2.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 0.5)))) // touching
        // A box rotated 45° next to the corner: axis-aligned bounds overlap, the boxes do not.
        val d = 0.5
        val rotated = LabelFit.Box(Vec2(1.9, 1.4), Vec2(d, d), Vec2(-d * 0.2, d * 0.2))
        assertFalse(a.overlaps(rotated))
    }

    @Test
    fun wallLengthsNeedRoomOnTheirWallAndInTheRoom() {
        fun wall(index: Int, length: Double, center: Vec2, horizontal: Boolean) = LabelFit.WallLabel(
            index, length, widthPt = 30.0,
            box = if (horizontal) LabelFit.Box(center, Vec2(0.32, 0.0), Vec2(0.0, 0.09)) else LabelFit.Box(center, Vec2(0.0, 0.32), Vec2(0.09, 0.0)),
        )
        val mpp = 0.02
        val candidates = listOf(
            wall(0, 4.0, Vec2(2.0, 0.14), true), // bottom
            wall(1, 3.0, Vec2(3.86, 1.5), false), // right
            wall(2, 4.0, Vec2(2.0, 2.86), true), // top
            wall(3, 0.6, Vec2(0.14, 1.5), false), // "left": only 30 pt long, text is 30 pt + margins
        )
        assertEquals(setOf(0, 1, 2), LabelFit.wallLabels(room4x3, candidates, null, mpp))
        // A room label covering the middle of the bottom wall's text hides that wall length only.
        val roomBox = LabelFit.Box(Vec2(2.0, 0.3), Vec2(0.5, 0.0), Vec2(0.0, 0.3))
        assertEquals(setOf(1, 2), LabelFit.wallLabels(room4x3, candidates, roomBox, mpp))
        // Text that would stick out of the room is dropped.
        val outside = listOf(wall(0, 4.0, Vec2(2.0, -0.05), true))
        assertEquals(emptySet(), LabelFit.wallLabels(room4x3, outside, null, mpp))
    }

    /** Boxes of every room-label and wall-length text op, estimated the way LabelFit does. */
    private fun textBoxes(list: DrawList, metersPerPt: Double): List<Pair<DrawOp.Text, LabelFit.Box>> =
        list.ops.filterIsInstance<DrawOp.Text>().filter { it.tag?.startsWith("dimension:") != true }.map { op ->
            val along = Vec2.fromAngle(op.angle)
            val up = along.perp()
            val width = LabelFit.textWidthPt(op.text, op.sizePt, op.bold)
            op to LabelFit.Box(
                op.anchor + up * (op.offsetPt.y * metersPerPt),
                along * (width / 2 * metersPerPt),
                up * (op.sizePt / 2 * metersPerPt),
            )
        }

    @Test
    fun theSampleHouseHasNoOverlappingLabelsAtAnyZoom() {
        val project = SampleProjects.demo(now = 0L)
        val rooms: List<Room> = project.floors.first().rooms
        val settings: PlanSettings = project.settings
        // From a thumbnail (4 pt/m) through a phone screen (about 12 pt/m) to a page (47 pt/m) and a close zoom.
        for (pointsPerMeter in listOf(4.0, 8.0, 12.0, 14.0, 20.0, 30.0, 47.0, 80.0, 160.0)) {
            val list = PlanRenderer.render(rooms, settings, RenderOptions(pointsPerMeter = pointsPerMeter))
            val boxes = textBoxes(list, 1.0 / pointsPerMeter)
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertFalse(
                    boxes[i].second.overlaps(boxes[j].second),
                    "at $pointsPerMeter pt/m \"${boxes[i].first.text}\" overlaps \"${boxes[j].first.text}\"",
                )
            }
        }
    }

    @Test
    fun zoomingInBringsEveryLabelBack() {
        val project = SampleProjects.demo(now = 0L)
        val rooms = project.floors.first().rooms
        fun texts(ppm: Double?) = PlanRenderer.render(rooms, project.settings, RenderOptions(pointsPerMeter = ppm))
            .ops.filterIsInstance<DrawOp.Text>().filter { it.tag?.startsWith("dimension:") != true }
        val phone = texts(12.0)
        val close = texts(160.0)
        assertTrue(phone.size < close.size, "a phone-size plan shows fewer labels (${phone.size}) than a close zoom (${close.size})")
        // Every room is still named on a phone-size plan, except rooms too small for any text.
        val names = phone.filter { it.bold }.map { it.text }
        val joined = names.joinToString(" ") // a wrapped name is two consecutive lines
        for (room in listOf("Living / Kitchen", "Hallway", "Bedroom 2", "Bedroom 3", "Bathroom", "Entry", "Primary Bedroom")) {
            assertTrue(room in joined, "$room is not named in full on a phone-size plan: $names")
        }
        assertTrue(names.none { it.endsWith("…") }, "no name is cut short on a phone-size plan: $names")
        // Wall lengths: every wall of at least 30 cm is labelled once there is room.
        val walls = rooms.sumOf { r -> r.corners.indices.count { i -> r.corners[i].distanceTo(r.corners[(i + 1) % r.corners.size]) >= 0.3 } }
        assertTrue(close.count { it.tag?.startsWith("wall:") == true } >= walls * 0.9, "most wall lengths are shown at a close zoom")
    }
}
