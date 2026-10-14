package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.rillmaster.pipanel.PiProfile
import com.rillmaster.pipanel.CryptoManager
import com.google.gson.reflect.TypeToken
import java.util.UUID

class ProfileManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Premier lancement ─────────────────────────────────────────────────────
    var isFirstLaunch: Boolean
        get() = prefs.getBoolean("first_launch", true)
        set(value) = prefs.edit { putBoolean("first_launch", value) }

    // ── Profils de connexion ──────────────────────────────────────────────────
    var currentProfileId: String?
        get() = prefs.getString("current_profile_id", null)
        set(value) = prefs.edit { putString("current_profile_id", value) }

    var profiles: List<PiProfile>
        get() {
            val json = prefs.getString("profiles_list", null)
            if (json == null) {
                // Migration logic: create a profile from legacy settings
                val host = prefs.getString("host", "") ?: ""
                if (host.isNotEmpty()) {
                    val legacyProfile = PiProfile(
                        name = "Raspberry Pi",
                        host = host,
                        port = prefs.getInt("port", 22),
                        username = prefs.getString("username", "pi") ?: "pi",
                        password = prefs.getString("password", "") ?: "",
                        piHolePassword = prefs.getString("pihole_password", "") ?: ""
                    )
                    val list = listOf(legacyProfile)
                    val newJson = CryptoManager.encrypt(gson.toJson(list))
                    prefs.edit {
                        putString("profiles_list", newJson)
                        putString("current_profile_id", legacyProfile.id)
                        // Nettoie les anciennes clés en clair
                        remove("password")
                        remove("pihole_password")
                    }
                    return list
                }
                return emptyList()
            }
            return try {
                val type = object : TypeToken<List<PiProfile>>() {}.type
                val decrypted = CryptoManager.decrypt(json)
                val list: List<PiProfile> = gson.fromJson(decrypted, type)

                // On s'assure qu'aucun champ n'est nul (Gson bypass les constructeurs et les valeurs par défaut)
                // On cast en (String?) pour forcer le check car à l'exécution ça PEUT être nul malgré le type Kotlin non-null.
                list.map { p ->
                    PiProfile(
                        id = (p.id as String?) ?: UUID.randomUUID().toString(),
                        name = (p.name as String?) ?: "Raspberry Pi",
                        host = (p.host as String?) ?: "",
                        port = if (p.port == 0) 22 else p.port,
                        username = (p.username as String?) ?: "pi",
                        password = (p.password as String?) ?: "",
                        piHolePassword = (p.piHolePassword as String?) ?: "",
                        privateKey = (p.privateKey as String?) ?: "",
                        keyPassphrase = (p.keyPassphrase as String?) ?: "",
                        sshKeyId = p.sshKeyId,
                        shortcuts = p.shortcuts,
                        vpnPrivateKey = (p.vpnPrivateKey as String?) ?: "",
                        vpnPublicKey = (p.vpnPublicKey as String?) ?: "",
                        vpnConfig = (p.vpnConfig as String?) ?: "",
                        vpnInterfaceAddresses = (p.vpnInterfaceAddresses as String?) ?: "10.0.0.2/32",
                        vpnInterfaceListenPort = if (p.vpnInterfaceListenPort == 0) 0 else p.vpnInterfaceListenPort,
                        vpnInterfaceDns = (p.vpnInterfaceDns as String?) ?: "1.1.1.1",
                        vpnInterfaceMtu = if (p.vpnInterfaceMtu == 0) 0 else p.vpnInterfaceMtu,
                        vpnPeerPublicKey = (p.vpnPeerPublicKey as String?) ?: "",
                        vpnPeerPresharedKey = (p.vpnPeerPresharedKey as String?) ?: "",
                        vpnPeerPersistentKeepalive = if (p.vpnPeerPersistentKeepalive == 0) 0 else p.vpnPeerPersistentKeepalive,
                        vpnPeerEndpoint = (p.vpnPeerEndpoint as String?) ?: "",
                        vpnPeerAllowedIPs = (p.vpnPeerAllowedIPs as String?) ?: "0.0.0.0/0"
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val json = CryptoManager.encrypt(gson.toJson(value))
            prefs.edit { putString("profiles_list", json) }
        }

    fun getCurrentProfile(): PiProfile? {
        val id = currentProfileId
        val list = profiles
        if (id == null && list.isNotEmpty()) {
            currentProfileId = list.first().id
            return list.first()
        }
        return list.find { it.id == id } ?: list.firstOrNull()
    }

    // Proxy properties for backward compatibility and easy access to current profile
    var host: String
        get() = getCurrentProfile()?.host ?: prefs.getString("host", "") ?: ""
        set(value) = updateCurrentProfile { it.copy(host = value) }

    var port: Int
        get() = getCurrentProfile()?.port ?: prefs.getInt("port", 22)
        set(value) = updateCurrentProfile { it.copy(port = value) }

    var username: String
        get() = getCurrentProfile()?.username ?: prefs.getString("username", "pi") ?: "pi"
        set(value) = updateCurrentProfile { it.copy(username = value) }

    var password: String
        get() = getCurrentProfile()?.password ?: prefs.getString("password", "") ?: ""
        set(value) = updateCurrentProfile { it.copy(password = value) }

    var piHolePassword: String
        get() = getCurrentProfile()?.piHolePassword ?: prefs.getString("pihole_password", "") ?: ""
        set(value) = updateCurrentProfile { it.copy(piHolePassword = value) }

    var privateKey: String
        get() = getCurrentProfile()?.privateKey ?: ""
        set(value) = updateCurrentProfile { it.copy(privateKey = value) }

    var keyPassphrase: String
        get() = getCurrentProfile()?.keyPassphrase ?: ""
        set(value) = updateCurrentProfile { it.copy(keyPassphrase = value) }

    val useSshKey: Boolean
        get() = getCurrentProfile()?.let { it.privateKey.isNotEmpty() || it.sshKeyId != null } ?: false

    fun updateCurrentProfile(block: (PiProfile) -> PiProfile) {
        val current = getCurrentProfile()
        if (current != null) {
            val list = profiles.toMutableList()
            val index = list.indexOfFirst { it.id == current.id }
            if (index != -1) {
                list[index] = block(current)
                profiles = list
            }
        } else {
            // Create a new one if none exists (migration or first time)
            val newProfile = block(PiProfile(name = "Raspberry Pi", host = "", port = 22, username = "pi", password = ""))
            profiles = listOf(newProfile)
            currentProfileId = newProfile.id
        }
    }

    fun addProfile(profile: PiProfile) {
        val list = profiles.toMutableList()
        list.add(profile)
        profiles = list
        if (currentProfileId == null) {
            currentProfileId = profile.id
        }
    }

    fun deleteProfile(id: String) {
        val list = profiles.toMutableList()
        list.removeAll { it.id == id }
        profiles = list
        if (currentProfileId == id) {
            currentProfileId = list.firstOrNull()?.id
        }
    }

    fun isConfigured(): Boolean = host.isNotEmpty() && username.isNotEmpty()

    // ── Historique des commandes ──────────────────────────────────────────────
    fun getCommandHistory(profileId: String): List<String> {
        val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("command_history_$profileId", null)
        return if (json == null) emptyList()
        else try {
            val type = object : TypeToken<List<String>>() {}.type
            val gson = com.google.gson.Gson()
            gson.fromJson(json, type)
        } catch (_: Exception) { emptyList() }
    }

    fun saveCommandHistory(profileId: String, history: List<String>) {
        val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
        val gson = com.google.gson.Gson()
        val json = gson.toJson(history)
        prefs.edit { putString("command_history_$profileId", json) }
    }
}