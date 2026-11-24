package com.oroplex.floorplan.export

import android.content.Context
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.render.DxfExporter
import com.oroplex.floorplan.core.render.SvgExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** A finished export in the share cache. */
data class ExportedFile(val file: File, val format: ExportFormat)

/**
 * Turns a [Project] + [ExportRequest] into a file in `cacheDir/exports/`.
 * Rendering runs on [Dispatchers.Default], drawing/encoding/writing on [Dispatchers.IO].
 *
 * @param encodeJson produces the JSON backup (the repository's `exportJson`).
 */
class ProjectExporter(
    context: Context,
    private val encodeJson: (Project) -> String,
) {
    private val appContext = context.applicationContext

    /** Throws [ExportException] with a user-facing message for anything the user can fix. */
    suspend fun export(project: Project, request: ExportRequest, now: Long = System.currentTimeMillis()): ExportedFile {
        val name = fileName(project, request)
        val payload = withContext(Dispatchers.Default) { prepare(project, request, now) }
        val file = withContext(Dispatchers.IO) { ExportFiles.write(appContext, name) { out -> payload.writeTo(out) } }
        return ExportedFile(file, request.format)
    }

    /** Prepared output: all CPU work (rendering, text generation) is done; only drawing/encoding is left. */
    private sealed interface Payload {
        fun writeTo(out: OutputStream)

        class Bytes(private val bytes: ByteArray) : Payload {
            override fun writeTo(out: OutputStream) = out.write(bytes)
        }

        class Pdf(private val prepared: PdfExporter.Prepared) : Payload {
            override fun writeTo(out: OutputStream) = PdfExporter.write(prepared, out)
        }

        class Png(private val sheet: FloorSheet) : Payload {
            override fun writeTo(out: OutputStream) = PngExporter.write(sheet, out)
        }
    }

    private fun prepare(project: Project, request: ExportRequest, now: Long): Payload {
        if (request.format == ExportFormat.JSON) return Payload.Bytes(encodeJson(project).toByteArray(Charsets.UTF_8))
        val floors = floorsFor(project, request)
        if (floors.isEmpty()) throw ExportException("This project has no floors yet. Add a floor in the editor first.")
        val dateText = FloorSheets.dateText(now)
        return when (request.format) {
            ExportFormat.PDF -> {
                val options = PdfOptions(request.paper, request.showWallLengths, request.showAreas, request.includeRoomSchedule)
                Payload.Pdf(PdfExporter.prepare(project, floors, options, dateText))
            }
            ExportFormat.PNG -> Payload.Png(
                FloorSheets.build(project, floors.first(), request.showWallLengths, request.showAreas, dateText),
            )
            ExportFormat.SVG -> Payload.Bytes(svgFor(project, requireRooms(floors.first()), request).toByteArray(Charsets.UTF_8))
            ExportFormat.DXF -> {
                val floor = requireRooms(floors.first())
                val dxf = try {
                    DxfExporter.export(floor.rooms, project.settings)
                } catch (e: Exception) {
                    throw ExportException("Couldn't create the DXF drawing of \u201C${floor.name}\u201D.", e)
                }
                Payload.Bytes(dxf.toByteArray(Charsets.UTF_8))
            }
            ExportFormat.JSON -> error("JSON is handled above")
        }
    }

    private fun requireRooms(floor: Floor): Floor {
        if (floor.rooms.isEmpty()) throw ExportException(emptyFloorMessage(floor))
        return floor
    }

    private fun svgFor(project: Project, floor: Floor, request: ExportRequest): String {
        val drawList = FloorSheets.renderFloor(
            project,
            floor,
            request.showWallLengths,
            request.showAreas,
            textScale = 1.0,
        ) ?: throw ExportException(emptyFloorMessage(floor))
        val b = drawList.bounds
        val aspect = if (b.width > 1e-9) (b.height / b.width).coerceIn(0.25, 4.0) else 0.75
        val heightPt = (SVG_WIDTH_PT - 2 * SVG_MARGIN_PT) * aspect + 2 * SVG_MARGIN_PT + SVG_TITLE_SPACE_PT
        val title = "${project.name.ifBlank { "Floor plan" }} — ${floor.name}"
        return try {
            SvgExporter.export(drawList, SVG_WIDTH_PT, heightPt, SVG_MARGIN_PT, title)
        } catch (e: Exception) {
            throw ExportException("Couldn't create the SVG drawing of “${floor.name}”.", e)
        }
    }

    companion object {
        const val SVG_WIDTH_PT = 1000.0
        const val SVG_MARGIN_PT = 36.0

        /** Extra page height so the title drawn at the top-left does not crowd the plan. */
        const val SVG_TITLE_SPACE_PT = 28.0

        fun emptyFloorMessage(floor: Floor): String =
            "“${floor.name}” has no rooms yet. Scan or draw a room first, or export a PDF."

        /**
         * The floors [request] covers: the requested floor when it exists; otherwise all floors for
         * formats that hold several, or the first floor for single-floor formats.
         */
        fun floorsFor(project: Project, request: ExportRequest): List<Floor> {
            if (project.floors.isEmpty()) return emptyList()
            val chosen = request.floorId?.let { id -> project.floors.firstOrNull { it.id == id } }
            return when {
                chosen != null -> listOf(chosen)
                request.format.supportsAllFloors -> project.floors
                else -> listOf(project.floors.first())
            }
        }

        /** `<project>-<floor>.<ext>`, `<project>-All_floors.pdf`, or `<project>.json`. */
        fun fileName(project: Project, request: ExportRequest): String {
            val ext = request.format.extension
            if (request.format == ExportFormat.JSON) return ExportFileNames.fileName(project.name, null, ext)
            val floors = floorsFor(project, request)
            val floorName = when (floors.size) {
                0 -> null
                1 -> floors.first().name
                else -> "All floors"
            }
            return ExportFileNames.fileName(project.name, floorName, ext)
        }
    }
}
