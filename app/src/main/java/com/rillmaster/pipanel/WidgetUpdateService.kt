package com.rillmaster.pipanel

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class WidgetUpdateService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var updateJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "widget_monitoring_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, WidgetUpdateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WidgetUpdateService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startMonitoring()
    }

    private fun startMonitoring() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            val settings = SettingsManager(applicationContext)
            while (isActive) {
                if (settings.isConfigured()) {
                    fetchSystemStats(settings)?.let { updateWidget(it, settings) }
                }
                delay(5000) // 5 secondes
            }
        }
    }

    private suspend fun updateWidget(stats: SystemStats, settings: SettingsManager) {
        val context = applicationContext

        // Check if user is unlocked to avoid IllegalStateException: User 0 must be unlocked
        val userManager = getSystemService(Context.USER_SERVICE) as? UserManager
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.w("Widget", "Service: User is locked, skipping widget update")
            return
        }

        val manager = GlanceAppWidgetManager(context)
        val now = System.currentTimeMillis()
        
        // 1. Stats Système
        val statsIds = manager.getGlanceIds(StatsWidget::class.java)
        if (statsIds.isNotEmpty()) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            statsIds.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[StatsWidgetKeys.temp]      = stats.tempCelsius
                    prefs[StatsWidgetKeys.cpu]       = stats.cpuPercent
                    prefs[StatsWidgetKeys.ramUsed]   = stats.ramUsedMb
                    prefs[StatsWidgetKeys.ramTotal]  = stats.ramTotalMb
                    prefs[StatsWidgetKeys.host]      = settings.host
                    prefs[StatsWidgetKeys.user]      = settings.username
                    prefs[StatsWidgetKeys.lastUpdate] = time
                }
                StatsWidget().update(context, id)
            }
        }

        // 2. Pi-hole
        val piHoleIds = manager.getGlanceIds(PiHoleWidget::class.java)
        if (piHoleIds.isNotEmpty()) {
            val piStats = fetchPiHoleStatus(settings, settings.piHolePassword)
            if (piStats != null) {
                piHoleIds.forEach { id ->
                    updateAppWidgetState(context, id) { prefs ->
                        val currentTime = System.currentTimeMillis()
                        val ignoreUntil = prefs[PiHoleWidgetKeys.ignoreUntil] ?: 0L
                        if (currentTime > ignoreUntil) {
                            prefs[PiHoleWidgetKeys.enabled] = piStats.enabled
                            prefs[PiHoleWidgetKeys.pending] = false
                        } else {
                            Log.e("Widget", "Service: PiHole update ON/OFF ignoré (verrou actif pour ${ (ignoreUntil - currentTime)/1000 }s)")
                        }
                        prefs[PiHoleWidgetKeys.adsBlocked]  = piStats.adsBlockedToday
                        prefs[PiHoleWidgetKeys.queries]     = piStats.dnsQueriesToday
                        prefs[PiHoleWidgetKeys.blockingPct] = piStats.adsPercentage
                        prefs[PiHoleWidgetKeys.lastUpdate]  = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    }
                    PiHoleWidget().update(context, id)
                }
            }
        }

        // 3. WireGuard
        val wgIds = manager.getGlanceIds(WireGuardWidget::class.java)
        if (wgIds.isNotEmpty()) {
            val wgStatus = fetchWgStatus(settings)
            if (wgStatus != null) {
                wgIds.forEach { id ->
                    updateAppWidgetState(context, id) { prefs ->
                        val currentTime = System.currentTimeMillis()
                        val ignoreUntil = prefs[WireGuardWidgetKeys.ignoreUntil] ?: 0L
                        if (currentTime > ignoreUntil) {
                            prefs[WireGuardWidgetKeys.enabled] = wgStatus.isUp
                        } else {
                            Log.e("Widget", "Service: WireGuard update ON/OFF ignoré (verrou actif)")
                        }
                        prefs[WireGuardWidgetKeys.interfaceName] = wgStatus.interfaceName
                        prefs[WireGuardWidgetKeys.port]      = wgStatus.listenPort
                        prefs[WireGuardWidgetKeys.clients]   = wgStatus.peers.count { it.isOnline }
                        prefs[WireGuardWidgetKeys.lastUpdate] = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    }
                    WireGuardWidget().update(context, id)
                }
            }
        }

        // 4. Capteurs
        val sensorIds = manager.getGlanceIds(SensorWidget::class.java)
        if (sensorIds.isNotEmpty()) {
            val dsTemp = fetchDS18B20(settings)
            val dht = fetchDHT22(settings)
            sensorIds.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[SensorWidgetKeys.ds18b20Temp]   = dsTemp ?: -999f
                    prefs[SensorWidgetKeys.dht22Temp]     = dht?.first ?: -999f
                    prefs[SensorWidgetKeys.dht22Hum]      = dht?.second ?: -999f
                    prefs[SensorWidgetKeys.lastUpdate]    = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                }
                SensorWidget().update(context, id)
            }
        }

        // 5. Température CPU (+ alerte surchauffe avec cooldown)
        val cpuTempIds = manager.getGlanceIds(CpuTempWidget::class.java)
        if (cpuTempIds.isNotEmpty()) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            cpuTempIds.forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[CpuTempWidgetKeys.temp]       = stats.tempCelsius
                    prefs[CpuTempWidgetKeys.lastUpdate] = time
                }
                CpuTempWidget().update(context, id)
            }
            CpuTempWidget.checkTempAlert(context, stats.tempCelsius)
        }

        // 6. Docker (compteur conteneurs)
        val dockerIds = manager.getGlanceIds(DockerWidget::class.java)
        if (dockerIds.isNotEmpty()) {
            val counts = fetchDockerCounts(settings)
            if (counts != null) {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                dockerIds.forEach { id ->
                    updateAppWidgetState(context, id) { prefs ->
                        prefs[DockerWidgetKeys.running]    = counts.first
                        prefs[DockerWidgetKeys.total]      = counts.second
                        prefs[DockerWidgetKeys.lastUpdate] = time
                    }
                    DockerWidget().update(context, id)
                }
            }
        }

        if (statsIds.isEmpty() && piHoleIds.isEmpty() && wgIds.isEmpty() && sensorIds.isEmpty()
            && cpuTempIds.isEmpty() && dockerIds.isEmpty()) {
            stopSelf()
        }
    }

    /** Retourne (running, total) des conteneurs Docker, ou null si Docker indisponible. */
    private suspend fun fetchDockerCounts(settings: SettingsManager): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            val runningRaw = SshClient.execute(settings.host, settings.port, settings.username, settings.password,
                "docker ps -q | wc -l", settings.sshTimeoutMs)
            val totalRaw = SshClient.execute(settings.host, settings.port, settings.username, settings.password,
                "docker ps -aq | wc -l", settings.sshTimeoutMs)
            val running = runningRaw.trim().toIntOrNull() ?: return@withContext null
            val total   = totalRaw.trim().toIntOrNull() ?: return@withContext null
            running to total
        } catch (_: Exception) { null }
    }

    private suspend fun fetchDS18B20(settings: SettingsManager): Float? = withContext(Dispatchers.IO) {
        try {
            val raw = SshClient.execute(settings.host, settings.port, settings.username, settings.password,
                "cat /sys/bus/w1/devices/28-*/w1_slave | grep 't=' | sed 's/.*t=//'", settings.sshTimeoutMs)
            raw.trim().toFloat() / 1000f
        } catch (_: Exception) { null }
    }

    private suspend fun fetchDHT22(settings: SettingsManager): Pair<Float, Float>? = withContext(Dispatchers.IO) {
        try {
            val raw = SshClient.execute(settings.host, settings.port, settings.username, settings.password,
                "python3 -c \"import Adafruit_DHT; h,t=Adafruit_DHT.read_retry(Adafruit_DHT.DHT22,4); print(str(round(t,1))+','+str(round(h,1)))\"", settings.sshTimeoutMs)
            val parts = raw.trim().split(",")
            parts[0].toFloat() to parts[1].toFloat()
        } catch (_: Exception) { null }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.widget_service_title))
            .setContentText(getString(R.string.widget_service_desc))
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.widget_service_title),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
