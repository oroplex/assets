package com.oroplex.floorplan.render

import android.graphics.Canvas
import com.oroplex.floorplan.core.render.DrawList
import com.oroplex.floorplan.core.render.ViewTransform

/**
 * Draws a core DrawList onto an android.graphics.Canvas (screen via Compose nativeCanvas, PDF pages,
 * bitmaps). Shared by the editor, the capture review preview, and PDF/PNG export so they look identical.
 */
object PlanPainter {
    fun draw(canvas: Canvas, drawList: DrawList, view: ViewTransform, background: Int? = null): Unit = TODO("implement")
}
