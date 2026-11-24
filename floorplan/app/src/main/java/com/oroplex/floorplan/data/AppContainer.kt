package com.oroplex.floorplan.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.oroplex.floorplan.FloorPlanApplication

/** Minimal manual dependency container (one per process). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val projects: ProjectRepository = FileProjectRepository(appContext.filesDir)
    val settings: SettingsRepository = PrefsSettingsRepository(appContext)
}

/** Access the container from composables. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as FloorPlanApplication).container
