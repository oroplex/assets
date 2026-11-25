package com.oroplex.floorplan.walkthrough

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.oroplex.floorplan.MainActivity
import com.oroplex.floorplan.recording.RecordingFile
import com.oroplex.floorplan.recording.RecordingProgress
import com.oroplex.floorplan.ui.theme.FloorPlanTheme
import com.oroplex.floorplan.ui.walkthrough.StopRecordingDialog
import com.oroplex.floorplan.ui.walkthrough.WalkthroughActions
import com.oroplex.floorplan.ui.walkthrough.WalkthroughContent
import com.oroplex.floorplan.ui.walkthrough.WalkthroughPhase
import com.oroplex.floorplan.ui.walkthrough.WalkthroughUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the walk-through recording screen in each of its states without a camera, over a dim and a bright
 * backdrop standing in for the camera picture, and checks that its buttons reach their actions.
 * Screenshots: app/build/ui-walkthrough/recording-*.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-420dpi")
class RecordingWalkthroughTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val calls = ArrayList<String>()
    private val actions = WalkthroughActions(
        onStart = { calls += "start" },
        onStop = { calls += "stop" },
        onDiscard = { calls += "discard" },
        onClose = { calls += "close" },
        onShare = { calls += "share ${it.name}" },
        onSaveToDownloads = { calls += "downloads ${it.name}" },
        onDelete = { calls += "delete ${it.name}" },
        onRecover = { calls += "recover" },
        onRecordAnother = { calls += "another" },
        onDismissMessage = { calls += "dismiss" },
    )

    private fun recording(name: String, megabytes: Long, time: Long) =
        RecordingFile(File("/unused/$name.zip"), name, megabytes * 1_000_000L, time)

    private val kitchen = recording("Walkthrough-20261125-1432", 212, 1_795_617_120_000L)
    private val upstairs = recording("Walkthrough-20261124-0915", 1_340, 1_795_511_700_000L)

    private val progress = RecordingProgress(active = true, frames = 214, bytes = 43_600_000L, imageWidth = 1280, imageHeight = 720)

    @Test
    fun everyStateInADimRoom() = everyState("recording-dark", listOf(Color(0xFF2B2E33), Color(0xFF4A4741)))

    @Test
    fun everyStateFacingAWhiteWall() = everyState("recording-bright", listOf(Color(0xFFF4F2EE), Color(0xFFD9D5CC)))

    private fun everyState(group: String, backdrop: List<Color>) {
        var state by mutableStateOf(WalkthroughUiState())
        var askToStop by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.activity.setContent {
            FloorPlanTheme {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(backdrop))) {
                    WalkthroughContent(state, actions)
                }
                if (askToStop) StopRecordingDialog(onSave = { calls += "save+leave" }, onDiscard = { calls += "discard+leave" }, onKeepRecording = { askToStop = false })
            }
        }
        val ui = RawUi(rule.mainClock)
        fun show(next: WalkthroughUiState, name: String) {
            state = next
            ui.pump()
            ui.shoot(group, name)
        }

        show(WalkthroughUiState(), "start-first-time")
        ui.tap("Start recording")
        show(WalkthroughUiState(recordings = listOf(kitchen, upstairs), unfinished = 1), "start-with-recordings")
        ui.tap("Recover")
        ui.tap("Share ${kitchen.name}")
        ui.tap("Delete ${upstairs.name}")

        show(WalkthroughUiState(phase = WalkthroughPhase.RECORDING, progress = progress, elapsedSeconds = 37), "recording")
        ui.tap("Stop and save")
        show(
            WalkthroughUiState(phase = WalkthroughPhase.RECORDING, progress = progress.copy(turnRateDeg = 95f, dropped = 3), elapsedSeconds = 41),
            "recording-too-fast",
        )
        show(
            WalkthroughUiState(phase = WalkthroughPhase.RECORDING, progress = progress.copy(tracking = false), elapsedSeconds = 44),
            "recording-tracking-lost",
        )
        ui.tap("Discard this recording")
        askToStop = true
        ui.pump()
        ui.shoot(group, "recording-stop-dialog")
        ui.tap("Keep recording")
        ui.pump()

        show(WalkthroughUiState(phase = WalkthroughPhase.SAVING, packProgress = 0.43f), "saving")
        show(
            WalkthroughUiState(phase = WalkthroughPhase.DONE, saved = kitchen, savedFrames = 214, recordings = listOf(kitchen, upstairs)),
            "saved",
        )
        ui.tap("Share")
        ui.tap("Record another")
        show(
            WalkthroughUiState(
                phase = WalkthroughPhase.DONE, saved = kitchen, savedFrames = 214,
                message = "Storage is almost full, so the recording was stopped and saved.",
            ),
            "saved-with-notice",
        )
        ui.tap("Dismiss message")
        ui.tap("Close")

        assertEquals(
            listOf(
                "start", "recover", "share ${kitchen.name}", "delete ${upstairs.name}", "stop", "discard",
                "share ${kitchen.name}", "another", "dismiss", "close",
            ),
            calls,
        )
    }
}
