package com.oroplex.floorplan.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.io.File

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

/** SharedPreferences-backed implementation; logo stored at filesDir/branding/logo.png. */
class PrefsBrandingRepository(context: Context) : BrandingRepository {
    private val appContext = context.applicationContext
    override val branding: StateFlow<Branding> get() = TODO("implement")
    override fun update(transform: (Branding) -> Branding): Unit = TODO("implement")
    override suspend fun setLogo(source: Uri): Result<File> = TODO("implement")
    override fun clearLogo(): Unit = TODO("implement")
    override fun isFlagSet(key: String): Boolean = TODO("implement")
    override fun setFlag(key: String, value: Boolean): Unit = TODO("implement")
}
