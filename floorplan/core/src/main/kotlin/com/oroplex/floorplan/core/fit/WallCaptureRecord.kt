package com.oroplex.floorplan.core.fit

import com.oroplex.floorplan.core.geometry.Segment2
import com.oroplex.floorplan.core.geometry.Vec2
import com.oroplex.floorplan.core.geometry.Vec3
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Everything one wall capture fed to [WallEstimator.fromPoints], kept so that a capture made on a phone can
 * be measured again later on a computer ([replay]) with other settings, instead of asking for a re-scan.
 *
 * The record holds geometry in the AR session's arbitrary world frame only (no images, no location).
 * [encode] writes a plain-text block that survives being pasted into the middle of a log file; [decodeAll]
 * finds every complete block in a text and ignores everything else.
 */
data class WallCaptureRecord(
    /** When the capture ended (epoch milliseconds). */
    val timeMillis: Long,
    val floorY: Double,
    val ceilingY: Double?,
    /** The viewer passed to the estimator (mean camera position over the sampling frames). */
    val viewer: Vec3,
    /** The aim point passed to the estimator; null when the crosshair had too few hits. */
    val aimPoint: Vec3?,
    /** Camera position of every sampling frame; a sample's ray is `cameras[sample.frame]` → `sample.point`. */
    val cameras: List<Vec3>,
    /** Every sample in the order the estimator received it. */
    val samples: List<Sample>,
    /** The ARCore wall plane seen under the crosshair, as its extent in plan meters; null when there was none. */
    val plane: Segment2?,
    /** What the app concluded, one line of free text (the diagnostics summary of the capture). */
    val note: String,
) {
    /**
     * One wall sample. [kind] is the kind of ARCore hit it came from ('D' depth, 'P' plane, 'F' feature
     * point); [crosshair] marks the sample of the screen center, the others come from the sampling grid.
     */
    data class Sample(val frame: Int, val kind: Char, val crosshair: Boolean, val point: Vec3)

    /** The points handed to the estimator, in order. */
    val points: List<Vec3> get() = samples.map { it.point }

    /** Measures the capture again; with the default [config] this reproduces what the phone computed. */
    fun replay(config: WallEstimatorConfig = WallEstimatorConfig()): WallFit =
        WallEstimator.fromPoints(points, floorY, ceilingY, viewer, config, aimPoint)

    /** The capture as a text block (coordinates in meters, rounded to 0.1 mm). */
    fun encode(): String {
        val sb = StringBuilder(64 + samples.size * 34)
        sb.append(BEGIN).append('\n')
        sb.append("time ").append(timeMillis).append('\n')
        sb.append("floor ").appendFixed(floorY).append('\n')
        sb.append("ceiling ")
        if (ceilingY != null) sb.appendFixed(ceilingY) else sb.append(NONE)
        sb.append('\n')
        sb.append("viewer ").appendVec(viewer).append('\n')
        sb.append("aim ")
        if (aimPoint != null) sb.appendVec(aimPoint) else sb.append(NONE)
        sb.append('\n')
        if (plane != null) {
            sb.append("plane ").appendFixed(plane.a.x).append(' ').appendFixed(plane.a.y).append(' ')
                .appendFixed(plane.b.x).append(' ').appendFixed(plane.b.y).append('\n')
        }
        sb.append("note ").append(note.replace('\n', ' ').replace('\r', ' ').trim()).append('\n')
        for (i in cameras.indices) sb.append("f ").append(i).append(' ').appendVec(cameras[i]).append('\n')
        for (s in samples) {
            sb.append(if (s.crosshair) 'c' else 's').append(' ').append(s.frame).append(' ').append(s.kind).append(' ')
                .appendVec(s.point).append('\n')
        }
        sb.append(END).append('\n')
        return sb.toString()
    }

    companion object {
        const val BEGIN = "=== wall-capture v1 ==="
        const val END = "=== end wall-capture ==="
        private const val NONE = "none"

        /** 0.1 mm: far below the depth noise, and it keeps a capture of 3,000 samples under 100 kB. */
        private const val DECIMALS = 4
        private const val SCALE = 10_000.0

        /**
         * Every complete capture found in [text], in order. Lines outside a block, unknown lines inside one
         * and blocks that are cut off or damaged are skipped, so a log file with captures in it can be passed
         * as it is.
         */
        fun decodeAll(text: String): List<WallCaptureRecord> {
            val out = ArrayList<WallCaptureRecord>()
            var builder: Builder? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line == BEGIN -> builder = Builder()
                    line == END -> {
                        builder?.build()?.let { out += it }
                        builder = null
                    }
                    builder != null -> if (!builder.accept(line)) builder = null
                }
            }
            return out
        }

        private fun StringBuilder.appendVec(v: Vec3): StringBuilder =
            appendFixed(v.x).append(' ').appendFixed(v.y).append(' ').appendFixed(v.z)

        /** Fixed-point with [DECIMALS] decimals and a '.' whatever the system locale; "nan" when not finite. */
        private fun StringBuilder.appendFixed(value: Double): StringBuilder {
            if (!value.isFinite()) return append("nan")
            val scaled = (value * SCALE).roundToLong()
            if (scaled < 0) append('-')
            val magnitude = abs(scaled)
            append(magnitude / SCALE.toLong()).append('.')
            val fraction = (magnitude % SCALE.toLong()).toString()
            repeat(DECIMALS - fraction.length) { append('0') }
            return append(fraction)
        }
    }

    /** Collects the lines of one block; [accept] answers false when the block is damaged beyond use. */
    private class Builder {
        private var time: Long? = null
        private var floor: Double? = null
        private var ceiling: Double? = null
        private var viewer: Vec3? = null
        private var aim: Vec3? = null
        private var plane: Segment2? = null
        private var note = ""
        private val cameras = ArrayList<Vec3>()
        private val samples = ArrayList<Sample>()

        fun accept(line: String): Boolean {
            if (line.isEmpty()) return true
            val space = line.indexOf(' ')
            val key = if (space < 0) line else line.substring(0, space)
            val rest = if (space < 0) "" else line.substring(space + 1).trim()
            return try {
                when (key) {
                    "time" -> time = rest.toLong()
                    "floor" -> floor = rest.toDouble()
                    "ceiling" -> ceiling = if (rest == NONE) null else rest.toDouble()
                    "viewer" -> viewer = vec(rest.split(' '), 0)
                    "aim" -> aim = if (rest == NONE) null else vec(rest.split(' '), 0)
                    "plane" -> {
                        val n = rest.split(' ').map { it.toDouble() }
                        plane = Segment2(Vec2(n[0], n[1]), Vec2(n[2], n[3]))
                    }
                    "note" -> note = rest
                    "f" -> cameras += vec(rest.split(' '), 1)
                    "s", "c" -> {
                        val parts = rest.split(' ')
                        samples += Sample(parts[0].toInt(), parts[1].single(), key == "c", vec(parts, 2))
                    }
                    else -> Unit // a line this version does not know
                }
                true
            } catch (e: RuntimeException) {
                false // a number that does not parse or a missing field: the block cannot be trusted
            }
        }

        fun build(): WallCaptureRecord? {
            val at = time ?: return null
            val floorY = floor ?: return null
            val from = viewer ?: return null
            if (!floorY.isFinite() || !from.isFinite() || samples.any { !it.point.isFinite() }) return null
            return WallCaptureRecord(at, floorY, ceiling, from, aim, cameras, samples, plane, note)
        }

        private fun vec(parts: List<String>, start: Int) =
            Vec3(parts[start].toDouble(), parts[start + 1].toDouble(), parts[start + 2].toDouble())
    }
}
