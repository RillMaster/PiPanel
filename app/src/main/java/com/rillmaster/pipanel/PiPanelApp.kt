package com.rillmaster.pipanel

import android.app.Application

class PiPanelApp : Application() {
    lateinit var settingsManager: SettingsManager
    lateinit var updateManager: UpdateManager

    override fun onCreate() {
        super.onCreate()
        
        try {
            // On l'ajoute en position 1 pour qu'il soit prioritaire sur les implémentations système
            java.security.Security.removeProvider("BC")
            val result = java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            android.util.Log.d("PiPanelApp", "BouncyCastle registered at position $result")
            
            // Log available algorithms for Ed25519
            val kpg = java.security.KeyPairGenerator.getInstance("Ed25519")
            android.util.Log.d("PiPanelApp", "Ed25519 KeyPairGenerator provider: ${kpg.provider.name}")
        } catch (e: Exception) {
            android.util.Log.e("PiPanelApp", "Failed to register BouncyCastle or find Ed25519", e)
        }

        instance = this
        settingsManager = SettingsManager(this)
        settingsManager.loadTerminalTheme()
        updateManager = UpdateManager(this)
    }

    companion object {
        lateinit var instance: PiPanelApp
            private set
    }
}
