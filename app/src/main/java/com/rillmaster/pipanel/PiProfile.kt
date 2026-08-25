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
    /** Raccourcis SSH spécifiques à ce profil. */
    val shortcuts: List<SshShortcut>? = null
) {
    /** True si ce profil utilise une clé SSH plutôt qu'un mot de passe. */
    val useSshKey: Boolean get() = privateKey.isNotBlank()
}
