package com.oroplex.floorplan.ui.export

import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.solve.FloorIssue
import com.oroplex.floorplan.core.solve.IssueSeverity

/** One line of the "Before you export" card. */
data class CheckItem(
    val severity: IssueSeverity,
    val message: String,
    /** Set when the export covers several floors, so the user knows where to look. */
    val floorName: String?,
    val floorId: String,
    val roomId: String?,
) {
    /** What the card prints: "Upper Floor: message" for multi-floor exports. */
    val text: String get() = if (floorName != null) "$floorName: $message" else message
}

/**
 * Result of the pre-export checks ([com.oroplex.floorplan.core.solve.FloorChecks]) for the floors an
 * export covers, most severe first.
 */
data class PreExportReport(val items: List<CheckItem>) {
    val errors: List<CheckItem> get() = items.filter { it.severity == IssueSeverity.ERROR }
    val warningCount: Int get() = items.count { it.severity == IssueSeverity.WARNING }
    val hasErrors: Boolean get() = items.any { it.severity == IssueSeverity.ERROR }
    val isClean: Boolean get() = items.isEmpty()

    /**
     * Identifies the current set of errors. "Export anyway" acknowledges exactly this set: when the plan
     * changes and a different error appears, the user is asked again. Empty when there are no errors.
     */
    val errorKey: String
        get() = errors.joinToString(separator = "\n") { "${it.floorId}|${it.roomId.orEmpty()}|${it.message}" }

    companion object {
        val EMPTY = PreExportReport(emptyList())
    }
}

/** Whether Share / Save may go ahead or must first ask "Export anyway?". */
sealed interface ExportGate {
    data object Clear : ExportGate

    /** [errors] are unresolved and not yet acknowledged. */
    data class NeedsConfirmation(val errors: List<CheckItem>) : ExportGate
}

object PreExportChecks {
    /** Shown in place of a floor's results when its checks could not run. */
    fun checkFailedMessage(floorName: String): String =
        "Couldn't check “$floorName”. Review it in the editor before sending."

    /**
     * Runs [checker] on every floor in [floors] and merges the results: sorted by severity (errors
     * first), then by floor order, keeping each checker's own order within a floor. A checker that
     * throws for one floor yields a WARNING for that floor instead of failing the whole report.
     * Floors without rooms are skipped (nothing to check; the export screen already explains them).
     */
    fun build(
        floors: List<Floor>,
        settings: PlanSettings,
        checker: (Floor, PlanSettings) -> List<FloorIssue>,
        onCheckFailed: (Floor, Exception) -> Unit = { _, _ -> },
    ): PreExportReport {
        val multiFloor = floors.size > 1
        val items = ArrayList<Pair<Int, CheckItem>>() // (floor index, item) for a stable sort
        floors.forEachIndexed { index, floor ->
            if (floor.rooms.isEmpty()) return@forEachIndexed
            val floorName = floor.name.trim().ifEmpty { "Floor" }
            val label = if (multiFloor) floorName else null
            val issues = try {
                checker(floor, settings)
            } catch (e: Exception) {
                onCheckFailed(floor, e)
                listOf(FloorIssue(IssueSeverity.WARNING, null, checkFailedMessage(floorName)))
            }
            issues
                .filter { it.message.isNotBlank() }
                .distinct()
                .forEach { issue ->
                    val roomId = issue.roomId?.takeIf { id -> floor.rooms.any { it.id == id } }
                    items += index to CheckItem(issue.severity, issue.message.trim(), label, floor.id, roomId)
                }
        }
        val sorted = items.sortedWith(compareBy<Pair<Int, CheckItem>>({ it.second.severity.ordinal }, { it.first }))
        return PreExportReport(sorted.map { it.second })
    }

    /** Export may proceed when there are no errors or the user acknowledged exactly these errors. */
    fun gate(report: PreExportReport, acknowledgedErrorKey: String?): ExportGate =
        if (!report.hasErrors || report.errorKey == acknowledgedErrorKey) {
            ExportGate.Clear
        } else {
            ExportGate.NeedsConfirmation(report.errors)
        }

    /** One-line summary for the card's header, e.g. "2 problems to fix · 1 thing to review". */
    fun summary(report: PreExportReport): String {
        if (report.isClean) return "No problems found. Ready to export."
        val errors = report.errors.size
        val warnings = report.warningCount
        val notes = report.items.size - errors - warnings
        val parts = buildList {
            if (errors > 0) add(if (errors == 1) "1 problem to fix" else "$errors problems to fix")
            if (warnings > 0) add(if (warnings == 1) "1 thing to review" else "$warnings things to review")
            if (notes > 0) add(if (notes == 1) "1 note" else "$notes notes")
        }
        return parts.joinToString(" · ")
    }
}
