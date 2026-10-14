package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit
import com.rillmaster.pipanel.model.Screen
import com.rillmaster.pipanel.managers.ProfileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NotificationSettingsManager(
    private val context: Context,
    private val profileManager: ProfileManager
) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Notifications push ────────────────────────────────────────────────
    /** Active ou désactive toutes les notifications. */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notif_enabled", true)
        set(value) = prefs.edit { putBoolean("notif_enabled", value) }

    // ── CPU ───────────────────────────────────────────────────────────────────
    /** Active les alertes CPU. */
    var cpuAlertsEnabled: Boolean
        get() = prefs.getBoolean("cpu_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("cpu_alerts_enabled", value) }

    /** Seuil CPU en pourcentage (0-100) déclenchant une alerte. */
    var cpuThreshold: Int
        get() = prefs.getInt("cpu_threshold", 85)
        set(value) = prefs.edit { putInt("cpu_threshold", value) }

    // ── RAM ───────────────────────────────────────────────────────────────────
    /** Active les alertes RAM. */
    var ramAlertsEnabled: Boolean
        get() = prefs.getBoolean("ram_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("ram_alerts_enabled", value) }

    /** Seuil RAM en pourcentage (0-100) déclenchant une alerte. */
    var ramThreshold: Int
        get() = prefs.getInt("ram_threshold", 90)
        set(value) = prefs.edit { putInt("ram_threshold", value) }

    // ── Watchdog ──────────────────────────────────────────────────────────────
    /** Active l'alerte si le Pi devient injoignable. */
    var watchdogEnabled: Boolean
        get() = prefs.getBoolean("watchdog_enabled", true)
        set(value) = prefs.edit { putBoolean("watchdog_enabled", value) }

    /** Intervalle en secondes entre chaque tentative de contact du Pi. */
    var watchdogIntervalSeconds: Int
        get() = prefs.getInt("watchdog_interval", 30)
        set(value) = prefs.edit { putInt("watchdog_interval", value) }

    // ── Docker ────────────────────────────────────────────────────────────────
    /** Active les alertes quand un conteneur Docker s'arrête. */
    var dockerAlertsEnabled: Boolean
        get() = prefs.getBoolean("docker_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("docker_alerts_enabled", value) }

    // ── Disque ────────────────────────────────────────────────────────────────
    /** Active les alertes d'espace disque sur la partition racine. */
    var diskAlertsEnabled: Boolean
        get() = prefs.getBoolean("disk_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("disk_alerts_enabled", value) }

    /** Seuil d'utilisation disque en pourcentage (0-100) déclenchant une alerte. */
    var diskThreshold: Int
        get() = prefs.getInt("disk_threshold", 85)
        set(value) = prefs.edit { putInt("disk_threshold", value) }

    // ── Services critiques ────────────────────────────────────────────────
    /** Active la surveillance des services systemd critiques. */
    var serviceAlertsEnabled: Boolean
        get() = prefs.getBoolean("service_alerts_enabled", true)
        set(value) = prefs.edit { putBoolean("service_alerts_enabled", value) }

    /** Liste CSV des services systemd à surveiller (ex : "ssh,docker,pihole-FTL"). */
    var criticalServices: String
        get() = prefs.getString("critical_services", "ssh,docker,pihole-FTL") ?: "ssh,docker,pihole-FTL"
        set(value) = prefs.edit { putString("critical_services", value) }

    /** Liste normalisée des services critiques (CSV → List, sans espaces vides). */
    val criticalServicesList: List<String>
        get() = criticalServices.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // ── Détection des services ────────────────────────────────────────────
    fun isServiceInstalled(screen: Screen): Boolean {
        val profileId = profileManager.currentProfileId ?: "default"
        return prefs.getBoolean("svc_installed_${profileId}_${screen.name}", true)
    }

    fun setServiceInstalled(screen: Screen, installed: Boolean) {
        val profileId = profileManager.currentProfileId ?: "default"
        prefs.edit { putBoolean("svc_installed_${profileId}_${screen.name}", installed) }
    }

    var lastServiceScan: Long
        get() {
            val profileId = profileManager.currentProfileId ?: "default"
            return prefs.getLong("last_service_scan_$profileId", 0L)
        }
        set(value) {
            val profileId = profileManager.currentProfileId ?: "default"
            prefs.edit { putLong("last_service_scan_$profileId", value) }
        }
}