package com.rillmaster.pipanel.vpn

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WgTunnelManager(private val context: Context) {
    private val backend: Backend by lazy { GoBackend(context) }
    
    private val _state = MutableStateFlow(Tunnel.State.DOWN)
    val state = _state.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName() = "PiPanelVpn"
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = newState
            Log.d("WgTunnelManager", "Tunnel state changed to: $newState")
        }
    }

    /**
     * Génère un nouveau couple de clés Curve25519 pour le client local.
     */
    fun generateKeyPair(): KeyPair = KeyPair()

    /**
     * Démarre le tunnel VPN avec la configuration fournie.
     */
    suspend fun startTunnel(configText: String) = withContext(Dispatchers.IO) {
        try {
            val config = Config.parse(configText.byteInputStream())
            backend.setState(tunnel, Tunnel.State.UP, config)
        } catch (e: Exception) {
            Log.e("WgTunnelManager", "Failed to start tunnel", e)
            throw e
        }
    }

    /**
     * Arrête le tunnel VPN.
     */
    suspend fun stopTunnel() = withContext(Dispatchers.IO) {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (e: Exception) {
            Log.e("WgTunnelManager", "Failed to stop tunnel", e)
        }
    }

    /**
     * Récupère l'état actuel du tunnel depuis le backend.
     */
    fun refreshState() {
        val currentState = backend.getState(tunnel)
        _state.value = currentState
        Log.d("WgTunnelManager", "Refreshed state: $currentState")
    }
}
