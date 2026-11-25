package com.oroplex.floorplan.recording

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** What the recorder is doing right now; published a few times a second while recording. */
data class RecordingProgress(
    val active: Boolean = false,
    /** Saved frames and the bytes they take on disk so far. */
    val frames: Int = 0,
    val bytes: Long = 0L,
    /** Frames skipped because the phone could not keep up. */
    val dropped: Int = 0,
    /** The camera is tracking its motion; nothing is saved while it is not. */
    val tracking: Boolean = true,
    /** How fast the camera is turning, degrees per second (for "slow down"). */
    val turnRateDeg: Float = 0f,
    /** Resolution of the frames being saved; 0 until the first one. */
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** Something went wrong with saving (storage, camera image); recording goes on if it can. */
    val problem: String? = null,
)

/** What a finished recording holds; the folder is ready to be packed. */
data class RecordingResult(
    val directory: File,
    val frames: Int,
    val bytes: Long,
    val durationSeconds: Double,
    val dropped: Int,
)

/** Who made the recording, for its `meta.json`. */
data class RecordingInfo(val name: String, val device: String, val android: String, val app: String)

/**
 * Saves what the camera sees while the user walks through a building: camera frames with the phone's own
 * motion tracking (and depth where the phone has it), for a computer to turn into a floor plan afterwards.
 * Implemented by the AR layer; main-thread facing.
 */
interface WalkthroughRecording {
    val progress: StateFlow<RecordingProgress>

    /** Starts saving into the (empty, existing) folder [directory]. False when a recording is already running. */
    fun start(directory: File, info: RecordingInfo): Boolean

    /**
     * Stops and waits until everything is on disk. [interrupted] marks a recording the app ended itself.
     * Null when nothing was being recorded.
     */
    suspend fun stop(interrupted: Boolean = false): RecordingResult?
}
