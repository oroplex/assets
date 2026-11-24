package com.oroplex.floorplan.data

import android.content.Context
import com.oroplex.floorplan.core.model.PlanSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** App-wide defaults; new projects copy these into Project.settings. */
interface SettingsRepository {
    val defaults: StateFlow<PlanSettings>
    fun update(transform: (PlanSettings) -> PlanSettings)
}

/** SharedPreferences-backed implementation (stores PlanSettings as JSON under one key). */
class PrefsSettingsRepository(context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
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
            prefs.edit().putString(KEY, json.encodeToString(PlanSettings.serializer(), next)).apply()
        }
    }

    /** Missing or unreadable stored settings fall back to the built-in defaults instead of failing. */
    private fun load(): PlanSettings {
        val stored = try {
            prefs.getString(KEY, null)
        } catch (e: ClassCastException) {
            null // a value of another type under our key: ignore it
        } ?: return PlanSettings()
        return try {
            json.decodeFromString(PlanSettings.serializer(), stored).sanitized()
        } catch (e: IllegalArgumentException) {
            PlanSettings() // SerializationException is an IllegalArgumentException
        }
    }

    private companion object {
        const val KEY = "plan_settings_json"
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }
    }
}

/**
 * Replaces invalid values (non-finite, non-positive, or physically meaningless, e.g. from an old or
 * hand-edited backup) with the built-in defaults so the rest of the app can trust the settings it renders
 * and solves with. The accepted ranges are deliberately much wider than what the UI offers.
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
    )
}

private val VALID_DENOMINATORS = setOf(1, 2, 4, 8, 16, 32, 64)
