package com.rillmaster.pipanel

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rillmaster.pipanel.model.fetchSystemStats
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class MonitoringForegroundService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updateJob: Job? = null

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "monitoring_foreground"

        fun start(context: Context) {
            val intent = Intent(context, MonitoringForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Initialisation..."))
        startUpdates()
        return START_STICKY
    }

    private fun startUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val settings = PiPanelApp.instance.settingsManager
            while (isActive) {
                val stats = fetchSystemStats(settings)
                val text = if (stats != null) {
                    "Temp: %.1f°C | CPU: %d%% | RAM: %d/%d MB".format(
                        stats.tempCelsius, stats.cpuPercent, stats.ramUsedMb, stats.ramTotalMb
                    )
                } else {
                    "Échec de connexion au Pi"
                }
                updateNotification(text)
                delay(settings.tempRefreshMs.coerceAtLeast(2000).milliseconds)
            }
        }
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PiPanel Monitoring")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Persistent Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time Pi statistics in the status bar"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
