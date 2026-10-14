package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.ssh.SshClient

/**
 * Use case for monitoring Docker container status changes.
 */
class DockerMonitoringUseCase(private val context: Context) {

    /**
     * Checks for Docker container status changes and returns containers that have stopped.
     * @param previouslyRunning Set of container names that were previously running
     * @param settingsManager The settings manager containing connection info
     * @return Set of container names that have stopped since last check
     */
    suspend fun findStoppedContainers(
        previouslyRunning: Set<String>,
        settingsManager: SettingsManager
    ): Set<String> {
        val output = SshClient.execute(
            host = settingsManager.host,
            port = settingsManager.port,
            user = settingsManager.username,
            password = settingsManager.password,
            command = "docker ps -a --format '{{.Names}},{{.Status}}'",
            timeoutMs = settingsManager.sshTimeoutMs,
            context = context
        )

        val currentStates: Map<String, Boolean> = output
            .lines()
            .filter { line -> line.isNotBlank() }
            .associate { line ->
                val parts = line.split(",", limit = 2)
                val name = parts.getOrElse(0) { "unknown" }
                val isRunning = parts.getOrElse(1) { "" }.startsWith("Up", ignoreCase = true)
                name to isRunning
            }

        // Find containers that were running but are now stopped
        val stoppedContainers = mutableSetOf<String>()
        for (containerName in previouslyRunning) {
            val isNowRunning = currentStates[containerName] == true
            if (!isNowRunning) {
                stoppedContainers.add(containerName)
            }
        }

        return stoppedContainers
    }

    /**
     * Gets the current state of all Docker containers.
     * @param settingsManager The settings manager containing connection info
     * @return Map of container names to their running state
     */
    suspend fun getCurrentContainerStates(settingsManager: SettingsManager): Map<String, Boolean> {
        val output = SshClient.execute(
            host = settingsManager.host,
            port = settingsManager.port,
            user = settingsManager.username,
            password = settingsManager.password,
            command = "docker ps -a --format '{{.Names}},{{.Status}}'",
            timeoutMs = settingsManager.sshTimeoutMs,
            context = context
        )

        return output
            .lines()
            .filter { line -> line.isNotBlank() }
            .associate { line ->
                val parts = line.split(",", limit = 2)
                val name = parts.getOrElse(0) { "unknown" }
                val isRunning = parts.getOrElse(1) { "" }.startsWith("Up", ignoreCase = true)
                name to isRunning
            }
    }
}