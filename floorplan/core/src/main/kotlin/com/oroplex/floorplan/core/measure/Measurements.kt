package com.oroplex.floorplan.core.measure

import com.oroplex.floorplan.core.geometry.OrientedBox
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room

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
    fun room(room: Room): RoomStats = TODO("implement")

    /** Sum of room interior (net) areas on the floor. */
    fun floorArea(floor: Floor): Double = TODO("implement")

    fun projectArea(project: Project): Double = TODO("implement")
}
