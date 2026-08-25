package com.rillmaster.pipanel

import android.app.Application

class PiPanelApp : Application() {
    lateinit var settingsManager: SettingsManager
    lateinit var updateManager: UpdateManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsManager = SettingsManager(this)
        updateManager = UpdateManager(this)
    }

    companion object {
        lateinit var instance: PiPanelApp
            private set
    }
}
