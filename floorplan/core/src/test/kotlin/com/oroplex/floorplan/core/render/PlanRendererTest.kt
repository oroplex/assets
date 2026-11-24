package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.measure.Measurements
import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.DoorSwing
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.core.render.Fixtures.opening
import com.oroplex.floorplan.core.render.Fixtures.rect
import com.oroplex.floorplan.core.render.Fixtures.room
import com.oroplex.floorplan.core.render.Fixtures.settings
import com.oroplex.floorplan.core.render.Fixtures.t
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanRendererTest {
    private val sample = SampleProjects.demo(1_700_000_000_000L)
    private val sampleRooms = sample.floors.first().rooms

    private fun DrawOp.isBand() = this is DrawOp.FillPolygon && color == PlanColors.WALL && tag == null
    private fun DrawOp.isRoomFill() = this is DrawOp.FillPolygon && tag?.startsWith("room:") == true
    private fun DrawOp.isGap() = this is DrawOp.FillPolygon && color == PlanColors.OPENING_GAP && tag == null

    @Test fun emptyInputGivesEmptyDrawList() {
        val dl = PlanRenderer.render(emptyList(), settings)
        assertTrue(dl.ops.isEmpty())
        assertEquals(Bounds.EMPTY, dl.bounds)
        assertTrue(dl.bounds.isEmpty)
    }

    @Test fun opsAreOrderedBandsFillsOpeningsLabels() {
        val dl = PlanRenderer.render(sampleRooms, sample.settings)
        val ops = dl.ops
        val bands = ops.indices.filter { ops[it].isBand() }
        val fills = ops.indices.filter { ops[it].isRoomFill() }
        val gaps = ops.indices.filter { ops[it].isGap() }
        val symbols = ops.indices.filter { ops[it] is DrawOp.Arc || ops[it] is DrawOp.Polyline }
        val texts = ops.indices.filter { ops[it] is DrawOp.Text }
        assertEquals(sampleRooms.size, bands.size)
        assertEquals(sampleRooms.size, fills.size)
        assertEquals(sampleRooms.sumOf { it.openings.size }, gaps.size)
        assertTrue(symbols.isNotEmpty() && texts.isNotEmpty())
        assertTrue(bands.max() < fills.min(), "all bands before all fills")
        assertTrue(fills.max() < gaps.min(), "all fills before openings")
        assertTrue(gaps.max() < symbols.min(), "all gaps before door/window symbols")
        assertTrue(symbols.max() < texts.min(), "labels on top of symbols")
        // Fills are exactly the interior outlines; bands are those outlines grown by the wall thickness.
        fills.forEachIndexed { k, i -> assertEquals(sampleRooms[k].corners, (ops[i] as DrawOp.FillPolygon).points) }
        bands.forEachIndexed { k, i ->
            assertEquals(Polygons.offset(sampleRooms[k].corners, sample.settings.wallThickness), (ops[i] as DrawOp.FillPolygon).points)
        }
        assertTrue(ops.none { it.colorOrNull() == PlanColors.WARNING }, "sample house has no broken rooms")
    }

    @Test fun roomFillsAndWallLabelsAreTagged() {
        val dl = PlanRenderer.render(sampleRooms, sample.settings)
        for (r in sampleRooms) {
            assertEquals(1, dl.ops.count { it is DrawOp.FillPolygon && it.tag == "room:${r.id}" })
            val expected = Polygons.edges(r.corners).withIndex().filter { it.value.length >= 0.3 }.map { "wall:${r.id}:${it.index}" }
            val actual = dl.opsOf<DrawOp.Text>().mapNotNull { it.tag }.filter { it.startsWith("wall:${r.id}:") }
            assertEquals(expected, actual)
        }
    }

    @Test fun boundsCoverWallBandsAndLabels() {
        val dl = PlanRenderer.render(listOf(room("a", rect(0.0, 0.0, 4.0, 3.0))), settings)
        assertNear(-t, dl.bounds.minX, 1e-9)
        assertNear(-t, dl.bounds.minY, 1e-9)
        assertNear(4 + t, dl.bounds.maxX, 1e-9)
        assertNear(3 + t, dl.bounds.maxY, 1e-9)
        val sampleBounds = PlanRenderer.render(sampleRooms, sample.settings).bounds
        for (r in sampleRooms) for (p in Polygons.offset(r.corners, sample.settings.wallThickness)) {
            assertTrue(sampleBounds.expanded(1e-9).contains(p))
        }
    }

    @Test fun roomLabelsSitInsideTheirRooms() {
        val dl = PlanRenderer.render(sampleRooms, sample.settings)
        val names = dl.opsOf<DrawOp.Text>().filter { it.bold && it.tag == null } // not the overall dimensions
        assertEquals(sampleRooms.size, names.size)
        for (r in sampleRooms) {
            val label = names.single { it.text == r.name }
            assertTrue(Polygons.contains(r.corners, label.anchor), "${r.name} label inside")
            assertTrue(Polygons.signedDistanceToOutline(r.corners, label.anchor) > 0.3, "${r.name} label away from walls")
        }
        // The L-shaped great room's label goes into its wide part, not the narrow arm or the notch.
        val living = sampleRooms.first()
        val anchor = names.single { it.text == living.name }.anchor
        assertTrue(Polygons.signedDistanceToOutline(living.corners, anchor) > 2.0)
    }

    @Test fun labelShowsNameDimensionsAndArea() {
        val r = room("a", rect(0.0, 0.0, 4.0, 3.0), name = "Den")
        val texts = PlanRenderer.render(listOf(r), settings, RenderOptions(showWallLengths = false)).opsOf<DrawOp.Text>()
        assertEquals(3, texts.size)
        val (name, dims, area) = texts
        assertEquals("Den", name.text)
        assertTrue(name.bold)
        assertNear(10.0, name.sizePt)
        val stats = Measurements.room(r)
        assertEquals("${Units.formatLength(stats.length, UnitSystem.IMPERIAL)} × ${Units.formatLength(stats.width, UnitSystem.IMPERIAL)}", dims.text)
        assertEquals(Units.formatArea(12.0, UnitSystem.IMPERIAL), area.text)
        assertEquals(PlanColors.TEXT_SECONDARY, dims.color)
        assertNear(8.0, dims.sizePt)
        // Stacked top to bottom, centered on one anchor at the room center.
        assertTrue(name.offsetPt.y > dims.offsetPt.y && dims.offsetPt.y > area.offsetPt.y)
        assertNear(0.0, name.offsetPt.y * 10 + dims.offsetPt.y * 8 + area.offsetPt.y * 8, 1e-9) // size-weighted center
        assertTrue(texts.all { it.anchor.approxEquals(Vec2(2.0, 1.5), 1e-6) && it.angle == 0.0 })
        // Option and settings switches.
        val noArea = PlanRenderer.render(listOf(r), settings.copy(showRoomAreas = false), RenderOptions(showWallLengths = false))
        assertEquals(2, noArea.opsOf<DrawOp.Text>().size)
        val forcedArea = PlanRenderer.render(
            listOf(r), settings.copy(showRoomAreas = false), RenderOptions(showAreas = true, showRoomNames = false, showRoomDimensions = false, showWallLengths = false),
        )
        assertEquals(listOf(area.text), forcedArea.opsOf<DrawOp.Text>().map { it.text })
        val scaled = PlanRenderer.render(listOf(r), settings, RenderOptions(textScale = 0.8, showWallLengths = false))
        assertNear(8.0, scaled.opsOf<DrawOp.Text>().first().sizePt)
    }

    @Test fun smallRoomsShrinkTextAndTinyClosetsDropDimensions() {
        val closet = room("c", rect(0.0, 0.0, 0.7, 0.7), name = "Closet")
        val texts = PlanRenderer.render(listOf(closet), settings, RenderOptions(showWallLengths = false)).opsOf<DrawOp.Text>()
        assertEquals(2, texts.size, "name + area, no L × W line")
        assertEquals("Closet", texts[0].text)
        assertTrue(texts[0].sizePt < 10.0)
        assertFalse(texts.any { '×' in it.text })
        val small = room("s", rect(0.0, 0.0, 1.0, 1.6), name = "Pantry")
        val smallTexts = PlanRenderer.render(listOf(small), settings, RenderOptions(showWallLengths = false)).opsOf<DrawOp.Text>()
        assertEquals(3, smallTexts.size)
        assertTrue(smallTexts[0].sizePt < 10.0 && smallTexts[0].sizePt > texts[0].sizePt)
    }

    @Test fun longNarrowRoomsAreLabelledAlongTheirLongAxis() {
        val hall = room("h", rect(0.0, 0.0, 1.0, 5.0), name = "Hall")
        val texts = PlanRenderer.render(listOf(hall), settings, RenderOptions(showWallLengths = false)).opsOf<DrawOp.Text>()
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.all { abs(abs(it.angle) - PI / 2) < 1e-6 && it.angle > -PI / 2 && it.angle <= PI / 2 })
        val wide = room("w", rect(0.0, 0.0, 3.0, 5.0))
        assertTrue(PlanRenderer.render(listOf(wide), settings).opsOf<DrawOp.Text>().filter { it.tag == null }.all { it.angle == 0.0 })
        // A small closet that is clearly longer than wide also reads along its length.
        val closet = room("c", rect(0.0, 0.0, 0.75, 1.1))
        assertTrue(PlanRenderer.render(listOf(closet), settings).opsOf<DrawOp.Text>().filter { it.tag == null }.all { abs(it.angle - PI / 2) < 1e-6 })
        val squareCloset = room("q", rect(0.0, 0.0, 0.9, 1.0))
        assertTrue(PlanRenderer.render(listOf(squareCloset), settings).opsOf<DrawOp.Text>().filter { it.tag == null }.all { it.angle == 0.0 })
    }

    @Test fun wallLengthLabelsAreUprightAndInsideTheRoom() {
        // Rectangle with a 0.2 m jog in the top wall: that short edge gets no label.
        val corners = listOf(
            Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(2.0, 3.0), Vec2(2.0, 3.2), Vec2(0.0, 3.2),
        )
        val r = room("j", corners)
        val labels = PlanRenderer.render(listOf(r), settings).opsOf<DrawOp.Text>()
            .mapNotNull { op -> op.tag?.takeIf { it.startsWith("wall:j:") }?.let { it.substringAfterLast(':').toInt() to op } }
        assertEquals(listOf(0, 1, 2, 4, 5), labels.map { it.first })
        for ((i, label) in labels) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            assertNear(Vec2.lerp(a, b, 0.5), label.anchor)
            assertTrue(label.angle > -PI / 2 && label.angle <= PI / 2, "upright: ${label.angle}")
            assertNear(0.0, abs(angleDiff(label.angle, (b - a).angle())) % PI, 1e-9) // along the edge
            assertEquals(Units.formatLength(a.distanceTo(b), UnitSystem.IMPERIAL), label.text)
            assertNear(7.0, label.sizePt)
            assertNear(7.0, abs(label.offsetPt.y))
            assertEquals(PlanColors.DIMENSION, label.color)
            // The offset moves the label toward the room interior.
            val up = Vec2.fromAngle(label.angle + PI / 2) * label.offsetPt.y
            val inward = (b - a).normalized().perp()
            assertTrue((up dot inward) > 0, "label $i offset points into the room")
        }
        val off = PlanRenderer.render(listOf(r), settings.copy(showWallLengths = false)).opsOf<DrawOp.Text>()
        assertTrue(off.none { it.tag != null })
        val forced = PlanRenderer.render(listOf(r), settings.copy(showWallLengths = false), RenderOptions(showWallLengths = true))
        assertEquals(5, forced.opsOf<DrawOp.Text>().count { it.tag != null })
    }

    @Test fun doorGeometryForAllFourSwings() {
        val offset = 1.0
        val w = 0.9
        val s = Vec2(offset, 0.0)
        val e = Vec2(offset + w, 0.0)
        data class Expect(val swing: DoorSwing, val hinge: Vec2, val openDir: Vec2, val closedEnd: Vec2)
        val cases = listOf(
            Expect(DoorSwing.LEFT_IN, s, Vec2(0.0, 1.0), e),
            Expect(DoorSwing.RIGHT_IN, e, Vec2(0.0, 1.0), s),
            Expect(DoorSwing.LEFT_OUT, s + Vec2(0.0, -t), Vec2(0.0, -1.0), e + Vec2(0.0, -t)),
            Expect(DoorSwing.RIGHT_OUT, e + Vec2(0.0, -t), Vec2(0.0, -1.0), s + Vec2(0.0, -t)),
        )
        for (c in cases) {
            val r = room("d", rect(0.0, 0.0, 4.0, 3.0), listOf(opening(OpeningType.DOOR, 0, offset, w, c.swing)))
            val dl = PlanRenderer.render(listOf(r), settings)
            val arc = dl.opsOf<DrawOp.Arc>().single()
            val leaf = dl.opsOf<DrawOp.Polyline>().single()
            assertNear(c.hinge, leaf.points[0], 1e-9, "${c.swing} hinge")
            assertNear(c.hinge + c.openDir * w, leaf.points[1], 1e-9, "${c.swing} leaf open end")
            assertNear(1.5, leaf.widthPt)
            assertEquals(PlanColors.DOOR, leaf.color)
            assertNear(c.hinge, arc.center, 1e-9, "${c.swing} arc center")
            assertNear(w, arc.radius, 1e-9)
            assertNear(PI / 2, abs(arc.sweepAngle), 1e-12)
            assertNear(0.75, arc.widthPt)
            assertNear(c.closedEnd, arc.center + Vec2.fromAngle(arc.startAngle, arc.radius), 1e-9, "${c.swing} arc starts closed")
            assertNear(leaf.points[1], arc.center + Vec2.fromAngle(arc.startAngle + arc.sweepAngle, arc.radius), 1e-9, "${c.swing} arc ends open")
            // Out-swinging doors extend the bounds beyond the wall band.
            if (c.swing.name.endsWith("OUT")) assertTrue(dl.bounds.minY <= -t - w + 1e-9)
        }
    }

    @Test fun doorOnAnAngledWallSwingsIntoTheRoom() {
        // Triangle; edge 1 runs from (4,0) to (0,3) diagonally.
        val tri = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(0.0, 3.0))
        val r = room("tri", tri, listOf(opening(OpeningType.DOOR, 1, 2.0, 0.8, DoorSwing.LEFT_IN)))
        val dl = PlanRenderer.render(listOf(r), settings)
        val leaf = dl.opsOf<DrawOp.Polyline>().single()
        val dir = (tri[2] - tri[1]).normalized()
        assertNear(tri[1] + dir * 2.0, leaf.points[0])
        assertNear(tri[1] + dir * 2.0 + dir.perp() * 0.8, leaf.points[1])
        assertTrue(Polygons.contains(tri, leaf.points[1]))
    }

    @Test fun windowGetsGapAndFrameLines() {
        val r = room("w", rect(0.0, 0.0, 4.0, 3.0), listOf(opening(OpeningType.WINDOW, 0, 1.0, 1.2)))
        val dl = PlanRenderer.render(listOf(r), settings)
        val gap = dl.ops.filter { it.isGap() }.single() as DrawOp.FillPolygon
        val gb = Bounds.of(gap.points)
        assertNear(1.0, gb.minX)
        assertNear(2.2, gb.maxX)
        assertNear(-(2 * t + 0.02), gb.minY, 1e-12, "exterior cut depth")
        assertTrue(gb.maxY >= 0.0 && gb.maxY <= 0.002, "cut starts at the interior face")
        val lines = dl.opsOf<DrawOp.Polyline>()
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.color == PlanColors.WINDOW && it.widthPt == 0.75 })
        val frame = lines.single { it.closed }
        assertEquals(4, frame.points.size)
        val fb = Bounds.of(frame.points)
        assertNear(1.0, fb.minX)
        assertNear(2.2, fb.maxX)
        assertNear(-t, fb.minY)
        assertNear(0.0, fb.maxY)
        val center = lines.single { !it.closed }
        assertNear(Vec2(1.0, -t / 2), center.points[0])
        assertNear(Vec2(2.2, -t / 2), center.points[1])
        assertTrue(dl.opsOf<DrawOp.Arc>().isEmpty())
    }

    @Test fun plainOpeningIsOnlyAGap() {
        val r = room("o", rect(0.0, 0.0, 4.0, 3.0), listOf(opening(OpeningType.OPENING, 1, 0.5, 1.0)))
        val dl = PlanRenderer.render(listOf(r), settings, RenderOptions(showWallLengths = false))
        assertEquals(1, dl.ops.count { it.isGap() })
        assertTrue(dl.opsOf<DrawOp.Arc>().isEmpty() && dl.opsOf<DrawOp.Polyline>().isEmpty())
        val gb = Bounds.of((dl.ops.single { it.isGap() } as DrawOp.FillPolygon).points)
        assertNear(0.5, gb.minY)
        assertNear(1.5, gb.maxY)
        assertNear(4 + 2 * t + 0.02, gb.maxX, 1e-12)
    }

    @Test fun interiorDoorCutsThroughTheSharedWallOnly() {
        val a = room("a", rect(0.0, 0.0, 3.0, 3.0), listOf(opening(OpeningType.DOOR, 1, 1.0, 0.8, DoorSwing.LEFT_OUT)))
        val b = room("b", rect(3.0 + t, 0.0, 6.0, 3.0))
        val dl = PlanRenderer.render(listOf(a, b), settings, RenderOptions(selectedRoomId = "b"))
        val gap = dl.ops.single { it.isGap() } as DrawOp.FillPolygon
        val gb = Bounds.of(gap.points)
        // Clears the whole merged wall and stops just inside the neighbour (no notch into its blue fill).
        assertNear(3.0 + t + 0.001, gb.maxX, 1e-9)
        assertTrue(gb.minX < 3.0)
        // The out-swinging door hinges on the neighbour's face and opens into it.
        val leaf = dl.opsOf<DrawOp.Polyline>().first { it.color == PlanColors.DOOR }
        assertNear(Vec2(3.0 + t, 1.0), leaf.points[0])
        assertNear(Vec2(3.0 + t + 0.8, 1.0), leaf.points[1])
    }

    @Test fun doorMarkedFromBothRoomsIsDrawnOnce() {
        val a = room("a", rect(0.0, 0.0, 3.0, 3.0), listOf(opening(OpeningType.DOOR, 1, 1.0, 0.8, DoorSwing.LEFT_OUT)))
        // Room b's edge 3 runs (3+t,3) -> (3+t,0); the same door seen from b is at offset 3 - 1.8 = 1.2.
        val b = room("b", rect(3.0 + t, 0.0, 6.0, 3.0), listOf(opening(OpeningType.DOOR, 3, 1.22, 0.8, DoorSwing.RIGHT_IN)))
        val dl = PlanRenderer.render(listOf(a, b), settings)
        assertEquals(2, dl.ops.count { it.isGap() })
        assertEquals(1, dl.opsOf<DrawOp.Arc>().size)
        // A second, distinct door on the same wall is still drawn.
        val c = b.copy(openings = b.openings + opening(OpeningType.DOOR, 3, 0.1, 0.8))
        assertEquals(2, PlanRenderer.render(listOf(a, c), settings).opsOf<DrawOp.Arc>().size)
    }

    @Test fun degenerateRoomsDoNotThrowAndAreFlagged() {
        val nan = Double.NaN
        val rooms = listOf(
            room("empty", emptyList()),
            room("one", listOf(Vec2(1.0, 1.0))),
            room("two", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0))),
            room("bowtie", listOf(Vec2(0.0, 0.0), Vec2(2.0, 2.0), Vec2(2.0, 0.0), Vec2(0.0, 2.0))),
            room("flat", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0))),
            room("nan", listOf(Vec2(0.0, 0.0), Vec2(nan, 0.0), Vec2(2.0, 2.0), Vec2(0.0, 2.0))),
            room("dup", listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.0), Vec2(2.0, 2.0), Vec2(0.0, 2.0))),
            room(
                "badOpenings", rect(10.0, 0.0, 14.0, 3.0),
                listOf(
                    opening(OpeningType.DOOR, 7, 0.0, 0.9),
                    opening(OpeningType.DOOR, -1, 0.0, 0.9),
                    opening(OpeningType.WINDOW, 0, nan, nan),
                    opening(OpeningType.DOOR, 0, -5.0, 100.0),
                    opening(OpeningType.OPENING, 2, 3.9, 0.0),
                ),
            ),
        )
        val weird = settings.copy(wallThickness = nan, imperialDenominator = 0)
        val dl = PlanRenderer.render(
            rooms, weird,
            RenderOptions(selectedRoomId = "bowtie", showCornerHandles = true, selectedWall = 99, textScale = nan),
        )
        // Only the valid room gets a band and a fill.
        assertEquals(1, dl.ops.count { it.isBand() })
        assertEquals(listOf("room:badOpenings"), dl.ops.filter { it.isRoomFill() }.map { it.tag })
        val warnings = dl.ops.filter { it.colorOrNull() == PlanColors.WARNING }
        assertEquals(setOf("room:one", "room:two", "room:bowtie", "room:flat", "room:nan", "room:dup"), warnings.map { it.tag }.toSet())
        assertTrue(warnings.filterIsInstance<DrawOp.Polyline>().all { it.points.all(Vec2::isFinite) })
        // The broken selected room can still be repaired: handles at its corners.
        assertEquals(4, dl.opsOf<DrawOp.Dot>().count { it.tag?.startsWith("corner:bowtie:") == true })
        // The clamped door (offset -5, width 100) fills the whole edge; bad indices are skipped.
        assertEquals(1, dl.opsOf<DrawOp.Arc>().size)
        assertTrue(dl.ops.all { op -> op.points().all { it.isFinite() } })
        assertFalse(dl.bounds.isEmpty)
        // A clockwise outline is still drawn, with the band outside and the labels inside.
        val cw = room("cw", rect(0.0, 0.0, 4.0, 3.0).reversed())
        val cwDl = PlanRenderer.render(listOf(cw), settings)
        assertNear(-t, cwDl.bounds.minX)
        for (label in cwDl.opsOf<DrawOp.Text>().filter { it.tag != null }) {
            val up = Vec2.fromAngle(label.angle + PI / 2) * label.offsetPt.y
            val p = label.anchor + up.normalized() * 0.1
            assertTrue(Polygons.contains(cw.corners, p))
        }
        // Rooms with nothing drawable at all still yield a valid, empty list.
        val nothing = PlanRenderer.render(listOf(room("x", emptyList())), settings)
        assertTrue(nothing.ops.isEmpty() && nothing.bounds.isEmpty)
    }

    @Test fun selectionHighlightAndHandles() {
        val a = room("a", rect(0.0, 0.0, 4.0, 3.0))
        val b = room("b", rect(4.0 + t, 0.0, 7.0, 3.0))
        val dl = PlanRenderer.render(listOf(a, b), settings, RenderOptions(selectedRoomId = "b", selectedWall = 1, showCornerHandles = true))
        val fills = dl.ops.filter { it.isRoomFill() }.map { it as DrawOp.FillPolygon }
        assertEquals(PlanColors.ROOM_FILL, fills.single { it.tag == "room:a" }.color)
        assertEquals(PlanColors.ROOM_FILL_SELECTED, fills.single { it.tag == "room:b" }.color)
        val selection = dl.opsOf<DrawOp.Polyline>().filter { it.color == PlanColors.SELECTION }
        val outline = selection.single { it.closed }
        assertEquals(b.corners, outline.points)
        assertNear(2.0, outline.widthPt)
        val wall = selection.single { !it.closed }
        assertEquals(listOf(b.corners[1], b.corners[2]), wall.points)
        assertNear(3.0, wall.widthPt)
        val handles = dl.opsOf<DrawOp.Dot>()
        assertEquals((0..3).map { "corner:b:$it" }, handles.map { it.tag })
        assertTrue(handles.all { it.radiusPt == 7.0 && it.fillColor == PlanColors.HANDLE_FILL && it.strokeColor == PlanColors.SELECTION })
        assertEquals(b.corners, handles.map { it.center })
        // Handles are drawn last; the selection outline sits under the labels.
        assertTrue(dl.ops.takeLast(4).all { it is DrawOp.Dot })
        assertTrue(dl.ops.indexOf(outline) < dl.ops.indexOfFirst { it is DrawOp.Text })
        // Label halos follow the room fill so text blends into a highlighted room.
        val bLabel = dl.opsOf<DrawOp.Text>().first { it.text == "Room b" }
        assertEquals(PlanColors.ROOM_FILL_SELECTED, bLabel.haloColor)
        // No handles without the option; no highlight without a selection.
        val plain = PlanRenderer.render(listOf(a, b), settings, RenderOptions(selectedRoomId = "b"))
        assertTrue(plain.opsOf<DrawOp.Dot>().isEmpty())
        val none = PlanRenderer.render(listOf(a, b), settings, RenderOptions(selectedWall = 1, showCornerHandles = true))
        assertTrue(none.ops.none { it.colorOrNull() == PlanColors.SELECTION })
    }

    @Test fun sharedWallBandsOverlapIntoOneWall() {
        val a = room("a", rect(0.0, 0.0, 3.0, 3.0))
        val b = room("b", rect(3.0 + t, 0.0, 6.0, 3.0))
        val dl = PlanRenderer.render(listOf(a, b), settings)
        val bands = dl.ops.filter { it.isBand() }.map { Bounds.of((it as DrawOp.FillPolygon).points) }
        // Each band spans the full gap between the two interiors.
        assertNear(3.0 + t, bands[0].maxX)
        assertNear(3.0, bands[1].minX)
        assertNotNull(dl.ops.firstOrNull { it.isRoomFill() })
    }

    private fun DrawOp.colorOrNull(): Int? = when (this) {
        is DrawOp.FillPolygon -> color
        is DrawOp.Polyline -> color
        is DrawOp.Arc -> color
        is DrawOp.Dot -> fillColor
        is DrawOp.Text -> color
    }

    private fun DrawOp.points(): List<Vec2> = when (this) {
        is DrawOp.FillPolygon -> points
        is DrawOp.Polyline -> points
        is DrawOp.Arc -> listOf(center)
        is DrawOp.Dot -> listOf(center)
        is DrawOp.Text -> listOf(anchor)
    }
}
