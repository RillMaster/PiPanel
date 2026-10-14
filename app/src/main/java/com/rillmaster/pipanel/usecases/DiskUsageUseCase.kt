package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.ssh.SshClient

/**
 * Use case for monitoring disk space utilization.
 */
class DiskUsageUseCase(private val context: Context) {

    /**
     * Checks disk usage percentage on the root partition.
     * @param settingsManager The settings manager containing connection info
     * @return Disk usage percentage (0-100), or -1 if check failed
     */
    suspend fun getDiskUsagePercent(settingsManager: SettingsManager): Int {
        val output = SshClient.execute(
            host = settingsManager.host,
            port = settingsManager.port,
            user = settingsManager.username,
            password = settingsManager.password,
            command = "df / --output=pcent | tail -1",
            timeoutMs = settingsManager.sshTimeoutMs,
            context = context
        )

        return output.trim()
            .removeSuffix("%")
            .toIntOrNull() ?: -1
    }

    /**
     * Checks if disk usage exceeds the specified threshold.
     * @param settingsManager The settings manager containing connection info and threshold
     * @return True if disk usage exceeds threshold, false otherwise
     */
    suspend fun isDiskUsageExceeded(settingsManager: SettingsManager): Boolean {
        val usage = getDiskUsagePercent(settingsManager)
        return usage != -1 && usage >= settingsManager.diskThreshold
    }
}