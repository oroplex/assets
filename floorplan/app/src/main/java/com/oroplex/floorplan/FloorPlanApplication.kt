package com.oroplex.floorplan

import android.app.Application
import com.oroplex.floorplan.data.AppContainer

class FloorPlanApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
