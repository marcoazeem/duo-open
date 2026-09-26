package com.duoopen

import android.app.Application
import com.duoopen.settings.DuoSettings

class DuoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The activity and the wallpaper service share this process and read
        // the same settings flow.
        DuoSettings.init(this)
        com.duoopen.overlay.OverlayFeature.initProcess(this)
    }
}
