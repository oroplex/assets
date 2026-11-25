package com.oroplex.floorplan.data

import android.content.Context
import com.oroplex.floorplan.core.model.PlanSettings
import com.oroplex.floorplan.core.model.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs

/** App-wide defaults; new projects copy these into Project.settings. */
interface SettingsRepository {
    val defaults: StateFlow<PlanSettings>
    fun update(transform: (PlanSettings) -> PlanSettings)
}

/**
 * The defaults a fresh install starts with, chosen from where the device is (pure, unit-tested).
 *
 * Only the United States, Liberia and Myanmar build and sell homes in feet and inches; every other country
 * gets metric units together with sizes that are round numbers in metric (a 2.40 m ceiling and an 80 cm
 * door, not "2.44 m" and "81 cm", which is what the imperial defaults look like in meters).
 */
object LocaleDefaults {
    /** ISO 3166 alpha-2 codes of the countries that use feet and inches. */
    private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")

    /** Units for [country] (an ISO 3166 alpha-2 code as in `Locale.getCountry()`; unknown or blank = metric). */
    fun unitSystem(country: String): UnitSystem =
        if (country.trim().uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) UnitSystem.IMPERIAL else UnitSystem.METRIC

    /** First-run defaults for a device in [country]. */
    fun settings(country: String): PlanSettings = forUnits(unitSystem(country))

    /**
     * The built-in defaults in [system]: for feet and inches the model's own defaults (4 1/2" walls, 8' ceiling,
     * 32" x 80" door, 36" x 48" window with a 36" sill, 6 1/2" exterior walls); for metric 10 cm walls, 2.40 m
     * ceiling, 0.80 x 2.04 m door, 1.20 x 1.20 m window with a 0.90 m sill, 15 cm exterior walls.
     */
    fun forUnits(system: UnitSystem): PlanSettings = when (system) {
        UnitSystem.IMPERIAL -> PlanSettings(unitSystem = UnitSystem.IMPERIAL)
        UnitSystem.METRIC -> PlanSettings(
            unitSystem = UnitSystem.METRIC,
            wallThickness = 0.10,
            defaultCeilingHeight = 2.40,
            defaultDoorWidth = 0.80,
            defaultDoorHeight = 2.04,
            defaultWindowWidth = 1.20,
            defaultWindowHeight = 1.20,
            defaultWindowSill = 0.90,
            exteriorWallThickness = 0.15,
        )
    }

    /**
     * [current] switched to [system] the way the welcome tour's units choice does it. Sizes that are still the
     * built-in defaults of the old system become the new system's defaults (so a first-time metric user never
     * sees "2.44 m"); sizes the user already changed, and every other setting, are kept.
     */
    fun switchUnits(current: PlanSettings, system: UnitSystem): PlanSettings {
        if (current.unitSystem == system) return current
        val old = forUnits(current.unitSystem)
        val new = forUnits(system)
        fun pick(value: Double, oldDefault: Double, newDefault: Double) =
            if (abs(value - oldDefault) < SAME_LENGTH) newDefault else value
        val exterior = current.exteriorWallThickness
        val oldExterior = old.exteriorWallThickness
        return current.copy(
            unitSystem = system,
            wallThickness = pick(current.wallThickness, old.wallThickness, new.wallThickness),
            defaultCeilingHeight = pick(current.defaultCeilingHeight, old.defaultCeilingHeight, new.defaultCeilingHeight),
            defaultDoorWidth = pick(current.defaultDoorWidth, old.defaultDoorWidth, new.defaultDoorWidth),
            defaultDoorHeight = pick(current.defaultDoorHeight, old.defaultDoorHeight, new.defaultDoorHeight),
            defaultWindowWidth = pick(current.defaultWindowWidth, old.defaultWindowWidth, new.defaultWindowWidth),
            defaultWindowHeight = pick(current.defaultWindowHeight, old.defaultWindowHeight, new.defaultWindowHeight),
            defaultWindowSill = pick(current.defaultWindowSill, old.defaultWindowSill, new.defaultWindowSill),
            // null ("same as interior walls") is a choice of its own and stays.
            exteriorWallThickness = if (exterior != null && oldExterior != null && abs(exterior - oldExterior) < SAME_LENGTH) {
                new.exteriorWallThickness
            } else {
                exterior
            },
        )
    }

    /** Lengths closer than this (m) are the same value (a stored default after a JSON round trip). */
    private const val SAME_LENGTH = 1e-6
}

/**
 * SharedPreferences-backed implementation (stores PlanSettings as JSON under one key).
 *
 * On the first run nothing is stored: the defaults then come from the device's country ([LocaleDefaults]) and
 * are saved at once, so they are decided exactly one time and never flip when the user later changes the
 * phone's language or region.
 *
 * @param country the device's ISO 3166 country code; only asked when nothing usable is stored.
 */
class PrefsSettingsRepository internal constructor(
    private val store: KeyValueStore,
    private val country: () -> String,
) : SettingsRepository {

    constructor(context: Context) : this(
        store = SharedPreferencesStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        country = { Locale.getDefault().country },
    )

    private val lock = Any()
    private val state = MutableStateFlow(load())

    override val defaults: StateFlow<PlanSettings> = state.asStateFlow()

    /**
     * Applies [transform] to the current defaults, publishes the result and persists it asynchronously
     * (SharedPreferences.apply). Updates are serialized so the stored value always matches the last
     * published one.
     */
    override fun update(transform: (PlanSettings) -> PlanSettings) {
        synchronized(lock) {
            val next = transform(state.value).sanitized()
            if (next == state.value) return
            state.value = next
            persist(next)
        }
    }

    /**
     * The stored settings, or, when there are none (first run) or they cannot be read, the defaults for the
     * device's country, which are stored right away (see the class comment).
     */
    private fun load(): PlanSettings {
        val stored = store.getString(KEY)
        if (stored != null) {
            try {
                return json.decodeFromString(PlanSettings.serializer(), stored).sanitized()
            } catch (e: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException: unreadable, start over below.
            }
        }
        return LocaleDefaults.settings(country()).sanitized().also(::persist)
    }

    private fun persist(settings: PlanSettings) {
        store.putStrings(mapOf(KEY to json.encodeToString(PlanSettings.serializer(), settings)))
    }

    internal companion object {
        const val PREFS_NAME = "settings"
        const val KEY = "plan_settings_json"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}

/**
 * Replaces invalid values (non-finite, non-positive, or physically meaningless, e.g. from an old or
 * hand-edited backup) with the built-in defaults so the rest of the app can trust the settings it renders
 * and solves with. The accepted ranges are deliberately much wider than what the UI offers. A null
 * [PlanSettings.exteriorWallThickness] is valid (it means "same as interior walls") and kept.
 */
fun PlanSettings.sanitized(): PlanSettings {
    val d = PlanSettings()
    fun Double.validOr(range: ClosedFloatingPointRange<Double>, fallback: Double) =
        if (isFinite() && this in range) this else fallback
    return copy(
        imperialDenominator = if (imperialDenominator in VALID_DENOMINATORS) imperialDenominator else d.imperialDenominator,
        wallThickness = wallThickness.validOr(0.01..1.0, d.wallThickness),
        squareToleranceDeg = squareToleranceDeg.validOr(0.0..45.0, d.squareToleranceDeg),
        defaultCeilingHeight = defaultCeilingHeight.validOr(0.5..20.0, d.defaultCeilingHeight),
        defaultDoorHeight = defaultDoorHeight.validOr(0.3..6.0, d.defaultDoorHeight),
        defaultWindowHeight = defaultWindowHeight.validOr(0.05..6.0, d.defaultWindowHeight),
        defaultWindowSill = defaultWindowSill.validOr(0.0..6.0, d.defaultWindowSill),
        defaultDoorWidth = defaultDoorWidth.validOr(0.2..5.0, d.defaultDoorWidth),
        defaultWindowWidth = defaultWindowWidth.validOr(0.1..6.0, d.defaultWindowWidth),
        // 0 is meaningful here (overall dimensions then measure the interior extents).
        exteriorWallThickness = exteriorWallThickness?.validOr(0.0..1.0, d.exteriorWallThickness ?: d.wallThickness),
    )
}

private val VALID_DENOMINATORS = setOf(1, 2, 4, 8, 16, 32, 64)
