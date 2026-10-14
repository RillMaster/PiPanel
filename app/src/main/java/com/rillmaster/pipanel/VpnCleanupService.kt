package com.rillmaster.pipanel

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Service chargé de couper le VPN si l'application est "swipée" (fermée manuellement)
 * de la liste des applications récentes.
 */
class VpnCleanupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // L'utilisateur a enlevé l'app des tâches récentes
        scope.launch {
            try {
                PiPanelApp.instance.vpnManager.stopTunnel()
            } catch (_: Exception) {}
            stopSelf()
        }
    }
}
