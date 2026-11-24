package com.oroplex.floorplan.ui.export

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oroplex.floorplan.core.render.PlanColors
import com.oroplex.floorplan.core.render.ViewTransform
import com.oroplex.floorplan.export.FloorSheet
import com.oroplex.floorplan.export.PlanSheetPainter
import com.oroplex.floorplan.export.ProjectExporter
import com.oroplex.floorplan.render.PlanPainter
import kotlin.math.min

/** Thumbnail of what will be exported: the first page / image for PDF and PNG, the drawing for SVG / DXF. */
@Composable
internal fun ExportPreviewCard(preview: ExportPreview, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (preview) {
                    ExportPreview.Loading -> CircularProgressIndicator(Modifier.size(32.dp))
                    is ExportPreview.Message -> Text(
                        preview.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    is ExportPreview.Sheet -> SheetCanvas(preview.sheet, preview.widthPt, preview.heightPt)
                    is ExportPreview.Plan -> PlanCanvas(preview)
                }
            }
            val caption = when (preview) {
                is ExportPreview.Sheet -> preview.caption
                is ExportPreview.Plan -> preview.caption
                else -> null
            }
            if (caption != null) {
                Text(
                    caption,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val PageShadow = Color(0x24000000)
private val PageBorder = Color(0x1F000000)

/** The sheet drawn as a paper page scaled to fit, exactly as the exporter lays it out. */
@Composable
private fun SheetCanvas(sheet: FloorSheet, widthPt: Double, heightPt: Double) {
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Preview of ${sheet.floorName}" },
    ) {
        if (widthPt <= 0 || heightPt <= 0) return@Canvas
        val scale = min(size.width / widthPt, size.height / heightPt)
        val pageW = (widthPt * scale).toFloat()
        val pageH = (heightPt * scale).toFloat()
        val origin = Offset((size.width - pageW) / 2, (size.height - pageH) / 2)
        drawPage(origin, Size(pageW, pageH))
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(origin.x, origin.y)
            native.clipRect(0f, 0f, pageW, pageH)
            PlanSheetPainter.draw(native, sheet, widthPt, heightPt, scale)
            native.restore()
        }
    }
}

/** Plan-only thumbnail on a white page shaped like the exported drawing. */
@Composable
private fun PlanCanvas(preview: ExportPreview.Plan) {
    Canvas(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Plan preview" },
    ) {
        val bounds = preview.drawList.bounds
        val aspect = if (bounds.width > 1e-9) (bounds.height / bounds.width).coerceIn(0.25, 4.0) else 0.75
        val pageW = min(size.width.toDouble(), size.height / aspect)
        val pageH = pageW * aspect
        val origin = Offset(((size.width - pageW) / 2).toFloat(), ((size.height - pageH) / 2).toFloat())
        drawPage(origin, Size(pageW.toFloat(), pageH.toFloat()))
        // Same proportions as the exported SVG page: text sizes relative to a 1000 pt wide drawing.
        val pxPerPt = pageW / ProjectExporter.SVG_WIDTH_PT
        val view = ViewTransform.fit(bounds, pageW, pageH, ProjectExporter.SVG_MARGIN_PT * pxPerPt, pxPerPt)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(origin.x, origin.y)
            native.clipRect(0f, 0f, pageW.toFloat(), pageH.toFloat())
            PlanPainter.draw(native, preview.drawList, view)
            native.restore()
        }
    }
}

private fun DrawScope.drawPage(origin: Offset, pageSize: Size) {
    drawRect(PageShadow, topLeft = origin + Offset(1.5f, 2.5f), size = pageSize)
    drawRect(Color(PlanColors.BACKGROUND), topLeft = origin, size = pageSize)
    drawRect(
        PageBorder,
        topLeft = origin,
        size = pageSize,
        style = Stroke(width = 1f),
    )
}
