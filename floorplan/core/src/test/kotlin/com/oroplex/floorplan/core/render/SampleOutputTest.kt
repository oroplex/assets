package com.oroplex.floorplan.core.render

import com.oroplex.floorplan.core.io.SampleProjects
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Writes the sample house as SVG (US Letter landscape) and DXF to core/build/sample-output/ for visual QA
 * (open the SVG in a browser, the DXF in any CAD viewer). Gradle runs tests with the module directory as
 * the working directory.
 */
class SampleOutputTest {
    @Test fun writeSamplePlanFiles() {
        val project = SampleProjects.demo(1_700_000_000_000L)
        val floor = project.floors.first()
        val dir = File("build/sample-output").apply { mkdirs() }
        val drawList = PlanRenderer.render(floor.rooms, project.settings, RenderOptions(textScale = 0.8))
        val svg = SvgExporter.export(drawList, 792.0, 612.0, 36.0, title = "${project.name} — ${floor.name}")
        val dxf = DxfExporter.export(floor.rooms, project.settings)
        val svgFile = File(dir, "sample-plan.svg").apply { writeText(svg) }
        val dxfFile = File(dir, "sample-plan.dxf").apply { writeText(dxf) }
        assertTrue(svgFile.length() > 1000 && dxfFile.length() > 1000)
    }
}
