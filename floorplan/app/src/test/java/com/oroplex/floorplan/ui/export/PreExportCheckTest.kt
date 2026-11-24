package com.oroplex.floorplan.ui.export

import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.IssueSeverity
import com.oroplex.floorplan.export.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreExportCheckTest {

    private fun room(id: String) = Room(
        id = id,
        name = "Room $id",
        corners = listOf(Vec2(0.0, 0.0), Vec2(3.0, 0.0), Vec2(3.0, 3.0), Vec2(0.0, 3.0)),
    )

    private val main = Floor("f1", "Main", rooms = listOf(room("a"), room("b")))
    private val upper = Floor("f2", "Upper", rooms = listOf(room("c")))
    private val settings = PlanSettings()

    private val overlap = FloorIssue(IssueSeverity.ERROR, "a", "Room a overlaps Room b. Move it into place.")
    private val warning = FloorIssue(IssueSeverity.WARNING, "b", "Room b: a wall seems to be missing.")
    private val info = FloorIssue(IssueSeverity.INFO, null, "Floor isn't tape-calibrated.")

    private fun checker(byFloor: Map<String, List<FloorIssue>>): (Floor, PlanSettings) -> List<FloorIssue> =
        { floor, _ -> byFloor[floor.id].orEmpty() }

    @Test
    fun `single floor items carry no floor prefix and keep the checker's order within a severity`() {
        val second = FloorIssue(IssueSeverity.WARNING, null, "Second warning")
        val report = PreExportChecks.build(listOf(main), settings, checker(mapOf("f1" to listOf(info, warning, overlap, second))))
        assertEquals(listOf(IssueSeverity.ERROR, IssueSeverity.WARNING, IssueSeverity.WARNING, IssueSeverity.INFO), report.items.map { it.severity })
        assertEquals(listOf(overlap.message, warning.message, "Second warning", info.message), report.items.map { it.text })
        assertTrue(report.items.all { it.floorName == null })
        assertEquals("a", report.items.first().roomId)
    }

    @Test
    fun `several floors are prefixed and sorted by severity, then floor`() {
        val report = PreExportChecks.build(
            listOf(main, upper),
            settings,
            checker(mapOf("f1" to listOf(info), "f2" to listOf(overlap.copy(roomId = "c"), info))),
        )
        assertEquals(
            listOf("Upper: ${overlap.message}", "Main: ${info.message}", "Upper: ${info.message}"),
            report.items.map { it.text },
        )
        assertEquals(listOf("f2", "f1", "f2"), report.items.map { it.floorId })
    }

    @Test
    fun `unknown room ids, blank messages, duplicates and empty floors are dropped`() {
        val stranger = FloorIssue(IssueSeverity.WARNING, "nope", "Something")
        val blank = FloorIssue(IssueSeverity.ERROR, null, "  ")
        val empty = Floor("f3", "Attic")
        var emptyChecked = false
        val report = PreExportChecks.build(listOf(main, empty), settings, { floor, _ ->
            if (floor.id == "f3") emptyChecked = true
            listOf(stranger, stranger, blank)
        })
        assertFalse(emptyChecked)
        assertEquals(1, report.items.size)
        assertNull(report.items.single().roomId)
        assertFalse(report.hasErrors)
    }

    @Test
    fun `a checker that throws becomes a warning for that floor only`() {
        var failed: Floor? = null
        val report = PreExportChecks.build(
            listOf(main, upper),
            settings,
            { floor, _ -> if (floor.id == "f1") throw IllegalStateException("bug") else listOf(info) },
            onCheckFailed = { floor, _ -> failed = floor },
        )
        assertEquals(main, failed)
        assertEquals(2, report.items.size)
        assertEquals(IssueSeverity.WARNING, report.items[0].severity)
        assertEquals("Main: ${PreExportChecks.checkFailedMessage("Main")}", report.items[0].text)
    }

    @Test
    fun `errors gate the export until exactly these errors are acknowledged`() {
        val report = PreExportChecks.build(listOf(main), settings, checker(mapOf("f1" to listOf(overlap, warning))))
        assertTrue(report.hasErrors)
        val gate = PreExportChecks.gate(report, acknowledgedErrorKey = null)
        assertTrue(gate is ExportGate.NeedsConfirmation)
        assertEquals(listOf(overlap.message), (gate as ExportGate.NeedsConfirmation).errors.map { it.message })

        assertEquals(ExportGate.Clear, PreExportChecks.gate(report, report.errorKey))

        // A different error after an edit asks again.
        val changed = PreExportChecks.build(
            listOf(main),
            settings,
            checker(mapOf("f1" to listOf(overlap.copy(message = "Room a overlaps Room c.")))),
        )
        assertTrue(PreExportChecks.gate(changed, report.errorKey) is ExportGate.NeedsConfirmation)
    }

    @Test
    fun `warnings and notes never gate the export`() {
        val report = PreExportChecks.build(listOf(main), settings, checker(mapOf("f1" to listOf(warning, info))))
        assertEquals("", report.errorKey)
        assertEquals(ExportGate.Clear, PreExportChecks.gate(report, null))
        assertEquals(ExportGate.Clear, PreExportChecks.gate(PreExportReport.EMPTY, "stale"))
    }

    @Test
    fun `summary counts problems, things to review and notes`() {
        assertEquals("No problems found. Ready to export.", PreExportChecks.summary(PreExportReport.EMPTY))
        val report = PreExportChecks.build(
            listOf(main),
            settings,
            checker(mapOf("f1" to listOf(overlap, overlap.copy(roomId = "b", message = "Other"), warning, info))),
        )
        assertEquals("2 problems to fix · 1 thing to review · 1 note", PreExportChecks.summary(report))
        val one = PreExportChecks.build(listOf(main), settings, checker(mapOf("f1" to listOf(overlap))))
        assertEquals("1 problem to fix", PreExportChecks.summary(one))
    }

    @Test
    fun `out of memory advice matches the format`() {
        assertTrue(ExportViewModel.outOfMemoryMessage(ExportFormat.PDF).contains("one floor at a time"))
        assertFalse(ExportViewModel.outOfMemoryMessage(ExportFormat.PDF).contains("export a PDF"))
        assertTrue(ExportViewModel.outOfMemoryMessage(ExportFormat.PNG).contains("PDF or SVG"))
        assertTrue(ExportViewModel.outOfMemoryMessage(null).startsWith("Not enough memory"))
    }

    @Test
    fun `overall dimensions follow the project setting unless chosen`() {
        val project = Project(id = "p", name = "P", createdAt = 0, updatedAt = 0, floors = listOf(main))
        assertTrue(ExportSelection().resolve(project).showOverallDimensions)
        val off = project.copy(settings = PlanSettings(showOverallDimensions = false))
        assertFalse(ExportSelection().resolve(off).showOverallDimensions)
        assertTrue(ExportSelection(showOverallDimensions = true).resolve(off).showOverallDimensions)
    }
}
