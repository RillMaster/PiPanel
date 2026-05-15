package com.example.raspberrycontroller
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
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit


class MonitoringWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val settings   = SettingsManager(ctx)
    private val prefs      = ctx.getSharedPreferences("monitoring_cooldown", Context.MODE_PRIVATE)
    private val cooldownMs = 30 * 60_000L   // 30 min entre deux alertes identiques

    override suspend fun doWork(): Result {
        if (!settings.isConfigured() || !settings.notificationsEnabled) return Result.success()

        NotificationHelper.createChannels(ctx)

        // ── CPU / RAM + Watchdog ──────────────────────────────────────────────
        val stats = runCatching { fetchSystemStats(settings) }.getOrNull()

        if (stats == null) {
            if (settings.watchdogEnabled) {
                sendAlertOnce(
                    key       = "pi_unreachable",
                    channelId = NotificationHelper.CHANNEL_WATCHDOG,
                    title     = ctx.getString(R.string.notif_pi_unreachable_title),
                    message   = ctx.getString(R.string.notif_pi_unreachable_msg)
                )
            }
        } else {
            clearCooldown("pi_unreachable")
            checkCpuRam(stats)
        }

        // ── Docker ────────────────────────────────────────────────────────────
        if (settings.dockerAlertsEnabled) {
            runCatching { checkDockerContainers() }
        }

        return Result.success()
    }

    // ── CPU / RAM ─────────────────────────────────────────────────────────────

    private fun checkCpuRam(stats: SystemStats) {
        if (settings.cpuAlertsEnabled && stats.cpuPercent >= settings.cpuThreshold) {
            sendAlertOnce(
                key       = "cpu_high",
                channelId = NotificationHelper.CHANNEL_SYSTEM,
                title     = ctx.getString(R.string.notif_cpu_high_title, stats.cpuPercent),
                message   = ctx.getString(R.string.notif_cpu_high_msg, settings.cpuThreshold, stats.cpuPercent)
            )
        }

        val ramPercent = if (stats.ramTotalMb > 0)
            stats.ramUsedMb * 100 / stats.ramTotalMb else 0

        if (settings.ramAlertsEnabled && ramPercent >= settings.ramThreshold) {
            sendAlertOnce(
                key       = "ram_high",
                channelId = NotificationHelper.CHANNEL_SYSTEM,
                title     = ctx.getString(R.string.notif_ram_high_title, ramPercent),
                message   = ctx.getString(R.string.notif_ram_high_msg, settings.ramThreshold, ramPercent, stats.ramUsedMb, stats.ramTotalMb)
            )
        }
    }

    // ── Docker ────────────────────────────────────────────────────────────────

    private suspend fun checkDockerContainers() {
        val output = SshClient.execute(
            host      = settings.host,
            port      = settings.port,
            user      = settings.username,
            password  = settings.password,
            command   = "docker ps -a --format '{{.Names}},{{.Status}}'",
            timeoutMs = settings.sshTimeoutMs,
            context   = ctx
        )

        val currentStates: Map<String, Boolean> = output
            .lines()
            .filter { line -> line.isNotBlank() }
            .associate { line ->
                val parts     = line.split(",", limit = 2)
                val name      = parts.getOrElse(0) { "unknown" }
                val isRunning = parts.getOrElse(1) { "" }.startsWith("Up", ignoreCase = true)
                name to isRunning
            }

        // Charger l'état précédent depuis les prefs (le Worker est recréé à chaque run)
        val previousStates: Map<String, Boolean> = prefs.all
            .entries
            .filter { entry -> entry.key.startsWith("docker_state_") }
            .associate { entry ->
                entry.key.removePrefix("docker_state_") to (entry.value as? Boolean ?: false)
            }

        // Détecter les conteneurs passés de running → stopped
        previousStates.forEach { (name, wasRunning) ->
            val isNowRunning = currentStates[name] ?: false
            if (wasRunning && !isNowRunning) {
                sendAlertOnce(
                    key       = "docker_$name",
                    channelId = NotificationHelper.CHANNEL_DOCKER,
                    title     = ctx.getString(R.string.notif_docker_stopped_title),
                    message   = ctx.getString(R.string.notif_docker_stopped_msg, name)
                )
            }
        }

        // Sauvegarder l'état actuel pour la prochaine exécution
        prefs.edit {
            prefs.all.keys
                .filter { key -> key.startsWith("docker_state_") }
                .forEach { key -> remove(key) }
            currentStates.forEach { (name, running) ->
                putBoolean("docker_state_$name", running)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendAlertOnce(key: String, channelId: String, title: String, message: String) {
        val now      = System.currentTimeMillis()
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
            // Permission POST_NOTIFICATIONS non accordée (Android 13+)
        }
    }

    private fun clearCooldown(@Suppress("SameParameterValue") key: String) {
        prefs.edit { remove("cooldown_$key") }
    }

    // ── Planification ─────────────────────────────────────────────────────────

    companion object {
        private const val WORK_NAME = "raspberry_monitoring"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitoringWorker>(
                repeatInterval         = 15,
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