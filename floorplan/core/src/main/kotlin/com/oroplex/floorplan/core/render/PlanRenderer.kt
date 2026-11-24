package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room

data class RenderOptions(
    val showRoomNames: Boolean = true,
    val showRoomDimensions: Boolean = true,
    /** null = follow PlanSettings.showRoomAreas */
    val showAreas: Boolean? = null,
    /** null = follow PlanSettings.showWallLengths */
    val showWallLengths: Boolean? = null,
    val selectedRoomId: String? = null,
    /** Show draggable corner handles for the selected room (editor). */
    val showCornerHandles: Boolean = false,
    val selectedWall: Int? = null,
    /** Base text size multiplier (PDF may use 0.8, phone screen 1.0). */
    val textScale: Double = 1.0,
)

object PlanRenderer {
    /**
     * Render rooms to a DrawList, CubiCasa-style: dark wall bands of [PlanSettings.wallThickness] drawn
     * OUTSIDE each room's interior outline (all bands first, then all room fills, so shared walls merge),
     * openings cut into the band (doors: gap + leaf line + swing arc; windows: gap + thin triple line;
     * openings: gap), room label at the pole of inaccessibility (name, "L × W", area), optional wall
     * lengths along each interior edge, selection highlight and corner handles.
     * DrawOp tags: "room:<id>" on room fills, "wall:<roomId>:<edgeIndex>" on wall-length labels,
     * "corner:<roomId>:<index>" on handles. Bounds include wall bands and labels' anchors.
     */
    fun render(rooms: List<Room>, settings: PlanSettings, options: RenderOptions = RenderOptions()): DrawList = TODO("implement")
}
