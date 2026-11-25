package com.oroplex.floorplan.walkthrough

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.MainTestClock
import org.robolectric.Shadows.shadowOf

/**
 * A minimal UI driver that does not wait for Compose to report "idle": it advances the test clock itself and
 * reads / clicks nodes straight from the semantics tree of every window. Used only where the normal test
 * API cannot be (see AppWalkthroughTest.withTextDialog). Robolectric tests run on the main thread.
 */
internal class RawUi(private val clock: MainTestClock) {
    /** Lets [ms] of animations, recomposition and layout happen. */
    fun pump(ms: Long = 400) {
        var left = ms
        while (left > 0) {
            clock.advanceTimeByFrame()
            shadowOf(Looper.getMainLooper()).idle()
            left -= 16
        }
    }

    private fun composeRoots(view: View, out: MutableList<ViewRootForTest>) {
        if (view is ViewRootForTest) out += view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoots(view.getChildAt(i), out)
    }

    private fun nodes(): List<SemanticsNode> {
        val roots = ArrayList<ViewRootForTest>()
        for (window in WindowInspector.getGlobalWindowViews()) if (window.isShown) composeRoots(window, roots)
        return roots.flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
    }

    private fun SemanticsNode.labels(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun find(label: String): List<SemanticsNode> = nodes().filter { label in it.labels() }

    fun exists(label: String): Boolean = find(label).isNotEmpty()

    fun await(label: String, timeoutMs: Long = 5_000) {
        var waited = 0L
        while (!exists(label)) {
            if (waited >= timeoutMs) throw AssertionError("Never saw \"$label\" (raw). Labels on screen: ${nodes().flatMap { it.labels() }}")
            pump(100)
            waited += 100
        }
        pump()
    }

    fun awaitGone(label: String, timeoutMs: Long = 5_000) {
        var waited = 0L
        while (exists(label)) {
            if (waited >= timeoutMs) throw AssertionError("\"$label\" never went away (raw)")
            pump(100)
            waited += 100
        }
        pump()
    }

    /** Clicks the LAST node labelled [label] (the topmost window is listed last) that has a click action. */
    fun tap(label: String) {
        val node = find(label).lastOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
            ?: throw AssertionError("No clickable \"$label\" (raw). Labels on screen: ${nodes().flatMap { it.labels() }}")
        node.config[SemanticsActions.OnClick].action?.invoke()
        pump()
    }

    /** Clicks the last clickable node with a label that starts with [prefix]. */
    fun tapStartingWith(prefix: String) {
        val node = nodes().lastOrNull { n -> n.labels().any { it.startsWith(prefix) } && n.config.getOrNull(SemanticsActions.OnClick) != null }
            ?: throw AssertionError("No clickable label starting with \"$prefix\" (raw). Labels on screen: ${nodes().flatMap { it.labels() }}")
        node.config[SemanticsActions.OnClick].action?.invoke()
        pump()
    }

    /**
     * Runs the accessibility custom action whose label starts with [actionPrefix] on the node whose label
     * contains [nodeLabelPart] (e.g. the plan canvas' "Select wall 1 ..." action, as TalkBack would).
     */
    fun customAction(nodeLabelPart: String, actionPrefix: String) {
        val node = nodes().lastOrNull { n -> n.labels().any { nodeLabelPart in it } }
            ?: throw AssertionError("No node labelled like \"$nodeLabelPart\" (raw)")
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val action = actions.firstOrNull { it.label.startsWith(actionPrefix) }
            ?: throw AssertionError("No custom action starting with \"$actionPrefix\"; available: ${actions.map { it.label }}")
        action.action()
        pump()
    }

    /** System Back on the topmost window (closes a dialog, menu or sheet); false if only the screen is shown. */
    fun dismissTop(): Boolean {
        val roots = WindowInspector.getGlobalWindowViews().filter { it.isShown }
        if (roots.size < 2) return false
        val top = roots.last()
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        pump()
        return true
    }

    /** Closes every dialog, menu and sheet that is open. */
    fun dismissAll() {
        var guard = 0
        while (guard++ < 5 && dismissTop()) Unit
    }

    fun shoot(group: String, name: String) {
        pump(600)
        Screenshots.shoot(group, name)
    }
}
