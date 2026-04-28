package com.example.raspberrycontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: SettingsManager

    // Cooldown : évite de spammer la même alerte (clé → timestamp dernier envoi)
    private val lastAlertTime = mutableMapOf<String, Long>()
    private val cooldownMs = 5 * 60_000L   // 5 minutes entre deux alertes identiques

    // État Docker précédent pour détecter les changements (nom → isRunning)
    private var previousDockerStates = mapOf<String, Boolean>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        NotificationHelper.createChannels(this)
        startForeground(FOREGROUND_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Boucle principale ─────────────────────────────────────────────────────

    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                if (settings.notificationsEnabled) {
                    val stats = runCatching { fetchSystemStats(settings) }.getOrNull()

                    if (stats == null) {
                        // Pi injoignable
                        if (settings.watchdogEnabled) {
                            sendAlertOnce(
                                key       = "pi_unreachable",
                                channelId = NotificationHelper.CHANNEL_WATCHDOG,
                                title     = "🔴 Raspberry Pi injoignable",
                                message   = "Impossible de contacter le Raspberry Pi. " +
                                        "Vérifiez la connexion réseau ou l'alimentation."
                            )
                        }
                    } else {
                        // Pi joignable → reset watchdog
                        lastAlertTime.remove("pi_unreachable")
                        checkCpuRam(stats)
                    }

                    if (settings.dockerAlertsEnabled) {
                        runCatching { checkDockerContainers() }
                    }
                }
                delay(settings.watchdogIntervalSeconds * 1_000L)
            }
        }
    }

    // ── CPU / RAM ─────────────────────────────────────────────────────────────

    private fun checkCpuRam(stats: SystemStats) {
        if (settings.cpuAlertsEnabled && stats.cpuPercent >= settings.cpuThreshold) {
            sendAlertOnce(
                key       = "cpu_high",
                channelId = NotificationHelper.CHANNEL_SYSTEM,
                title     = "⚠️ CPU élevé – ${stats.cpuPercent}%",
                message   = "Le CPU dépasse le seuil de ${settings.cpuThreshold}% " +
                        "(actuel : ${stats.cpuPercent}%)"
            )
        }

        val ramPercent = if (stats.ramTotalMb > 0)
            stats.ramUsedMb * 100 / stats.ramTotalMb else 0

        if (settings.ramAlertsEnabled && ramPercent >= settings.ramThreshold) {
            sendAlertOnce(
                key       = "ram_high",
                channelId = NotificationHelper.CHANNEL_SYSTEM,
                title     = "⚠️ RAM élevée – $ramPercent%",
                message   = "La RAM dépasse le seuil de ${settings.ramThreshold}% " +
                        "(actuel : $ramPercent% — ${stats.ramUsedMb}/${stats.ramTotalMb} Mo)"
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
            timeoutMs = settings.sshTimeoutMs
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

        // Alerte uniquement quand un conteneur passe de running → stopped
        previousDockerStates.forEach { (name, wasRunning) ->
            val isNowRunning = currentStates[name] ?: false
            if (wasRunning && !isNowRunning) {
                sendAlertOnce(
                    key       = "docker_$name",
                    channelId = NotificationHelper.CHANNEL_DOCKER,
                    title     = "🐳 Conteneur Docker arrêté",
                    message   = "Le conteneur « $name » s'est arrêté de manière inattendue."
                )
            }
        }

        previousDockerStates = currentStates
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Envoie une alerte au plus une fois par [cooldownMs] pour la même clé. */
    private fun sendAlertOnce(key: String, channelId: String, title: String, message: String) {
        val now = System.currentTimeMillis()
        if ((now - (lastAlertTime[key] ?: 0L)) < cooldownMs) return
        lastAlertTime[key] = now

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(notifIdCounter++, notification)
        } catch (_: SecurityException) {
            // Permission POST_NOTIFICATIONS non accordée (Android 13+)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val foregroundChannel = "channel_foreground"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                foregroundChannel,
                "Surveillance active",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        return NotificationCompat.Builder(this, foregroundChannel)
            .setContentTitle("RaspberryController")
            .setContentText("Surveillance active en arrière-plan…")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val FOREGROUND_ID = 1
        private var notifIdCounter      = 1000

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}