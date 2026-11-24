package com.oroplex.floorplan.ui.view3d

import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import com.oroplex.floorplan.core.render.Face3
import com.oroplex.floorplan.core.render.FaceKind
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A room name drawn on the floor at [anchor] (dollhouse frame). [probe] is the same plan point at half the
 * room's wall height: the label counts as hidden only when that point is behind something, which matches
 * what a viewer calls "this room is hidden" (the floor point itself is behind a near wall from most angles).
 * [area] (square meters) ranks labels when they compete for space.
 */
data class RoomLabel(val roomId: String, val text: String, val anchor: Vec3, val probe: Vec3, val area: Double)

/**
 * Render-ready copy of the dollhouse faces, built once per model change (off the main thread):
 * flat vertex arrays, per-face plane, precomputed flat-shaded fill color, the outline segments worth drawing,
 * label anchors and bounds. Immutable after construction; the per-frame work is in [DollhouseProjector].
 */
class DollhouseMesh private constructor(
    val faceCount: Int,
    /** Vertex coordinates: every face's polygon vertices, then every outline segment's end points. */
    val vx: DoubleArray,
    val vy: DoubleArray,
    val vz: DoubleArray,
    /** Polygon of face i = vertices faceStart[i] until faceStart[i + 1]. */
    val faceStart: IntArray,
    /** Outline segments of face i = segments lineStart[i] until lineStart[i + 1]; segment k = vertices lineVertex[2k], lineVertex[2k + 1]. */
    val lineStart: IntArray,
    val lineVertex: IntArray,
    /** Unit face normal and plane offset (n · p = d for points p of the face). */
    val nx: DoubleArray,
    val ny: DoubleArray,
    val nz: DoubleArray,
    val planeD: DoubleArray,
    /** In-plane 2D coordinates of each face polygon (same index range as the polygon), for ray hits. */
    val planeU: DoubleArray,
    val planeV: DoubleArray,
    /** In-plane axes per face (u axis, v axis), 3 doubles each. */
    val axisU: DoubleArray,
    val axisV: DoubleArray,
    val kinds: Array<FaceKind>,
    /** Shaded ARGB fill per face. */
    val fillColor: IntArray,
    val bounds: Box3,
    val labels: List<RoomLabel>,
) {
    val vertexCount: Int get() = vx.size

    fun isFloor(face: Int): Boolean = kinds[face] == FaceKind.FLOOR

    companion object {
        val EMPTY: DollhouseMesh = build(emptyList(), emptyList())

        /**
         * Flatten [faces] (from Dollhouse.build) for rendering. [rooms] supply floor tints (by room type) and
         * label text; labels are made only for rooms that have a floor face. Faces with fewer than 3 points,
         * non-finite points or no area are dropped.
         */
        fun build(faces: List<Face3>, rooms: List<Room>): DollhouseMesh {
            val usable = faces.mapNotNull { f -> PlanarFace.of(f) }
            val outlines = OutlineEdges.compute(usable)
            val roomsById = rooms.associateBy { it.id }

            val polyVertices = usable.sumOf { it.points.size }
            val lineCount = outlines.sumOf { it.size }
            val total = polyVertices + 2 * lineCount
            val vx = DoubleArray(total)
            val vy = DoubleArray(total)
            val vz = DoubleArray(total)
            val n = usable.size
            val faceStart = IntArray(n + 1)
            val lineStart = IntArray(n + 1)
            val lineVertex = IntArray(2 * lineCount)
            val nx = DoubleArray(n)
            val ny = DoubleArray(n)
            val nz = DoubleArray(n)
            val planeD = DoubleArray(n)
            val planeU = DoubleArray(polyVertices)
            val planeV = DoubleArray(polyVertices)
            val axisU = DoubleArray(3 * n)
            val axisV = DoubleArray(3 * n)
            val kinds = Array(n) { usable[it].face.kind }
            val fill = IntArray(n)

            var v = 0
            for ((i, pf) in usable.withIndex()) {
                faceStart[i] = v
                for (p in pf.points) {
                    vx[v] = p.x
                    vy[v] = p.y
                    vz[v] = p.z
                    planeU[v] = p dot pf.u
                    planeV[v] = p dot pf.v
                    v++
                }
                nx[i] = pf.normal.x
                ny[i] = pf.normal.y
                nz[i] = pf.normal.z
                planeD[i] = pf.d
                axisU[3 * i] = pf.u.x; axisU[3 * i + 1] = pf.u.y; axisU[3 * i + 2] = pf.u.z
                axisV[3 * i] = pf.v.x; axisV[3 * i + 1] = pf.v.y; axisV[3 * i + 2] = pf.v.z
                val type = pf.face.roomId?.let { roomsById[it]?.type }
                fill[i] = DollhousePalette.shade(DollhousePalette.baseColor(pf.face.kind, type), pf.normal)
            }
            faceStart[n] = v
            var seg = 0
            for (i in 0 until n) {
                lineStart[i] = seg
                for ((a, b) in outlines[i]) {
                    vx[v] = a.x; vy[v] = a.y; vz[v] = a.z
                    lineVertex[2 * seg] = v++
                    vx[v] = b.x; vy[v] = b.y; vz[v] = b.z
                    lineVertex[2 * seg + 1] = v++
                    seg++
                }
            }
            lineStart[n] = seg

            val bounds = Box3.of(usable.flatMap { it.points })
            val floored = usable.filter { it.face.kind == FaceKind.FLOOR }.mapNotNull { it.face.roomId }.toSet()
            val roomTop = HashMap<String, Double>()
            for (pf in usable) {
                val id = pf.face.roomId ?: continue
                roomTop[id] = max(roomTop[id] ?: 0.0, pf.points.maxOf { it.y })
            }
            val labels = rooms.filter { it.id in floored }.mapNotNull { room ->
                val text = room.name.replace('\n', ' ').trim().ifEmpty { room.type.displayName }
                val probeHeight = max(LABEL_HEIGHT, (roomTop[room.id] ?: 0.0) / 2)
                labelAnchor(room.corners)?.let {
                    RoomLabel(room.id, text, Vec3.fromPlan(it, LABEL_HEIGHT), Vec3.fromPlan(it, probeHeight), Polygons.area(room.corners))
                }
            }
            return DollhouseMesh(
                n, vx, vy, vz, faceStart, lineStart, lineVertex, nx, ny, nz, planeD, planeU, planeV, axisU, axisV,
                kinds, fill, bounds, labels,
            )
        }

        /** Labels float this far above the floor, so a ray test toward them never hits the floor itself (m). */
        const val LABEL_HEIGHT = 0.05

        /**
         * Where a room's name goes: its area centroid when that is inside and roughly as far from the walls as
         * the most interior point; otherwise (L shapes, odd outlines) the pole of inaccessibility. Null for an
         * unusable outline.
         */
        fun labelAnchor(corners: List<Vec2>): Vec2? {
            if (corners.size < 3 || corners.any { !it.isFinite() } || Polygons.area(corners) < 1e-6) return null
            val centroid = Polygons.centroid(corners)
            val pole = Polygons.poleOfInaccessibility(corners, 0.02)
            val poleDistance = Polygons.signedDistanceToOutline(corners, pole)
            val centroidDistance = Polygons.signedDistanceToOutline(corners, centroid)
            return if (centroidDistance > 0 && centroidDistance >= 0.6 * poleDistance) centroid else pole
        }
    }
}

/** A face with its plane: unit [normal], offset [d] and in-plane axes [u], [v] (u × v = normal). */
internal class PlanarFace(val face: Face3, val points: List<Vec3>, val normal: Vec3, val d: Double, val u: Vec3, val v: Vec3) {
    /** The polygon in this face's in-plane coordinates; counter-clockwise. */
    val polygon2: List<Vec2> = points.map { Vec2(it dot u, it dot v) }

    fun to2(p: Vec3): Vec2 = Vec2(p dot u, p dot v)

    companion object {
        fun of(face: Face3): PlanarFace? {
            val pts = face.points
            if (pts.size < 3 || pts.any { !it.isFinite() }) return null
            val newell = newell(pts)
            val len = newell.length
            if (!(len > 1e-10)) return null
            val n = newell / len
            val helper = if (abs(n.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            val u = (helper - n * (helper dot n)).normalized()
            val v = n cross u
            return PlanarFace(face, pts, n, n dot pts[0], u, v)
        }

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
    }
}

/**
 * Which edges get a thin outline. A face edge is outlined unless the same surface carries on past it: where a
 * coplanar face of the same kind, facing the same way, lies on the far side of the edge (a seam between the
 * pieces a wall is split into around an opening, two rooms' collinear exterior walls, overlapping caps), the
 * line is left out, so walls read as one surface and only real creases and opening outlines are drawn.
 * Coincident duplicates (the same jamb built from both rooms of a shared wall) do not hide each other's edges.
 */
internal object OutlineEdges {
    /** How far past an edge the "does the surface continue" probe looks (meters). */
    private const val PROBE = 0.001
    private const val PLANE_TOLERANCE = 1e-5
    private const val NORMAL_TOLERANCE = 1e-6
    private const val MIN_SEGMENT = 0.001

    fun compute(faces: List<PlanarFace>): List<List<Pair<Vec3, Vec3>>> {
        val order = faces.indices.sortedBy { faces[it].d }
        val partners = Array(faces.size) { ArrayList<Int>() }
        for (a in order.indices) {
            val fa = faces[order[a]]
            var b = a + 1
            while (b < order.size && faces[order[b]].d - fa.d <= PLANE_TOLERANCE) {
                val fb = faces[order[b]]
                if (fa.face.kind == fb.face.kind && (fa.normal dot fb.normal) >= 1 - NORMAL_TOLERANCE) {
                    partners[order[a]] += order[b]
                    partners[order[b]] += order[a]
                }
                b++
            }
        }
        return faces.mapIndexed { i, f -> outline(f, partners[i].map { faces[it] }) }
    }

    private fun outline(f: PlanarFace, partners: List<PlanarFace>): List<Pair<Vec3, Vec3>> {
        val pts = f.points
        val out = ArrayList<Pair<Vec3, Vec3>>(pts.size)
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val length = a.distanceTo(b)
            if (length < MIN_SEGMENT) continue
            if (partners.isEmpty()) {
                out += a to b
                continue
            }
            val a2 = f.to2(a)
            val b2 = f.to2(b)
            val dir = (b2 - a2).normalized()
            // The polygon is counter-clockwise in its plane, so the outside of this edge is to its right.
            val outside = Vec2(dir.y, -dir.x) * PROBE
            val p0 = a2 + outside
            val p1 = b2 + outside
            val hidden = ArrayList<Pair<Double, Double>>()
            for (g in partners) hidden += insideIntervals(p0, p1, g.points.map { f.to2(it) })
            for ((s, e) in complement(hidden)) {
                if ((e - s) * length < MIN_SEGMENT) continue
                out += lerp(a, b, s) to lerp(a, b, e)
            }
        }
        return out
    }

    /** Parameter intervals (0..1) of segment [p0]-[p1] that lie inside [polygon]. */
    internal fun insideIntervals(p0: Vec2, p1: Vec2, polygon: List<Vec2>): List<Pair<Double, Double>> {
        val d = p1 - p0
        val cuts = ArrayList<Double>()
        cuts += 0.0
        cuts += 1.0
        for (k in polygon.indices) {
            val q0 = polygon[k]
            val q1 = polygon[(k + 1) % polygon.size]
            val e = q1 - q0
            val denom = d cross e
            if (abs(denom) < 1e-15) continue
            val w = q0 - p0
            val s = (w cross e) / denom
            val r = (w cross d) / denom
            if (s > 0.0 && s < 1.0 && r >= -1e-12 && r <= 1 + 1e-12) cuts += s
        }
        cuts.sort()
        val inside = ArrayList<Pair<Double, Double>>()
        for (k in 0 until cuts.size - 1) {
            val s = cuts[k]
            val e = cuts[k + 1]
            if (e - s < 1e-12) continue
            if (Polygons.contains(polygon, p0 + d * ((s + e) / 2))) {
                val last = inside.lastOrNull()
                if (last != null && abs(last.second - s) < 1e-12) inside[inside.lastIndex] = last.first to e else inside += s to e
            }
        }
        return inside
    }

    /** 0..1 minus the union of [intervals]. */
    private fun complement(intervals: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val sorted = intervals.sortedBy { it.first }
        val out = ArrayList<Pair<Double, Double>>()
        var cursor = 0.0
        for ((s, e) in sorted) {
            if (s > cursor) out += cursor to s
            cursor = max(cursor, e)
        }
        if (cursor < 1.0) out += cursor to 1.0
        return out
    }

    private fun lerp(a: Vec3, b: Vec3, t: Double) = a + (b - a) * t
}

/**
 * Colors of the dollhouse (the CubiCasa look): light warm-gray floors with a subtle tint per room type,
 * off-white inner walls, light-gray outer walls, near-black wall tops. Flat Lambert shading from a fixed
 * light up and to the side, normalized so up-facing faces keep their exact palette color.
 */
object DollhousePalette {
    const val INNER_WALL = 0xFFF6F5F2.toInt()
    const val OUTER_WALL = 0xFFD8DADD.toInt()
    const val WALL_TOP = 0xFF2A2C30.toInt()
    const val OPENING_FRAME = 0xFFE7E5E0.toInt()
    const val FLOOR = 0xFFE8E4DE.toInt()

    /** Thin outline drawn on creases and around openings. */
    const val OUTLINE = 0x5C34373C

    /** Light direction (unit, pointing toward the light) and the ambient / diffuse split of the shading. */
    val LIGHT: Vec3 = Vec3(-0.45, 0.75, 0.5).normalized()
    const val AMBIENT = 0.78
    const val DIFFUSE = 0.26

    fun floorColor(type: RoomType?): Int = when (type) {
        RoomType.LIVING, RoomType.FAMILY -> 0xFFEBE4D9.toInt()
        RoomType.KITCHEN, RoomType.DINING -> 0xFFEDE6D5.toInt()
        RoomType.BEDROOM, RoomType.PRIMARY_BEDROOM -> 0xFFEAE2DC.toInt()
        RoomType.BATHROOM, RoomType.HALF_BATH, RoomType.LAUNDRY -> 0xFFE0E7EB.toInt()
        RoomType.OFFICE -> 0xFFE5E7DF.toInt()
        RoomType.GARAGE -> 0xFFDEDEDC.toInt()
        RoomType.HALLWAY, RoomType.ENTRY, RoomType.STAIRS -> 0xFFE7E4DF.toInt()
        RoomType.CLOSET, RoomType.STORAGE, RoomType.UTILITY -> 0xFFE4E2DE.toInt()
        RoomType.OTHER, null -> FLOOR
    }

    fun baseColor(kind: FaceKind, roomType: RoomType?): Int = when (kind) {
        FaceKind.FLOOR -> floorColor(roomType)
        FaceKind.WALL_INNER -> INNER_WALL
        FaceKind.WALL_OUTER -> OUTER_WALL
        FaceKind.WALL_TOP -> WALL_TOP
        FaceKind.OPENING_FRAME -> OPENING_FRAME
    }

    /** Brightness factor for a face with unit [normal]: 1 for up-facing faces, darker as it turns from the light. */
    fun brightness(normal: Vec3): Double {
        val up = AMBIENT + DIFFUSE * max(0.0, LIGHT.y)
        return (AMBIENT + DIFFUSE * max(0.0, normal dot LIGHT)) / up
    }

    /** [argb] scaled by [brightness] of [normal] (alpha kept, channels clamped). */
    fun shade(argb: Int, normal: Vec3): Int {
        val k = brightness(normal).takeIf { it.isFinite() } ?: 1.0
        fun ch(shift: Int) = min(255, max(0, (((argb shr shift) and 0xFF) * k).roundToInt()))
        return (argb and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
