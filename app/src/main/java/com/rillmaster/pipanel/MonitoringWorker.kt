package com.rillmaster.pipanel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.rillmaster.pipanel.model.SystemStats
import com.rillmaster.pipanel.data.db.AppDatabase
import com.rillmaster.pipanel.data.db.MetricEntity
import com.rillmaster.pipanel.usecases.CpuRamUseCase
import com.rillmaster.pipanel.usecases.SystemStatsUseCase
import com.rillmaster.pipanel.usecases.WatchdogUseCase
import com.rillmaster.pipanel.ssh.SshClient
import java.util.concurrent.TimeUnit

/**
 * Worker that performs monitoring tasks using cases for separation of concerns.
 */
class MonitoringWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val settings   = SettingsManager(ctx)
    private val prefs      = ctx.getSharedPreferences("monitoring_cooldown", Context.MODE_PRIVATE)
    private val cooldownMs = 30 * 60_000L   // 30 min between identical alerts

    // Initialize use cases
    private val systemStatsUseCase = SystemStatsUseCase(ctx)
    private val cpuRamUseCase = CpuRamUseCase()
    private val watchdogUseCase = WatchdogUseCase()

    override suspend fun doWork(): Result {
        if (settings.isFirstLaunch || !settings.isConfigured() || !settings.notificationsEnabled) {
            return Result.success()
        }

        // Ensure notification channels are created
        NotificationHelper.createChannels(ctx)

        // ── CPU / RAM + Watchdog ──────────────────────────────────────────────
        val stats = systemStatsUseCase.fetchSystemStats(
            settings.host,
            settings.port,
            settings.username,
            settings.password,
            settings.sshTimeoutMs
        )

        if (stats == null) {
            // Device unreachable - check watchdog
            if (settings.watchdogEnabled) {
                watchdogUseCase.checkUnreachable(stats, settings)?.let { (title, message) ->
                    sendAlertOnce(
                        key = "pi_unreachable",
                        channelId = NotificationHelper.CHANNEL_WATCHDOG,
                        title = title,
                        message = message
                    )
                }
            }
        } else {
            // Device reachable - clear unreachable cooldown and check metrics
            clearCooldown("pi_unreachable")

            // Check CPU and RAM usage
            cpuRamUseCase.checkCpuUsage(stats, settings)?.let { (title, message) ->
                sendAlertOnce(
                    key = "cpu_high",
                    channelId = NotificationHelper.CHANNEL_SYSTEM,
                    title = title,
                    message = message
                )
            }

            cpuRamUseCase.checkRamUsage(stats, settings)?.let { (title, message) ->
                sendAlertOnce(
                    key = "ram_high",
                    channelId = NotificationHelper.CHANNEL_SYSTEM,
                    title = title,
                    message = message
                )
            }

            // Persist metrics to Room database
            persistMetrics(stats)
        }

        // ── Docker Monitoring ───────────────────────────────────────────────
        if (settings.dockerAlertsEnabled) {
            checkDockerContainers()
        }

        // ── Disk Usage Monitoring ───────────────────────────────────────────
        if (settings.diskAlertsEnabled) {
            checkDiskUsage()
        }

        // ── Critical Services Monitoring ────────────────────────────────────
        if (settings.serviceAlertsEnabled) {
            checkCriticalServices()
        }

        return Result.success()
    }

    // ── CPU / RAM ─────────────────────────────────────────────────────────────
    /* Note: CPU/RAM checking logic moved to CpuRamUseCase */

    // ── Docker ────────────────────────────────────────────────────────────────
    private suspend fun checkDockerContainers() {
        // TODO: Implement using DockerMonitoringUseCase with state tracking
        // For now, keeping original implementation to ensure functionality
        val output = SshClient.execute(
            host = settings.host,
            port = settings.port,
            user = settings.username,
            password = settings.password,
            command = "docker ps -a --format '{{.Names}},{{.Status}}'",
            timeoutMs = settings.sshTimeoutMs,
            context = ctx
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

        // Load previous state from prefs
        val previousStates: Map<String, Boolean> = prefs.all
            .entries
            .filter { entry -> entry.key.startsWith("docker_state_") }
            .associate { entry ->
                entry.key.removePrefix("docker_state_") to (entry.value as? Boolean ?: false)
            }

        // Detect containers that went from running to stopped
        previousStates.forEach { (name, wasRunning) ->
            val isNowRunning = currentStates[name] ?: false
            if (wasRunning && !isNowRunning) {
                sendAlertOnce(
                    key = "docker_$name",
                    channelId = NotificationHelper.CHANNEL_DOCKER,
                    title = ctx.getString(R.string.notif_docker_stopped_title),
                    message = ctx.getString(R.string.notif_docker_stopped_msg, name)
                )
            }
        }

        // Save current state for next execution
        prefs.edit {
            prefs.all.keys
                .filter { key -> key.startsWith("docker_state_") }
                .forEach { key -> remove(key) }
            currentStates.forEach { (name, running) ->
                putBoolean("docker_state_$name", running)
            }
        }
    }

    // ── Disk Usage ────────────────────────────────────────────────────────────
    private suspend fun checkDiskUsage() {
        // TODO: Implement using DiskUsageUseCase
        // For now, keeping original implementation
        val output = SshClient.execute(
            host = settings.host,
            port = settings.port,
            user = settings.username,
            password = settings.password,
            command = "df / --output=pct | tail -1",
            timeoutMs = settings.sshTimeoutMs,
            context = ctx
        )
        val usedPct = output.trim().removeSuffix("%").toIntOrNull() ?: return

        if (usedPct >= settings.diskThreshold) {
            sendAlertOnce(
                key = "disk_high",
                channelId = NotificationHelper.CHANNEL_SYSTEM,
                title = ctx.getString(R.string.notif_disk_high_title, usedPct),
                message = ctx.getString(R.string.notif_disk_high_msg, settings.diskThreshold, usedPct)
            )
        }
    }

    // ── Services Critiques ────────────────────────────────────────────────
    private suspend fun checkCriticalServices() {
        // TODO: Implement using ServiceMonitoringUseCase
        // For now, keeping original implementation
        settings.criticalServicesList.forEach { service ->
            // Basic protection protects against command injection
            if (!service.matches(Regex("[A-Za-z0-9_.@-]+"))) return@forEach

            val status = runCatching {
                SshClient.execute(
                    host = settings.host,
                    port = settings.port,
                    user = settings.username,
                    password = settings.password,
                    command = "systemctl is-active $service",
                    timeoutMs = settings.sshTimeoutMs,
                    context = ctx
                ).trim()
            }.getOrDefault("unknown")

            if (status != "active") {
                sendAlertOnce(
                    key = "service_$service",
                    channelId = NotificationHelper.CHANNEL_SERVICES,
                    title = ctx.getString(R.string.notif_service_down_title),
                    message = ctx.getString(R.string.notif_service_down_msg, service, status)
                )
            } else {
                clearCooldown("service_$service")
            }
        }
    }

    // ── Persistence Room ────────────────────────────────────────────────────────
    private suspend fun persistMetrics(stats: SystemStats) {
        runCatching {
            val dao = AppDatabase.getInstance(ctx).metricDao()
            dao.insert(
                MetricEntity(
                    timestamp = System.currentTimeMillis(),
                    tempCelsius = stats.tempCelsius,
                    cpuPercent = stats.cpuPercent,
                    ramUsedMb = stats.ramUsedMb,
                    ramTotalMb = stats.ramTotalMb,
                    diskReadBytes = stats.diskReadBytes,
                    diskWriteBytes = stats.diskWriteBytes,
                    netRxBytes = stats.netRxBytes,
                    netTxBytes = stats.netTxBytes
                )
            )
            dao.pruneOlderThan(System.currentTimeMillis() - AppDatabase.RETENTION_MS)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun sendAlertOnce(key: String, channelId: String, title: String, message: String) {
        val now = System.currentTimeMillis()
        val lastSent = prefs.getLong("cooldown_$key", 0L)
        if ((now - lastSent) < cooldownMs) return
        prefs.edit { putLong("cooldown_$key", now) }

        val pendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission POST_NOTIFICATIONS not granted (Android 13+)
        }
    }

    private fun clearCooldown(key: String) {
        prefs.edit { remove("cooldown_$key") }
    }

    // ── Scheduling ──────────────────────────────────────────────────────────
    companion object {
        private const val WORK_NAME = "raspberry_monitoring"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitoringWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        @Suppress("unused")
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<MonitoringWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}