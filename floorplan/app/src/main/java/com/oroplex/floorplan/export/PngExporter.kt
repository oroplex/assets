package com.oroplex.floorplan.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.oroplex.floorplan.core.render.PlanColors
import java.io.IOException
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * High-resolution PNG of one floor sheet: [WIDTH_PX] wide, white background, the same title block,
 * plan, scale bar and footer as the PDF. The sheet is laid out as if it were [SHEET_WIDTH_PT] points
 * wide (a landscape Letter page), so text and line weights match the PDF proportionally.
 */
object PngExporter {
    const val WIDTH_PX = 3000
    const val SHEET_WIDTH_PT = 792.0

    /** Bitmap pixels per sheet point. */
    const val PX_PER_PT = WIDTH_PX / SHEET_WIDTH_PT

    /** Pixel size of the image [sheet] produces (width, height). */
    fun sizeFor(sheet: FloorSheet): Pair<Int, Int> {
        val heightPt = SheetLayout.pngSheetHeight(SHEET_WIDTH_PT, sheet.drawList?.bounds)
        return WIDTH_PX to (heightPt * PX_PER_PT).roundToInt().coerceAtLeast(1)
    }

    /** Draws and encodes the image to [out]. Blocking; throws [ExportException] when memory runs out. */
    fun write(sheet: FloorSheet, out: OutputStream) {
        val (width, height) = sizeFor(sheet)
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw ExportException("Not enough memory to create the image. Close other apps or export a PDF instead.", e)
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(PlanColors.BACKGROUND)
            PlanSheetPainter.draw(canvas, sheet, SHEET_WIDTH_PT, height / PX_PER_PT, PX_PER_PT)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encoding failed")
        } finally {
            bitmap.recycle()
        }
    }
}
