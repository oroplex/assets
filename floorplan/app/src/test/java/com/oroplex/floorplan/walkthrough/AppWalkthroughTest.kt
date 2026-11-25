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
@Config(sdk = [35], qualifiers = PHONE)
class AppWalkthroughTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shoot(group: String, name: String) {
        rule.waitForIdle()
        Screenshots.shoot(group, name)
    }

    /**
     * Drives a dialog that contains a text field. On the JVM runtime such a dialog never reports "idle" once
     * the clock runs (a runtime artifact, not an app loop: a bare AlertDialog with one OutlinedTextField does
     * the same), and every normal test lookup waits for idle. So inside [block] the clock is advanced by hand
     * and nodes are found and clicked directly through the semantics tree ([RawUi]).
     */
    private fun withTextDialog(block: RawUi.() -> Unit) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            RawUi(rule.mainClock).block()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
    }

    private fun exists(label: String): Boolean =
        rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun uiTree(): String =
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n---\n") {
            rule.onAllNodes(isRoot())[it].printToString(maxDepth = 40)
        }

    /** Waits until a node with text or content description [label] exists; dumps the UI tree if not. */
    private fun await(label: String, timeoutMs: Long = 15_000) {
        try {
            rule.waitUntil(timeoutMs) { exists(label) }
        } catch (e: Throwable) {
            Screenshots.shoot("failure", label.replace(' ', '-').lowercase())
            throw AssertionError("Never saw \"$label\". UI tree:\n${uiTree()}", e)
        }
    }

    /** Taps the first node whose text or content description is [label]. */
    private fun tap(label: String) {
        await(label)
        val byText = rule.onAllNodesWithText(label)
        if (byText.fetchSemanticsNodes().isNotEmpty()) {
            byText.onFirst().performClick()
        } else {
            rule.onAllNodesWithContentDescription(label).onFirst().performClick()
        }
        rule.waitForIdle()
    }

    /** System Back: dismisses the topmost dialog / menu / sheet if there is one, else navigates back. */
    private fun back() {
        rule.waitForIdle()
        rule.runOnUiThread {
            val roots = WindowInspector.getGlobalWindowViews().filter { it.isShown }
            if (roots.size > 1) {
                val top = roots.last()
                top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            } else {
                rule.activity.onBackPressedDispatcher.onBackPressed()
            }
        }
        rule.waitForIdle()
    }

    /** Scrolls the screen's content down by about a third of the screen. */
    private fun scrollDown() {
        rule.onAllNodes(isRoot()).onFirst().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.35f, durationMillis = 400) }
        rule.waitForIdle()
    }

    /** Waits until some text containing [part] is on screen. */
    private fun awaitTextContaining(part: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(part, substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun skipOnboarding() {
        tap("Skip")
        await("FloorPlan Scan")
    }

    private fun openSampleHouse() {
        skipOnboarding()
        tap("Open sample house")
        await("Export")
    }

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
    @Config(sdk = [35], qualifiers = "w412dp-h915dp-night-420dpi")
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

/** A tall phone, close to the Galaxy S26 Ultra's default display size. */
private const val PHONE = "w412dp-h915dp-420dpi"
