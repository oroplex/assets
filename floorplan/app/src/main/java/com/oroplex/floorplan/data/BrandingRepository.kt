package com.oroplex.floorplan.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** Company details printed on exported plans (PDF/PNG header). App-wide, not per project. */
data class Branding(
    val companyName: String = "",
    /** Free-form contact line, e.g. phone · email · license #. */
    val contactLine: String = "",
    /** Absolute path of the stored logo PNG in app storage, or null. */
    val logoPath: String? = null,
) {
    val isEmpty: Boolean get() = companyName.isBlank() && contactLine.isBlank() && logoPath == null
}

interface BrandingRepository {
    val branding: StateFlow<Branding>
    fun update(transform: (Branding) -> Branding)

    /** Copy (and downscale to <= 1024 px, PNG) the picked image into app storage and set it as the logo. */
    suspend fun setLogo(source: Uri): Result<File>

    fun clearLogo()

    /** One-time flags (e.g. onboarding shown). */
    fun isFlagSet(key: String): Boolean
    fun setFlag(key: String, value: Boolean = true)
}

/**
 * A logo could not be imported. [message] is written for the user (shown verbatim), the cause is for logs.
 */
class LogoImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * SharedPreferences-backed implementation; logo stored at filesDir/branding/logo.png.
 *
 * The logo file itself is the source of truth for [Branding.logoPath]: it is non-null exactly while
 * the file exists, so preferences and storage can never disagree (e.g. after a restore from backup).
 * Text fields are persisted asynchronously (SharedPreferences.apply) after being published.
 */
class PrefsBrandingRepository internal constructor(
    private val store: KeyValueStore,
    private val logoDir: File,
    /** Decodes [Uri] content into a PNG at the given file. Null only in tests that never call [setLogo]. */
    private val importer: ((Uri, File) -> Unit)?,
    private val log: (message: String, error: Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) },
) : BrandingRepository {

    constructor(context: Context) : this(
        store = SharedPreferencesStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        logoDir = File(context.applicationContext.filesDir, LOGO_DIR),
        importer = AndroidLogoImporter(context.applicationContext)::import,
    )

    private val lock = Any()
    private val logoFile = File(logoDir, LOGO_FILE)
    private val state = MutableStateFlow(load())

    override val branding: StateFlow<Branding> = state.asStateFlow()

    /**
     * Applies [transform] and publishes the sanitized result (see [BrandingRules.sanitize]). The logo
     * can only be removed here (setting it to null deletes the file); a new logo must go through [setLogo].
     */
    override fun update(transform: (Branding) -> Branding) {
        synchronized(lock) {
            val current = state.value
            val requested = transform(current)
            if (requested.logoPath == null && current.logoPath != null) deleteLogoFile()
            val next = BrandingRules.sanitize(requested, logoFile.absolutePath, logoFile.isFile)
            if (next == current) return
            state.value = next
            store.putStrings(mapOf(KEY_COMPANY to next.companyName, KEY_CONTACT to next.contactLine))
        }
    }

    override suspend fun setLogo(source: Uri): Result<File> {
        val import = importer ?: return Result.failure(LogoImportException(BrandingRules.MSG_UNREADABLE))
        return installLogo { target -> import(source, target) }
    }

    /**
     * Writes the logo through [write] into a temporary file and, only when that succeeds, moves it over
     * the current logo, so a failed import never destroys the logo already in place. Failures are
     * returned as [LogoImportException]s with a user-facing message; cancellation is rethrown.
     */
    internal suspend fun installLogo(write: (File) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            if (!logoDir.isDirectory && !logoDir.mkdirs()) throw IOException("Cannot create ${logoDir.path}")
            // A unique temporary file, so two imports racing each other can't write into the same file.
            val partial = File.createTempFile("logo", ".partial", logoDir).also { temp = it }
            write(partial)
            if (!partial.isFile || partial.length() == 0L) throw LogoImportException(BrandingRules.MSG_UNREADABLE)
            synchronized(lock) {
                // rename(2) replaces the old logo atomically; the fallback covers file systems that refuse.
                if (!partial.renameTo(logoFile) && !(logoFile.delete() && partial.renameTo(logoFile))) {
                    throw IOException("Cannot move ${partial.name} to ${logoFile.name}")
                }
                state.value = state.value.copy(logoPath = logoFile.absolutePath)
            }
            Result.success(logoFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LogoImportException) {
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            Result.failure(LogoImportException(BrandingRules.MSG_TOO_LARGE, e))
        } catch (e: Exception) {
            // IOException, SecurityException (grant revoked), IllegalArgumentException (bad provider) ...
            log("Logo import failed", e)
            Result.failure(LogoImportException(BrandingRules.MSG_SAVE_FAILED, e))
        } finally {
            temp?.let { if (it.exists()) it.delete() }
        }
    }

    override fun clearLogo() {
        synchronized(lock) {
            deleteLogoFile()
            if (state.value.logoPath != null) state.value = state.value.copy(logoPath = null)
        }
    }

    override fun isFlagSet(key: String): Boolean = store.getBoolean(BrandingRules.flagKey(key))

    override fun setFlag(key: String, value: Boolean) = store.putBoolean(BrandingRules.flagKey(key), value)

    private fun deleteLogoFile() {
        if (logoFile.exists() && !logoFile.delete()) log("Couldn't delete ${logoFile.path}", null)
    }

    private fun load(): Branding = BrandingRules.sanitize(
        Branding(
            companyName = store.getString(KEY_COMPANY).orEmpty(),
            contactLine = store.getString(KEY_CONTACT).orEmpty(),
            logoPath = logoFile.absolutePath,
        ),
        logoFile.absolutePath,
        logoFile.isFile,
    )

    internal companion object {
        const val PREFS_NAME = "branding"
        const val LOGO_DIR = "branding"
        const val LOGO_FILE = "logo.png"
        const val KEY_COMPANY = "company_name"
        const val KEY_CONTACT = "contact_line"
        private const val TAG = "BrandingRepository"
    }
}

/** Pure rules for branding data, shared by the repository and the settings UI. */
object BrandingRules {
    const val MAX_COMPANY_LENGTH = 80
    const val MAX_CONTACT_LENGTH = 120

    /** Longest side of the stored logo, in pixels. */
    const val MAX_LOGO_PX = 1024

    const val MSG_UNREADABLE = "That image couldn't be opened. Try a PNG or JPEG file."
    const val MSG_TOO_LARGE = "That image is too large to use as a logo. Try a smaller file."
    const val MSG_SAVE_FAILED = "Couldn't save the logo. Check that storage isn't full and try again."

    fun flagKey(key: String): String = "flag.$key"

    /**
     * Makes a text field printable on one line: control characters (incl. line breaks and tabs) become
     * spaces and the result is capped at [maxLength] characters. Leading/trailing spaces are kept so
     * the value round-trips while the user is still typing; exports trim it.
     */
    fun cleanText(text: String, maxLength: Int): String {
        val sb = StringBuilder(minOf(text.length, maxLength))
        for (ch in text) {
            if (sb.length >= maxLength) break
            sb.append(if (ch.isISOControl() || ch == ' ' || ch == ' ') ' ' else ch)
        }
        return sb.toString()
    }

    /**
     * Cleans [candidate]'s text fields and pins its logo to the one file this app manages:
     * [Branding.logoPath] is [logoPath] when [logoExists] and the candidate keeps a logo, else null.
     */
    fun sanitize(candidate: Branding, logoPath: String, logoExists: Boolean): Branding = Branding(
        companyName = cleanText(candidate.companyName, MAX_COMPANY_LENGTH),
        contactLine = cleanText(candidate.contactLine, MAX_CONTACT_LENGTH),
        logoPath = if (candidate.logoPath != null && logoExists) logoPath else null,
    )

    /**
     * BitmapFactory sample size for decoding a [width] x [height] image whose longer side should end up
     * at most [maxDimension]: the largest power of two that still leaves the longer side >= [maxDimension]
     * (the final, exact downscale is done with filtering, which looks better than sampling).
     */
    fun sampleSize(width: Int, height: Int, maxDimension: Int = MAX_LOGO_PX): Int {
        val longer = max(width, height)
        if (longer <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (longer / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    /** Size of a [width] x [height] image scaled down (never up) so its longer side is <= [maxDimension]. */
    fun scaledSize(width: Int, height: Int, maxDimension: Int = MAX_LOGO_PX): Pair<Int, Int> {
        val longer = max(width, height)
        if (width <= 0 || height <= 0 || longer <= maxDimension) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val f = maxDimension.toDouble() / longer
        return (width * f).roundToInt().coerceAtLeast(1) to (height * f).roundToInt().coerceAtLeast(1)
    }

    /**
     * Rotation (degrees, clockwise; applied first) and horizontal mirroring (applied after the rotation)
     * that make an image with EXIF orientation [orientation] (1..8) upright. Unknown values mean "as stored".
     */
    fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
        2 -> ExifTransform(0, mirror = true)
        3 -> ExifTransform(180, mirror = false)
        4 -> ExifTransform(180, mirror = true)
        5 -> ExifTransform(90, mirror = true)
        6 -> ExifTransform(90, mirror = false)
        7 -> ExifTransform(270, mirror = true)
        8 -> ExifTransform(270, mirror = false)
        else -> ExifTransform(0, mirror = false)
    }

    data class ExifTransform(val rotationDegrees: Int, val mirror: Boolean) {
        val isIdentity: Boolean get() = rotationDegrees == 0 && !mirror
    }
}

/** The few key-value operations [PrefsBrandingRepository] needs (SharedPreferences in the app). */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean
    fun putStrings(values: Map<String, String>)
    fun putBoolean(key: String, value: Boolean)
}

private class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    // A value of another type under one of our keys (old build, hand-edited backup) reads as absent.
    override fun getString(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: ClassCastException) {
        null
    }

    override fun getBoolean(key: String): Boolean = try {
        prefs.getBoolean(key, false)
    } catch (e: ClassCastException) {
        false
    }

    override fun putStrings(values: Map<String, String>) {
        val editor = prefs.edit()
        values.forEach { (k, v) -> editor.putString(k, v) }
        editor.apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/**
 * Decodes a picked image (any format BitmapFactory reads), applies its EXIF orientation, downscales it
 * to [BrandingRules.MAX_LOGO_PX] and writes it as PNG (keeping transparency). Blocking.
 */
private class AndroidLogoImporter(private val context: Context) {

    fun import(source: Uri, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw LogoImportException(BrandingRules.MSG_UNREADABLE)

        val orientation = try {
            open(source).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        } catch (e: IOException) {
            1 // no EXIF (PNG, WebP, ...) or unreadable metadata: use the pixels as stored
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = BrandingRules.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw LogoImportException(BrandingRules.MSG_UNREADABLE)

        val output = upright(decoded, BrandingRules.exifTransform(orientation))
        try {
            val ok = FileOutputStream(target).use { out ->
                val written = output.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.fd.sync()
                written
            }
            if (!ok) throw IOException("PNG encoding failed")
        } finally {
            if (output !== decoded) output.recycle()
            decoded.recycle()
        }
    }

    /** Opens [source]; a missing document or a revoked read grant counts as "couldn't be opened". */
    private fun open(source: Uri) = try {
        context.contentResolver.openInputStream(source)
    } catch (e: FileNotFoundException) {
        throw LogoImportException(BrandingRules.MSG_UNREADABLE, e)
    } catch (e: SecurityException) {
        throw LogoImportException(BrandingRules.MSG_UNREADABLE, e)
    } ?: throw LogoImportException(BrandingRules.MSG_UNREADABLE)

    /**
     * Scales to the final size, rotates, then mirrors (the EXIF order: e.g. "transpose" = rotate 90° and
     * flip horizontally) in one filtered pass. createBitmap re-origins the transformed bounds.
     */
    private fun upright(bitmap: Bitmap, transform: BrandingRules.ExifTransform): Bitmap {
        val (w, h) = BrandingRules.scaledSize(bitmap.width, bitmap.height)
        if (transform.isIdentity && w == bitmap.width && h == bitmap.height) return bitmap
        val matrix = Matrix().apply {
            postScale(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
            if (transform.rotationDegrees != 0) postRotate(transform.rotationDegrees.toFloat())
            if (transform.mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
