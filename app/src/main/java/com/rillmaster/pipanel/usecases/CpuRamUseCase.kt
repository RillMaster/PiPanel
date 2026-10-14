package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.SystemStats

/**
 * Use case for checking CPU and RAM usage against thresholds.
 */
class CpuRamUseCase(private val context: Context? = null) {

    fun checkCpuUsage(
        stats: SystemStats,
        settingsManager: SettingsManager,
        ctx: Context? = context
    ): Pair<String, String>? {
        if (!settingsManager.cpuAlertsEnabled) return null
        if (stats.cpuPercent < settingsManager.cpuThreshold) return null

        val targetCtx = ctx ?: return null
        val title = targetCtx.getString(R.string.notif_cpu_high_title, stats.cpuPercent)
        val message = targetCtx.getString(
            R.string.notif_cpu_high_msg,
            settingsManager.cpuThreshold,
            stats.cpuPercent
        )
        return Pair(title, message)
    }

    fun checkRamUsage(
        stats: SystemStats,
        settingsManager: SettingsManager,
        ctx: Context? = context
    ): Pair<String, String>? {
        if (!settingsManager.ramAlertsEnabled) return null

        val ramPercent = if (stats.ramTotalMb > 0) {
            stats.ramUsedMb * 100 / stats.ramTotalMb
        } else 0

        if (ramPercent < settingsManager.ramThreshold) return null

        val targetCtx = ctx ?: return null
        val title = targetCtx.getString(R.string.notif_ram_high_title, ramPercent)
        val message = targetCtx.getString(
            R.string.notif_ram_high_msg,
            settingsManager.ramThreshold,
            ramPercent,
            stats.ramUsedMb,
            stats.ramTotalMb
        )
        return Pair(title, message)
    }
}