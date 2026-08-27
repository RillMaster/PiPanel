package com.rillmaster.pipanel

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tuile Quick Settings exécutant un raccourci SSH sans ouvrir l'app.
 *
 * Android exige une sous-classe déclarée dans le manifest par tuile ajoutable ;
 * [slot] identifie quel raccourci (via [SettingsManager.getTileShortcut]) la
 * tuile déclenche. 3 slots sont exposés : 0, 1, 2.
 */
abstract class QuickActionTileService : TileService() {

    /** Index du slot (0, 1 ou 2) — défini par chaque sous-classe. */
    protected abstract val slot: Int

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (running) return

        val settings = SettingsManager(applicationContext)
        val shortcut = settings.getTileShortcut(slot)
        if (shortcut == null || !settings.isConfigured()) {
            refreshTile()
            return
        }

        running = true
        updateTile(Tile.STATE_ACTIVE, shortcut.label, getString(R.string.tile_running))

        scope.launch {
            val success = runShortcut(settings, shortcut)
            // Retour visuel bref sur la tuile, puis retour à l'état normal
            updateTile(
                Tile.STATE_ACTIVE,
                shortcut.label,
                getString(if (success) R.string.tile_done else R.string.tile_error)
            )
            delay(2500)
            running = false
            refreshTile()
        }
    }

    private suspend fun runShortcut(settings: SettingsManager, shortcut: SshShortcut): Boolean {
        return try {
            for (cmd in shortcut.commands) {
                val out = SshClient.execute(
                    settings.host, settings.port, settings.username, settings.password,
                    cmd, settings.sshTimeoutMs, applicationContext,
                    settings.privateKey, settings.keyPassphrase
                )
                if (out.startsWith("[err]")) return false
            }
            true
        } catch (e: Exception) {
            Log.e("QuickTile", "Slot $slot failed", e)
            false
        }
    }

    private fun refreshTile() {
        val settings = SettingsManager(applicationContext)
        val shortcut = settings.getTileShortcut(slot)
        when {
            !settings.isConfigured() ->
                updateTile(Tile.STATE_UNAVAILABLE, getString(R.string.tile_not_configured), null)
            shortcut == null ->
                updateTile(Tile.STATE_UNAVAILABLE, getString(R.string.tile_empty_slot, slot + 1), null)
            else -> {
                val preview = shortcut.commands.joinToString(" && ")
                updateTile(Tile.STATE_INACTIVE, shortcut.label, preview)
            }
        }
    }

    private fun updateTile(state: Int, label: String, subtitle: String?) {
        val tile = qsTile ?: return
        tile.label = label
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        tile.state = state
        tile.icon = Icon.createWithResource(this, R.drawable.ic_widget_stats)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class QuickActionTile1Service : QuickActionTileService() { override val slot = 0 }
class QuickActionTile2Service : QuickActionTileService() { override val slot = 1 }
class QuickActionTile3Service : QuickActionTileService() { override val slot = 2 }
