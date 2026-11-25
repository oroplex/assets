package com.oroplex.floorplan.recording

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Which camera frames of a walk-through are worth saving. The camera delivers 30 a second; a reconstruction
 * needs views that differ, sharp ones, and not thousands of them.
 *
 * A frame is saved when the camera has moved [minTranslation] metres or turned [minRotationDeg] degrees since
 * the last saved frame, but not sooner than [minIntervalNs] after it (about five a second while walking), and
 * not while the camera turns faster than [maxTurnRateDeg] degrees per second (motion blur) unless
 * [maxBlurGapNs] has passed without a frame: a blurred view of a doorway is better than none. A camera that is
 * held still saves one frame every [stillIntervalNs], so a pause shows in the recording.
 *
 * Not thread-safe; feed it every tracked camera frame in order.
 */
class KeyframePolicy(
    private val minIntervalNs: Long = 180_000_000L,
    private val minTranslation: Double = 0.04,
    private val minRotationDeg: Double = 3.0,
    private val maxTurnRateDeg: Double = 100.0,
    private val maxBlurGapNs: Long = 700_000_000L,
    private val stillIntervalNs: Long = 2_000_000_000L,
) {
    enum class Decision { SAVE, TOO_SOON, STILL, BLURRED }

    private var hasPrevious = false
    private var previousNs = 0L
    private val previous = FloatArray(7)

    private var hasSaved = false
    private var savedNs = 0L
    private val saved = FloatArray(7)

    /** Turn rate (degrees per second) and speed (metres per second) between the last two frames offered. */
    var turnRateDeg: Double = 0.0
        private set
    var speed: Double = 0.0
        private set

    /**
     * [pose] is tx, ty, tz, qx, qy, qz, qw. A [timestampNs] that does not advance (a repeated frame) is
     * [Decision.TOO_SOON].
     */
    fun offer(timestampNs: Long, pose: FloatArray): Decision {
        if (hasPrevious) {
            val dt = (timestampNs - previousNs) / 1e9
            if (dt <= 0.0) return Decision.TOO_SOON
            // A gap (tracking was lost, the app was paused) says nothing about how fast the camera moves now.
            if (dt < MAX_RATE_GAP_S) {
                turnRateDeg = rotationDeg(previous, pose) / dt
                speed = distance(previous, pose) / dt
            } else {
                turnRateDeg = 0.0
                speed = 0.0
            }
        }
        pose.copyInto(previous, 0, 0, 7)
        previousNs = timestampNs
        hasPrevious = true

        if (!hasSaved) return save(timestampNs, pose)
        val sinceSaved = timestampNs - savedNs
        if (sinceSaved < minIntervalNs) return Decision.TOO_SOON
        val moved = distance(saved, pose) >= minTranslation || rotationDeg(saved, pose) >= minRotationDeg
        if (!moved) return if (sinceSaved >= stillIntervalNs) save(timestampNs, pose) else Decision.STILL
        if (turnRateDeg > maxTurnRateDeg && sinceSaved < maxBlurGapNs) return Decision.BLURRED
        return save(timestampNs, pose)
    }

    private fun save(timestampNs: Long, pose: FloatArray): Decision {
        pose.copyInto(saved, 0, 0, 7)
        savedNs = timestampNs
        hasSaved = true
        return Decision.SAVE
    }

    companion object {
        private const val MAX_RATE_GAP_S = 0.5

        fun distance(a: FloatArray, b: FloatArray): Double {
            val dx = (a[0] - b[0]).toDouble()
            val dy = (a[1] - b[1]).toDouble()
            val dz = (a[2] - b[2]).toDouble()
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Angle between the orientations of two poses (their quaternions at indices 3..6), degrees. */
        fun rotationDeg(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in 3..6) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            if (na < 1e-12 || nb < 1e-12) return 0.0
            val c = (abs(dot) / sqrt(na * nb)).coerceAtMost(1.0)
            return Math.toDegrees(2.0 * acos(c))
        }
    }
}
