package com.example.raspberrycontroller

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)

    // ── Premier lancement ─────────────────────────────────────────────────────
    var isFirstLaunch: Boolean
        get()      = prefs.getBoolean("first_launch", true)
        set(value) = prefs.edit { putBoolean("first_launch", value) }

    // ── Connexion SSH ─────────────────────────────────────────────────────────
    var host: String
        get()      = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit { putString("host", value) }

    var port: Int
        get()      = prefs.getInt("port", 22)
        set(value) = prefs.edit { putInt("port", value) }

    var username: String
        get()      = prefs.getString("username", "pi") ?: "pi"
        set(value) = prefs.edit { putString("username", value) }

    var password: String
        get()      = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit { putString("password", value) }

    // ── Pi-hole ───────────────────────────────────────────────────────────────
    var piHolePassword: String
        get()      = prefs.getString("pihole_password", "") ?: ""
        set(value) = prefs.edit { putString("pihole_password", value) }

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

    // ── Raccourcis terminal ───────────────────────────────────────────────────
    var shortcuts: List<Pair<String, String>>
        get() {
            val json = prefs.getString("shortcuts", null) ?: return defaultShortcuts()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map {
                    val parts = arr.getString(it).split("|", limit = 2)
                    Pair(parts[0], if (parts.size > 1) parts[1] else parts[0])
                }
            } catch (_: Exception) { defaultShortcuts() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { (label, cmd) -> arr.put("$label|$cmd") }
            prefs.edit { putString("shortcuts", arr.toString()) }
        }

    fun defaultShortcuts() = listOf(
        Pair("ls",     "ls -la"),
        Pair("top",    "top -bn1 | head -20"),
        Pair("temp",   "cat /sys/class/thermal/thermal_zone0/temp"),
        Pair("df",     "df -h"),
        Pair("free",   "free -h"),
        Pair("uptime", "uptime"),
        Pair("reboot", "sudo reboot"),
    )

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
}