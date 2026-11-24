package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Room

object DxfExporter {
    /**
     * ASCII DXF (AutoCAD R12 / AC1009, maximally compatible) of the rooms, in the plan frame (y up).
     * Units: inches when settings.unitSystem == IMPERIAL, millimeters when METRIC ($INSUNITS 1 / 4 in header).
     * Layers: ROOMS (closed interior outlines), WALLS (closed outer outlines offset by wall thickness),
     * DOORS (leaf LINE + swing ARC), WINDOWS (LINEs), TEXT (room name / dims / area TEXT entities).
     */
    fun export(rooms: List<Room>, settings: PlanSettings): String = TODO("implement")
}
