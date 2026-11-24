package com.oroplex.floorplan.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.oroplex.floorplan.data.Branding
import com.oroplex.floorplan.data.BrandingRules
import java.io.File

/**
 * Company details as printed in the header band of every sheet (PDF pages, PNG, on-screen preview).
 * Text is trimmed; [logo] is already decoded at print resolution. Build with [of] / [SheetBrandings.load]
 * so an "empty" branding is represented by null rather than by an instance that prints nothing.
 */
data class SheetBranding(
    val companyName: String,
    val contactLine: String,
    val logo: Bitmap?,
) {
    companion object {
        /** Null when nothing would be printed (blank texts and no logo). */
        fun of(companyName: String, contactLine: String, logo: Bitmap?): SheetBranding? {
            val name = companyName.trim()
            val contact = contactLine.trim()
            if (name.isEmpty() && contact.isEmpty() && logo == null) return null
            return SheetBranding(name, contact, logo)
        }
    }
}

object SheetBrandings {
    private const val TAG = "SheetBrandings"

    /**
     * Longest side of the logo bitmap used for printing. The logo is drawn at most
     * [HeaderLayout.LOGO_MAX_WIDTH] pt wide, so 512 px is about 300 dpi on paper and keeps PDFs small.
     */
    const val PRINT_LOGO_PX = 512

    /**
     * Decodes [branding]'s logo for printing and returns what the sheets should show, or null when there
     * is nothing to print. A missing or unreadable logo file is skipped (the export goes ahead without
     * it), never an error. Blocking; call from a background dispatcher.
     */
    fun load(branding: Branding): SheetBranding? {
        val logo = branding.logoPath?.let { decodeLogo(File(it)) }
        return SheetBranding.of(branding.companyName, branding.contactLine, logo)
    }

    private fun decodeLogo(file: File): Bitmap? {
        if (!file.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = BrandingRules.sampleSize(bounds.outWidth, bounds.outHeight, PRINT_LOGO_PX)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
            val (w, h) = BrandingRules.scaledSize(decoded.width, decoded.height, PRINT_LOGO_PX)
            if (w == decoded.width && h == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to decode the logo; exporting without it", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't decode the logo; exporting without it", e)
            null
        }
    }
}
