package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DollhouseTest {
    private val t = 0.1
    private val settings = PlanSettings(wallThickness = t)

    private fun room(id: String, corners: List<Vec2>, height: Double? = 2.5, openings: List<Opening> = emptyList()) =
        Room(id = id, name = id, corners = corners, ceilingHeight = height, openings = openings)

    private fun rect(x: Double, y: Double, w: Double, h: Double) =
        listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))

    private fun door(wall: Int, offset: Double, width: Double = 0.8, height: Double = 2.0, type: OpeningType = OpeningType.DOOR) =
        Opening(id = "o$wall-$offset", type = type, wallIndex = wall, offset = offset, width = width, height = height)

    private fun window(wall: Int, offset: Double, width: Double = 1.0, height: Double = 1.2, sill: Double = 0.9) =
        Opening(id = "w$wall-$offset", type = OpeningType.WINDOW, wallIndex = wall, offset = offset, width = width, height = height, sillHeight = sill)

    private fun List<Face3>.of(kind: FaceKind) = filter { it.kind == kind }
    private fun List<Face3>.ofRoom(id: String) = filter { it.roomId == id }

    private fun normal(f: Face3): Vec3 = FaceMath.newell(f.points).normalized()
    private fun area(f: Face3): Double = FaceMath.newell(f.points).length / 2
    private fun centroid(f: Face3): Vec3 = f.points.fold(Vec3.ZERO) { a, p -> a + p } / f.points.size.toDouble()
    private fun minY(f: Face3) = f.points.minOf { it.y }
    private fun maxY(f: Face3) = f.points.maxOf { it.y }

    /** True when [p] lies in the plane of [f] and inside its outline (faces here are convex). */
    private fun covers(f: Face3, p: Vec3): Boolean {
        val n = normal(f)
        if (abs((p - f.points[0]) dot n) > 1e-6) return false
        return f.points.indices.all { i ->
            val a = f.points[i]
            val b = f.points[(i + 1) % f.points.size]
            ((b - a) cross (p - a)) dot n >= -1e-9
        }
    }

    private fun assertWellFormed(faces: List<Face3>) {
        for (f in faces) {
            assertTrue(f.points.size >= 3, "face with ${f.points.size} points")
            assertTrue(f.points.all { it.isFinite() }, "non-finite point in $f")
            assertTrue(area(f) > 1e-8, "degenerate face $f")
            val n = normal(f)
            val d = f.points.maxOf { abs((it - f.points[0]) dot n) }
            assertTrue(d < 1e-9, "non-planar face (off by $d): $f")
            if (f.kind == FaceKind.WALL_INNER || f.kind == FaceKind.WALL_OUTER) assertTrue(abs(n.y) < 1e-9, "wall not vertical: $f")
            if (f.kind == FaceKind.FLOOR || f.kind == FaceKind.WALL_TOP) assertTrue(n.y > 1 - 1e-9, "${f.kind} not facing up: $f")
        }
    }

    // ---- Basics ----------------------------------------------------------------------------------------------

    @Test fun emptyInputGivesNoFaces() {
        assertTrue(Dollhouse.build(emptyList(), settings).isEmpty())
    }

    @Test fun rectangleHasFloorAndThreeFacesPerWall() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0))), settings)
        assertWellFormed(faces)
        assertEquals(1, faces.of(FaceKind.FLOOR).size)
        assertEquals(4, faces.of(FaceKind.WALL_INNER).size)
        assertEquals(4, faces.of(FaceKind.WALL_OUTER).size)
        assertEquals(4, faces.of(FaceKind.WALL_TOP).size)
        assertEquals(0, faces.of(FaceKind.OPENING_FRAME).size)
        assertEquals(13, faces.size)
        assertTrue(faces.all { it.roomId == "r" })
        assertEquals(FaceKind.FLOOR, faces.first().kind)
    }

    @Test fun floorIsTheOutlineAtHeightZeroFacingUp() {
        val corners = rect(1.0, 2.0, 4.0, 3.0)
        val floor = Dollhouse.build(listOf(room("r", corners)), settings).of(FaceKind.FLOOR).single()
        assertEquals(corners.map { Vec3.fromPlan(it, 0.0) }.toSet(), floor.points.toSet())
        assertTrue(normal(floor).y > 0.999)
        assertEquals(12.0, area(floor), 1e-9)
    }

    @Test fun wallsRiseToTheCeilingHeight() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), height = 2.7)), settings)
        for (f in faces.of(FaceKind.WALL_INNER) + faces.of(FaceKind.WALL_OUTER)) {
            assertEquals(0.0, minY(f), 1e-12)
            assertEquals(2.7, maxY(f), 1e-12)
        }
        for (f in faces.of(FaceKind.WALL_TOP)) f.points.forEach { assertEquals(2.7, it.y, 1e-12) }
        // The inner faces cover exactly perimeter × height.
        assertEquals(14.0 * 2.7, faces.of(FaceKind.WALL_INNER).sumOf { area(it) }, 1e-9)
    }

    @Test fun unknownOrUnusableCeilingUsesTheDefault() {
        for (h in listOf(null, Double.NaN, -1.0, 0.0, 0.2, 50.0, Double.POSITIVE_INFINITY)) {
            val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 3.0, 3.0), height = h)), settings, defaultCeiling = 2.6)
            assertEquals(2.6, faces.of(FaceKind.WALL_TOP).first().points.first().y, 1e-12, "ceiling $h")
        }
        // The default parameter falls back to the settings' default, and an unusable default to the built-in one.
        val viaSettings = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 3.0, 3.0), height = null)), settings.copy(defaultCeilingHeight = 3.0))
        assertEquals(3.0, viaSettings.of(FaceKind.WALL_TOP).first().points.first().y, 1e-12)
        val viaBuiltIn = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 3.0, 3.0), height = null)), settings, defaultCeiling = Double.NaN)
        assertEquals(settings.defaultCeilingHeight, viaBuiltIn.of(FaceKind.WALL_TOP).first().points.first().y, 1e-12)
    }

    @Test fun facesPointTheRightWay() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0))), settings)
        val center = Vec3.fromPlan(Vec2(2.0, 1.5), 1.25)
        for (f in faces.of(FaceKind.WALL_INNER)) assertTrue((normal(f) dot (center - centroid(f))) > 0, "inner face should face the room")
        for (f in faces.of(FaceKind.WALL_OUTER)) assertTrue((normal(f) dot (center - centroid(f))) < 0, "outer face should face away")
    }

    @Test fun outerFacesAreOneWallThicknessOutAndMitered() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0))), settings)
        val outer = faces.of(FaceKind.WALL_OUTER)
        // Mitered outer faces of a 4 × 3 room are (4 + 2t) and (3 + 2t) long.
        val lengths = outer.map { area(it) / 2.5 }.sorted()
        listOf(3.0 + 2 * t, 3.0 + 2 * t, 4.0 + 2 * t, 4.0 + 2 * t).zip(lengths).forEach { (want, got) -> assertEquals(want, got, 1e-9) }
        val xs = outer.flatMap { f -> f.points.map { it.x } }
        assertEquals(-t, xs.min(), 1e-12)
        assertEquals(4.0 + t, xs.max(), 1e-12)
        // Top caps are trapezoids of width t: total area = outer band area.
        val band = (4.0 + 2 * t) * (3.0 + 2 * t) - 12.0
        assertEquals(band, faces.of(FaceKind.WALL_TOP).sumOf { area(it) }, 1e-9)
    }

    @Test fun clockwiseOutlineStillFacesCorrectly() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0).reversed())), settings)
        assertWellFormed(faces)
        assertEquals(13, faces.size)
        val center = Vec3.fromPlan(Vec2(2.0, 1.5), 1.25)
        for (f in faces.of(FaceKind.WALL_INNER)) assertTrue((normal(f) dot (center - centroid(f))) > 0)
        val xs = faces.of(FaceKind.WALL_OUTER).flatMap { f -> f.points.map { it.x } }
        assertEquals(-t, xs.min(), 1e-12) // outer faces still outside the room
    }

    @Test fun lShapedRoomMitersItsInsideCorner() {
        val l = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0), Vec2(2.0, 2.0), Vec2(2.0, 4.0), Vec2(0.0, 4.0))
        val faces = Dollhouse.build(listOf(room("l", l)), settings)
        assertWellFormed(faces)
        assertEquals(1 + 6 * 3, faces.size)
        // The band (top caps) exactly fills the L grown by t.
        val grown = Polygons.offset(l, t)
        assertEquals(Polygons.area(grown) - Polygons.area(l), faces.of(FaceKind.WALL_TOP).sumOf { area(it) }, 1e-9)
        // The inside-corner outer faces meet at the miter point (2 + t, 2 + t).
        val miter = Vec3.fromPlan(Vec2(2.0 + t, 2.0 + t), 0.0)
        assertEquals(2, faces.of(FaceKind.WALL_OUTER).count { f -> f.points.any { it.distanceTo(miter) < 1e-9 } })
    }

    @Test fun sampleProjectBuildsCleanly() {
        val project = SampleProjects.demo(1_700_000_000_000L)
        val rooms = project.floors.first().rooms
        val faces = Dollhouse.build(rooms, project.settings)
        assertWellFormed(faces)
        assertEquals(rooms.size, faces.of(FaceKind.FLOOR).size)
        assertTrue(faces.of(FaceKind.OPENING_FRAME).isNotEmpty())
    }

    // ---- Openings ------------------------------------------------------------------------------------------

    @Test fun doorSplitsTheWallAroundAFloorToHeadHole() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(0, 1.0)))), settings)
        assertWellFormed(faces)
        // Wall 0 runs along plan y = 0, i.e. world z = 0.
        val inner = faces.of(FaceKind.WALL_INNER).filter { f -> f.points.all { abs(it.z) < 1e-9 } }
        assertEquals(3, inner.size) // left, header, right
        assertEquals(4.0 * 2.5 - 0.8 * 2.0, inner.sumOf { area(it) }, 1e-9)
        val header = inner.single { minY(it) > 0.0 }
        assertEquals(2.0, minY(header), 1e-12)
        // The hole is clear: no inner face covers the middle of the door.
        assertFalse(inner.any { covers(it, Vec3(1.4, 1.0, 0.0)) })
        assertTrue(inner.any { covers(it, Vec3(1.4, 2.2, 0.0)) })

        val frames = faces.of(FaceKind.OPENING_FRAME)
        assertEquals(3, frames.size) // two jambs + head
        val jambs = frames.filter { abs(normal(it).y) < 1e-9 }
        assertEquals(2, jambs.size)
        jambs.forEach { assertEquals(0.0, minY(it), 1e-12); assertEquals(2.0, maxY(it), 1e-12) }
        // Jambs face into the opening and span the wall thickness (z from 0 to +t: wall 0 is on plan y = 0).
        val left = jambs.single { it.points.all { p -> abs(p.x - 1.0) < 1e-9 } }
        val right = jambs.single { it.points.all { p -> abs(p.x - 1.8) < 1e-9 } }
        assertTrue(normal(left).x > 0.999)
        assertTrue(normal(right).x < -0.999)
        assertEquals(t, left.points.maxOf { it.z } - left.points.minOf { it.z }, 1e-12)
        val head = frames.single { abs(normal(it).y) > 0.5 }
        assertTrue(normal(head).y < -0.999) // the head faces down
        head.points.forEach { assertEquals(2.0, it.y, 1e-12) }
        // The outer face has the same hole.
        val outer = faces.of(FaceKind.WALL_OUTER).filter { f -> f.points.all { abs(it.z - t) < 1e-9 } }
        assertEquals(3, outer.size)
        assertFalse(outer.any { covers(it, Vec3(1.4, 1.0, t)) })
        assertEquals(20, faces.size)
    }

    @Test fun windowLeavesWallBelowTheSillAndAboveTheHead() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(window(0, 1.5)))), settings)
        assertWellFormed(faces)
        val inner = faces.of(FaceKind.WALL_INNER).filter { f -> f.points.all { abs(it.z) < 1e-9 } }
        assertEquals(4, inner.size) // left, below sill, above head, right
        assertEquals(4.0 * 2.5 - 1.0 * 1.2, inner.sumOf { area(it) }, 1e-9)
        assertFalse(inner.any { covers(it, Vec3(2.0, 1.5, 0.0)) })
        assertTrue(inner.any { covers(it, Vec3(2.0, 0.5, 0.0)) })
        assertTrue(inner.any { covers(it, Vec3(2.0, 2.3, 0.0)) })

        val frames = faces.of(FaceKind.OPENING_FRAME)
        assertEquals(4, frames.size) // two jambs, sill, head
        val sill = frames.single { normal(it).y > 0.5 }
        val head = frames.single { normal(it).y < -0.5 }
        sill.points.forEach { assertEquals(0.9, it.y, 1e-12) }
        head.points.forEach { assertEquals(2.1, it.y, 1e-12) }
        frames.filter { abs(normal(it).y) < 1e-9 }.forEach { assertEquals(0.9, minY(it), 1e-12); assertEquals(2.1, maxY(it), 1e-12) }
        assertEquals(4, faces.of(FaceKind.WALL_OUTER).count { f -> f.points.all { abs(it.z - t) < 1e-9 } })
        assertEquals(23, faces.size)
    }

    @Test fun passageOpeningIsCutLikeADoor() {
        val faces = Dollhouse.build(
            listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(1, 0.5, width = 1.2, height = 2.1, type = OpeningType.OPENING)))),
            settings,
        )
        assertWellFormed(faces)
        // Wall 1 runs along plan x = 4.
        val inner = faces.of(FaceKind.WALL_INNER).filter { f -> f.points.all { abs(it.x - 4.0) < 1e-9 } }
        assertEquals(3, inner.size)
        assertEquals(2.1, inner.single { minY(it) > 0 }.let { minY(it) }, 1e-12)
        assertEquals(3, faces.of(FaceKind.OPENING_FRAME).size)
    }

    @Test fun fullHeightOpeningAlsoCutsTheTopCap() {
        val faces = Dollhouse.build(
            listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(0, 1.0, width = 1.5, height = 3.0, type = OpeningType.OPENING)))),
            settings,
        )
        assertWellFormed(faces)
        val tops = faces.of(FaceKind.WALL_TOP)
        assertEquals(5, tops.size) // wall 0's cap is split in two
        assertFalse(tops.any { covers(it, Vec3(1.75, 2.5, t / 2)) })
        assertEquals(2, faces.of(FaceKind.OPENING_FRAME).size) // jambs only: no head
        faces.of(FaceKind.OPENING_FRAME).forEach { assertEquals(2.5, maxY(it), 1e-12) }
        // Total cap area = band area minus the cut.
        val band = (4.0 + 2 * t) * (3.0 + 2 * t) - 12.0
        assertEquals(band - 1.5 * t, tops.sumOf { area(it) }, 1e-9)
    }

    @Test fun doorAtTheCornerLeavesNoSliver() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(0, 0.001)))), settings)
        assertWellFormed(faces)
        val inner = faces.of(FaceKind.WALL_INNER).filter { f -> f.points.all { abs(it.z) < 1e-9 } }
        assertEquals(2, inner.size) // header + right; the 1 mm strip at the corner is snapped away
    }

    @Test fun overlappingOpeningsAreMerged() {
        val faces = Dollhouse.build(
            listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(0, 1.0), door(0, 1.4)))),
            settings,
        )
        assertWellFormed(faces)
        val inner = faces.of(FaceKind.WALL_INNER).filter { f -> f.points.all { abs(it.z) < 1e-9 } }
        assertEquals(3, inner.size)
        assertEquals(4.0 * 2.5 - 1.2 * 2.0, inner.sumOf { area(it) }, 1e-9)
    }

    @Test fun unusableOpeningsCutNothing() {
        val bad = listOf(
            door(7, 1.0), // no such wall
            door(-1, 1.0),
            door(0, 1.0, width = 0.0),
            door(0, Double.NaN, width = Double.NaN),
            window(0, 1.0, sill = 3.0), // above the ceiling
        )
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = bad)), settings)
        assertWellFormed(faces)
        assertEquals(13, faces.size)
    }

    @Test fun nonFiniteOpeningHeightsUseTheDefaults() {
        val o = Opening("w", OpeningType.WINDOW, 0, 1.0, 1.0, height = Double.NaN, sillHeight = Double.NaN)
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(o))), settings)
        assertWellFormed(faces)
        val sill = faces.of(FaceKind.OPENING_FRAME).single { normal(it).y > 0.5 }
        assertEquals(settings.defaultWindowSill, sill.points.first().y, 1e-12)
        val head = faces.of(FaceKind.OPENING_FRAME).single { normal(it).y < -0.5 }
        assertEquals(settings.defaultWindowSill + settings.defaultWindowHeight, head.points.first().y, 1e-12)
    }

    @Test fun tallerOpeningIsClampedToTheWall() {
        val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(window(0, 1.0, height = 5.0)))), settings)
        assertWellFormed(faces)
        assertTrue(faces.all { maxY(it) <= 2.5 + 1e-12 })
        assertEquals(0, faces.of(FaceKind.OPENING_FRAME).count { normal(it).y < -0.5 }) // no head when the hole reaches the top
        assertEquals(1, faces.of(FaceKind.OPENING_FRAME).count { normal(it).y > 0.5 }) // the sill stays
        assertEquals(5, faces.of(FaceKind.WALL_TOP).size) // the notch cuts the top cap
    }

    // ---- Degenerate input --------------------------------------------------------------------------------

    @Test fun degenerateRoomsAreSkipped() {
        val good = room("good", rect(10.0, 0.0, 3.0, 3.0))
        val bad = listOf(
            room("empty", emptyList()),
            room("two", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0))),
            room("nan", listOf(Vec2(0.0, 0.0), Vec2(Double.NaN, 0.0), Vec2(1.0, 1.0))),
            room("inf", listOf(Vec2(0.0, 0.0), Vec2(Double.POSITIVE_INFINITY, 0.0), Vec2(1.0, 1.0))),
            room("flat", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0))),
            room("bowtie", listOf(Vec2(0.0, 0.0), Vec2(2.0, 2.0), Vec2(2.0, 0.0), Vec2(0.0, 2.0))),
            room("dup", listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 0.0), Vec2(0.0, 1.0))),
        )
        val faces = Dollhouse.build(bad + good, settings)
        assertWellFormed(faces)
        assertTrue(faces.all { it.roomId == "good" })
        assertEquals(13, faces.size)
    }

    @Test fun unusableWallThicknessFallsBackToTheDefault() {
        for (bad in listOf(Double.NaN, 0.0, -0.2, Double.POSITIVE_INFINITY)) {
            val faces = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0))), settings.copy(wallThickness = bad))
            assertWellFormed(faces)
            val xs = faces.of(FaceKind.WALL_OUTER).flatMap { f -> f.points.map { it.x } }
            assertEquals(-PlanSettings().wallThickness, xs.min(), 1e-12, "thickness $bad")
        }
        val capped = Dollhouse.build(listOf(room("r", rect(0.0, 0.0, 4.0, 3.0))), settings.copy(wallThickness = 7.0))
        assertEquals(-Dollhouse.MAX_WALL_THICKNESS, capped.of(FaceKind.WALL_OUTER).flatMap { f -> f.points.map { it.x } }.min(), 1e-12)
    }

    @Test fun sharpCornerIsBeveled() {
        // A thin wedge: the 0.2 m-wide end makes a ~5.7° corner whose miter would reach far out.
        val wedge = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(0.0, 0.4))
        val faces = Dollhouse.build(listOf(room("w", wedge)), settings)
        assertWellFormed(faces)
        val reach = faces.flatMap { it.points }.maxOf { it.x }
        assertTrue(reach <= 4.0 + Dollhouse.MITER_LIMIT * t + 1e-9, "outer corner reaches $reach")
        assertTrue(faces.of(FaceKind.WALL_OUTER).size >= 4) // three walls + the bevel
    }

    @Test fun tinyEdgeWithSharpCornersStaysWellFormed() {
        // A 2 cm notch edge between two right angles in a 10 cm-thick wall: the outer face folds over itself.
        val notch = listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 2.0), Vec2(1.52, 2.0), Vec2(1.52, 1.0), Vec2(1.5, 1.0), Vec2(1.5, 2.0), Vec2(0.0, 2.0))
        val faces = Dollhouse.build(listOf(room("n", notch)), settings)
        assertWellFormed(faces)
        assertEquals(1, faces.of(FaceKind.FLOOR).size)
    }

    // ---- Neighbouring rooms ------------------------------------------------------------------------------

    @Test fun sharedWallHasNoOuterFacesButExteriorWallsDo() {
        val a = room("a", rect(0.0, 0.0, 4.0, 3.0))
        val b = room("b", rect(4.0 + t, 0.0, 3.0, 3.0))
        val faces = Dollhouse.build(listOf(a, b), settings)
        assertWellFormed(faces)
        val outer = faces.of(FaceKind.WALL_OUTER)
        // No outer face inside the shared slab x in [4, 4 + t].
        assertFalse(outer.any { f -> f.points.all { it.x > 4.0 - 1e-9 && it.x < 4.0 + t + 1e-9 } && abs(normal(f).x) > 0.5 })
        assertEquals(3, outer.ofRoom("a").size)
        assertEquals(3, outer.ofRoom("b").size)
        // Inner faces on both sides of the shared wall remain.
        assertEquals(4, faces.ofRoom("a").of(FaceKind.WALL_INNER).size)
        assertEquals(4, faces.ofRoom("b").of(FaceKind.WALL_INNER).size)
        // The exterior north wall of a still reaches its mitered corner.
        val north = outer.ofRoom("a").single { normal(it).z < -0.5 }
        assertEquals(4.0 + t, north.points.maxOf { it.x }, 1e-12)
    }

    @Test fun lowerNeighbourHidesOnlyTheBottomOfTheSharedOuterFace() {
        val tall = room("tall", rect(0.0, 0.0, 4.0, 3.0), height = 3.0)
        val low = room("low", rect(4.0 + t, 0.0, 3.0, 3.0), height = 2.4)
        val faces = Dollhouse.build(listOf(tall, low), settings)
        assertWellFormed(faces)
        val shared = faces.ofRoom("tall").of(FaceKind.WALL_OUTER).filter { normal(it).x > 0.5 }
        assertEquals(1, shared.size)
        assertEquals(2.4, minY(shared.single()), 1e-9)
        assertEquals(3.0, maxY(shared.single()), 1e-12)
        assertTrue(faces.ofRoom("low").of(FaceKind.WALL_OUTER).none { normal(it).x < -0.5 })
    }

    @Test fun partlySharedWallKeepsItsExposedOuterFace() {
        val a = room("a", rect(0.0, 0.0, 4.0, 4.0))
        val b = room("b", rect(4.0 + t, 0.0, 3.0, 2.0)) // covers the lower half of a's east wall
        val faces = Dollhouse.build(listOf(a, b), settings)
        assertWellFormed(faces)
        val east = faces.ofRoom("a").of(FaceKind.WALL_OUTER).filter { normal(it).x > 0.5 }
        assertEquals(1, east.size)
        // Exposed from the top of b's band (2 + t) to a's mitered corner (4 + t), in plan y = -world z.
        val ys = east.single().points.map { -it.z }
        assertEquals(2.0 + t, ys.min(), 1e-4)
        assertEquals(4.0 + t, ys.max(), 1e-9)
    }

    @Test fun distantRoomsKeepBothOuterFaces() {
        val a = room("a", rect(0.0, 0.0, 4.0, 3.0))
        val b = room("b", rect(4.0 + 2 * t + 0.05, 0.0, 3.0, 3.0))
        val faces = Dollhouse.build(listOf(a, b), settings)
        assertEquals(4, faces.ofRoom("a").of(FaceKind.WALL_OUTER).size)
        assertEquals(4, faces.ofRoom("b").of(FaceKind.WALL_OUTER).size)
    }

    @Test fun doorMarkedOnOneSideCutsBothRoomsWalls() {
        val a = room("a", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(1, 1.0))) // wall 1: x = 4, y 0 -> 3
        val b = room("b", rect(4.0 + t, 0.0, 3.0, 3.0))
        val faces = Dollhouse.build(listOf(a, b), settings)
        assertWellFormed(faces)
        val probeA = Vec3.fromPlan(Vec2(4.0, 1.4), 1.0)
        val probeB = Vec3.fromPlan(Vec2(4.0 + t, 1.4), 1.0)
        assertFalse(faces.of(FaceKind.WALL_INNER).any { covers(it, probeA) })
        assertFalse(faces.of(FaceKind.WALL_INNER).any { covers(it, probeB) })
        // b's west wall: two side pieces + header, and its own jambs and head.
        val bWest = faces.ofRoom("b").of(FaceKind.WALL_INNER).filter { f -> f.points.all { abs(it.x - (4.0 + t)) < 1e-9 } }
        assertEquals(3, bWest.size)
        assertEquals(2.0, bWest.single { minY(it) > 0 }.let { minY(it) }, 1e-12)
        assertEquals(3, faces.ofRoom("b").of(FaceKind.OPENING_FRAME).size)
    }

    @Test fun doorMarkedFromBothSidesStaysOneOpening() {
        val a = room("a", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(1, 1.0)))
        // b's wall 3 runs from (4 + t, 3) down to (4 + t, 0); offset 1.2 puts its door at y 1.0 .. 1.8 as well.
        val b = room("b", rect(4.0 + t, 0.0, 3.0, 3.0), openings = listOf(door(3, 1.2)))
        val faces = Dollhouse.build(listOf(a, b), settings)
        assertWellFormed(faces)
        assertEquals(3, faces.ofRoom("a").of(FaceKind.OPENING_FRAME).size)
        assertEquals(3, faces.ofRoom("b").of(FaceKind.OPENING_FRAME).size)
    }

    @Test fun openingOnAnUnrelatedWallDoesNotCutTheNeighbour() {
        val a = room("a", rect(0.0, 0.0, 4.0, 3.0), openings = listOf(door(3, 1.0))) // west wall, away from b
        val b = room("b", rect(4.0 + t, 0.0, 3.0, 3.0))
        val faces = Dollhouse.build(listOf(a, b), settings)
        assertEquals(0, faces.ofRoom("b").of(FaceKind.OPENING_FRAME).size)
        assertEquals(4, faces.ofRoom("b").of(FaceKind.WALL_INNER).size)
    }

    // ---- Helpers -----------------------------------------------------------------------------------------

    @Test fun wallGridColumnsAndSpans() {
        val door = WallRect(1.0, 1.8, 0.0, 2.0)
        val window = WallRect(2.5, 3.5, 0.9, 2.1)
        val cols = WallGrid.columns(0.0, 4.0, 2.5, listOf(door, window))
        assertEquals(listOf(0.0, 1.0, 1.8, 2.5, 3.5), cols.map { it.u0 })
        assertEquals(listOf(Span(0.0, 2.0)), cols[1].open)
        assertEquals(listOf(Span(2.0, 2.5)), cols[1].solid(2.5))
        assertEquals(listOf(Span(0.0, 0.9), Span(2.1, 2.5)), cols[3].solid(2.5))
        assertTrue(cols[0].open.isEmpty() && cols[2].open.isEmpty() && cols[4].open.isEmpty())
        assertTrue(WallGrid.columns(0.0, 0.001, 2.5, emptyList()).isEmpty())
        assertTrue(WallGrid.columns(0.0, 4.0, Double.NaN, emptyList()).isEmpty())
        // Infinite hole bounds are clamped to the wall.
        val covered = WallGrid.columns(-0.1, 4.1, 2.5, listOf(WallRect(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, 2.5)))
        assertEquals(1, covered.size)
        assertTrue(covered.single().isOpenAtTop(2.5))
        assertEquals(listOf(Span(1.0, 2.0)), Spans.subtract(listOf(Span(0.0, 2.0)), listOf(Span(-1.0, 1.0))))
        assertEquals(listOf(Span(0.0, 3.0)), Spans.normalize(listOf(Span(1.0, 3.0), Span(0.0, 1.5)), 0.0, 2.5 + 0.5))
    }
}
