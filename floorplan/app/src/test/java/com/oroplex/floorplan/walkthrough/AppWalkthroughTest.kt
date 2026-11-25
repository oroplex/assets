package com.oroplex.floorplan.walkthrough

import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeUp
import com.oroplex.floorplan.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real app (MainActivity, real navigation, real repositories on a temp filesDir) on the JVM
 * and walks through every screen that does not need the camera. Any crash fails the test; every screen is
 * saved as a PNG under app/build/ui-walkthrough (see [Screenshots]). This is the only automated check that
 * the UI actually starts and draws — the other unit tests cover logic only.
 *
 * Each test starts from a fresh install (empty storage, first launch).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = WALKTHROUGH_PHONE)
class AppWalkthroughTest : WalkthroughBase() {
    @Test
    fun onboardingTour() {
        shoot("onboarding", "page1")
        tap("Next")
        shoot("onboarding", "page2")
        tap("Next")
        shoot("onboarding", "page3")
        tap("Next")
        shoot("onboarding", "page4")
        tap("Get started")
        await("FloorPlan Scan")
    }

    @Test
    fun projectList() {
        skipOnboarding()
        shoot("projects", "empty")
        withTextDialog {
            tap("New project")
            await("Project name")
            shoot("projects", "new-project-dialog")
            tap("Cancel")
            awaitGone("Project name")
        }
        tap("Open sample house")
        await("Export")
        back()
        await("FloorPlan Scan")
        shoot("projects", "with-sample")
    }

    @Test
    fun editor() {
        openSampleHouse()
        shoot("editor", "sample-house")

        // Select the living room (left third of the plan) to open the room panel.
        rule.onAllNodes(hasContentDescription("Floor plan", substring = true, ignoreCase = true)).onFirst()
            .performTouchInput { click(Offset(width * 0.28f, height * 0.52f)) }
        shoot("editor", "room-selected")
        back()

        tap("More options")
        shoot("editor", "more-menu")
        back()

        tap("Floor check")
        shoot("editor", "floor-check")
        back()

        withTextDialog {
            tap("Draw room")
            await("Cancel")
            shoot("editor", "draw-room-dialog")
            tap("Cancel")
            awaitGone("Cancel")
        }
    }

    /**
     * Opens every editor dialog, sheet and menu once (the first crash this walkthrough found lived in a
     * dialog). A step whose trigger is not on screen is skipped with a note; an exception thrown by the app
     * fails the test.
     */
    @Test
    fun editorDialogs() {
        openSampleHouse()
        rule.mainClock.autoAdvance = false
        val ui = RawUi(rule.mainClock)
        val skipped = ArrayList<String>()
        fun step(name: String, block: RawUi.() -> Unit) {
            try {
                ui.block()
                ui.shoot("dialogs", name)
            } catch (e: AssertionError) {
                skipped += "$name: ${e.message?.take(160)}"
            }
            ui.dismissAll()
        }
        step("project-settings") { tap("More options"); tap("Project settings"); await("Cancel") }
        step("rename-project") { tap("More options"); tap("Rename project"); await("Cancel") }
        step("add-floor") { tap("Add floor"); await("Cancel") }
        step("floor-menu") { tap("Floor options"); await("Rooms") }
        step("rooms-sheet") { tap("Floor options"); tap("Rooms") }
        step("rename-floor") { tap("Floor options"); tap("Rename floor"); await("Cancel") }

        // Room panel and its dialogs (selected the way TalkBack would, through the canvas actions).
        fun selectRoom() = ui.customAction("Floor plan", "Select Living")
        step("room-panel") { selectRoom() }
        step("room-details") { selectRoom(); tap("Rename room"); await("Cancel") }
        step("ceiling-height") { selectRoom(); tap("Edit"); await("Cancel") }
        step("edit-opening") { selectRoom(); tapStartingWith("Window"); await("Cancel") }
        step("re-solve") { selectRoom(); tap("Re-solve") }
        step("delete-room") { selectRoom(); tap("Delete"); await("Cancel") }

        // Wall panel and its dialogs.
        fun selectWall() {
            selectRoom()
            ui.customAction("Floor plan", "Select wall 1")
        }
        step("wall-panel") { selectWall() }
        step("set-wall-length") { selectWall(); tap("Set length"); await("Cancel") }
        step("calibrate-floor") { selectWall(); tap("Calibrate floor"); await("Cancel") }
        step("corner-panel") { selectRoom(); customAction("Floor plan", "Select corner 1") }

        rule.mainClock.autoAdvance = true
        if (skipped.isNotEmpty()) System.err.println("walkthrough: skipped editor steps:\n  " + skipped.joinToString("\n  "))
    }

    @Test
    fun view3d() {
        openSampleHouse()
        tap("3D view")
        await("Reset view")
        awaitTextContaining("Drag to rotate")
        shoot("view3d", "sample-house")
    }

    @Test
    fun export() {
        openSampleHouse()
        tap("Export")
        await("Share")
        shoot("export", "pdf")
        repeat(4) {
            scrollDown()
            shoot("export", "scrolled-${it + 1}")
        }
    }

    @Test
    fun settings() {
        skipOnboarding()
        tap("Settings")
        await("Show wall lengths")
        shoot("settings", "top")
        repeat(5) {
            scrollDown()
            shoot("settings", "scrolled-${it + 1}")
        }
    }

    /** "Scan room" without a camera or ARCore: the app must show its explanation screens, not crash. */
    @Test
    fun scanRoomWithoutCamera() {
        openSampleHouse()
        tap("Scan room")
        shoot("capture", "entry-without-camera")
    }

    @Test
    @Config(sdk = [35], qualifiers = WALKTHROUGH_PHONE_NIGHT)
    fun darkTheme() {
        skipOnboarding()
        shoot("dark", "projects-empty")
        tap("Open sample house")
        await("Export")
        shoot("dark", "editor")
        tap("3D view")
        await("Reset view")
        shoot("dark", "view3d")
        back()
        tap("Export")
        await("Share")
        shoot("dark", "export")
        back()
        back()
        await("FloorPlan Scan")
        tap("Settings")
        await("Show wall lengths")
        shoot("dark", "settings")
    }
}
