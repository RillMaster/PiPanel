package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.SystemStats
import com.rillmaster.pipanel.model.fetchSystemStats

/**
 * Use case for fetching and parsing system statistics.
 */
class SystemStatsUseCase(private val context: Context) {

    /**
     * Fetches system statistics from the remote device using individual connection parameters.
     */
    suspend fun fetchSystemStats(
        host: String,
        port: Int,
        username: String,
        password: String,
        sshTimeoutMs: Int
    ): SystemStats? {
        return fetchSystemStats(
            host, port, username, password, sshTimeoutMs, context
        )
    }

    /**
     * Convenience method to fetch system statistics directly from SettingsManager.
     */
    suspend fun fetchSystemStats(settingsManager: SettingsManager): SystemStats? {
        return fetchSystemStats(
            settingsManager.host,
            settingsManager.port,
            settingsManager.username,
            settingsManager.password,
            settingsManager.sshTimeoutMs,
            context
        )
    }
}