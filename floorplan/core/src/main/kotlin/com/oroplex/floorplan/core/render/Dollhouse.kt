package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.geometry.Bounds
import com.oroplex.floorplan.core.geometry.Line2
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.OpeningType
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.RoomOps
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class FaceKind { FLOOR, WALL_INNER, WALL_OUTER, WALL_TOP, OPENING_FRAME }

/**
 * Planar polygon of the 3D "dollhouse" model, in a y-up frame where x = plan.x, y = height above the floor,
 * z = -plan.y (Vec3.fromPlan). [roomId] is null for shared/exterior pieces.
 *
 * [points] are counter-clockwise when seen from the face's visible side, so the right-handed (Newell) normal
 * points toward the viewer that should see it: floors, wall tops and window sills face up, inner walls face
 * into their room, outer walls away from it, opening heads face down and opening jambs face into the opening.
 * Renderers can back-face cull on that normal.
 */
data class Face3(val points: List<Vec3>, val kind: FaceKind, val roomId: String?)

object Dollhouse {
    /** Wall thicknesses above this are treated as typos (meters), as on the 2D plan. */
    const val MAX_WALL_THICKNESS = 1.0

    /** Ceiling heights outside this range are treated as unknown, so the default is used (meters). */
    const val MIN_CEILING_HEIGHT = 0.5
    const val MAX_CEILING_HEIGHT = 20.0

    /** Openings narrower or shorter than this get no hole (meters). */
    const val MIN_OPENING_SIZE = 0.01

    /** Outer wall corners whose miter would reach further than this many wall thicknesses are beveled. */
    const val MITER_LIMIT = 4.0

    /** Geometric tolerance: hole edges this close together or to a wall end are snapped together (meters). */
    internal const val EPS = 0.002

    /** Spacing of the samples that find which parts of an outer face are buried in a neighbour (meters). */
    internal const val COVER_SAMPLE_SPACING = 0.05

    /** How far beyond an outer face the "is something solid behind it" probe looks (meters). */
    internal const val COVER_PROBE = 0.002

    /** A neighbour's opening cuts this room's wall when the two walls face each other this squarely... */
    internal const val FACING_DOT = -0.95

    /** ...and its face lies within two wall thicknesses plus this slack outside this wall's face (meters). */
    internal const val FACING_SLACK = 0.05

    /** Faces with less area than this are dropped (square meters). */
    internal const val MIN_FACE_AREA = 1e-8

    /**
     * Build an extruded model of the rooms: a floor polygon per room, wall slabs of settings.wallThickness
     * around each room's interior outline up to its ceiling height (ceilingHeight ?: [defaultCeiling]),
     * with door/opening holes cut full-height-of-opening and window holes between sill and head
     * (walls split into the pieces around each opening). No ceilings (open-top dollhouse view).
     *
     * Details:
     * * Every wall slab is made of planar quads: the inner face (split around openings), the outer face
     *   [wallThickness] outside it, the top cap, and OPENING_FRAME faces lining each hole (jambs, the head
     *   above it and a window's sill), so the wall reads as solid from any angle. Outer corners are mitered
     *   (beveled beyond [MITER_LIMIT]).
     * * Rooms whose outlines are unusable (fewer than 3 finite corners, zero area, self-intersecting) are
     *   skipped; clockwise outlines are accepted and still produce correctly facing faces. Unusable openings
     *   (unknown wall, no width, window above the ceiling, non-finite values) get no hole.
     * * Shared walls: where another room's slab fills the space behind an outer face (the usual case of two
     *   rooms one wall thickness apart), that part of the outer face is left out up to the neighbour's height,
     *   so coincident faces never fight in a painter's-algorithm renderer and a taller room still shows its
     *   wall above a lower neighbour. An opening marked on one side of a shared wall also cuts the
     *   neighbour's slab, so a door marked from one room is a real gap in 3D.
     * * Non-finite or non-positive wall thickness falls back to the default, and is capped at
     *   [MAX_WALL_THICKNESS]; ceiling heights outside [MIN_CEILING_HEIGHT]..[MAX_CEILING_HEIGHT] fall back to
     *   [defaultCeiling] (and that, if unusable, to the settings' / built-in default).
     *
     * Faces are listed room by room, the floor first.
     */
    fun build(rooms: List<Room>, settings: PlanSettings, defaultCeiling: Double = settings.defaultCeilingHeight): List<Face3> {
        val thickness = wallThickness(settings)
        val fallbackHeight = usableCeiling(defaultCeiling)
            ?: usableCeiling(settings.defaultCeilingHeight)
            ?: PlanSettings().defaultCeilingHeight
        val solids = rooms.mapNotNull { RoomSolid.of(it, thickness, fallbackHeight) }
        val out = ArrayList<Face3>()
        for (solid in solids) {
            solid.floorFace()?.let(out::add)
            WallBuilder(solid, solids, thickness, settings, out).build()
        }
        return out
    }

    /** The settings' wall thickness, or the default when it is not a usable positive number (meters). */
    internal fun wallThickness(settings: PlanSettings): Double =
        settings.wallThickness.takeIf { it.isFinite() && it > 0.0 }?.coerceAtMost(MAX_WALL_THICKNESS)
            ?: PlanSettings().wallThickness

    internal fun usableCeiling(height: Double?): Double? =
        height?.takeIf { it.isFinite() && it >= MIN_CEILING_HEIGHT && it <= MAX_CEILING_HEIGHT }
}

// ---------------------------------------------------------------------------------------------------------
// Wall coordinates: a wall is parameterized by u (meters along its edge from the edge's start corner, which
// may run slightly negative / beyond the edge length on the mitered outer face) and v (height above floor).
// ---------------------------------------------------------------------------------------------------------

/** Axis-aligned rectangle in wall (u, v) coordinates. Bounds may be infinite (clamped by the consumer). */
internal data class WallRect(val u0: Double, val u1: Double, val v0: Double, val v1: Double)

/** Closed 1D interval lo..hi. */
internal data class Span(val lo: Double, val hi: Double)

/** A vertical strip u0..u1 of a wall face and the height ranges that are open (holes) in it. */
internal class WallColumn(val u0: Double, val u1: Double, val open: List<Span>) {
    fun solid(height: Double): List<Span> = Spans.complement(open, 0.0, height)

    /** True when a hole in this column reaches the top of the wall (the slab is cut through its top cap). */
    fun isOpenAtTop(height: Double): Boolean = open.isNotEmpty() && open.last().hi >= height
}

internal object Spans {
    /** Clamp into lo..hi, snap ends within [eps] of the bounds, drop tiny spans, and merge overlapping ones. */
    fun normalize(spans: List<Span>, lo: Double, hi: Double, eps: Double = Dollhouse.EPS): List<Span> {
        val clamped = spans.mapNotNull { s ->
            if (s.lo.isNaN() || s.hi.isNaN()) return@mapNotNull null
            var a = s.lo.coerceIn(lo, hi)
            var b = s.hi.coerceIn(lo, hi)
            if (a - lo <= eps) a = lo
            if (hi - b <= eps) b = hi
            if (b - a <= eps) null else Span(a, b)
        }.sortedBy { it.lo }
        val merged = ArrayList<Span>(clamped.size)
        for (s in clamped) {
            val last = merged.lastOrNull()
            if (last != null && s.lo <= last.hi + eps) merged[merged.lastIndex] = Span(last.lo, max(last.hi, s.hi))
            else merged += s
        }
        return merged
    }

    /** Parts of lo..hi not covered by the (normalized) [spans]; pieces of [eps] or less are dropped. */
    fun complement(spans: List<Span>, lo: Double, hi: Double, eps: Double = Dollhouse.EPS): List<Span> {
        val out = ArrayList<Span>(spans.size + 1)
        var cursor = lo
        for (s in spans) {
            if (s.lo - cursor > eps) out += Span(cursor, s.lo)
            cursor = max(cursor, s.hi)
        }
        if (hi - cursor > eps) out += Span(cursor, hi)
        return out
    }

    /** Parts of the (normalized) spans [a] not covered by the (normalized) spans [b]. */
    fun subtract(a: List<Span>, b: List<Span>, eps: Double = Dollhouse.EPS): List<Span> {
        if (b.isEmpty()) return a
        val out = ArrayList<Span>()
        for (s in a) {
            var cursor = s.lo
            for (cut in b) {
                if (cut.hi <= cursor || cut.lo >= s.hi) continue
                if (cut.lo - cursor > eps) out += Span(cursor, cut.lo)
                cursor = max(cursor, cut.hi)
            }
            if (s.hi - cursor > eps) out += Span(cursor, s.hi)
        }
        return out
    }

    fun sameAs(a: List<Span>, b: List<Span>, tol: Double = 1e-9): Boolean =
        a.size == b.size && a.indices.all { abs(a[it].lo - b[it].lo) <= tol && abs(a[it].hi - b[it].hi) <= tol }
}

internal object WallGrid {
    /**
     * Decompose the wall face uMin..uMax × 0..[height] minus the union of [holes] into vertical columns. Each
     * column has the same open height ranges across its width; neighbouring columns always differ (equal ones
     * are merged), so a wall with one door becomes three columns: full, door (open below the head), full.
     * Hole edges closer than [eps] to each other or to the wall ends are snapped together, so no sliver
     * pieces are produced. Overlapping holes are merged.
     */
    fun columns(uMin: Double, uMax: Double, height: Double, holes: List<WallRect>, eps: Double = Dollhouse.EPS): List<WallColumn> {
        if (!(uMax - uMin > eps) || !(height > eps)) return emptyList()
        val usable = holes.filter { !it.u0.isNaN() && !it.u1.isNaN() && !it.v0.isNaN() && !it.v1.isNaN() }
        val breaks = ArrayList<Double>(2 + 2 * usable.size)
        breaks += uMin
        breaks += uMax
        for (h in usable) {
            breaks += h.u0.coerceIn(uMin, uMax)
            breaks += h.u1.coerceIn(uMin, uMax)
        }
        breaks.sort()
        val cuts = ArrayList<Double>(breaks.size)
        for (b in breaks) if (cuts.isEmpty() || b - cuts.last() > eps) cuts += b
        // The last break may have been folded into an earlier one within eps: the wall still ends at uMax.
        if (cuts.size < 2) cuts += uMax else cuts[cuts.lastIndex] = uMax

        val columns = ArrayList<WallColumn>(cuts.size - 1)
        for (k in 0 until cuts.size - 1) {
            val a = cuts[k]
            val b = cuts[k + 1]
            val mid = (a + b) / 2
            val open = Spans.normalize(
                usable.filter { min(it.u0, it.u1) < mid && max(it.u0, it.u1) > mid }
                    .map { Span(min(it.v0, it.v1), max(it.v0, it.v1)) },
                0.0,
                height,
                eps,
            )
            val last = columns.lastOrNull()
            if (last != null && Spans.sameAs(last.open, open)) columns[columns.lastIndex] = WallColumn(last.u0, b, open)
            else columns += WallColumn(a, b, open)
        }
        return columns
    }
}

internal object FaceMath {
    /** Newell normal: its length is twice the polygon's area. */
    fun newell(points: List<Vec3>): Vec3 {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            x += (a.y - b.y) * (a.z + b.z)
            y += (a.z - b.z) * (a.x + b.x)
            z += (a.x - b.x) * (a.y + b.y)
        }
        return Vec3(x, y, z)
    }

    /**
     * [points] without repeated consecutive vertices, wound so its normal agrees with [facing]; null when
     * fewer than 3 distinct vertices remain or the polygon has (almost) no area.
     */
    fun oriented(points: List<Vec3>, facing: Vec3): List<Vec3>? {
        if (points.any { !it.isFinite() }) return null
        val clean = ArrayList<Vec3>(points.size)
        for (p in points) if (clean.isEmpty() || clean.last().distanceTo(p) > 1e-9) clean += p
        while (clean.size > 1 && clean.first().distanceTo(clean.last()) <= 1e-9) clean.removeAt(clean.lastIndex)
        if (clean.size < 3) return null
        val n = newell(clean)
        if (!(n.length > 2 * Dollhouse.MIN_FACE_AREA)) return null
        return if ((n dot facing) < 0) clean.reversed() else clean
    }
}

/** One edge of a room outline: [dir] unit direction, [outward] unit normal pointing away from the room. */
private class WallEdge(val index: Int, val a: Vec2, val dir: Vec2, val length: Double, val outward: Vec2) {
    fun inner(u: Double): Vec2 = a + dir * u
}

/** A beveled outer corner: the outer faces of the two edges end at [p1] and start at [p2]. */
private class Bevel(val corner: Vec2, val p1: Vec2, val p2: Vec2, val incomingDir: Vec2)

/** A room with a usable outline, ready to be extruded. */
private class RoomSolid(val room: Room, val orientation: Double, val height: Double, val thickness: Double) {
    val corners: List<Vec2> = room.corners
    val edges: List<WallEdge>

    /** Per edge: where its outer face starts and ends (miter point or bevel end) in plan coordinates. */
    val outerStart: Array<Vec2>
    val outerEnd: Array<Vec2>
    val bevels: List<Bevel>

    /** Outline of the room grown by its wall band (interior + slab): what a neighbour's outer face can hide in. */
    val band: List<Vec2>
    val bandBounds: Bounds

    init {
        val n = corners.size
        edges = List(n) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % n]
            val length = a.distanceTo(b)
            val dir = (b - a) / length
            WallEdge(i, a, dir, length, -dir.perp() * orientation)
        }
        val start = Array(n) { Vec2.ZERO }
        val end = Array(n) { Vec2.ZERO }
        val bevelList = ArrayList<Bevel>()
        val bandPoints = ArrayList<Vec2>(n + 4)
        for (i in 0 until n) {
            val prev = edges[(i - 1 + n) % n]
            val next = edges[i]
            val c = corners[i]
            val p1 = c + prev.outward * thickness
            val p2 = c + next.outward * thickness
            val hit = Line2(p1, prev.dir).intersect(Line2(p2, next.dir), 1e-6)
            when {
                hit == null && (prev.dir dot next.dir) > 0 -> {
                    // Collinear edges: the outer faces simply continue.
                    val m = Vec2.lerp(p1, p2, 0.5)
                    end[prev.index] = m
                    start[next.index] = m
                    bandPoints += m
                }
                hit != null && hit.isFinite() && hit.distanceTo(c) <= Dollhouse.MITER_LIMIT * thickness -> {
                    end[prev.index] = hit
                    start[next.index] = hit
                    bandPoints += hit
                }
                else -> {
                    end[prev.index] = p1
                    start[next.index] = p2
                    bevelList += Bevel(c, p1, p2, prev.dir)
                    bandPoints += p1
                    bandPoints += p2
                }
            }
        }
        outerStart = start
        outerEnd = end
        bevels = bevelList
        band = bandPoints
        bandBounds = Bounds.of(bandPoints)
    }

    fun floorFace(): Face3? {
        val pts = (if (orientation > 0) corners else corners.reversed()).map { Vec3.fromPlan(it, 0.0) }
        return FaceMath.oriented(pts, Vec3.UP)?.let { Face3(it, FaceKind.FLOOR, room.id) }
    }

    companion object {
        fun of(room: Room, thickness: Double, fallbackHeight: Double): RoomSolid? {
            val c = room.corners
            if (c.size < 3 || c.any { !it.isFinite() }) return null
            val area = Polygons.signedArea(c)
            if (!area.isFinite() || abs(area) < 1e-6 || !Polygons.isSimple(c)) return null
            val height = Dollhouse.usableCeiling(room.ceilingHeight) ?: fallbackHeight
            return RoomSolid(room, if (area > 0) 1.0 else -1.0, height, thickness)
        }
    }
}

/** Emits the wall faces of one room into [out]. */
private class WallBuilder(
    private val solid: RoomSolid,
    private val solids: List<RoomSolid>,
    private val t: Double,
    private val settings: PlanSettings,
    private val out: MutableList<Face3>,
) {
    private val height = solid.height

    fun build() {
        for (edge in solid.edges) wall(edge)
        for (bevel in solid.bevels) bevel(bevel)
    }

    private fun wall(e: WallEdge) {
        val holes = openingHoles(e)
        val inner = WallGrid.columns(0.0, e.length, height, holes)
        val intoRoom = dir3(-e.outward)
        for (col in inner) for (s in col.solid(height)) {
            emit(listOf(innerAt(e, col.u0, s.lo), innerAt(e, col.u1, s.lo), innerAt(e, col.u1, s.hi), innerAt(e, col.u0, s.hi)), intoRoom, FaceKind.WALL_INNER)
        }
        frames(e, inner)

        val start = solid.outerStart[e.index]
        val end = solid.outerEnd[e.index]
        val s0 = (start - e.a) dot e.dir
        val s1 = (end - e.a) dot e.dir
        if (s1 - s0 > Dollhouse.EPS) {
            val away = dir3(e.outward)
            val outer = WallGrid.columns(s0, s1, height, holes + coverRects(e, s0, s1))
            for (col in outer) for (s in col.solid(height)) {
                emit(listOf(outerAt(e, col.u0, s.lo), outerAt(e, col.u1, s.lo), outerAt(e, col.u1, s.hi), outerAt(e, col.u0, s.hi)), away, FaceKind.WALL_OUTER)
            }
            top(e, inner, s0, s1, start, end)
        } else {
            // A very short edge between sharp corners: its outer face folds over itself. Cap the slab with the
            // triangle the two corner diagonals enclose and leave the outer face out.
            val c0 = e.a
            val c1 = e.inner(e.length)
            val x = Line2.through(c0, start).intersect(Line2.through(c1, end))
            if (x != null) emit(listOf(c0, c1, x).map { Vec3.fromPlan(it, height) }, Vec3.UP, FaceKind.WALL_TOP)
        }
    }

    /** Jambs at every column boundary where open and solid meet, plus the head above / sill below each hole. */
    private fun frames(e: WallEdge, cols: List<WallColumn>) {
        if (cols.isEmpty()) return
        val forward = dir3(e.dir)
        val backward = -forward
        for (k in 0..cols.size) {
            val u = if (k < cols.size) cols[k].u0 else cols.last().u1
            val left = if (k > 0) cols[k - 1].open else emptyList()
            val right = if (k < cols.size) cols[k].open else emptyList()
            for (s in Spans.subtract(left, right)) jamb(e, u, s, backward)
            for (s in Spans.subtract(right, left)) jamb(e, u, s, forward)
        }
        for (col in cols) for (s in col.open) {
            if (s.lo > Dollhouse.EPS) horizontal(e, col.u0, col.u1, s.lo, Vec3.UP)
            if (s.hi < height - Dollhouse.EPS) horizontal(e, col.u0, col.u1, s.hi, -Vec3.UP)
        }
    }

    private fun jamb(e: WallEdge, u: Double, s: Span, facing: Vec3) =
        emit(listOf(innerAt(e, u, s.lo), outerAt(e, u, s.lo), outerAt(e, u, s.hi), innerAt(e, u, s.hi)), facing, FaceKind.OPENING_FRAME)

    private fun horizontal(e: WallEdge, u0: Double, u1: Double, v: Double, facing: Vec3) =
        emit(listOf(innerAt(e, u0, v), innerAt(e, u1, v), outerAt(e, u1, v), outerAt(e, u0, v)), facing, FaceKind.OPENING_FRAME)

    /**
     * Top cap of the slab: the trapezoid between the inner edge and the (mitered) outer edge, interrupted
     * where an opening reaches the top of the wall.
     */
    private fun top(e: WallEdge, inner: List<WallColumn>, s0: Double, s1: Double, start: Vec2, end: Vec2) {
        val ext0 = min(0.0, s0)
        val ext1 = max(e.length, s1)
        val cut = inner.filter { it.isOpenAtTop(height) }.map { Span(it.u0, it.u1) }
        for (piece in Spans.complement(cut, ext0, ext1, 0.0)) {
            val ia = e.inner(piece.lo.coerceIn(0.0, e.length))
            val ib = e.inner(piece.hi.coerceIn(0.0, e.length))
            val oa = if (piece.lo <= ext0) start else e.inner(piece.lo.coerceIn(s0, s1)) + e.outward * t
            val ob = if (piece.hi >= ext1) end else e.inner(piece.hi.coerceIn(s0, s1)) + e.outward * t
            emit(listOf(ia, ib, ob, oa).map { Vec3.fromPlan(it, height) }, Vec3.UP, FaceKind.WALL_TOP)
        }
    }

    private fun bevel(b: Bevel) {
        val mid = Vec2.lerp(b.p1, b.p2, 0.5)
        var normal = (b.p2 - b.p1).perp().normalized()
        val fromCorner = mid - b.corner
        if (fromCorner.length > 1e-9) {
            if ((fromCorner dot normal) < 0) normal = -normal
        } else if ((normal dot b.incomingDir) < 0) {
            normal = -normal // a full reversal: the bevel caps the tip, facing the way the wall was running
        }
        val covered = coverHeightAt(mid + normal * Dollhouse.COVER_PROBE).coerceAtMost(height)
        if (height - covered > Dollhouse.EPS) {
            emit(
                listOf(Vec3.fromPlan(b.p1, covered), Vec3.fromPlan(b.p2, covered), Vec3.fromPlan(b.p2, height), Vec3.fromPlan(b.p1, height)),
                dir3(normal),
                FaceKind.WALL_OUTER,
            )
        }
        emit(listOf(b.corner, b.p1, b.p2).map { Vec3.fromPlan(it, height) }, Vec3.UP, FaceKind.WALL_TOP)
    }

    // ---- Openings ----------------------------------------------------------------------------------------

    /** Holes in wall [e]: the room's own openings on it, plus neighbours' openings in the wall facing it. */
    private fun openingHoles(e: WallEdge): List<WallRect> {
        val holes = ArrayList<WallRect>()
        for (o in solid.room.openings) {
            if (o.wallIndex != e.index) continue
            val (s, en) = RoomOps.openingSegment(solid.room, o)
            hole(e, s, en, o)?.let(holes::add)
        }
        for (other in solids) {
            if (other === solid) continue
            for (o in other.room.openings) {
                val otherEdge = other.edges.getOrNull(o.wallIndex) ?: continue
                if ((otherEdge.dir dot e.dir) > Dollhouse.FACING_DOT) continue
                val (s, en) = RoomOps.openingSegment(other.room, o)
                if (!s.isFinite() || !en.isFinite() || s.distanceTo(en) < Dollhouse.MIN_OPENING_SIZE) continue
                val across = (Vec2.lerp(s, en, 0.5) - e.a) dot e.outward
                if (across < -0.01 || across > 2 * t + Dollhouse.FACING_SLACK) continue
                hole(e, s, en, o)?.let(holes::add)
            }
        }
        return holes
    }

    /** The hole an opening spanning plan points [s]..[en] cuts into [e], or null when it misses the wall. */
    private fun hole(e: WallEdge, s: Vec2, en: Vec2, o: Opening): WallRect? {
        if (!s.isFinite() || !en.isFinite()) return null
        val p0 = (s - e.a) dot e.dir
        val p1 = (en - e.a) dot e.dir
        val u0 = min(p0, p1).coerceIn(0.0, e.length)
        val u1 = max(p0, p1).coerceIn(0.0, e.length)
        if (u1 - u0 < Dollhouse.MIN_OPENING_SIZE) return null
        val (v0, v1) = verticalExtent(o) ?: return null
        return WallRect(u0, u1, v0, v1)
    }

    /** Doors and passages run from the floor to their height; windows from the sill to sill + height. */
    private fun verticalExtent(o: Opening): Pair<Double, Double>? {
        val defaults = PlanSettings()
        val (lo, hi) = when (o.type) {
            OpeningType.WINDOW -> {
                val sill = o.sillHeight.takeIf { it.isFinite() && it >= 0.0 }
                    ?: settings.defaultWindowSill.takeIf { it.isFinite() && it >= 0.0 }
                    ?: defaults.defaultWindowSill
                val h = positive(o.height) ?: positive(settings.defaultWindowHeight) ?: defaults.defaultWindowHeight
                sill to sill + h
            }
            OpeningType.DOOR, OpeningType.OPENING ->
                0.0 to (positive(o.height) ?: positive(settings.defaultDoorHeight) ?: defaults.defaultDoorHeight)
        }
        val v0 = lo.coerceIn(0.0, height)
        val v1 = hi.coerceIn(0.0, height)
        return if (v1 - v0 < Dollhouse.MIN_OPENING_SIZE) null else v0 to v1
    }

    private fun positive(x: Double): Double? = x.takeIf { it.isFinite() && it > 0.0 }

    // ---- Shared walls ------------------------------------------------------------------------------------

    /**
     * Parts of the outer face of [e] (params [s0]..[s1]) that sit inside another room's wall band, as
     * rectangles from the floor up to that room's height. Sampled every [Dollhouse.COVER_SAMPLE_SPACING] and
     * refined by bisection where the covering changes; runs touching the ends extend past them (to infinity),
     * so the mitered corners are treated like the samples next to them.
     */
    private fun coverRects(e: WallEdge, s0: Double, s1: Double): List<WallRect> {
        val inset = min(Dollhouse.EPS, (s1 - s0) / 4)
        val a = s0 + inset
        val b = s1 - inset
        val n = max(2, ceil((b - a) / Dollhouse.COVER_SAMPLE_SPACING).toInt())
        val us = DoubleArray(n + 1) { a + (b - a) * it / n }
        val hs = DoubleArray(n + 1) { coverHeight(e, us[it]) }
        if (hs.all { it <= 0.0 }) return emptyList()
        val rects = ArrayList<WallRect>()
        var runStart = Double.NEGATIVE_INFINITY
        var runHeight = hs[0]
        for (k in 0 until n) {
            if (abs(hs[k + 1] - hs[k]) <= 1e-9) continue
            val boundary = refine(e, us[k], us[k + 1], hs[k])
            if (runHeight > Dollhouse.EPS) rects += WallRect(runStart, boundary, 0.0, runHeight)
            runStart = boundary
            runHeight = hs[k + 1]
        }
        if (runHeight > Dollhouse.EPS) rects += WallRect(runStart, Double.POSITIVE_INFINITY, 0.0, runHeight)
        return rects
    }

    private fun refine(e: WallEdge, from: Double, to: Double, heightAtFrom: Double): Double {
        var lo = from
        var hi = to
        repeat(14) {
            val m = (lo + hi) / 2
            if (abs(coverHeight(e, m) - heightAtFrom) <= 1e-9) lo = m else hi = m
        }
        return (lo + hi) / 2
    }

    private fun coverHeight(e: WallEdge, u: Double): Double =
        coverHeightAt(e.inner(u) + e.outward * (t + Dollhouse.COVER_PROBE))

    /** Height of the tallest other room whose wall band (interior + slab) contains plan point [p]; 0 if none. */
    private fun coverHeightAt(p: Vec2): Double {
        var h = 0.0
        for (other in solids) {
            if (other === solid || other.height <= h) continue
            if (other.bandBounds.contains(p) && Polygons.contains(other.band, p)) h = other.height
        }
        return h
    }

    // ---- Emission ----------------------------------------------------------------------------------------

    private fun innerAt(e: WallEdge, u: Double, v: Double): Vec3 = Vec3.fromPlan(e.inner(u), v)

    private fun outerAt(e: WallEdge, u: Double, v: Double): Vec3 = Vec3.fromPlan(e.inner(u) + e.outward * t, v)

    /** A horizontal plan direction as a 3D vector. */
    private fun dir3(p: Vec2): Vec3 = Vec3(p.x, 0.0, -p.y)

    private fun emit(points: List<Vec3>, facing: Vec3, kind: FaceKind) {
        FaceMath.oriented(points, facing)?.let { out += Face3(it, kind, solid.room.id) }
    }
}
