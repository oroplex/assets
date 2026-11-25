package com.oroplex.floorplan.data.settings

import com.oroplex.floorplan.core.measure.Units
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.UnitSystem
import com.oroplex.floorplan.data.KeyValueStore
import com.oroplex.floorplan.data.LocaleDefaults
import com.oroplex.floorplan.data.PrefsSettingsRepository
import com.oroplex.floorplan.data.sanitized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** First-run defaults by country (PREM-11). */
class LocaleDefaultsTest {

    @Test
    fun `only the US, Liberia and Myanmar start in feet and inches`() {
        for (country in listOf("US", "LR", "MM", "us", " US ")) {
            assertEquals(country, UnitSystem.IMPERIAL, LocaleDefaults.unitSystem(country))
        }
        for (country in listOf("GB", "CA", "AU", "DE", "FR", "IN", "JP", "BR", "MX", "ZA", "", "419", "XX")) {
            assertEquals(country, UnitSystem.METRIC, LocaleDefaults.unitSystem(country))
        }
    }

    @Test
    fun `imperial defaults are the model's own`() {
        assertEquals(PlanSettings(), LocaleDefaults.settings("US"))
        assertEquals(PlanSettings(), LocaleDefaults.forUnits(UnitSystem.IMPERIAL))
    }

    @Test
    fun `metric defaults are round metric numbers`() {
        val m = LocaleDefaults.settings("DE")
        assertEquals(UnitSystem.METRIC, m.unitSystem)
        assertEquals(0.10, m.wallThickness, 0.0)
        assertEquals(2.40, m.defaultCeilingHeight, 0.0)
        assertEquals(0.80, m.defaultDoorWidth, 0.0)
        assertEquals(2.04, m.defaultDoorHeight, 0.0)
        assertEquals(1.20, m.defaultWindowWidth, 0.0)
        assertEquals(1.20, m.defaultWindowHeight, 0.0)
        assertEquals(0.90, m.defaultWindowSill, 0.0)
        assertEquals(0.15, m.exteriorWallThickness!!, 0.0)
        // They read as round numbers, unlike the imperial defaults shown in meters ("2.44 m", "81 cm").
        fun text(v: Double) = Units.formatLength(v, UnitSystem.METRIC)
        assertEquals("2.40 m", text(m.defaultCeilingHeight))
        assertEquals("80 cm", text(m.defaultDoorWidth))
        assertEquals("10 cm", text(m.wallThickness))
        assertEquals("15 cm", text(m.exteriorWallThickness!!))
        assertEquals("2.44 m", text(PlanSettings().defaultCeilingHeight))
        // Everything that is not a size is the same in both systems.
        val i = PlanSettings()
        assertEquals(i.squareAngles, m.squareAngles)
        assertEquals(i.squareToleranceDeg, m.squareToleranceDeg, 0.0)
        assertEquals(i.showWallLengths, m.showWallLengths)
        assertEquals(i.showRoomAreas, m.showRoomAreas)
        assertEquals(i.showOverallDimensions, m.showOverallDimensions)
        // And they pass the repository's own sanity check unchanged.
        assertEquals(m, m.sanitized())
        assertEquals(i, i.sanitized())
    }

    @Test
    fun `switching units swaps sizes that are still defaults and keeps the user's own`() {
        val imperial = PlanSettings()
        val metric = LocaleDefaults.forUnits(UnitSystem.METRIC)
        assertEquals(metric, LocaleDefaults.switchUnits(imperial, UnitSystem.METRIC))
        assertEquals(imperial, LocaleDefaults.switchUnits(metric, UnitSystem.IMPERIAL))
        // Choosing the system already in use changes nothing.
        assertEquals(imperial, LocaleDefaults.switchUnits(imperial, UnitSystem.IMPERIAL))

        // A 9 ft ceiling and a hidden label are the user's choices: they survive, the rest follows.
        val custom = imperial.copy(defaultCeilingHeight = 2.7432, showRoomAreas = false, imperialDenominator = 8, squareAngles = false)
        val switched = LocaleDefaults.switchUnits(custom, UnitSystem.METRIC)
        assertEquals(UnitSystem.METRIC, switched.unitSystem)
        assertEquals(2.7432, switched.defaultCeilingHeight, 0.0)
        assertEquals(false, switched.showRoomAreas)
        assertEquals(8, switched.imperialDenominator)
        assertEquals(false, switched.squareAngles)
        assertEquals(metric.wallThickness, switched.wallThickness, 0.0)
        assertEquals(metric.defaultDoorWidth, switched.defaultDoorWidth, 0.0)
        assertEquals(metric.exteriorWallThickness, switched.exteriorWallThickness)
        // And back again without losing it.
        assertEquals(custom, LocaleDefaults.switchUnits(switched, UnitSystem.IMPERIAL))
    }

    @Test
    fun `exterior walls set to follow the interior ones stay that way`() {
        val same = PlanSettings(exteriorWallThickness = null)
        assertNull(LocaleDefaults.switchUnits(same, UnitSystem.METRIC).exteriorWallThickness)
        val own = PlanSettings(exteriorWallThickness = 0.30)
        assertEquals(0.30, LocaleDefaults.switchUnits(own, UnitSystem.METRIC).exteriorWallThickness!!, 0.0)
    }
}

class SettingsSanitizingTest {

    @Test
    fun `exterior wall thickness is kept when sane and replaced when not`() {
        assertEquals(0.30, PlanSettings(exteriorWallThickness = 0.30).sanitized().exteriorWallThickness!!, 0.0)
        // Everything the editor's project settings accept (5-80 cm) passes.
        assertEquals(0.05, PlanSettings(exteriorWallThickness = 0.05).sanitized().exteriorWallThickness!!, 0.0)
        assertEquals(0.80, PlanSettings(exteriorWallThickness = 0.80).sanitized().exteriorWallThickness!!, 0.0)
        // 0 means "measure the interior extents" and null "same as interior walls": both are valid.
        assertEquals(0.0, PlanSettings(exteriorWallThickness = 0.0).sanitized().exteriorWallThickness!!, 0.0)
        assertNull(PlanSettings(exteriorWallThickness = null).sanitized().exteriorWallThickness)
        // Nonsense falls back to the built-in default, like every other size.
        val fallback = PlanSettings().exteriorWallThickness!!
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 7.0)) {
            assertEquals(fallback, PlanSettings(exteriorWallThickness = bad).sanitized().exteriorWallThickness!!, 0.0)
            assertEquals(
                fallback,
                PlanSettings(unitSystem = UnitSystem.METRIC, exteriorWallThickness = bad).sanitized().exteriorWallThickness!!,
                0.0,
            )
        }
    }

    @Test
    fun `other sizes keep falling back to the built-in defaults and valid values are untouched`() {
        // The editor relies on this fallback (PlanEditorViewModel.updateSettings); it must not change here.
        val bad = PlanSettings(unitSystem = UnitSystem.METRIC, wallThickness = Double.NaN, defaultCeilingHeight = 0.0, defaultDoorWidth = -1.0)
        val fixed = bad.sanitized()
        val d = PlanSettings()
        assertEquals(UnitSystem.METRIC, fixed.unitSystem)
        assertEquals(d.wallThickness, fixed.wallThickness, 0.0)
        assertEquals(d.defaultCeilingHeight, fixed.defaultCeilingHeight, 0.0)
        assertEquals(d.defaultDoorWidth, fixed.defaultDoorWidth, 0.0)
        val ok = PlanSettings(unitSystem = UnitSystem.METRIC, wallThickness = 0.1143, defaultCeilingHeight = 2.4384)
        assertEquals(ok, ok.sanitized())
    }
}

class PrefsSettingsRepositoryTest {

    /** In-memory stand-in for SharedPreferences that counts writes. */
    private class MemoryStore : KeyValueStore {
        val strings = mutableMapOf<String, String>()
        var writes = 0

        override fun getString(key: String): String? = strings[key]
        override fun getBoolean(key: String): Boolean = false
        override fun putStrings(values: Map<String, String>) {
            writes++
            strings.putAll(values)
        }
        override fun putBoolean(key: String, value: Boolean) = Unit
    }

    private fun repo(store: MemoryStore, country: String) = PrefsSettingsRepository(store) { country }

    @Test
    fun `a fresh install in a metric country starts metric`() {
        val repository = repo(MemoryStore(), "GB")
        assertEquals(LocaleDefaults.forUnits(UnitSystem.METRIC), repository.defaults.value)
        assertEquals(UnitSystem.METRIC, repository.defaults.value.unitSystem)
    }

    @Test
    fun `a fresh install in the US starts in feet and inches`() {
        assertEquals(PlanSettings(), repo(MemoryStore(), "US").defaults.value)
    }

    @Test
    fun `first-run defaults are stored at once, so a later locale change never flips them`() {
        val store = MemoryStore()
        val first = repo(store, "FR")
        assertEquals(1, store.writes)
        assertNotNull(store.strings[PrefsSettingsRepository.KEY])
        // The phone's region changes to the US (or the user travels): the app keeps what it decided.
        val later = repo(store, "US")
        assertEquals(first.defaults.value, later.defaults.value)
        assertEquals(UnitSystem.METRIC, later.defaults.value.unitSystem)
        assertEquals(1, store.writes) // nothing rewritten on a normal start
    }

    @Test
    fun `the country is only asked when nothing is stored`() {
        val store = MemoryStore()
        repo(store, "DE")
        var asked = 0
        PrefsSettingsRepository(store) {
            asked++
            "US"
        }
        assertEquals(0, asked)
    }

    @Test
    fun `updates are published, sanitized and persisted`() {
        val store = MemoryStore()
        val repository = repo(store, "US")
        repository.update { it.copy(unitSystem = UnitSystem.METRIC, exteriorWallThickness = 0.3, wallThickness = Double.NaN) }
        val updated = repository.defaults.value
        assertEquals(UnitSystem.METRIC, updated.unitSystem)
        assertEquals(0.3, updated.exteriorWallThickness!!, 0.0)
        assertEquals(PlanSettings().wallThickness, updated.wallThickness, 0.0) // NaN replaced by the built-in default
        assertEquals(2, store.writes)
        assertEquals(updated, repo(store, "US").defaults.value)

        // A no-op update writes nothing.
        repository.update { it }
        assertEquals(2, store.writes)
    }

    @Test
    fun `unreadable stored settings start over from the country defaults`() {
        val store = MemoryStore()
        store.strings[PrefsSettingsRepository.KEY] = "{ not json"
        val repository = repo(store, "AU")
        assertEquals(LocaleDefaults.forUnits(UnitSystem.METRIC), repository.defaults.value)
        // The repaired value replaced the broken one.
        assertEquals(repository.defaults.value, repo(store, "US").defaults.value)
    }

    @Test
    fun `settings stored by an older build load with the model's defaults for new fields`() {
        val store = MemoryStore()
        // Written before exterior wall thickness, overall dimensions and default widths existed.
        store.strings[PrefsSettingsRepository.KEY] =
            """{"unitSystem":"METRIC","imperialDenominator":4,"wallThickness":0.12,"defaultCeilingHeight":2.5,"someFutureField":1}"""
        val loaded = repo(store, "US").defaults.value
        assertEquals(UnitSystem.METRIC, loaded.unitSystem)
        assertEquals(4, loaded.imperialDenominator)
        assertEquals(0.12, loaded.wallThickness, 0.0)
        assertEquals(2.5, loaded.defaultCeilingHeight, 0.0)
        assertEquals(PlanSettings().exteriorWallThickness, loaded.exteriorWallThickness)
        assertTrue(loaded.showOverallDimensions)
        assertEquals(0, store.writes) // readable settings are left alone
    }
}
