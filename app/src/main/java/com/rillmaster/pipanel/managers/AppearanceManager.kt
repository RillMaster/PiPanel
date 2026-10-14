package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit

class AppearanceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)

    // ── Thème & sécurité ──────────────────────────────────────────────────────
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit { putString("theme", value) }

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", false)
        set(value) = prefs.edit { putBoolean("biometric_enabled", value) }

    var backgroundActivityEnabled: Boolean
        get() = prefs.getBoolean("background_activity_enabled", true)
        set(value) = prefs.edit { putBoolean("background_activity_enabled", value) }

    var monitoringServiceEnabled: Boolean
        get() = prefs.getBoolean("monitoring_service_enabled", false)
        set(value) = prefs.edit { putBoolean("monitoring_service_enabled", value) }
}