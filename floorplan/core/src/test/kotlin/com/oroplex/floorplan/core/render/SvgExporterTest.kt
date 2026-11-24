package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.render.Fixtures.rect
import com.oroplex.floorplan.core.render.Fixtures.room
import com.oroplex.floorplan.core.render.Fixtures.settings
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgExporterTest {
    private val sample = SampleProjects.demo(1_700_000_000_000L)

    private fun parse(svg: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // No DTDs / external entities in our output, and none should be fetched while testing.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))
    }

    private fun Document.elements(name: String): List<Element> {
        val list = getElementsByTagNameNS("http://www.w3.org/2000/svg", name)
        return (0 until list.length).map { list.item(it) as Element }
    }

    @Test fun sampleIsWellFormedWithExpectedElementCounts() {
        val dl = PlanRenderer.render(sample.floors.first().rooms, sample.settings)
        val svg = SvgExporter.export(dl, 792.0, 612.0, 36.0, title = "Sample House — Main Floor")
        val doc = parse(svg)
        val root = doc.documentElement
        assertEquals("svg", root.localName)
        assertEquals("0 0 792 612", root.getAttribute("viewBox"))
        assertEquals("792pt", root.getAttribute("width"))
        assertEquals("612pt", root.getAttribute("height"))
        assertEquals("Sample House — Main Floor", doc.elements("title").single().textContent)
        // White background, then one element per op.
        val rects = doc.elements("rect")
        assertEquals(1, rects.size)
        assertEquals("#FFFFFF", rects[0].getAttribute("fill"))
        assertEquals(dl.opsOf<DrawOp.FillPolygon>().size, doc.elements("polygon").size)
        assertEquals(dl.opsOf<DrawOp.Polyline>().count { !it.closed }, doc.elements("polyline").size)
        assertEquals(dl.opsOf<DrawOp.Polyline>().count { it.closed } + dl.opsOf<DrawOp.Arc>().size, doc.elements("path").size)
        assertEquals(dl.opsOf<DrawOp.Dot>().size, doc.elements("circle").size)
        assertEquals(dl.opsOf<DrawOp.Text>().size + 1, doc.elements("text").size) // + title
        // Everything lands on the page; the walls (what the plan is fitted to) sit inside the margins,
        // below the title band.
        for (poly in doc.elements("polygon")) {
            val isWall = poly.getAttribute("fill") == "#262626"
            for (pair in poly.getAttribute("points").split(' ')) {
                val (x, y) = pair.split(',').map { it.toDouble() }
                assertTrue(x in 0.0..792.0 && y in 0.0..612.0, "($x, $y) on page")
                if (isWall) assertTrue(y >= 28.0 + 36.0 - 0.01 && y <= 612.0 - 36.0 + 0.01, "wall below title band: $y")
            }
        }
        val texts = doc.elements("text")
        assertTrue(texts.all { it.getAttribute("dominant-baseline") == "central" })
        assertTrue(texts.filter { it.hasAttribute("stroke") }.all { it.getAttribute("paint-order") == "stroke" })
        assertTrue(doc.elements("path").all { it.getAttribute("stroke-linejoin") == "round" && it.getAttribute("fill") == "none" })
    }

    @Test fun textIsEscapedAndInvalidCharactersDropped() {
        val name = "Tom & Jerry's <Den> \"quoted\"\u0001\uD800 🏠"
        val dl = PlanRenderer.render(listOf(room("a", rect(0.0, 0.0, 4.0, 3.0), name = name)), settings)
        val svg = SvgExporter.export(dl, 600.0, 400.0, title = "A&B <plan>")
        assertFalse(svg.contains("<Den>"))
        assertTrue(svg.contains("&amp;") && svg.contains("&lt;Den&gt;"))
        val doc = parse(svg)
        assertEquals("A&B <plan>", doc.elements("title").single().textContent)
        val texts = doc.elements("text").map { it.textContent }
        assertTrue("Tom & Jerry's <Den> \"quoted\" 🏠" in texts, "escaped name round-trips: $texts")
    }

    @Test fun arcsConvertToSvgArcCommandsInYDownSpace() {
        fun arcPath(start: Double, sweep: Double): String {
            val dl = DrawList(
                listOf(DrawOp.Arc(Vec2.ZERO, 1.0, start, sweep, 1.0, PlanColors.DOOR)),
                Bounds(-1.0, -1.0, 1.0, 1.0),
            )
            val doc = parse(SvgExporter.export(dl, 200.0, 200.0, 0.0))
            return doc.elements("path").single().getAttribute("d")
        }
        // 200 pt page, 2 m bounds, no margin -> 100 pt per meter, plan (0,0) at page (100,100).
        // CCW quarter from +x to +y: page (200,100) -> (100,0); counter-clockwise on the page => sweep-flag 0.
        assertEquals("M200 100 A100 100 0 0 0 100 0", arcPath(0.0, PI / 2))
        // Clockwise quarter from +x to -y: page (200,100) -> (100,200), sweep-flag 1.
        assertEquals("M200 100 A100 100 0 0 1 100 200", arcPath(0.0, -PI / 2))
        // 270 degrees needs the large-arc flag.
        assertEquals("M200 100 A100 100 0 1 0 100 200", arcPath(0.0, 3 * PI / 2))
        // A full circle is two half arcs (coincident end points would draw nothing).
        val full = arcPath(0.0, 2 * PI)
        assertEquals(2, full.split('A').size - 1)
        assertTrue(full.startsWith("M200 100 A100 100 0 0 0 0 100"))
    }

    @Test fun colorsDashesDotsAndRotatedText() {
        val dl = DrawList(
            listOf(
                DrawOp.FillPolygon(listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0)), 0x80FF0000.toInt()),
                DrawOp.Polyline(listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0)), false, 1.5, 0xFF00FF00.toInt(), dashPt = 4.0),
                DrawOp.Dot(Vec2(0.5, 0.5), 7.0, PlanColors.HANDLE_FILL, PlanColors.SELECTION),
                DrawOp.Text(Vec2(0.5, 0.5), "Up", 7.0, PlanColors.DIMENSION, angle = PI / 2, offsetPt = Vec2(0.0, 7.0), align = TextAlign.START),
                DrawOp.Text(Vec2(0.5, 0.5), "Two\nlines", 8.0, PlanColors.TEXT, bold = true),
                // Unusable ops are skipped instead of producing NaN attributes.
                DrawOp.FillPolygon(listOf(Vec2(Double.NaN, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0)), PlanColors.WALL),
                DrawOp.Arc(Vec2.ZERO, -1.0, 0.0, 1.0, 1.0, PlanColors.DOOR),
                DrawOp.Text(Vec2(0.0, 0.0), "   ", 7.0, PlanColors.TEXT),
            ),
            Bounds(0.0, 0.0, 1.0, 1.0),
        )
        val svg = SvgExporter.export(dl, 300.0, 300.0)
        assertFalse(svg.contains("NaN"))
        val doc = parse(svg)
        val polygon = doc.elements("polygon").single()
        assertEquals("#FF0000", polygon.getAttribute("fill"))
        assertEquals("0.502", polygon.getAttribute("fill-opacity"))
        val line = doc.elements("polyline").single()
        assertEquals("#00FF00", line.getAttribute("stroke"))
        assertEquals("4 4", line.getAttribute("stroke-dasharray"))
        assertEquals("1.5", line.getAttribute("stroke-width"))
        assertFalse(line.hasAttribute("stroke-opacity"))
        val dot = doc.elements("circle").single()
        assertEquals("7", dot.getAttribute("r"))
        assertEquals("#1A73E8", dot.getAttribute("stroke"))
        val texts = doc.elements("text")
        assertEquals(2, texts.size)
        val up = texts[0]
        assertTrue(up.getAttribute("transform").endsWith("rotate(-90)"), up.getAttribute("transform"))
        assertEquals("start", up.getAttribute("text-anchor"))
        assertEquals("-7", up.getAttribute("y")) // +7 pt toward the text's up = -7 in the y-down frame
        val two = texts[1]
        assertEquals("bold", two.getAttribute("font-weight"))
        assertEquals("middle", two.getAttribute("text-anchor"))
        assertEquals(2, two.getElementsByTagNameNS("http://www.w3.org/2000/svg", "tspan").length)
        assertEquals("Floor plan", doc.elements("title").single().textContent)
    }

    @Test fun emptyAndInvalidInputStillProduceAValidDocument() {
        val empty = parse(SvgExporter.export(DrawList(emptyList(), Bounds.EMPTY), 612.0, 792.0))
        assertEquals(1, empty.elements("rect").size)
        val weird = SvgExporter.export(DrawList(emptyList(), Bounds.EMPTY), Double.NaN, -5.0, Double.POSITIVE_INFINITY, "  ")
        val doc = parse(weird)
        assertEquals("0 0 612 792", doc.documentElement.getAttribute("viewBox"))
        assertEquals("Floor plan", doc.elements("title").single().textContent)
        assertEquals(0, doc.elements("text").size) // blank title is not drawn
    }

    @Test fun numbersAreLocaleIndependent() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.25", SvgExporter.n(1.25))
            assertEquals("0", SvgExporter.n(-0.0001))
            assertEquals("-3.5", SvgExporter.n(-3.5))
            val svg = SvgExporter.export(PlanRenderer.render(listOf(room("a", rect(0.0, 0.0, 4.3, 3.1))), settings), 612.0, 792.0)
            parse(svg)
            assertFalse(Regex("\\d,\\d+,").containsMatchIn(svg), "no decimal commas")
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
