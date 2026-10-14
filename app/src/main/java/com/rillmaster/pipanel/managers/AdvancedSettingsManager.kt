package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rillmaster.pipanel.GpioSchedule

class AdvancedSettingsManager(
    private val context: Context,
    private val profileManager: ProfileManager
) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Timeouts & rafraîchissement ───────────────────────────────────────────
    var sshTimeoutMs: Int
        get() = prefs.getInt("ssh_timeout_ms", 8000)
        set(value) { prefs.edit { putInt("ssh_timeout_ms", value) } }

    var tempRefreshMs: Int
        get() = prefs.getInt("temp_refresh_ms", 2000)
        set(value) { prefs.edit { putInt("temp_refresh_ms", value) } }

    // ── Pi-hole (Global settings) ───────────────────────────────────────────
    var piHoleAutoRefresh: Boolean
        get() = prefs.getBoolean("pihole_auto_refresh", true)
        set(value) { prefs.edit { putBoolean("pihole_auto_refresh", value) } }

    var piHoleRefreshDelaySec: Int
        get() = prefs.getInt("pihole_refresh_delay_sec", 30)
        set(value) { prefs.edit { putInt("pihole_refresh_delay_sec", value) } }

    // ── Favoris gestionnaire de fichiers ─────────────────────────────────────
    var fileManagerBookmarks: List<String>
        get() {
            val json = prefs.getString("file_manager_bookmarks", null)
            return if (json == null) emptyList()
            else try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) { prefs.edit { putString("file_manager_bookmarks", gson.toJson(value)) } }

    // ── Planifications GPIO ───────────────────────────────────────────────────
    var gpioSchedules: List<GpioSchedule>
        get() {
            val profileId = profileManager.currentProfileId ?: "default"
            val json = prefs.getString("gpio_schedules_$profileId", null)
            return if (json == null) emptyList()
            else try {
                val type = object : TypeToken<List<GpioSchedule>>() {}.type
                gson.fromJson<List<GpioSchedule>>(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val profileId = profileManager.currentProfileId ?: "default"
            val json = gson.toJson(value)
            prefs.edit { putString("gpio_schedules_$profileId", json) }
        }

    // ── Couleurs des graphiques ────────────────────────────────────────────────
    fun getChartColor(key: String, defaultHex: String): String {
        return prefs.getString("chart_color_$key", defaultHex) ?: defaultHex
    }

    fun setChartColor(key: String, hex: String) {
        prefs.edit { putString("chart_color_$key", hex) }
    }
}