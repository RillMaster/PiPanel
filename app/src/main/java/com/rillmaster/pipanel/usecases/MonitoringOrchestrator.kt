package com.rillmaster.pipanel.usecases

import android.content.Context
import com.rillmaster.pipanel.NotificationHelper
import com.rillmaster.pipanel.R
import com.rillmaster.pipanel.SettingsManager
import com.rillmaster.pipanel.model.SystemStats

/**
 * Orchestrator that coordinates all monitoring use cases based on enabled settings.
 */
class MonitoringOrchestrator(
    private val context: Context,
    private val systemStatsUseCase: SystemStatsUseCase = SystemStatsUseCase(context),
    private val dockerMonitoringUseCase: DockerMonitoringUseCase = DockerMonitoringUseCase(context),
    private val diskUsageUseCase: DiskUsageUseCase = DiskUsageUseCase(context),
    private val serviceMonitoringUseCase: ServiceMonitoringUseCase = ServiceMonitoringUseCase(context),
    private val alertingUseCase: AlertingUseCase = AlertingUseCase(context),
    private val cpuRamUseCase: CpuRamUseCase = CpuRamUseCase(context),
    private val watchdogUseCase: WatchdogUseCase = WatchdogUseCase(context)
) {

    /**
     * Executes all monitoring checks based on the provided settings.
     *
     * @param settingsManager The settings manager containing configuration
     * @param previousDockerStates Previously known Docker container states (for change detection)
     * @return Pair containing (updated Docker states, list of system stats if fetched)
     */
    suspend fun executeMonitoring(
        settingsManager: SettingsManager,
        previousDockerStates: Map<String, Boolean>
    ): Pair<Map<String, Boolean>, SystemStats?> {
        // Ensure notification channels exist
        NotificationHelper.createChannels(context)

        // Fetch system stats if monitoring is enabled
        val systemStats = if (settingsManager.monitoringServiceEnabled) {
            systemStatsUseCase.fetchSystemStats(settingsManager)
        } else {
            null
        }

        if (systemStats == null) {
            if (settingsManager.watchdogEnabled) {
                watchdogUseCase.checkUnreachable(systemStats, settingsManager, context)?.let { (title, message) ->
                    alertingUseCase.sendAlertOnce(
                        key = "pi_unreachable",
                        channelId = NotificationHelper.CHANNEL_WATCHDOG,
                        title = title,
                        message = message
                    )
                }
            }
        } else {
            alertingUseCase.clearCooldown("pi_unreachable")

            // CPU
            cpuRamUseCase.checkCpuUsage(systemStats, settingsManager, context)?.let { (title, message) ->
                alertingUseCase.sendAlertOnce(
                    key = "cpu_high",
                    channelId = NotificationHelper.CHANNEL_SYSTEM,
                    title = title,
                    message = message
                )
            }

            // RAM
            cpuRamUseCase.checkRamUsage(systemStats, settingsManager, context)?.let { (title, message) ->
                alertingUseCase.sendAlertOnce(
                    key = "ram_high",
                    channelId = NotificationHelper.CHANNEL_SYSTEM,
                    title = title,
                    message = message
                )
            }
        }

        // Check Docker containers if alerts are enabled
        val currentDockerStates = if (settingsManager.dockerAlertsEnabled) {
            dockerMonitoringUseCase.getCurrentContainerStates(settingsManager)
        } else {
            previousDockerStates
        }

        // Check for stopped containers and send alerts
        if (settingsManager.dockerAlertsEnabled && systemStats != null) {
            val stoppedContainers = dockerMonitoringUseCase.findStoppedContainers(
                previousDockerStates.keys,
                settingsManager
            )

            for (containerName in stoppedContainers) {
                alertingUseCase.sendAlertOnce(
                    key = "docker_$containerName",
                    channelId = NotificationHelper.CHANNEL_DOCKER,
                    title = context.getString(R.string.notif_docker_stopped_title),
                    message = context.getString(R.string.notif_docker_stopped_msg, containerName)
                )
            }
        }

        // Check disk usage if alerts are enabled
        if (settingsManager.diskAlertsEnabled && systemStats != null) {
            if (diskUsageUseCase.isDiskUsageExceeded(settingsManager)) {
                alertingUseCase.sendAlertOnce(
                    key = "disk_high",
                    channelId = NotificationHelper.CHANNEL_SYSTEM,
                    title = context.getString(R.string.notif_disk_high_title, settingsManager.diskThreshold),
                    message = context.getString(R.string.notif_disk_high_msg, settingsManager.diskThreshold, settingsManager.diskThreshold)
                )
            }
        }

        // Check critical services if alerts are enabled
        if (settingsManager.serviceAlertsEnabled && systemStats != null) {
            val inactiveServices = serviceMonitoringUseCase.getInactiveServices(settingsManager)
            for (service in inactiveServices) {
                alertingUseCase.sendAlertOnce(
                    key = "service_$service",
                    channelId = NotificationHelper.CHANNEL_SERVICES,
                    title = context.getString(R.string.notif_service_down_title),
                    message = context.getString(R.string.notif_service_down_msg, service, "inactive")
                )
            }
        }

        return Pair(currentDockerStates, systemStats)
    }
}