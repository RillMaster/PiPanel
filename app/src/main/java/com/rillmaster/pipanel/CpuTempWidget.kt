package com.rillmaster.pipanel

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.color.ColorProvider
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

// ── État du Widget ───────────────────────────────────────────────────────────
object CpuTempWidgetKeys {
    val temp       = doublePreferencesKey("cpu_temp")
    val lastUpdate = stringPreferencesKey("cpu_temp_last_update")

    /** Seuil d'alerte température (°C). Pas de réglage dédié : seuil fixe. */
    const val ALERT_THRESHOLD_C = 70.0
}

class CpuTempWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }

    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val temp  = prefs[CpuTempWidgetKeys.temp] ?: 0.0
        val time  = prefs[CpuTempWidgetKeys.lastUpdate] ?: "--:--"

        val isHot      = temp >= CpuTempWidgetKeys.ALERT_THRESHOLD_C
        val accent     = if (isHot) Color(0xFFEF5350) else Color(0xFF39FF14)
        val accentProv = ColorProvider(day = accent, night = accent)
        val cardBg     = Color(0xFF161C22)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(cardBg)
                .cornerRadius(16.dp)
                .clickable(actionRunCallback<RefreshCpuTempActionCallback>())
                .padding(12.dp),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(accent),
                    content = {}
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = "Température CPU",
                    style = TextStyle(color = accentProv, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                )
            }

            Spacer(GlanceModifier.defaultWeight())

            // Température principale
            Text(
                text = "%.1f°C".format(temp),
                style = TextStyle(color = accentProv, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            )
            Text(
                text = if (isHot) "Surchauffe !" else "Température normale",
                style = TextStyle(
                    color = ColorProvider(day = Color.White.copy(alpha = 0.7f), night = Color.White.copy(alpha = 0.7f)),
                    fontSize = 11.sp
                )
            )

            Spacer(GlanceModifier.height(8.dp))

            // Jauge linéaire (0-100°C)
            LinearProgressIndicator(
                progress = (temp / 100.0).toFloat().coerceIn(0f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = accentProv,
                backgroundColor = ColorProvider(day = Color.White.copy(alpha = 0.1f), night = Color.White.copy(alpha = 0.1f))
            )

            Spacer(GlanceModifier.defaultWeight())

            Text(
                text = "Mise à jour : $time",
                style = TextStyle(
                    color = ColorProvider(day = Color.White.copy(alpha = 0.4f), night = Color.White.copy(alpha = 0.4f)),
                    fontSize = 9.sp
                )
            )
        }
    }

    companion object {
        private const val COOLDOWN_MS = 30 * 60_000L   // 30 min entre deux alertes

        /**
         * Envoie une notification d'alerte si la température dépasse le seuil,
         * avec cooldown anti-spam (partagé avec MonitoringWorker).
         */
        fun checkTempAlert(context: Context, tempCelsius: Double) {
            if (tempCelsius < CpuTempWidgetKeys.ALERT_THRESHOLD_C) return
            val settings = SettingsManager(context)
            if (!settings.notificationsEnabled) return

            val prefs = context.getSharedPreferences("monitoring_cooldown", Context.MODE_PRIVATE)
            val now      = System.currentTimeMillis()
            val lastSent = prefs.getLong("cooldown_temp_high", 0L)
            if ((now - lastSent) < COOLDOWN_MS) return
            prefs.edit { putLong("cooldown_temp_high", now) }

            NotificationHelper.createChannels(context)

            val pendingIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                },
                PendingIntent.FLAG_IMMUTABLE
            )

            val threshold = CpuTempWidgetKeys.ALERT_THRESHOLD_C.toInt()
            val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_SYSTEM)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(context.getString(R.string.notif_temp_high_title, tempCelsius))
                .setContentText(context.getString(R.string.notif_temp_high_msg, threshold, tempCelsius))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            try {
                NotificationManagerCompat.from(context).notify("temp_high".hashCode(), notification)
            } catch (_: SecurityException) {
                // Permission POST_NOTIFICATIONS non accordée (Android 13+)
            }
        }
    }
}

// ── Callback de rafraîchissement manuel ──────────────────────────────────────
class RefreshCpuTempActionCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        UpdateStatsWorker.enqueue(context, force = true)
    }
}

// ── Receiver ──────────────────────────────────────────────────────────────────
class CpuTempWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CpuTempWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetUpdateService.start(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        UpdateStatsWorker.enqueue(context)
        WidgetUpdateService.start(context)
    }
}
