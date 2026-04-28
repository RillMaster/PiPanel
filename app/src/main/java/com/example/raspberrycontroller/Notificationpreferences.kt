package com.example.raspberrycontroller

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stocke et lit tous les paramètres de notification dans les SharedPreferences.
 */
class NotificationPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE)

    // ── Activation globale ────────────────────────────────────────────────────
    var notificationsEnabled: Boolean
        get()      = prefs.getBoolean(KEY_NOTIF_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIF_ENABLED, value) }

    // ── CPU ───────────────────────────────────────────────────────────────────
    var cpuAlertsEnabled: Boolean
        get()      = prefs.getBoolean(KEY_CPU_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_CPU_ENABLED, value) }

    var cpuThreshold: Int                          // pourcentage 0-100
        get()      = prefs.getInt(KEY_CPU_THRESHOLD, 85)
        set(value) = prefs.edit { putInt(KEY_CPU_THRESHOLD, value) }

    // ── RAM ───────────────────────────────────────────────────────────────────
    var ramAlertsEnabled: Boolean
        get()      = prefs.getBoolean(KEY_RAM_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_RAM_ENABLED, value) }

    var ramThreshold: Int                          // pourcentage 0-100
        get()      = prefs.getInt(KEY_RAM_THRESHOLD, 90)
        set(value) = prefs.edit { putInt(KEY_RAM_THRESHOLD, value) }

    // ── Watchdog ──────────────────────────────────────────────────────────────
    var watchdogEnabled: Boolean
        get()      = prefs.getBoolean(KEY_WATCHDOG_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_WATCHDOG_ENABLED, value) }

    var watchdogIntervalSeconds: Int               // intervalle de ping (secondes)
        get()      = prefs.getInt(KEY_WATCHDOG_INTERVAL, 30)
        set(value) = prefs.edit { putInt(KEY_WATCHDOG_INTERVAL, value) }

    // ── Docker ────────────────────────────────────────────────────────────────
    var dockerAlertsEnabled: Boolean
        get()      = prefs.getBoolean(KEY_DOCKER_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_DOCKER_ENABLED, value) }

    companion object {
        private const val KEY_NOTIF_ENABLED     = "notif_enabled"
        private const val KEY_CPU_ENABLED       = "cpu_alerts_enabled"
        private const val KEY_CPU_THRESHOLD     = "cpu_threshold"
        private const val KEY_RAM_ENABLED       = "ram_alerts_enabled"
        private const val KEY_RAM_THRESHOLD     = "ram_threshold"
        private const val KEY_WATCHDOG_ENABLED  = "watchdog_enabled"
        private const val KEY_WATCHDOG_INTERVAL = "watchdog_interval"
        private const val KEY_DOCKER_ENABLED    = "docker_alerts_enabled"
    }
}