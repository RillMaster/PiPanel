package com.rillmaster.pipanel.managers

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rillmaster.pipanel.SshShortcut
import com.rillmaster.pipanel.ui.terminal.TerminalThemes
import com.rillmaster.pipanel.ui.terminal.CurrentTerminalTheme

class TerminalSettingsManager(
    private val context: Context,
    private val profileManager: ProfileManager
) {
    private val prefs = context.getSharedPreferences("ssh_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Thème Terminal ────────────────────────────────────────────────────────
    var terminalThemeName: String
        get() = prefs.getString("terminal_theme_v2", "Dracula") ?: "Dracula"
        set(value) {
            prefs.edit { putString("terminal_theme_v2", value) }
            com.rillmaster.pipanel.ui.terminal.TerminalThemes.find { it.name == value }?.let {
                com.rillmaster.pipanel.ui.terminal.CurrentTerminalTheme = it
            }
        }

    fun loadTerminalTheme() {
        val name = terminalThemeName
        com.rillmaster.pipanel.ui.terminal.TerminalThemes.find { it.name == name }?.let {
            com.rillmaster.pipanel.ui.terminal.CurrentTerminalTheme = it
        }
    }

    // ── Raccourcis terminal ───────────────────────────────────────────────────
    var sshShortcuts: List<SshShortcut>
        get() {
            val currentProfile = profileManager.getCurrentProfile()
            currentProfile?.shortcuts?.let { return it }
            val json = prefs.getString("ssh_shortcuts_v2", null)
            if (json == null) {
                // Migration from v1
                val oldJson = prefs.getString("shortcuts", null)
                if (oldJson != null) {
                    try {
                        val arr = org.json.JSONArray(oldJson)
                        val migrated = (0 until arr.length()).map {
                            val parts = arr.getString(it).split("|", limit = 2)
                            val label = parts[0]
                            val cmd = if (parts.size > 1) parts[1] else parts[0]
                            SshShortcut(label = label, commands = listOf(cmd))
                        }
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
            val current = profileManager.getCurrentProfile()
            if (current != null) {
                profileManager.updateCurrentProfile { it.copy(shortcuts = value) }
            } else {
                // No current profile, store in legacy location
                val json = gson.toJson(value)
                prefs.edit { putString("ssh_shortcuts_v2", json) }
            }
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
}