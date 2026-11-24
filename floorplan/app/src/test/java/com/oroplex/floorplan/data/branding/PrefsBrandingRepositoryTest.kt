package com.oroplex.floorplan.data.branding

import com.oroplex.floorplan.data.Branding
import com.oroplex.floorplan.data.BrandingRules
import com.oroplex.floorplan.data.KeyValueStore
import com.oroplex.floorplan.data.LogoImportException
import com.oroplex.floorplan.data.PrefsBrandingRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PrefsBrandingRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** In-memory stand-in for SharedPreferences that counts writes. */
    private class MemoryStore : KeyValueStore {
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        var writes = 0

        override fun getString(key: String): String? = strings[key]
        override fun getBoolean(key: String): Boolean = booleans[key] ?: false
        override fun putStrings(values: Map<String, String>) {
            writes++
            strings.putAll(values)
        }
        override fun putBoolean(key: String, value: Boolean) {
            writes++
            booleans[key] = value
        }
    }

    private val logged = mutableListOf<String>()

    private fun repo(store: MemoryStore, dir: File = File(tmp.root, "branding")) =
        PrefsBrandingRepository(store, dir, importer = null, log = { m, _ -> logged += m })

    private val logoDir get() = File(tmp.root, "branding")
    private val logoFile get() = File(logoDir, "logo.png")

    private fun assertNoLeftovers() {
        val names = logoDir.listFiles().orEmpty().map { it.name }
        assertTrue(names.toString(), names.none { it.endsWith(".partial") })
    }

    @Test
    fun `starts empty and persists text updates`() {
        val store = MemoryStore()
        val repository = repo(store)
        assertEquals(Branding(), repository.branding.value)
        assertTrue(repository.branding.value.isEmpty)

        repository.update { it.copy(companyName = "Oak & Stone", contactLine = "555-0100 · hello@oak.example") }
        assertEquals("Oak & Stone", repository.branding.value.companyName)
        assertEquals("Oak & Stone", store.strings["company_name"])
        assertEquals("555-0100 · hello@oak.example", store.strings["contact_line"])

        // A fresh repository (next app start) reads the same values back.
        assertEquals(repository.branding.value, repo(store).branding.value)
    }

    @Test
    fun `unchanged values are not written again`() {
        val store = MemoryStore()
        val repository = repo(store)
        repository.update { it.copy(companyName = "A") }
        val writes = store.writes
        repository.update { it.copy(companyName = "A") }
        assertEquals(writes, store.writes)
    }

    @Test
    fun `text is cleaned and capped`() {
        val repository = repo(MemoryStore())
        repository.update { it.copy(companyName = "Line one\nLine\ttwo", contactLine = "x".repeat(500)) }
        assertEquals("Line one Line two", repository.branding.value.companyName)
        assertEquals(BrandingRules.MAX_CONTACT_LENGTH, repository.branding.value.contactLine.length)
    }

    @Test
    fun `update cannot point the logo at an arbitrary file`() {
        val repository = repo(MemoryStore())
        val foreign = tmp.newFile("elsewhere.png")
        repository.update { it.copy(logoPath = foreign.absolutePath) }
        assertNull(repository.branding.value.logoPath)
    }

    @Test
    fun `installing a logo publishes its path and survives a restart`() = runTest {
        val store = MemoryStore()
        val repository = repo(store)
        val result = repository.installLogo { target -> target.writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(result.isSuccess)
        assertEquals(logoFile.absolutePath, repository.branding.value.logoPath)
        assertTrue(logoFile.isFile)
        assertNoLeftovers()
        assertEquals(logoFile.absolutePath, repo(store).branding.value.logoPath)
    }

    @Test
    fun `replacing a logo swaps the file in place`() = runTest {
        val repository = repo(MemoryStore())
        repository.installLogo { it.writeBytes(byteArrayOf(1)) }
        repository.installLogo { it.writeBytes(byteArrayOf(2, 2)) }
        assertEquals(listOf<Byte>(2, 2), logoFile.readBytes().toList())
        assertEquals(logoFile.absolutePath, repository.branding.value.logoPath)
        assertNoLeftovers()
    }

    @Test
    fun `a failed import keeps the previous logo`() = runTest {
        val repository = repo(MemoryStore())
        repository.installLogo { it.writeBytes(byteArrayOf(7)) }
        val failure = repository.installLogo { throw LogoImportException(BrandingRules.MSG_UNREADABLE) }
        assertTrue(failure.isFailure)
        assertEquals(BrandingRules.MSG_UNREADABLE, failure.exceptionOrNull()?.message)
        assertEquals(listOf<Byte>(7), logoFile.readBytes().toList())
        assertEquals(logoFile.absolutePath, repository.branding.value.logoPath)
        assertNoLeftovers()
    }

    @Test
    fun `io errors and empty output become friendly failures`() = runTest {
        val repository = repo(MemoryStore())
        val io = repository.installLogo { throw IOException("disk full") }
        assertEquals(BrandingRules.MSG_SAVE_FAILED, io.exceptionOrNull()?.message)
        assertTrue(io.exceptionOrNull() is LogoImportException)
        assertTrue(logged.isNotEmpty())

        val empty = repository.installLogo { it.writeBytes(ByteArray(0)) }
        assertEquals(BrandingRules.MSG_UNREADABLE, empty.exceptionOrNull()?.message)

        val oom = repository.installLogo { throw OutOfMemoryError() }
        assertEquals(BrandingRules.MSG_TOO_LARGE, oom.exceptionOrNull()?.message)
        assertNull(repository.branding.value.logoPath)
        assertNoLeftovers()
    }

    @Test
    fun `clearing the logo deletes the file, also through update`() = runTest {
        val repository = repo(MemoryStore())
        repository.installLogo { it.writeBytes(byteArrayOf(1)) }
        repository.clearLogo()
        assertNull(repository.branding.value.logoPath)
        assertFalse(logoFile.exists())

        repository.installLogo { it.writeBytes(byteArrayOf(1)) }
        repository.update { it.copy(logoPath = null) }
        assertNull(repository.branding.value.logoPath)
        assertFalse(logoFile.exists())
    }

    @Test
    fun `a logo file deleted behind our back reads as no logo`() = runTest {
        val store = MemoryStore()
        repo(store).installLogo { it.writeBytes(byteArrayOf(1)) }
        logoFile.delete()
        assertNull(repo(store).branding.value.logoPath)
    }

    @Test
    fun `flags default to false and are namespaced`() {
        val store = MemoryStore()
        val repository = repo(store)
        assertFalse(repository.isFlagSet("onboarding_done"))
        repository.setFlag("onboarding_done")
        assertTrue(repository.isFlagSet("onboarding_done"))
        assertEquals(true, store.booleans["flag.onboarding_done"])
        assertTrue(repo(store).isFlagSet("onboarding_done"))
        repository.setFlag("onboarding_done", false)
        assertFalse(repository.isFlagSet("onboarding_done"))
        assertFalse(repository.isFlagSet("other"))
    }
}

class BrandingRulesTest {

    @Test
    fun `clean text replaces control characters and caps length`() {
        assertEquals("a b c", BrandingRules.cleanText("a\nb\rc", 10))
        assertEquals(" padded ", BrandingRules.cleanText(" padded ", 10)) // kept while typing
        assertEquals("abc", BrandingRules.cleanText("abcdef", 3))
        assertEquals("x y", BrandingRules.cleanText("x y", 10))
        assertEquals("Café · Ñandú ☎", BrandingRules.cleanText("Café · Ñandú ☎", 80))
    }

    @Test
    fun `sanitize pins the logo to the managed file`() {
        val path = "/data/app/files/branding/logo.png"
        assertEquals(path, BrandingRules.sanitize(Branding(logoPath = "/sdcard/x.png"), path, logoExists = true).logoPath)
        assertNull(BrandingRules.sanitize(Branding(logoPath = path), path, logoExists = false).logoPath)
        assertNull(BrandingRules.sanitize(Branding(logoPath = null), path, logoExists = true).logoPath)
    }

    @Test
    fun `sample size keeps the longer side at or above the target`() {
        assertEquals(1, BrandingRules.sampleSize(800, 600))
        assertEquals(1, BrandingRules.sampleSize(1024, 300))
        assertEquals(2, BrandingRules.sampleSize(2048, 1000))
        assertEquals(2, BrandingRules.sampleSize(4000, 3000))
        assertEquals(8, BrandingRules.sampleSize(12000, 9000)) // 108 MP photo -> 1500 x 1125
        assertEquals(1, BrandingRules.sampleSize(0, 0))
        assertEquals(1, BrandingRules.sampleSize(5000, 5000, maxDimension = 0))
        for (w in listOf(1025, 3000, 7999, 16384)) {
            val s = BrandingRules.sampleSize(w, w / 2)
            assertTrue(w / s >= BrandingRules.MAX_LOGO_PX)
            assertTrue(w / (s * 2) < BrandingRules.MAX_LOGO_PX)
        }
    }

    @Test
    fun `scaled size fits the limit, keeps the aspect and never upscales`() {
        assertEquals(1024 to 512, BrandingRules.scaledSize(2048, 1024))
        assertEquals(256 to 1024, BrandingRules.scaledSize(1000, 4000))
        assertEquals(300 to 200, BrandingRules.scaledSize(300, 200))
        assertEquals(1024 to 1, BrandingRules.scaledSize(100_000, 3)) // never collapses to zero
        assertEquals(1 to 1, BrandingRules.scaledSize(0, -5))
    }

    @Test
    fun `exif orientations map to rotate-then-mirror`() {
        assertTrue(BrandingRules.exifTransform(1).isIdentity)
        assertTrue(BrandingRules.exifTransform(0).isIdentity) // undefined
        assertTrue(BrandingRules.exifTransform(42).isIdentity)
        assertEquals(BrandingRules.ExifTransform(0, true), BrandingRules.exifTransform(2))
        assertEquals(BrandingRules.ExifTransform(180, false), BrandingRules.exifTransform(3))
        assertEquals(BrandingRules.ExifTransform(180, true), BrandingRules.exifTransform(4))
        assertEquals(BrandingRules.ExifTransform(90, true), BrandingRules.exifTransform(5))
        assertEquals(BrandingRules.ExifTransform(90, false), BrandingRules.exifTransform(6))
        assertEquals(BrandingRules.ExifTransform(270, true), BrandingRules.exifTransform(7))
        assertEquals(BrandingRules.ExifTransform(270, false), BrandingRules.exifTransform(8))
    }

    @Test
    fun `flag keys are namespaced`() {
        assertEquals("flag.onboarding_done", BrandingRules.flagKey("onboarding_done"))
    }
}
