package com.oroplex.floorplan

import android.app.Application
import android.os.Build
import com.oroplex.floorplan.data.AppContainer
import com.oroplex.floorplan.diagnostics.ScanLog
import com.oroplex.floorplan.diagnostics.WallCaptureLog
import java.io.File

class FloorPlanApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so that a crash anywhere later (including in the lines below) ends up in the scan log.
        val diagnostics = File(filesDir, "diagnostics")
        ScanLog.init(diagnostics)
        ScanLog.installCrashHandler()
        WallCaptureLog.init(diagnostics)
        ScanLog.add("App started on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        container = AppContainer(this)
    }
}
