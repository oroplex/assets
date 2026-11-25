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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Shared driver for the headless walkthrough tests: launches the real app (MainActivity, real navigation,
 * real repositories on a temp filesDir) on the JVM with a fresh install per test, and offers helpers to
 * tap, wait, go back, scroll and save screenshots (see [Screenshots]). Subclass it to add screens:
 *
 *     @RunWith(RobolectricTestRunner::class)
 *     @GraphicsMode(GraphicsMode.Mode.NATIVE)
 *     @Config(sdk = [35], qualifiers = WALKTHROUGH_PHONE)
 *     class MyWalkthroughTest : WalkthroughBase() { @Test fun myScreen() { openSampleHouse(); tap("Export"); shoot("mine", "export") } }
 *
 * Dialogs that contain a text field must be driven inside [withTextDialog] (see its comment).
 */
abstract class WalkthroughBase {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    protected fun shoot(group: String, name: String) {
        rule.waitForIdle()
        Screenshots.shoot(group, name)
    }

    /**
     * Drives a dialog that contains a text field. On the JVM runtime such a dialog never reports "idle" once
     * the clock runs (a runtime artifact, not an app loop: a bare AlertDialog with one OutlinedTextField does
     * the same), and every normal test lookup waits for idle. So inside [block] the clock is advanced by hand
     * and nodes are found and clicked directly through the semantics tree ([RawUi]).
     */
    protected fun withTextDialog(block: RawUi.() -> Unit) {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            RawUi(rule.mainClock).block()
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
    }

    protected fun exists(label: String): Boolean =
        rule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    protected fun uiTree(): String =
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().indices.joinToString("\n---\n") {
            rule.onAllNodes(isRoot())[it].printToString(maxDepth = 40)
        }

    /** Waits until a node with text or content description [label] exists; dumps the UI tree if not. */
    protected fun await(label: String, timeoutMs: Long = 15_000) {
        try {
            rule.waitUntil(timeoutMs) { exists(label) }
        } catch (e: Throwable) {
            Screenshots.shoot("failure", label.replace(' ', '-').lowercase())
            throw AssertionError("Never saw \"$label\". UI tree:\n${uiTree()}", e)
        }
    }

    /** Taps the first node whose text or content description is [label]. */
    protected fun tap(label: String) {
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
    protected fun back() {
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
    protected fun scrollDown() {
        rule.onAllNodes(isRoot()).onFirst().performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.35f, durationMillis = 400) }
        rule.waitForIdle()
    }

    /** Waits until some text containing [part] is on screen. */
    protected fun awaitTextContaining(part: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(part, substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    protected fun skipOnboarding() {
        tap("Skip")
        await("FloorPlan Scan")
    }

    protected fun openSampleHouse() {
        skipOnboarding()
        tap("Open sample house")
        await("Export")
    }

}

/** A tall phone, close to the Galaxy S26 Ultra's default display size. */
const val WALKTHROUGH_PHONE = "w412dp-h915dp-420dpi"

/** The same phone in dark mode. */
const val WALKTHROUGH_PHONE_NIGHT = "w412dp-h915dp-night-420dpi"
