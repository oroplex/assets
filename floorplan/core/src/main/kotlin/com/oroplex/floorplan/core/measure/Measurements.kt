package com.oroplex.floorplan.core.measure

import com.oroplex.floorplan.core.geometry.OrientedBox
import com.oroplex.floorplan.core.geometry.Polygons
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Opening
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import kotlin.math.max
import kotlin.math.min

data class RoomStats(
    val area: Double,
    val perimeter: Double,
    /** Oriented bounding box length/width (length >= width), what plans print as "L × W". */
    val length: Double,
    val width: Double,
    val box: OrientedBox,
    val ceilingHeight: Double?,
    val volume: Double?,
    /** Length of each edge i (corners[i] -> corners[i+1]). */
    val wallLengths: List<Double>,
    /** Wall surface area (perimeter * height minus openings), null without ceiling height. */
    val wallArea: Double?,
)

object Measurements {
    /**
     * Statistics of one room's interior outline. Robust to bad data: non-finite corners are ignored, a
     * room with fewer than 3 corners has zero area and an empty box, and a non-positive / non-finite
     * ceiling height counts as unknown (volume and wall area null).
     *
     * wallArea = perimeter * ceiling - sum(width * height) over openings on existing edges, where an
     * opening's height is limited to the wall above its sill (so bad data can never subtract more than the
     * wall itself) and the total is never negative.
     */
    fun room(room: Room): RoomStats {
        val pts = if (room.corners.all { it.isFinite() }) room.corners else room.corners.filter { it.isFinite() }
        val area = if (pts.size >= 3) Polygons.area(pts) else 0.0
        val wallLengths = Polygons.edges(pts).map { it.length }
        val perimeter = if (pts.size >= 3) wallLengths.sum() else 0.0
        val box = if (pts.isEmpty()) OrientedBox(Vec2.ZERO, 0.0, 0.0, 0.0) else Polygons.orientedBoundingBox(pts)
        val ceiling = room.ceilingHeight?.takeIf { it.isFinite() && it > 0.0 }
        val wallArea = ceiling?.let { h ->
            val openings = room.openings.filter { it.wallIndex in pts.indices }.sumOf { openingArea(it, h) }
            max(0.0, perimeter * h - openings)
        }
        return RoomStats(
            area = area,
            perimeter = perimeter,
            length = box.length,
            width = box.width,
            box = box,
            ceilingHeight = ceiling,
            volume = ceiling?.let { area * it },
            wallLengths = wallLengths,
            wallArea = wallArea,
        )
    }

    /** Sum of room interior (net) areas on the floor. */
    fun floorArea(floor: Floor): Double = floor.rooms.sumOf { room(it).area }

    /** Sum of the net areas of all floors. */
    fun projectArea(project: Project): Double = project.floors.sumOf { floorArea(it) }

    private fun openingArea(o: Opening, ceiling: Double): Double {
        val width = if (o.width.isFinite()) max(o.width, 0.0) else 0.0
        val sill = if (o.sillHeight.isFinite()) max(o.sillHeight, 0.0) else 0.0
        val height = if (o.height.isFinite()) min(max(o.height, 0.0), max(ceiling - sill, 0.0)) else 0.0
        return width * height
    }
}
