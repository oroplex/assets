package com.oroplex.floorplan.ar

import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.oroplex.floorplan.MainActivity
import com.oroplex.floorplan.ui.theme.FloorPlanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ARX-5 / CAPX-10: Back on the "Camera unavailable" screen that covers a running scan must leave through the
 * host's onExit, on the first press, for the button and for system Back; it must never reach the capture
 * UI's own back handling underneath (which steps back through the capture flow where the user cannot see it).
 * Runs the real [ArSessionLayers] with a stand-in capture UI that owns a back handler, like CaptureContent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class ArSessionLayersTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val events = ArrayList<String>()
    private var cameraUnavailable by mutableStateOf(false)
    private var showContent by mutableStateOf(true)
    private var contentHandlesBack by mutableStateOf(true)

    private fun show() {
        rule.activity.setContent {
            FloorPlanTheme {
                ArSessionLayers(
                    cameraView = { Box(Modifier.fillMaxSize()) },
                    showContent = showContent,
                    cameraUnavailable = cameraUnavailable,
                    onRetry = { events += "retry" },
                    onExit = { events += "exit" },
                ) {
                    BackHandler(enabled = contentHandlesBack) { events += "step back in the capture flow" }
                    Text("capture ui")
                }
            }
        }
        rule.waitForIdle()
    }

    private fun systemBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun backOnTheOverlayExitsOnTheFirstPressAndNeverStepsBackUnderneath() {
        show()
        // While scanning, system Back belongs to the capture UI.
        systemBack()
        assertEquals(listOf("step back in the capture flow"), events)
        events.clear()

        // Another app takes the camera: the overlay covers the scan, the capture UI stays composed below.
        cameraUnavailable = true
        rule.waitForIdle()
        rule.onNodeWithText(ArSetupError.CAMERA_UNAVAILABLE.title).assertIsDisplayed()
        systemBack()
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf("exit", "exit"), events)
        events.clear()

        // The capture UI's handler changing state underneath does not move it in front of the overlay.
        contentHandlesBack = false
        rule.waitForIdle()
        contentHandlesBack = true
        rule.waitForIdle()
        systemBack()
        assertEquals(listOf("exit"), events)
        events.clear()

        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf("retry"), events)
        events.clear()

        // Retry worked: the overlay is gone and Back is the capture UI's again.
        cameraUnavailable = false
        rule.waitForIdle()
        systemBack()
        assertEquals(listOf("step back in the capture flow"), events)
    }

    @Test
    fun backBeforeTheSessionEverRanExits() {
        showContent = false
        cameraUnavailable = true
        show()
        rule.onNodeWithText(ArSetupError.CAMERA_UNAVAILABLE.title).assertIsDisplayed()
        systemBack()
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf("exit", "exit"), events)
    }
}
