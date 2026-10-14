package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.SystemStats

/**
 * Use case for watchdog monitoring (checking if device is reachable).
 */
class WatchdogUseCase(private val context: Context? = null) {

    fun checkUnreachable(
        stats: SystemStats?,
        settingsManager: SettingsManager,
        ctx: Context? = context
    ): Pair<String, String>? {
        if (stats != null) {
            // Device is reachable, no alert needed
            return null
        }

        if (!settingsManager.watchdogEnabled) {
            // Watchdog disabled, no alert needed
            return null
        }

        val targetCtx = ctx ?: return null
        val title = targetCtx.getString(R.string.notif_pi_unreachable_title)
        val message = targetCtx.getString(R.string.notif_pi_unreachable_msg)
        return Pair(title, message)
    }
}