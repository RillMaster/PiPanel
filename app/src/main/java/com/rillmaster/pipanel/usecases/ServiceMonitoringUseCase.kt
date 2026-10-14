package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.ssh.SshClient

/**
 * Use case for monitoring critical systemd services.
 */
class ServiceMonitoringUseCase(private val context: Context) {

    /**
     * Checks the status of critical services and returns those that are not active.
     * @param settingsManager The settings manager containing connection info and critical services list
     * @return List of service names that are not active
     */
    suspend fun getInactiveServices(settingsManager: SettingsManager): List<String> {
        val inactiveServices = mutableListOf<String>()

        for (service in settingsManager.criticalServicesList) {
            // Basic protection against command injection
            if (!service.matches(Regex("[A-Za-z0-9_.@-]+"))) {
                continue
            }

            val status = SshClient.execute(
                host = settingsManager.host,
                port = settingsManager.port,
                user = settingsManager.username,
                password = settingsManager.password,
                command = "systemctl is-active $service",
                timeoutMs = settingsManager.sshTimeoutMs,
                context = context
            ).trim()

            if (status != "active") {
                inactiveServices.add(service)
            }
        }

        return inactiveServices
    }
}