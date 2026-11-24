package com.oroplex.floorplan.data

import android.content.Context
import com.oroplex.floorplan.core.model.PlanSettings
import kotlinx.coroutines.flow.StateFlow

/** App-wide defaults; new projects copy these into Project.settings. */
interface SettingsRepository {
    val defaults: StateFlow<PlanSettings>
    fun update(transform: (PlanSettings) -> PlanSettings)
}

/** SharedPreferences-backed implementation (stores PlanSettings as JSON under one key). */
class PrefsSettingsRepository(context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override val defaults: StateFlow<PlanSettings> get() = TODO("implement")
    override fun update(transform: (PlanSettings) -> PlanSettings): Unit = TODO("implement")
}
