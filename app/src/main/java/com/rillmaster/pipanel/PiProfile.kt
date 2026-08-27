package com.rillmaster.pipanel

import java.util.UUID

data class PiProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "pi",
    val password: String = "",
    val piHolePassword: String = "",
    /** Clé privée SSH (format PEM/OpenSSH). Vide = authentification par mot de passe. */
    val privateKey: String = "",
    /** Passphrase de la clé privée (si protégée). */
    val keyPassphrase: String = "",
    /** ID de la clé SSH gérée par SshKeyManager. */
    val sshKeyId: String? = null,
    /** Raccourcis SSH spécifiques à ce profil. */
    val shortcuts: List<SshShortcut>? = null,
    /** Clé privée WireGuard locale. */
    val vpnPrivateKey: String = "",
    /** Clé publique WireGuard locale. */
    val vpnPublicKey: String = "",
    /** Configuration complète WireGuard (client). */
    val vpnConfig: String = "",
    
    // Paramètres détaillés pour l'éditeur
    val vpnInterfaceAddresses: String = "10.0.0.2/32",
    val vpnInterfaceListenPort: Int = 0,
    val vpnInterfaceDns: String = "1.1.1.1",
    val vpnInterfaceMtu: Int = 0,
    
    val vpnPeerPublicKey: String = "",
    val vpnPeerPresharedKey: String = "",
    val vpnPeerPersistentKeepalive: Int = 0,
    val vpnPeerEndpoint: String = "",
    val vpnPeerAllowedIPs: String = "0.0.0.0/0"
) {
    /** True si ce profil utilise une clé SSH plutôt qu'un mot de passe. */
    val useSshKey: Boolean get() = privateKey.isNotBlank() || sshKeyId != null
}
