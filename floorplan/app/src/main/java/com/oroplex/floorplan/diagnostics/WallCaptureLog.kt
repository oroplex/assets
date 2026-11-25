package com.oroplex.floorplan.diagnostics

import com.oroplex.floorplan.core.fit.WallCaptureRecord
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The raw samples of recent wall captures, one small text file each ([WallCaptureRecord.encode]) in
 * [directory]. Only the newest [maxFiles] are kept. Not thread-safe; callers serialize access.
 */
internal class WallCaptureStore(private val directory: File, private val maxFiles: Int) {
    private val sequence = AtomicInteger()

    fun save(record: WallCaptureRecord) {
        directory.mkdirs()
        val name = String.format(Locale.US, "%s%013d-%04d%s", PREFIX, record.timeMillis, sequence.getAndIncrement() % 10_000, SUFFIX)
        File(directory, name).writeText(record.encode(), Charsets.UTF_8)
        val files = files()
        for (i in 0 until files.size - maxFiles) files[i].delete()
    }

    /**
     * Writes the newest captures that fit in [maxBytes] to [out], oldest of them first, and answers how many
     * were written and how many are stored.
     */
    fun export(out: OutputStream, maxBytes: Long): Pair<Int, Int> {
        val files = files()
        var budget = maxBytes
        var first = files.size
        while (first > 0 && files[first - 1].length() <= budget) {
            budget -= files[first - 1].length()
            first--
        }
        for (i in first until files.size) out.write(files[i].readBytes())
        return (files.size - first) to files.size
    }

    fun clear() {
        files().forEach { it.delete() }
    }

    /** Stored captures, oldest first (the names start with the capture time). */
    private fun files(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            ?.sortedBy { it.name }
            .orEmpty()

    private companion object {
        const val PREFIX = "wall-"
        const val SUFFIX = ".txt"
    }
}

/**
 * Keeps what the camera saw of each wall, so that a measurement made on a job site can be recomputed later
 * with other settings instead of scanning again: every sample point of the newest [MAX_FILES] wall captures,
 * with the camera positions and what the app concluded. "Share scan log" appends them to the shared file.
 *
 * Like [ScanLog] it holds geometry in the AR session's arbitrary frame only (no photos, no location), lives
 * in the app's private storage and leaves the phone only when the user shares it. [record] only queues, so it
 * is safe on the GL thread; nothing here ever throws into the caller.
 */
object WallCaptureLog {
    /** About 90 kB per capture at most, so the folder stays under roughly 5 MB. */
    const val MAX_FILES = 60

    /** Upper bound of what one shared file carries (the newest captures win). */
    const val MAX_EXPORT_BYTES = 4L * 1024 * 1024
    private const val DIRECTORY = "wall-captures"

    private val lock = Any()
    private var store: WallCaptureStore? = null

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wall-capture-log").apply { isDaemon = true }
    }

    /** Start keeping captures under [directory] (the app's private diagnostics folder). */
    fun init(directory: File) {
        synchronized(lock) { store = WallCaptureStore(File(directory, DIRECTORY), MAX_FILES) }
    }

    /** Keep one capture. The record must not be changed afterwards; it is written on a background thread. */
    fun record(record: WallCaptureRecord) {
        try {
            writer.execute {
                try {
                    synchronized(lock) { store?.save(record) }
                } catch (e: Throwable) {
                    // A full disk must never break scanning.
                }
            }
        } catch (e: Throwable) {
            // Rejected (shutting down).
        }
    }

    /**
     * Append the stored captures to [out] (after a heading), once everything queued has been written. A
     * problem reading them costs only this section, never the log written before it.
     */
    fun export(out: OutputStream) {
        flush()
        val body = java.io.ByteArrayOutputStream()
        val heading = try {
            val (written, stored) = synchronized(lock) { store?.export(body, MAX_EXPORT_BYTES) } ?: (0 to 0)
            if (stored == 0) {
                "(no wall captures recorded yet)\n"
            } else {
                "$written of $stored recent wall captures, oldest first. Points are in metres in the scan's own frame.\n\n"
            }
        } catch (e: Exception) {
            body.reset()
            "(the wall captures could not be read: ${e.javaClass.simpleName})\n"
        }
        out.write("\n$HEADING\n$heading".toByteArray(Charsets.UTF_8))
        body.writeTo(out)
    }

    /** Wait (briefly) until queued captures are on disk. */
    fun flush() {
        try {
            writer.submit {}.get(2, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            // Export what is there.
        }
    }

    /** Forget every stored capture. */
    fun clear() {
        flush()
        try {
            synchronized(lock) { store?.clear() }
        } catch (e: Throwable) {
            // Nothing to clear.
        }
    }

    const val HEADING = "===== RAW WALL CAPTURES ====="
}
