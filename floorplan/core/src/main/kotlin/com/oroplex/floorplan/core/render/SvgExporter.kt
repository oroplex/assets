package com.oroplex.floorplan.core.render

object SvgExporter {
    /**
     * Standalone SVG document of [drawList] fitted into a widthPt x heightPt page with [marginPt] margins
     * (1 SVG user unit = 1 pt). Optional [title] is drawn at the top-left and in <title>.
     * Text must be XML-escaped. Output must be valid, well-formed XML.
     */
    fun export(drawList: DrawList, widthPt: Double, heightPt: Double, marginPt: Double = 36.0, title: String? = null): String = TODO("implement")
}
