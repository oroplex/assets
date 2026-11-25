package com.oroplex.floorplan.ui.walkthrough

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oroplex.floorplan.diagnostics.ScanLog
import com.oroplex.floorplan.recording.RecordingFile
import com.oroplex.floorplan.recording.RecordingInfo
import com.oroplex.floorplan.recording.RecordingProgress
import com.oroplex.floorplan.recording.WalkthroughRecording
import com.oroplex.floorplan.recording.WalkthroughStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class WalkthroughPhase { IDLE, RECORDING, SAVING, DONE }

data class WalkthroughUiState(
    val phase: WalkthroughPhase = WalkthroughPhase.IDLE,
    val progress: RecordingProgress = RecordingProgress(),
    val elapsedSeconds: Int = 0,
    /** Share of the recording packed into its zip, 0..1 (phase SAVING). */
    val packProgress: Float = 0f,
    /** Finished recordings on the phone, newest first. */
    val recordings: List<RecordingFile> = emptyList(),
    /** Recordings that were cut off before they were packed (the app was closed while recording). */
    val unfinished: Int = 0,
    /** The recording just made (phase DONE). */
    val saved: RecordingFile? = null,
    val savedFrames: Int = 0,
    val message: String? = null,
    /** "Stop recording?" is showing because the user tried to leave while recording. */
    val confirmLeave: Boolean = false,
    /** The screen may close now. */
    val leave: Boolean = false,
)

/**
 * The walk-through screen: start and stop a recording, pack it into one file, list what is on the phone.
 * The recorder itself belongs to the AR session and is passed in by the screen ([WalkthroughRecording]).
 */
class WalkthroughViewModel(
    private val store: WalkthroughStore,
    private val describe: (name: String) -> RecordingInfo,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WalkthroughUiState())
    val state: StateFlow<WalkthroughUiState> = mutableState.asStateFlow()

    private var directory: File? = null
    private var startedAtMillis = 0L
    private var watcher: Job? = null

    /** The user chose to leave from the "Stop recording?" prompt: close the screen once the file is safe. */
    private var leaveWhenSaved = false

    init {
        refresh()
    }

    /** Re-read what is on the phone. */
    fun refresh() {
        viewModelScope.launch {
            val (recordings, unfinished) = withContext(io) { store.recordings() to store.unfinished() }
            // A folder that is being written right now is not "unfinished".
            val leftBehind = unfinished.count { it != directory }
            mutableState.update { it.copy(recordings = recordings, unfinished = leftBehind) }
        }
    }

    fun start(recording: WalkthroughRecording?) {
        val phase = mutableState.value.phase
        if (phase == WalkthroughPhase.RECORDING || phase == WalkthroughPhase.SAVING) return
        if (recording == null) {
            mutableState.update { it.copy(message = "The camera is not ready yet. Try again in a moment.") }
            return
        }
        viewModelScope.launch {
            val dir = try {
                withContext(io) {
                    if (WalkthroughStore.storageExhausted(store.freeBytes(), 0L)) null else store.newRecordingDir(clockMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = "Couldn't start recording: the storage is not writable.") }
                return@launch
            }
            if (dir == null) {
                mutableState.update { it.copy(message = "Not enough free storage to record. Free up some space first.") }
                return@launch
            }
            if (!recording.start(dir, describe(dir.name))) {
                withContext(io) { store.discard(dir) }
                val problem = recording.progress.value.problem ?: "Couldn't start recording."
                mutableState.update { it.copy(message = problem) }
                return@launch
            }
            directory = dir
            startedAtMillis = clockMillis()
            mutableState.update {
                it.copy(
                    phase = WalkthroughPhase.RECORDING, progress = RecordingProgress(active = true), elapsedSeconds = 0,
                    saved = null, savedFrames = 0, message = null, confirmLeave = false,
                )
            }
            watch(recording)
        }
    }

    /** Follows the recorder while it runs: progress, the clock, and the storage that is left. */
    private fun watch(recording: WalkthroughRecording) {
        watcher?.cancel()
        watcher = viewModelScope.launch {
            var tick = 0
            while (mutableState.value.phase == WalkthroughPhase.RECORDING) {
                val progress = recording.progress.value
                mutableState.update { it.copy(progress = progress, elapsedSeconds = ((clockMillis() - startedAtMillis) / 1000).toInt()) }
                if (!progress.active) {
                    // The recorder ended it itself (length limit, the camera session closed).
                    finish(recording, interrupted = true, discard = false, notice = progress.problem ?: "The recording was stopped and saved.")
                    return@launch
                }
                if (tick % STORAGE_CHECK_TICKS == 0) {
                    val free = withContext(io) { store.freeBytes() }
                    if (WalkthroughStore.storageExhausted(free, progress.bytes)) {
                        finish(recording, interrupted = true, discard = false, notice = "Storage is almost full, so the recording was stopped and saved.")
                        return@launch
                    }
                }
                tick++
                delay(TICK_MS)
            }
        }
    }

    /** Stop and keep the recording. */
    fun stop(recording: WalkthroughRecording?) {
        if (mutableState.value.phase != WalkthroughPhase.RECORDING) return
        watcher?.cancel()
        viewModelScope.launch { finish(recording, interrupted = false, discard = false, notice = null) }
    }

    /** Stop and throw the recording away. */
    fun discard(recording: WalkthroughRecording?) {
        if (mutableState.value.phase != WalkthroughPhase.RECORDING) return
        watcher?.cancel()
        viewModelScope.launch { finish(recording, interrupted = false, discard = true, notice = null) }
    }

    private suspend fun finish(recording: WalkthroughRecording?, interrupted: Boolean, discard: Boolean, notice: String?) {
        val dir = directory
        mutableState.update { it.copy(phase = WalkthroughPhase.SAVING, packProgress = 0f, confirmLeave = false) }
        val result = try {
            recording?.stop(interrupted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        directory = null
        val leaving = leaveWhenSaved
        leaveWhenSaved = false
        if (dir == null) {
            mutableState.update { it.copy(phase = WalkthroughPhase.IDLE, message = notice, leave = leaving) }
            return
        }
        val frames = result?.frames ?: 0
        if (discard || frames == 0) {
            withContext(io) { store.discard(dir) }
            val message = when {
                discard -> "Recording discarded."
                else -> "Nothing was recorded: the camera never got going. Try again, moving the phone slowly."
            }
            mutableState.update { it.copy(phase = WalkthroughPhase.IDLE, message = message, leave = leaving) }
            refresh()
            return
        }
        val packed = try {
            withContext(io) { store.pack(dir) { share -> mutableState.update { it.copy(packProgress = share) } } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanLog.add("walk-through could not be packed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (packed == null) {
            mutableState.update {
                it.copy(phase = WalkthroughPhase.IDLE, message = "Couldn't pack the recording (storage full?). It is kept; free up space and tap Recover.")
            }
        } else {
            mutableState.update { it.copy(phase = WalkthroughPhase.DONE, saved = packed, savedFrames = frames, message = notice, leave = leaving) }
        }
        refresh()
    }

    /** From the "Stop recording?" prompt: save (or discard) the recording, then close the screen. */
    fun stopAndLeave(recording: WalkthroughRecording?, discard: Boolean) {
        if (mutableState.value.phase != WalkthroughPhase.RECORDING) return
        leaveWhenSaved = true
        watcher?.cancel()
        viewModelScope.launch { finish(recording, interrupted = false, discard = discard, notice = null) }
    }

    /** Pack recordings that were cut off before they were packed. */
    fun recover() {
        if (mutableState.value.phase == WalkthroughPhase.RECORDING || mutableState.value.phase == WalkthroughPhase.SAVING) return
        viewModelScope.launch {
            mutableState.update { it.copy(phase = WalkthroughPhase.SAVING, packProgress = 0f) }
            var recovered = 0
            var failed = 0
            withContext(io) {
                for (dir in store.unfinished()) {
                    try {
                        store.pack(dir) { share -> mutableState.update { it.copy(packProgress = share) } }
                        recovered++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            }
            val message = when {
                failed > 0 -> "Couldn't recover $failed recording(s). Free up some storage and try again."
                recovered == 1 -> "1 recording recovered."
                else -> "$recovered recordings recovered."
            }
            mutableState.update { it.copy(phase = WalkthroughPhase.IDLE, message = message) }
            refresh()
        }
    }

    fun delete(recording: RecordingFile) {
        viewModelScope.launch {
            withContext(io) { store.delete(recording) }
            mutableState.update { if (it.saved == recording) it.copy(saved = null, phase = WalkthroughPhase.IDLE) else it }
            refresh()
        }
    }

    /** Back to the start panel after a recording. */
    fun recordAnother() {
        if (mutableState.value.phase == WalkthroughPhase.DONE) mutableState.update { it.copy(phase = WalkthroughPhase.IDLE, saved = null) }
    }

    fun showMessage(text: String) = mutableState.update { it.copy(message = text) }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    /** Back / close: leaves at once unless a recording is running or being saved. */
    fun requestLeave() {
        when (mutableState.value.phase) {
            WalkthroughPhase.RECORDING -> mutableState.update { it.copy(confirmLeave = true) }
            WalkthroughPhase.SAVING -> Unit // a few seconds; leaving now would cut the file off
            else -> mutableState.update { it.copy(leave = true) }
        }
    }

    fun dismissLeave() = mutableState.update { it.copy(confirmLeave = false) }

    class Factory(private val store: WalkthroughStore, private val describe: (String) -> RecordingInfo) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WalkthroughViewModel(store, describe) as T
    }

    private companion object {
        const val TICK_MS = 500L
        const val STORAGE_CHECK_TICKS = 4
    }
}

/** What to tell the person recording, most urgent first; pure so it can be tested. */
object WalkthroughCoach {
    const val FAST_TURN_DEG = 60f
    private const val TIP_SECONDS = 8

    val TIPS = listOf(
        "Walk slowly through every room, phone upright at chest height.",
        "Pan across each wall, from one corner to the next.",
        "Keep the bottom of the walls and some ceiling in view.",
        "Go through doorways slowly, then look back into the room you left.",
        "Finish where you started: it helps the rooms line up.",
    )

    data class Hint(val text: String, val warning: Boolean)

    fun hint(progress: RecordingProgress, elapsedSeconds: Int): Hint = when {
        !progress.tracking -> Hint("Tracking lost. Point back at what you were just looking at and hold still.", warning = true)
        progress.turnRateDeg > FAST_TURN_DEG -> Hint("Slow down: turn the phone more gently.", warning = true)
        progress.problem != null -> Hint(progress.problem, warning = true)
        else -> Hint(TIPS[(elapsedSeconds.coerceAtLeast(0) / TIP_SECONDS) % TIPS.size], warning = false)
    }

    /** "3:07" */
    fun clock(seconds: Int): String = "${seconds.coerceAtLeast(0) / 60}:${(seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')}"

    /** "412 MB", "1.3 GB", "640 kB" */
    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "${(bytes / 100_000_000L) / 10.0} GB"
        bytes >= 1_000_000L -> "${bytes / 1_000_000L} MB"
        else -> "${bytes / 1_000L} kB"
    }
}
