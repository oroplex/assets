package com.oroplex.floorplan.recording

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A finished recording: one zip ready to be sent to the computer. */
data class RecordingFile(val file: File, val name: String, val sizeBytes: Long, val modifiedMillis: Long)

/**
 * Where walk-through recordings live on the phone: a folder per recording while it is being made, one zip per
 * recording afterwards. [root] is app-private external storage (no permission needed, removed with the app);
 * [freeSpace] replaces the real free-space query in tests. Blocking file work; call from a background thread.
 */
class WalkthroughStore(private val root: File, private val freeSpace: (() -> Long)? = null) {
    /** A new, empty folder for a recording started at [nowMillis], named like `Walkthrough-20261125-1432`. */
    fun newRecordingDir(nowMillis: Long): File {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create ${root.path}")
        val base = "Walkthrough-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(nowMillis))
        var candidate = File(root, base)
        var n = 2
        while (candidate.exists() || File(root, candidate.name + ZIP).exists()) candidate = File(root, "$base-${n++}")
        if (!candidate.mkdirs()) throw IOException("Cannot create ${candidate.path}")
        return candidate
    }

    /** Finished recordings, newest first. */
    fun recordings(): List<RecordingFile> =
        root.listFiles { f -> f.isFile && f.name.endsWith(ZIP) }
            ?.map { RecordingFile(it, it.name.removeSuffix(ZIP), it.length(), it.lastModified()) }
            ?.sortedByDescending { it.modifiedMillis }
            .orEmpty()

    /**
     * Folders left behind by a recording that never got packaged (the app was closed or crashed while
     * recording): they still hold every frame written up to then, and [pack] turns them into a zip.
     */
    fun unfinished(): List<File> =
        root.listFiles { f -> f.isDirectory && File(f, WalkthroughFormat.RGB_DIR).isDirectory }?.sortedBy { it.name }.orEmpty()

    fun delete(recording: RecordingFile): Boolean = recording.file.delete()

    fun discard(directory: File) {
        if (directory.parentFile == root) directory.deleteRecursively()
    }

    /** Bytes that can still be written where recordings are kept. */
    fun freeBytes(): Long = freeSpace?.invoke() ?: ((root.takeIf { it.isDirectory } ?: root.parentFile)?.usableSpace ?: 0L)

    /**
     * Packs the recording folder [directory] into `<name>.zip` beside it and deletes the folder. JPEGs are
     * stored as they are (they do not compress), everything else is compressed. [onProgress] gets the share
     * done, 0..1. On failure the folder is kept and the partial zip removed.
     */
    fun pack(directory: File, onProgress: (Float) -> Unit = {}): RecordingFile {
        val files = directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }.toList()
        if (files.isEmpty()) throw IOException("Nothing was recorded")
        val total = files.sumOf { it.length() }.coerceAtLeast(1L)
        val target = File(root, directory.name + ZIP)
        val partial = File(root, directory.name + ZIP + ".partial")
        try {
            var done = 0L
            ZipOutputStream(partial.outputStream().buffered(BUFFER)).use { zip ->
                val buffer = ByteArray(BUFFER)
                for (file in files) {
                    val path = file.relativeTo(directory).path.replace(File.separatorChar, '/')
                    zip.setLevel(if (path.endsWith(".jpg")) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
                    zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                    file.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            done += n
                        }
                    }
                    zip.closeEntry()
                    onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                }
            }
            if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.name}")
            if (!partial.renameTo(target)) throw IOException("Cannot rename ${partial.name}")
        } finally {
            if (partial.exists()) partial.delete()
        }
        directory.deleteRecursively()
        return RecordingFile(target, target.name.removeSuffix(ZIP), target.length(), target.lastModified())
    }

    companion object {
        const val ZIP = ".zip"
        const val DIRECTORY = "walkthroughs"
        private const val BUFFER = 256 * 1024

        /** Room that must stay free while recording: packing needs the recording's size once more. */
        const val RESERVE_BYTES = 200L * 1024 * 1024

        /** True when a recording that has written [recordedBytes] must stop to leave room for packing it. */
        fun storageExhausted(freeBytes: Long, recordedBytes: Long): Boolean = freeBytes < recordedBytes + RESERVE_BYTES
    }
}
