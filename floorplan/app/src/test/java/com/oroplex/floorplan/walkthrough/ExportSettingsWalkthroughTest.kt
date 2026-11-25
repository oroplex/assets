package com.oroplex.floorplan.walkthrough

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import com.oroplex.floorplan.FloorPlanApplication
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.io.SampleProjects
import com.oroplex.floorplan.core.model.Floor
import com.oroplex.floorplan.core.model.Project
import com.oroplex.floorplan.core.model.Room
import com.oroplex.floorplan.core.model.RoomType
import com.oroplex.floorplan.core.model.UnitSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Walks through the screens of the export / settings / onboarding / 3D package and saves a screenshot of
 * each state under app/build/ui-walkthrough (groups `x-export`, `x-preflight`, `x-settings`, `x-onboarding`,
 * `x-view3d`, `x-metric`, `x-dark`): the export screen with every format selected and its options, the
 * pre-flight card with tappable rows (and where a tap leads), the settings sections, the units question of
 * the welcome tour, and the 3D view. Any crash fails the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = WALKTHROUGH_PHONE)
class ExportSettingsWalkthroughTest : WalkthroughBase() {

    private fun scrollToTop() {
        repeat(4) {
            rule.onAllNodes(isRoot()).onFirst().performTouchInput {
                swipeDown(startY = height * 0.35f, endY = height * 0.8f, durationMillis = 300)
            }
            rule.waitForIdle()
        }
    }

    /** Scrolls a lazy list down until a node labelled [label] is composed (it need not be fully visible yet). */
    private fun scrollUntil(label: String) {
        var tries = 0
        while (!exists(label) && tries++ < 8) scrollDown()
        await(label)
    }

    /**
     * Selects an export format. The format rows sit in a lazy list, so the list is scrolled until the row
     * exists; the click then goes through the row's own semantics action, which does not depend on where the
     * row is on screen (it may be under the bottom bar).
     */
    private fun selectFormat(label: String) {
        scrollToTop()
        scrollUntil(label)
        withTextDialog { tap(label) }
        scrollToTop()
    }

    /** Selects a floor chip the same way (the chips are below the pre-flight card). */
    private fun selectFloor(chip: String) {
        scrollToTop()
        scrollUntil(chip)
        withTextDialog { tap(chip) }
        scrollToTop()
    }

    /** Selects [format], waits for its preview ([captionPart] is in the caption), and shoots top and options. */
    private fun shootFormat(group: String, name: String, format: String, captionPart: String, optionShots: Int = 2) {
        selectFormat(format)
        awaitTextContaining(captionPart)
        shoot(group, "$name-preview")
        repeat(optionShots) {
            scrollDown()
            scrollDown()
            shoot(group, "$name-options-${it + 1}")
        }
    }

    /**
     * A project that exercises the pre-flight card and the multi-floor export: a main floor with a garage
     * (not living area), a room overlapping another (a problem with a "snap" fix) and an estimated ceiling;
     * an upper floor turned 6° (a floor-wide note with a "straighten" fix); and an attic without rooms.
     */
    private fun saveProblemHouse(): Project {
        val sample = SampleProjects.demo(0L)
        val main = sample.floors.first()
        fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1))
        val garage = Room(id = "garage", name = "Garage", type = RoomType.GARAGE, corners = rect(-6.21, 0.0, -0.1143, 6.1), ceilingHeight = 2.7)
        val office = Room(id = "office", name = "Office", type = RoomType.OFFICE, corners = rect(9.0, 6.0, 12.5, 9.0), ceilingHeight = 2.44)
        val rooms = main.rooms.map { if (it.name == "Entry") it.copy(ceilingEstimated = true) else it } + garage + office
        val turn = Math.toRadians(6.0)
        val upper = Floor(
            id = "upper",
            name = "Upper Floor",
            rooms = listOf(
                Room(id = "bed4", name = "Bedroom 4", type = RoomType.BEDROOM, corners = rect(0.0, 0.0, 4.0, 3.5).map { it.rotated(turn) }, ceilingHeight = 2.44),
                Room(id = "loft", name = "Loft", type = RoomType.BONUS, corners = rect(4.1143, 0.0, 8.0, 3.5).map { it.rotated(turn) }, ceilingHeight = 2.44),
            ),
        )
        val project = sample.copy(
            id = "walkthrough-house",
            name = "Walkthrough House",
            floors = listOf(main.copy(rooms = rooms), upper, Floor(id = "attic", name = "Attic")),
        )
        val container = (rule.activity.application as FloorPlanApplication).container
        return runBlocking { container.projects.save(project) }
    }

    private fun openProblemHouseExport() {
        skipOnboarding()
        saveProblemHouse()
        tap("Walkthrough House")
        await("Export")
        tap("Export")
        await("Share")
        await("Before you export")
    }

    @Test
    fun exportEveryFormat() {
        openSampleHouse()
        tap("Export")
        await("Share")
        // PDF: one standard scale; the caption names paper, orientation, scale and page count.
        // The 39' 10" sample house is too wide for 1/4" and too deep for 3/16" on Letter, so it prints at 1/8".
        awaitTextContaining("1/8\" = 1'-0\"")
        shoot("x-export", "pdf-preview")
        repeat(3) {
            scrollDown()
            shoot("x-export", "pdf-options-${it + 1}")
        }
        shootFormat("x-export", "png", "PNG image", " px", optionShots = 1)
        shootFormat("x-export", "svg", "SVG vector", "vector drawing", optionShots = 1)
        // DXF: Room areas and Overall dimensions switches (no Wall lengths), and the layers note.
        shootFormat("x-export", "dxf", "DXF (CAD)", "drawing units")
        assertTrue("DXF offers the Overall dimensions switch", exists("Overall dimensions"))
        assertTrue("DXF offers the Room areas switch", exists("Room areas"))
        assertTrue("DXF has no Wall lengths switch", !exists("Wall lengths"))
        // Turning the dimensions off must change the preview too (same switch as the file).
        tap("Overall dimensions")
        scrollToTop()
        awaitTextContaining("drawing units")
        shoot("x-export", "dxf-preview-without-dimensions")
        shootFormat("x-export", "json", "JSON backup", "Project backup", optionShots = 1)
    }

    @Test
    fun preflightRowOpensTheRoomInTheEditor() {
        openProblemHouseExport()
        // All floors of a three-floor project: the empty attic is left out, the other two share one scale.
        awaitTextContaining("left out")
        awaitTextContaining("pages")
        shoot("x-preflight", "card")
        scrollDown()
        shoot("x-preflight", "card-scrolled")
        scrollToTop()

        // Tap the overlap problem: back in the editor with the Office selected.
        awaitTextContaining("Office overlaps")
        rule.onAllNodesWithText("Office overlaps", substring = true).onFirst().performClick()
        rule.waitForIdle()
        await("Rename room")
        awaitTextContaining("Office")
        shoot("x-preflight", "editor-shows-office")
        assertTrue("the export screen was left", !exists("Before you export"))
    }

    @Test
    fun preflightFloorWideRowGoesBackToTheEditor() {
        openProblemHouseExport()
        awaitTextContaining("straighten")
        rule.onAllNodesWithText("straighten", substring = true).onFirst().performClick()
        rule.waitForIdle()
        // Back in the editor (the Export action is in its top bar); nothing was changed from the export screen.
        await("Export")
        assertTrue("the export screen was left", !exists("Before you export"))
        shoot("x-preflight", "editor-after-floor-row")
        val container = (rule.activity.application as FloorPlanApplication).container
        val saved = runBlocking { container.projects.get("walkthrough-house") }
        assertEquals(listOf(2, 10, 0).sorted(), saved?.floors?.map { it.rooms.size }?.sorted())
    }

    @Test
    fun preflightBackToEditorButtonAndExportAnyway() {
        openProblemHouseExport()
        tap("Export anyway")
        awaitTextContaining("You chose to export anyway")
        shoot("x-preflight", "acknowledged")
    }

    @Test
    fun singleFloorChoices() {
        openProblemHouseExport()
        // One floor of the set: its own page, and a floor without rooms still explains itself.
        selectFloor("Upper Floor (2)")
        awaitTextContaining("2 pages")
        shoot("x-preflight", "upper-floor-only")
        selectFloor("Attic (0)")
        awaitTextContaining("Letter portrait · 2 pages")
        shoot("x-preflight", "empty-attic")
    }

    @Test
    fun settingsWalls() {
        skipOnboarding()
        tap("Settings")
        await("Show wall lengths")
        scrollUntil("Exterior wall thickness")
        shoot("x-settings", "walls-imperial")
        // Metric: sizes that were still the imperial defaults become round metric numbers.
        scrollToTop()
        tap("Metric (m, cm)")
        scrollUntil("Exterior wall thickness")
        awaitTextContaining("15 cm")
        shoot("x-settings", "walls-metric")
        scrollDown()
        shoot("x-settings", "defaults-metric")
    }

    @Test
    fun onboardingUnitsChoice() {
        tap("Next")
        tap("Next")
        tap("Next")
        await("Measure in")
        shoot("x-onboarding", "units-default")
        tap("Metric")
        shoot("x-onboarding", "units-metric")
        tap("Get started")
        await("FloorPlan Scan")
        // The choice is the default for new projects: the sample house opens in meters.
        tap("Open sample house")
        await("Export")
        shoot("x-onboarding", "sample-house-metric")
        tap("Export")
        await("Share")
        awaitTextContaining("· 1:")
        shoot("x-onboarding", "export-metric")
        val settings = (rule.activity.application as FloorPlanApplication).container.settings.defaults.value
        assertEquals(UnitSystem.METRIC, settings.unitSystem)
        assertEquals(2.40, settings.defaultCeilingHeight, 0.0)
    }

    /** A phone set to the United Kingdom: metric from the first screen, A4 paper, no question needed. */
    @Test
    @Config(sdk = [35], qualifiers = "en-rGB-$WALKTHROUGH_PHONE")
    fun metricCountry() {
        val defaults = (rule.activity.application as FloorPlanApplication).container.settings.defaults.value
        assertEquals("first-run units follow the country", UnitSystem.METRIC, defaults.unitSystem)
        tap("Next")
        tap("Next")
        tap("Next")
        await("Measure in")
        shoot("x-metric", "onboarding-units")
        tap("Get started")
        await("FloorPlan Scan")
        tap("Settings")
        await("Show wall lengths")
        shoot("x-metric", "settings-top")
        scrollDown()
        scrollDown()
        shoot("x-metric", "settings-walls")
        back()
        await("FloorPlan Scan")
        tap("Open sample house")
        await("Export")
        tap("Export")
        await("Share")
        awaitTextContaining("A4")
        shoot("x-metric", "export-a4")
        scrollDown()
        scrollDown()
        scrollDown()
        shoot("x-metric", "export-options")
    }

    @Test
    fun view3dZoomedAndPanned() {
        openSampleHouse()
        tap("3D view")
        await("Reset view")
        awaitTextContaining("Drag to rotate")
        // Zoom in and push the model toward the bottom edge: it must stay inside the canvas.
        val canvas = rule.onAllNodes(hasContentDescription("3D model of", substring = true)).onFirst()
        canvas.performTouchInput {
            pinch(
                Offset(width * 0.45f, height * 0.5f), Offset(width * 0.2f, height * 0.3f),
                Offset(width * 0.55f, height * 0.5f), Offset(width * 0.8f, height * 0.7f),
                durationMillis = 300,
            )
        }
        rule.waitForIdle()
        canvas.performTouchInput { swipe(Offset(width * 0.5f, height * 0.4f), Offset(width * 0.5f, height * 0.8f), durationMillis = 300) }
        rule.waitForIdle()
        shoot("x-view3d", "zoomed")
    }

    @Test
    @Config(sdk = [35], qualifiers = WALKTHROUGH_PHONE_NIGHT)
    fun darkExport() {
        openProblemHouseExport()
        awaitTextContaining("pages")
        shoot("x-dark", "preflight-card")
        selectFormat("DXF (CAD)")
        awaitTextContaining("drawing units")
        scrollDown()
        scrollDown()
        shoot("x-dark", "dxf-options-1")
        scrollDown()
        scrollDown()
        shoot("x-dark", "dxf-options-2")
    }

    @Test
    @Config(sdk = [35], qualifiers = WALKTHROUGH_PHONE_NIGHT)
    fun darkOnboardingAndSettings() {
        tap("Next")
        tap("Next")
        tap("Next")
        await("Measure in")
        shoot("x-dark", "onboarding-units")
        tap("Get started")
        await("FloorPlan Scan")
        tap("Settings")
        await("Show wall lengths")
        scrollUntil("Exterior wall thickness")
        shoot("x-dark", "settings-walls")
    }
}
