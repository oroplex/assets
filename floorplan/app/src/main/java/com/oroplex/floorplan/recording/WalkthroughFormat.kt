package com.oroplex.floorplan.recording

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The on-disk format of a walk-through recording: what the phone hands to the computer that turns it into a
 * floor plan. It is documented for the people (and programs) reading it in `docs/WALKTHROUGH-FORMAT.md`;
 * keep the two in step and raise [VERSION] when a reader of the old format would misread the new one.
 *
 * A recording is one folder, zipped for transfer:
 *
 * ```
 * meta.json          what, when, on which phone, how many frames   ([WalkthroughMeta])
 * frames.jsonl       one JSON object per saved camera frame        ([FrameRecord])
 * trajectory.csv     the camera pose of EVERY camera frame (30 per second), saved or not
 * planes.json        the flat surfaces ARCore itself had found when the recording ended ([PlaneRecord])
 * rgb/000000.jpg     the saved frames, as the camera delivered them (landscape, not rotated)
 * depth/000000.raw16 ARCore's raw depth for some frames: width x height little-endian uint16, millimetres
 * conf/000000.raw8   its confidence: width x height bytes, 0 (unknown) to 255 (certain)
 * ```
 */
object WalkthroughFormat {
    const val NAME = "floorplan-walkthrough"
    const val VERSION = 1

    const val META_FILE = "meta.json"
    const val FRAMES_FILE = "frames.jsonl"
    const val TRAJECTORY_FILE = "trajectory.csv"
    const val PLANES_FILE = "planes.json"
    const val RGB_DIR = "rgb"
    const val DEPTH_DIR = "depth"
    const val CONFIDENCE_DIR = "conf"

    const val TRAJECTORY_HEADER = "timestamp_ns,segment,tracking,tx,ty,tz,qx,qy,qz,qw"

    /** Lenient on reading (a newer writer may add fields), compact and complete on writing. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val prettyJson: Json = Json(json) { prettyPrint = true }

    fun rgbPath(index: Int): String = String.format(Locale.US, "%s/%06d.jpg", RGB_DIR, index)

    fun depthPath(index: Int): String = String.format(Locale.US, "%s/%06d.raw16", DEPTH_DIR, index)

    fun confidencePath(index: Int): String = String.format(Locale.US, "%s/%06d.raw8", CONFIDENCE_DIR, index)

    fun encodeFrame(frame: FrameRecord): String = json.encodeToString(frame)

    fun decodeFrame(line: String): FrameRecord = json.decodeFromString(line)

    fun encodeMeta(meta: WalkthroughMeta): String = prettyJson.encodeToString(meta)

    fun decodeMeta(text: String): WalkthroughMeta = json.decodeFromString(text)

    fun encodePlanes(planes: List<PlaneRecord>): String = json.encodeToString(planes)

    /**
     * One line of `trajectory.csv`. [pose] is tx, ty, tz, qx, qy, qz, qw (see [FrameRecord.pose]); numbers are
     * written with a '.' whatever the phone's language.
     */
    fun trajectoryLine(out: StringBuilder, timestampNs: Long, segment: Int, tracking: Boolean, pose: FloatArray) {
        out.append(timestampNs).append(',').append(segment).append(',').append(if (tracking) 1 else 0)
        for (i in 0 until 7) out.append(',').append(pose[i].toString())
        out.append('\n')
    }
}

/** Pinhole intrinsics of an image, in pixels of that image (origin top-left, x right, y down). */
@Serializable
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
)

/**
 * One saved camera frame.
 *
 * [pose] is the camera in the scan's world frame as tx, ty, tz, qx, qy, qz, qw: metres, +Y up (against
 * gravity), right-handed. The rotation takes camera coordinates to world coordinates, where the camera frame
 * is OpenGL's and belongs to the IMAGE as saved (landscape, the sensor's own orientation): +X to the right of
 * the image, +Y up in the image, and the camera looks along −Z. A pixel (u, v) at depth d metres along the
 * optical axis is the camera point ((u − cx) d / fx, −(v − cy) d / fy, −d).
 *
 * [displayPose] is the same camera position with the orientation ARCore reports for the phone's screen
 * (portrait here); it is recorded so a reader can check its handling of the rotation between the two.
 */
@Serializable
data class FrameRecord(
    val index: Int,
    /** ARCore's timestamp of the camera image, nanoseconds; the key into `trajectory.csv`. */
    val timestampNs: Long,
    /** Counts up when the camera session was interrupted: poses of different segments may not share a frame. */
    val segment: Int,
    val image: String,
    val pose: List<Float>,
    val displayPose: List<Float>,
    /** Intrinsics of the saved image ([image]). */
    val intrinsics: Intrinsics,
    /**
     * Intrinsics of the camera picture ARCore itself works on (its GPU texture). The depth image covers that
     * picture: scale these by depthWidth / width and depthHeight / height to project depth pixels.
     */
    val textureIntrinsics: Intrinsics? = null,
    /** How fast the camera was turning (degrees per second) and moving (metres per second) at this frame. */
    val turnRate: Float,
    val speed: Float,
    /** Raw depth saved with this frame, when ARCore had a new one. */
    val depth: String? = null,
    val confidence: String? = null,
    val depthWidth: Int? = null,
    val depthHeight: Int? = null,
    /**
     * Timestamp of the camera image the raw depth was computed for. It is usually OLDER than [timestampNs]:
     * look its pose up in `trajectory.csv`, do not use this frame's.
     */
    val depthTimestampNs: Long? = null,
)

/** A flat surface ARCore was tracking at the end of the recording. */
@Serializable
data class PlaneRecord(
    /** "HORIZONTAL_UPWARD_FACING", "HORIZONTAL_DOWNWARD_FACING" or "VERTICAL". */
    val type: String,
    /** Centre pose, like [FrameRecord.pose]; the plane is the local XZ plane and +Y is its normal. */
    val pose: List<Float>,
    val extentX: Float,
    val extentZ: Float,
    /** Boundary in the plane's local XZ coordinates: x0, z0, x1, z1, … */
    val polygon: List<Float>,
)

@Serializable
data class WalkthroughMeta(
    val format: String = WalkthroughFormat.NAME,
    val version: Int = WalkthroughFormat.VERSION,
    val name: String,
    val startedAtMillis: Long,
    val durationSeconds: Double,
    /** Manufacturer and model, Android version, app version: free text for the reader's log. */
    val device: String,
    val android: String,
    val app: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val jpegQuality: Int,
    /** ARCore's Depth API was on; and whether it used a hardware depth sensor rather than depth from motion. */
    val depthEnabled: Boolean,
    val hardwareDepth: Boolean,
    val frames: Int,
    val depthFrames: Int,
    /** Frames that should have been saved but were not, because the phone could not keep up. */
    val droppedFrames: Int,
    /** Camera frames during which motion tracking was lost (nothing is saved then). */
    val trackingLostFrames: Int,
    val segments: Int,
    /** True when the recording was ended by the app (storage, time limit, leaving the screen), not by Stop. */
    val interrupted: Boolean = false,
    val note: String = "Images are saved in the sensor's landscape orientation. See docs/WALKTHROUGH-FORMAT.md.",
)
