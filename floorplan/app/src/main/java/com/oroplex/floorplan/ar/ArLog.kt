package com.oroplex.floorplan.ar

import android.util.Log
import com.oroplex.floorplan.diagnostics.ScanLog
import java.util.Locale

/**
 * Device diagnostics of the AR layer, all under ONE logcat tag so a test session can be read with
 *
 *     adb logcat -s FloorPlanScan
 *
 * What is logged (see the callers): session lifecycle (create / resume / pause / errors, depth mode, camera
 * config, focus mode), tracking-state changes, floor-selection changes (candidates, chosen floors, vetoes,
 * whether auto-lock is allowed and why not), the floor the capture flow locked, and one line per measurement
 * (see [MeasurementReport]).
 *
 * Rules for callers:
 * - one concise line per event; anything evaluated per frame goes through [ChangeLog], [ThrottledLog] or
 *   [FramePerf], never straight to the log;
 * - no personal data: only geometry in the session's arbitrary world frame, counts, timings and device
 *   capabilities (no project names, addresses, room names or images);
 * - numbers are formatted with [fmt] (Locale.US), so a comma-decimal system locale cannot garble them.
 *
 * Logging never throws: a diagnostics problem must not cost a camera frame.
 */
internal object ArLog {
    const val TAG = "FloorPlanScan"

    fun i(message: String) = write(Log.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = write(Log.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = write(Log.ERROR, message, error)

    private fun write(priority: Int, message: String, error: Throwable?) {
        try {
            when (priority) {
                Log.ERROR -> if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
                Log.WARN -> if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
                else -> Log.i(TAG, message)
            }
        } catch (e: RuntimeException) {
            // Plain JVM unit tests run against the stubbed android.jar, whose Log methods throw.
        }
        // The same line goes to the on-device scan log, so a test without a computer attached can share it.
        val level = when (priority) {
            Log.ERROR -> "E "
            Log.WARN -> "W "
            else -> ""
        }
        ScanLog.record(level + message + (error?.let { " [" + it.javaClass.simpleName + ": " + it.message + "]" } ?: ""))
    }
}

/** Fixed-point number for a log line ("n/a" when not finite). Locale-independent. */
internal fun Double.fmt(decimals: Int): String =
    if (isFinite()) String.format(Locale.US, "%.${decimals.coerceIn(0, 6)}f", this) else "n/a"

/** [fmt] for a nullable value ("n/a" when null). */
internal fun Double?.fmtOrNa(decimals: Int): String = this?.fmt(decimals) ?: "n/a"

/** A length in meters shown in millimeters with one decimal, e.g. "8.2 mm". */
internal fun Double.fmtMm(): String = if (isFinite()) "${(this * 1000.0).fmt(1)} mm" else "n/a"

/** Nanoseconds as milliseconds with one decimal, e.g. "7.3". */
internal fun Long.nsToMs(): String = (this / 1e6).fmt(1)

/**
 * Turns a value that is evaluated on every frame into log lines only when it CHANGES, and at most one line
 * per [minIntervalNs]. A change that arrives too soon is not lost: as long as the caller keeps offering the
 * current value every frame, it is logged once the interval has passed (if it still differs from the last
 * logged value), together with the number of changes that were skipped in between, so a flapping state is
 * visible without flooding the log. Pure (returns the line instead of logging); not thread-safe.
 */
internal class ChangeLog(private val minIntervalNs: Long = 500_000_000L) {
    private var hasLogged = false
    private var loggedKey: Any? = null
    private var offeredKey: Any? = null
    private var lastLogNs = 0L
    private var changesSinceLog = 0

    /**
     * The line to log for the current [key] at [nowNs], or null. [message] is only built when needed.
     * [intervalNs] overrides the minimum interval for this call (e.g. a slower rate once the value matters
     * less).
     */
    fun offer(nowNs: Long, key: Any?, intervalNs: Long = minIntervalNs, message: () -> String): String? {
        if (!hasLogged || key != offeredKey) {
            if (hasLogged) changesSinceLog++
            offeredKey = key
        }
        if (hasLogged && key == loggedKey) return null
        if (hasLogged && nowNs - lastLogNs in 0 until intervalNs) return null
        val skipped = changesSinceLog - 1
        hasLogged = true
        loggedKey = key
        lastLogNs = nowNs
        changesSinceLog = 0
        val line = message()
        return if (skipped > 0) "$line (+$skipped changes in between)" else line
    }
}

/**
 * Rate-limited warning for errors that can repeat every frame: the first occurrence and then every
 * [every]-th one are logged (with the running count). Not thread-safe (GL thread).
 */
internal class ThrottledLog(private val every: Int = 300) {
    private var count = 0

    fun warn(message: String, error: Throwable) {
        if (count % every.coerceAtLeast(1) == 0) ArLog.w("$message (occurrence ${count + 1})", error)
        count++
    }
}

/**
 * Frame-time bookkeeping of the GL thread, summarized in one log line per [periodNs] (default 10 s) instead
 * of per frame. "update" is ARCore's Session.update(); "processing" is everything this app does with a new
 * camera image (hit tests, floor tracking, measurements, publishing the frame state). A frame is "slow" when
 * update + processing exceeds [SLOW_FRAME_NS], i.e. it could not keep up with a 30 fps camera.
 * Pure (returns the line); not thread-safe.
 */
internal class FramePerf(private val periodNs: Long = 10_000_000_000L) {
    private var windowStartNs = Long.MIN_VALUE
    private var drawn = 0
    private var images = 0
    private var updateSumNs = 0L
    private var updateMaxNs = 0L
    private var processSumNs = 0L
    private var processMaxNs = 0L
    private var slow = 0

    /**
     * Records one drawn frame. [newImage] is false when ARCore returned the previous camera image again (a
     * 120 Hz display draws more often than the camera delivers). Returns the summary line when a period ended.
     */
    fun onFrame(nowNs: Long, newImage: Boolean, updateNs: Long, processNs: Long): String? {
        if (windowStartNs == Long.MIN_VALUE || nowNs < windowStartNs) reset(nowNs)
        drawn++
        updateSumNs += updateNs
        if (updateNs > updateMaxNs) updateMaxNs = updateNs
        if (newImage) {
            images++
            processSumNs += processNs
            if (processNs > processMaxNs) processMaxNs = processNs
            if (updateNs + processNs > SLOW_FRAME_NS) slow++
        }
        val elapsed = nowNs - windowStartNs
        if (elapsed < periodNs) return null
        val seconds = elapsed / 1e9
        val line = "perf ${seconds.fmt(0)}s: camera ${(images / seconds).fmt(1)} fps, drawn ${(drawn / seconds).fmt(0)} fps, " +
            "update avg ${(updateSumNs / drawn.coerceAtLeast(1)).nsToMs()} ms max ${updateMaxNs.nsToMs()}, " +
            "processing avg ${(processSumNs / images.coerceAtLeast(1)).nsToMs()} ms max ${processMaxNs.nsToMs()}, " +
            "slow frames (>${SLOW_FRAME_NS.nsToMs()} ms) $slow of $images"
        reset(nowNs)
        return line
    }

    /** Forgets the current window (after a pause, so the gap is not counted as one long period). */
    fun reset(nowNs: Long = Long.MIN_VALUE) {
        windowStartNs = nowNs
        drawn = 0
        images = 0
        updateSumNs = 0L
        updateMaxNs = 0L
        processSumNs = 0L
        processMaxNs = 0L
        slow = 0
    }

    companion object {
        /** One camera frame at 30 fps. */
        const val SLOW_FRAME_NS = 33_000_000L
    }
}
