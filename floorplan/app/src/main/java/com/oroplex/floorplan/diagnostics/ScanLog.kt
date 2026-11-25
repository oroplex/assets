package com.oroplex.floorplan.diagnostics

import android.util.Log
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A text file that never grows past [maxBytes]: when an append would exceed it, the oldest half is dropped
 * (cut at a line start) and a marker line says so. Not thread-safe; callers serialize access.
 */
internal class BoundedLogFile(private val file: File, private val maxBytes: Int) {
    fun append(text: String) {
        if (text.isEmpty()) return
        file.parentFile?.mkdirs()
        val incoming = text.toByteArray(Charsets.UTF_8)
        val current = if (file.exists()) file.length() else 0L
        if (current + incoming.size > maxBytes) {
            val kept = keepNewest(readBytes(), (maxBytes / 2 - incoming.size).coerceAtLeast(0))
            file.writeBytes(TRIMMED.toByteArray(Charsets.UTF_8) + kept)
        }
        file.appendBytes(if (incoming.size > maxBytes / 2) incoming.copyOfRange(incoming.size - maxBytes / 2, incoming.size) else incoming)
    }

    fun read(): String = String(readBytes(), Charsets.UTF_8)

    fun clear() {
        if (file.exists()) file.delete()
    }

    private fun readBytes(): ByteArray = if (file.exists()) file.readBytes() else ByteArray(0)

    /** The last [budget] bytes of [bytes], starting at a line start. */
    private fun keepNewest(bytes: ByteArray, budget: Int): ByteArray {
        if (bytes.size <= budget) return bytes
        var start = bytes.size - budget
        while (start < bytes.size && bytes[start - 1] != '\n'.code.toByte()) start++
        return bytes.copyOfRange(start, bytes.size)
    }

    companion object {
        const val TRIMMED = "… older entries were dropped to keep this log small …\n"
    }
}

/**
 * What the scanner did, kept on the phone so a problem seen on a job site can be diagnosed afterwards without
 * a computer attached: one line per measurement and floor / tracking decision from the AR layer, app starts,
 * and the stack trace of any crash. Settings → About → "Share scan log" sends it as a text file.
 *
 * It holds measurements and technical state only: no photos, no location, no names beyond what the user
 * typed for rooms. The file lives in the app's private storage, is capped at [MAX_BYTES], and leaves the
 * phone only when the user shares it. Every entry also goes to logcat under [TAG]
 * (`adb logcat -s FloorPlanScan`). Safe to call from any thread, including the GL thread: [add] only queues.
 */
object ScanLog {
    const val TAG = "FloorPlanScan"
    const val MAX_BYTES = 512 * 1024
    private const val FILE_NAME = "scan-log.txt"

    private val lock = Any()
    private var log: BoundedLogFile? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** One background thread writes entries in order; it never keeps the process alive. */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "scan-log").apply { isDaemon = true }
    }

    /** Start logging into [directory] (the app's private files). Until this is called entries only go to logcat. */
    fun init(directory: File) {
        synchronized(lock) { log = BoundedLogFile(File(directory, FILE_NAME), MAX_BYTES) }
    }

    /** Record one entry. Cheap and non-blocking; never throws. */
    fun add(message: String) {
        val now = System.currentTimeMillis()
        try {
            Log.i(TAG, message)
        } catch (e: Throwable) {
            // android.util.Log is not available in plain JVM unit tests.
        }
        try {
            writer.execute { write(now, message) }
        } catch (e: Throwable) {
            // Rejected (shutting down): logcat already has it.
        }
    }

    /** Record one entry in the file only, for callers that have already written it to logcat. Never throws. */
    fun record(message: String) {
        val now = System.currentTimeMillis()
        try {
            writer.execute { write(now, message) }
        } catch (e: Throwable) {
            // Rejected (shutting down): logcat already has it.
        }
    }

    /**
     * Record [message] before returning. For the crash handler: the process is about to die, so the queue
     * cannot be relied on.
     */
    fun addNow(message: String) = write(System.currentTimeMillis(), message)

    private fun write(timeMillis: Long, message: String) {
        try {
            synchronized(lock) {
                val target = log ?: return
                target.append(stamp.format(Date(timeMillis)) + "  " + message.trimEnd() + "\n")
            }
        } catch (e: Throwable) {
            // A full disk or a missing directory must never break scanning.
        }
    }

    /**
     * From now on an uncaught exception on any thread is written to the log (with its stack trace) before
     * the app closes as usual, so the next "Share scan log" shows what happened.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                addNow("CRASH on thread \"${thread.name}\"\n" + stackTrace(error))
            } catch (e: Throwable) {
                // Nothing more can be done here.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Write [header] and the whole log to [out], after everything queued so far has been written. */
    fun export(out: OutputStream, header: String) {
        flush()
        val text = synchronized(lock) { log?.read().orEmpty() }
        out.write(header.trimEnd().toByteArray(Charsets.UTF_8))
        out.write("\n\n".toByteArray(Charsets.UTF_8))
        out.write((text.ifEmpty { "(nothing recorded yet)\n" }).toByteArray(Charsets.UTF_8))
    }

    /** Wait (briefly) until queued entries are on disk. */
    fun flush() {
        try {
            writer.submit {}.get(2, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            // Export what is there.
        }
    }

    /** Forget everything recorded so far. */
    fun clear() {
        flush()
        synchronized(lock) { log?.clear() }
    }

    private fun stackTrace(error: Throwable): String {
        val text = java.io.StringWriter()
        error.printStackTrace(java.io.PrintWriter(text))
        return text.toString()
    }
}
