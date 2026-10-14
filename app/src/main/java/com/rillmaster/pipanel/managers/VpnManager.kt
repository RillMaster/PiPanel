package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit
import com.rillmaster.pipanel.model.Screen

class VpnManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)

    // ── WireGuard Local ──────────────────────────────────────────────────────
    var vpnPrivateKey: String
        get() = prefs.getString("vpn_private_key", "") ?: ""
        set(value) = prefs.edit { putString("vpn_private_key", value) }

    var vpnPublicKey: String
        get() = prefs.getString("vpn_public_key", "") ?: ""
        set(value) = prefs.edit { putString("vpn_public_key", value) }

    var vpnConfig: String
        get() = prefs.getString("vpn_config", "") ?: ""
        set(value) = prefs.edit { putString("vpn_config", value) }

    var vpnInterfaceAddresses: String
        get() = prefs.getString("vpn_interface_addresses", "10.0.0.2/32") ?: "10.0.0.2/32"
        set(value) = prefs.edit { putString("vpn_interface_addresses", value) }

    var vpnInterfaceListenPort: Int
        get() = prefs.getInt("vpn_interface_listen_port", 0)
        set(value) = prefs.edit { putInt("vpn_interface_listen_port", value) }

    var vpnInterfaceDns: String
        get() = prefs.getString("vpn_interface_dns", "1.1.1.1") ?: "1.1.1.1"
        set(value) = prefs.edit { putString("vpn_interface_dns", value) }

    var vpnInterfaceMtu: Int
        get() = prefs.getInt("vpn_interface_mtu", 0)
        set(value) = prefs.edit { putInt("vpn_interface_mtu", value) }

    var vpnPeerPublicKey: String
        get() = prefs.getString("vpn_peer_public_key", "") ?: ""
        set(value) = prefs.edit { putString("vpn_peer_public_key", value) }

    var vpnPeerPresharedKey: String
        get() = prefs.getString("vpn_peer_preshared_key", "") ?: ""
        set(value) = prefs.edit { putString("vpn_peer_preshared_key", value) }

    var vpnPeerPersistentKeepalive: Int
        get() = prefs.getInt("vpn_peer_persistent_keepalive", 0)
        set(value) = prefs.edit { putInt("vpn_peer_persistent_keepalive", value) }

    var vpnPeerEndpoint: String
        get() = prefs.getString("vpn_peer_endpoint", "") ?: ""
        set(value) = prefs.edit { putString("vpn_peer_endpoint", value) }

    var vpnPeerAllowedIPs: String
        get() = prefs.getString("vpn_peer_allowed_ips", "0.0.0.0/0") ?: "0.0.0.0/0"
        set(value) = prefs.edit { putString("vpn_peer_allowed_ips", value) }
}