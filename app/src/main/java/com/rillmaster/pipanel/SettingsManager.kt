package com.rillmaster.pipanel

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONArray

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Premier lancement ─────────────────────────────────────────────────────
    var isFirstLaunch: Boolean
        get()      = prefs.getBoolean("first_launch", true)
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
                // Les profils sont stockés chiffrés ; decrypt() retourne les
                // anciennes données en clair telles quelles pour la migration.
                gson.fromJson(CryptoManager.decrypt(json), type)
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
        get()      = getCurrentProfile()?.host ?: prefs.getString("host", "") ?: ""
        set(value) = updateCurrentProfile { it.copy(host = value) }

    var port: Int
        get()      = getCurrentProfile()?.port ?: prefs.getInt("port", 22)
        set(value) = updateCurrentProfile { it.copy(port = value) }

    var username: String
        get()      = getCurrentProfile()?.username ?: prefs.getString("username", "pi") ?: "pi"
        set(value) = updateCurrentProfile { it.copy(username = value) }

    var password: String
        get()      = getCurrentProfile()?.password ?: prefs.getString("password", "") ?: ""
        set(value) = updateCurrentProfile { it.copy(password = value) }

    var piHolePassword: String
        get()      = getCurrentProfile()?.piHolePassword ?: prefs.getString("pihole_password", "") ?: ""
        set(value) = updateCurrentProfile { it.copy(piHolePassword = value) }

    /** Clé privée SSH du profil courant (vide = auth par mot de passe). */
    var privateKey: String
        get()      = getCurrentProfile()?.privateKey ?: ""
        set(value) = updateCurrentProfile { it.copy(privateKey = value) }

    /** Passphrase de la clé privée du profil courant. */
    var keyPassphrase: String
        get()      = getCurrentProfile()?.keyPassphrase ?: ""
        set(value) = updateCurrentProfile { it.copy(keyPassphrase = value) }

    /** True si le profil courant s'authentifie par clé SSH. */
    val useSshKey: Boolean
        get() = privateKey.isNotBlank()

    private fun updateCurrentProfile(block: (PiProfile) -> PiProfile) {
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

    // ── Pi-hole (Global settings) ───────────────────────────────────────────
    var piHoleAutoRefresh: Boolean
        get()      = prefs.getBoolean("pihole_auto_refresh", true)
        set(value) = prefs.edit { putBoolean("pihole_auto_refresh", value) }

    var piHoleRefreshDelaySec: Int
        get()      = prefs.getInt("pihole_refresh_delay_sec", 30)
        set(value) = prefs.edit { putInt("pihole_refresh_delay_sec", value) }

    // ── Timeouts & rafraîchissement ───────────────────────────────────────────
    var sshTimeoutMs: Int
        get()      = prefs.getInt("ssh_timeout_ms", 8000)
        set(value) = prefs.edit { putInt("ssh_timeout_ms", value) }

    var tempRefreshMs: Int
        get()      = prefs.getInt("temp_refresh_ms", 2000)
        set(value) = prefs.edit { putInt("temp_refresh_ms", value) }

    // ── Thème & sécurité ──────────────────────────────────────────────────────
    var theme: String
        get()      = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit { putString("theme", value) }

    var biometricEnabled: Boolean
        get()      = prefs.getBoolean("biometric_enabled", false)
        set(value) = prefs.edit { putBoolean("biometric_enabled", value) }

    var backgroundActivityEnabled: Boolean
        get()      = prefs.getBoolean("background_activity_enabled", true)
        set(value) = prefs.edit { putBoolean("background_activity_enabled", value) }

    // ── Raccourcis terminal ───────────────────────────────────────────────────
    var sshShortcuts: List<SshShortcut>
        get() {
            val json = prefs.getString("ssh_shortcuts_v2", null)
            if (json == null) {
                // Migration from v1
                val oldJson = prefs.getString("shortcuts", null)
                if (oldJson != null) {
                    try {
                        val arr = JSONArray(oldJson)
                        val migrated = (0 until arr.length()).map {
                            val parts = arr.getString(it).split("|", limit = 2)
                            val label = parts[0]
                            val cmd = if (parts.size > 1) parts[1] else parts[0]
                            SshShortcut(label = label, commands = listOf(cmd))
                        }
                        sshShortcuts = migrated
                        return migrated
                    } catch (_: Exception) {}
                }
                return defaultSshShortcuts()
            }
            return try {
                val type = object : TypeToken<List<SshShortcut>>() {}.type
                gson.fromJson(json, type)
            } catch (_: Exception) { defaultSshShortcuts() }
        }
        set(value) {
            val json = gson.toJson(value)
            prefs.edit { putString("ssh_shortcuts_v2", json) }
        }

    fun defaultSshShortcuts() = listOf(
        SshShortcut(label = "ls",     commands = listOf("ls -la")),
        SshShortcut(label = "top",    commands = listOf("top -bn1 | head -20")),
        SshShortcut(label = "temp",   commands = listOf("cat /sys/class/thermal/thermal_zone0/temp")),
        SshShortcut(label = "df",     commands = listOf("df -h")),
        SshShortcut(label = "free",   commands = listOf("free -h")),
        SshShortcut(label = "uptime", commands = listOf("uptime")),
        SshShortcut(label = "reboot", commands = listOf("sudo reboot")),
    )

    // Keep legacy property for compatibility if needed, but it will now use sshShortcuts
    var shortcuts: List<Pair<String, String>>
        get() = sshShortcuts.map { it.label to (it.commands.firstOrNull() ?: "") }
        set(value) {
            sshShortcuts = value.map { (label, cmd) -> SshShortcut(label = label, commands = listOf(cmd)) }
        }

    fun isConfigured(): Boolean = host.isNotEmpty() && username.isNotEmpty()

    // ══════════════════════════════════════════════════════════════════════════
    //  Notifications push
    // ══════════════════════════════════════════════════════════════════════════

    /** Active ou désactive toutes les notifications. */
    var notificationsEnabled: Boolean
        get()      = prefs.getBoolean("notif_enabled", true)
        set(value) = prefs.edit { putBoolean("notif_enabled", value) }

    // ── CPU ───────────────────────────────────────────────────────────────────
    /** Active les alertes CPU. */
    var cpuAlertsEnabled: Boolean
        get()      = prefs.getBoolean("cpu_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("cpu_alerts_enabled", value) }

    /** Seuil CPU en pourcentage (0-100) déclenchant une alerte. */
    var cpuThreshold: Int
        get()      = prefs.getInt("cpu_threshold", 85)
        set(value) = prefs.edit { putInt("cpu_threshold", value) }

    // ── RAM ───────────────────────────────────────────────────────────────────
    /** Active les alertes RAM. */
    var ramAlertsEnabled: Boolean
        get()      = prefs.getBoolean("ram_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("ram_alerts_enabled", value) }

    /** Seuil RAM en pourcentage (0-100) déclenchant une alerte. */
    var ramThreshold: Int
        get()      = prefs.getInt("ram_threshold", 90)
        set(value) = prefs.edit { putInt("ram_threshold", value) }

    // ── Watchdog ──────────────────────────────────────────────────────────────
    /** Active l'alerte si le Pi devient injoignable. */
    var watchdogEnabled: Boolean
        get()      = prefs.getBoolean("watchdog_enabled", true)
        set(value) = prefs.edit { putBoolean("watchdog_enabled", value) }

    /** Intervalle en secondes entre chaque tentative de contact du Pi. */
    var watchdogIntervalSeconds: Int
        get()      = prefs.getInt("watchdog_interval", 30)
        set(value) = prefs.edit { putInt("watchdog_interval", value) }

    // ── Docker ────────────────────────────────────────────────────────────────
    /** Active les alertes quand un conteneur Docker s'arrête. */
    var dockerAlertsEnabled: Boolean
        get()      = prefs.getBoolean("docker_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("docker_alerts_enabled", value) }

    // ── Disque ────────────────────────────────────────────────────────────────
    /** Active les alertes d'espace disque sur la partition racine. */
    var diskAlertsEnabled: Boolean
        get()      = prefs.getBoolean("disk_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("disk_alerts_enabled", value) }

    /** Seuil d'utilisation disque en pourcentage (0-100) déclenchant une alerte. */
    var diskThreshold: Int
        get()      = prefs.getInt("disk_threshold", 85)
        set(value) = prefs.edit { putInt("disk_threshold", value) }

    // ── Services critiques ────────────────────────────────────────────────────
    /** Active la surveillance des services systemd critiques. */
    var serviceAlertsEnabled: Boolean
        get()      = prefs.getBoolean("service_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("service_alerts_enabled", value) }

    /** Liste CSV des services systemd à surveiller (ex : "ssh,docker,pihole-FTL"). */
    var criticalServices: String
        get()      = prefs.getString("critical_services", "ssh,docker,pihole-FTL") ?: "ssh,docker,pihole-FTL"
        set(value) = prefs.edit { putString("critical_services", value) }

    /** Liste normalisée des services critiques (CSV → List, sans espaces vides). */
    val criticalServicesList: List<String>
        get() = criticalServices.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // ── Détection des services ────────────────────────────────────────────────
    fun isServiceInstalled(screen: Screen): Boolean {
        // Services are now profile-dependent? Probably should be.
        // For now, let's prefix by profile ID if available
        val profileId = currentProfileId ?: "default"
        return prefs.getBoolean("svc_installed_${profileId}_${screen.name}", true)
    }

    fun setServiceInstalled(screen: Screen, installed: Boolean) {
        val profileId = currentProfileId ?: "default"
        prefs.edit { putBoolean("svc_installed_${profileId}_${screen.name}", installed) }
    }

    var lastServiceScan: Long
        get() {
            val profileId = currentProfileId ?: "default"
            return prefs.getLong("last_service_scan_$profileId", 0L)
        }
        set(value) {
            val profileId = currentProfileId ?: "default"
            prefs.edit { putLong("last_service_scan_$profileId", value) }
        }

    // ── Favoris gestionnaire de fichiers ─────────────────────────────────────
    var fileManagerBookmarks: List<String>
        get() {
            val json = prefs.getString("file_manager_bookmarks", null)
            return if (json == null) emptyList()
            else try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type)
            } catch (_: Exception) { emptyList() }
        }
        set(value) = prefs.edit { putString("file_manager_bookmarks", gson.toJson(value)) }

    // ── Planifications GPIO ───────────────────────────────────────────────────
    var gpioSchedules: List<GpioSchedule>
        get() {
            val profileId = currentProfileId ?: "default"
            val json = prefs.getString("gpio_schedules_$profileId", null)
            return if (json == null) emptyList()
            else try {
                val type = object : TypeToken<List<GpioSchedule>>() {}.type
                gson.fromJson(json, type)
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val profileId = currentProfileId ?: "default"
            val json = gson.toJson(value)
            prefs.edit { putString("gpio_schedules_$profileId", json) }
        }
}
