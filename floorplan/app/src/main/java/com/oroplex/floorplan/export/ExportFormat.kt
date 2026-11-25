package com.oroplex.floorplan.export

import com.oroplex.floorplan.core.model.UnitSystem

/** Every output the export screen can produce. */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val description: String,
    /** True when one file can hold several floors (PDF pages, the JSON backup). */
    val supportsAllFloors: Boolean,
    /** True when the output is a drawing of a single floor (needs at least one room to be useful). */
    val isDrawing: Boolean,
) {
    PDF(
        label = "PDF document",
        extension = "pdf",
        mimeType = "application/pdf",
        description = "Print-ready pages with title block, scale bar and room schedule",
        supportsAllFloors = true,
        isDrawing = false,
    ),
    PNG(
        label = "PNG image",
        extension = "png",
        mimeType = "image/png",
        description = "High-resolution picture (3000 px wide) for messages and listings",
        supportsAllFloors = false,
        isDrawing = false,
    ),
    SVG(
        label = "SVG vector",
        extension = "svg",
        mimeType = "image/svg+xml",
        description = "Scalable drawing for web pages and design tools",
        supportsAllFloors = false,
        isDrawing = true,
    ),
    DXF(
        label = "DXF (CAD)",
        extension = "dxf",
        mimeType = "application/dxf",
        description = "AutoCAD R12 drawing with layers for walls, doors, windows, text and dimensions",
        supportsAllFloors = false,
        isDrawing = true,
    ),
    JSON(
        label = "JSON backup",
        extension = "json",
        mimeType = "application/json",
        description = "Complete project data that FloorPlan Scan can import again",
        supportsAllFloors = true,
        isDrawing = false,
    ),
}

/** Paper sizes in PDF points (1/72 in), portrait orientation. */
enum class PaperSize(val label: String, val widthPt: Int, val heightPt: Int) {
    LETTER("Letter", 612, 792),
    A4("A4", 595, 842),
    ;

    fun widthPt(landscape: Boolean): Int = if (landscape) heightPt else widthPt

    fun heightPt(landscape: Boolean): Int = if (landscape) widthPt else heightPt

    companion object {
        /** Countries (ISO 3166 alpha-2) whose everyday paper is US Letter; everywhere else it is A4. */
        private val LETTER_COUNTRIES = setOf("US", "CA")

        /**
         * The paper a PDF starts with: Letter in the US and Canada, A4 in every other country. Without a
         * usable [country] (a locale such as plain "en") it follows the project's units instead
         * (feet and inches: Letter, metric: A4).
         */
        fun defaultFor(unitSystem: UnitSystem, country: String): PaperSize {
            val code = country.trim().uppercase()
            return when {
                code in LETTER_COUNTRIES -> LETTER
                code.length == 2 && code.all { it in 'A'..'Z' } -> A4
                else -> if (unitSystem == UnitSystem.METRIC) A4 else LETTER
            }
        }
    }
}

/**
 * Fully resolved export job.
 *
 * [floorId] null means "all floors"; it is only honored by formats with [ExportFormat.supportsAllFloors].
 * Single-floor formats fall back to the project's first floor (see [ProjectExporter.floorsFor]).
 */
data class ExportRequest(
    val format: ExportFormat,
    val floorId: String?,
    val paper: PaperSize = PaperSize.LETTER,
    val showWallLengths: Boolean = true,
    val showAreas: Boolean = true,
    val includeRoomSchedule: Boolean = true,
    /**
     * Overall width / depth dimension lines around the plan. Every drawing honours it: PDF, PNG and SVG draw
     * the lines, DXF writes them on its DIMENSIONS layer.
     */
    val showOverallDimensions: Boolean = true,
) {
    /**
     * The annotations the exported drawing really carries, which is also what its preview must show.
     * DXF has room labels (with areas when [showAreas]) and the DIMENSIONS layer, but never per-wall length
     * labels, whatever [showWallLengths] says.
     */
    val labels: PlanLabels
        get() = PlanLabels(
            showWallLengths = showWallLengths && format != ExportFormat.DXF,
            showAreas = showAreas,
            showOverallDimensions = showOverallDimensions,
        )
}

/** Which annotations a drawn plan carries. */
data class PlanLabels(
    val showWallLengths: Boolean = true,
    val showAreas: Boolean = true,
    val showOverallDimensions: Boolean = true,
)

/** An export failure whose [message] is written for the user (shown verbatim in a snackbar). */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)
