package com.oroplex.floorplan.walkthrough

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
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
 * saved as a PNG (see [Screenshots]). This is the only automated check that the UI actually starts and
 * draws — the other unit tests cover logic only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class AppWalkthroughTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shoot(name: String) {
        rule.waitForIdle()
        Screenshots.shoot(name)
    }

    /** Waits until some node with [text] or content description [text] exists; dumps the UI tree if not. */
    private fun awaitNode(text: String, timeoutMs: Long = 15_000) {
        try {
            rule.waitUntil(timeoutMs) {
                rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            val tree = rule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n---\n") {
                rule.onAllNodes(isRoot())[it].printToString(maxDepth = 40)
            }
            throw AssertionError("Never saw \"$text\". UI tree:\n$tree", e)
        }
    }

    @Test
    fun walkThroughTheApp() {
        // First launch: the welcome tour.
        shoot("onboarding-1")
        rule.onNodeWithText("Skip").performClick()

        // Empty project list.
        awaitNode("FloorPlan Scan")
        rule.onNodeWithText("FloorPlan Scan").assertIsDisplayed()
        shoot("projects-empty")

        // Sample house -> editor.
        rule.onNodeWithText("Open sample house").performClick()
        awaitNode("Export")
        shoot("editor-sample-house")

        // Export.
        rule.onNodeWithContentDescription("Export").performClick()
        awaitNode("Share")
        shoot("export")
    }
}
