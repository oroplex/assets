package com.oroplex.floorplan.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Export files live in `cacheDir/exports/` (declared as a FileProvider cache-path) so they can be
 * shared without storage permissions and are reclaimed by the system when space runs low.
 */
object ExportFiles {
    private const val DIR = "exports"

    /** Exports older than this are deleted the next time a file is written. */
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun exportDir(context: Context): File = File(context.cacheDir, DIR)

    /**
     * Writes a file named [fileName] into the export directory: [writer] streams into a temporary
     * file which then replaces the target, so a failed export never leaves a truncated file behind.
     * Blocking; call from a background dispatcher.
     */
    fun write(context: Context, fileName: String, writer: (OutputStream) -> Unit): File {
        val dir = exportDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        pruneOldFiles(dir, exceptName = fileName)
        val target = File(dir, fileName)
        val temp = File(dir, "$fileName.partial")
        try {
            FileOutputStream(temp).use { stream ->
                val buffered = NonClosingStream(stream.buffered())
                writer(buffered)
                buffered.flush()
                stream.fd.sync()
            }
            if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.name}")
            if (!temp.renameTo(target)) throw IOException("Cannot rename ${temp.name}")
        } finally {
            if (temp.exists()) temp.delete()
        }
        return target
    }

    private fun pruneOldFiles(dir: File, exceptName: String) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != exceptName && f.lastModified() < cutoff) f.delete()
        }
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * ACTION_SEND chooser for [file]. The read grant is attached both as a flag and through ClipData,
     * which is what lets the chosen target (and the chooser's own preview) open the content URI.
     */
    fun shareIntent(context: Context, file: File, mimeType: String, subject: String): Intent {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Copies [file] into a document the user picked through the Storage Access Framework
     * ("wt" truncates when the user chose to overwrite an existing document). Blocking.
     */
    fun copyToDocument(context: Context, file: File, destination: Uri) {
        val out = try {
            context.contentResolver.openOutputStream(destination, "wt")
        } catch (e: IllegalArgumentException) {
            // Some providers do not support the "wt" mode; plain "w" is the documented fallback.
            context.contentResolver.openOutputStream(destination, "w")
        } ?: throw IOException("The selected location cannot be written")
        out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
    }

    /** Lets writers close "their" stream (e.g. with `use`) without closing the file before it is synced. */
    private class NonClosingStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }
}

/** File naming for exports: `<project>-<floor>.<ext>` with characters that trip up other apps removed. */
object ExportFileNames {
    private const val MAX_PART_LENGTH = 60

    /**
     * Keeps letters (any script), digits, '-', '_', '.', '(' and ')'; whitespace runs become '_';
     * everything else is dropped. Leading dots / separators are stripped (no hidden files) and the
     * result is truncated to a sane length. Returns [fallback] when nothing usable remains.
     */
    fun sanitize(name: String, fallback: String): String {
        val sb = StringBuilder()
        var pendingSpace = false
        for (ch in name.trim()) {
            when {
                ch.isWhitespace() -> pendingSpace = true
                ch.isLetterOrDigit() || ch in "-_.()" -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append('_')
                    pendingSpace = false
                    sb.append(ch)
                }
                else -> Unit // drop path separators, quotes, emoji, control characters, ...
            }
        }
        val cleaned = sb.toString().trimStart('.', '_', '-').take(MAX_PART_LENGTH).trimEnd('.', '_', '-')
        return cleaned.ifEmpty { fallback }
    }

    /** [floorName] null = the file covers the whole project (all floors / JSON backup). */
    fun fileName(projectName: String, floorName: String?, extension: String): String {
        val project = sanitize(projectName, "Floor_plan")
        val base = if (floorName == null) project else "$project-${sanitize(floorName, "Floor")}"
        return "$base.$extension"
    }
}
