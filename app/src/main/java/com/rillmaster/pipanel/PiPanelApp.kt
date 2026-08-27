package com.rillmaster.pipanel

import android.app.Application
import android.util.Log
import com.rillmaster.pipanel.vpn.WgTunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class PiPanelApp : Application() {
    lateinit var settingsManager: SettingsManager
    lateinit var updateManager: UpdateManager
    lateinit var vpnManager: WgTunnelManager

    /** Scope lié au processus applicatif (survit à la destruction des Activity). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        
        try {
            // On l'ajoute en position 1 pour qu'il soit prioritaire sur les implémentations système
            Security.removeProvider("BC")
            val result = Security.insertProviderAt(BouncyCastleProvider(), 1)
            Log.d("PiPanelApp", "BouncyCastle registered at position $result")
        } catch (e: Exception) {
            Log.e("PiPanelApp", "Failed to register BouncyCastle", e)
        }

        instance = this
        settingsManager = SettingsManager(this)
        settingsManager.loadTerminalTheme()
        updateManager = UpdateManager(this)
        vpnManager = WgTunnelManager(this)
        vpnManager.refreshState()
    }

    companion object {
        lateinit var instance: PiPanelApp
            private set
    }
}
