package com.rillmaster.pipanel.usecases

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.rillmaster.pipanel.MainActivity

/**
 * Use case for handling alert deduplication and notification dispatch.
 */
class AlertingUseCase(private val context: Context) {

    companion object {
        private const val DEFAULT_COOLDOWN_MS = 30 * 60_000L // 30 min between identical alerts
    }

    /**
     * Sends an alert if enough time has passed since the last similar alert.
     * @param key Unique identifier for this type of alert
     * @param channelId Notification channel ID
     * @param title Alert title
     * @param message Alert message
     * @param cooldownMs Minimum time between similar alerts (defaults to 30 minutes)
     */
    fun sendAlertOnce(
        key: String,
        channelId: String,
        title: String,
        message: String,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ) {
        val prefs = context.getSharedPreferences("alert_cooldowns", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSent = prefs.getLong("cooldown_$key", 0L)

        if ((now - lastSent) < cooldownMs) {
            return // Too soon since last alert of this type
        }

        prefs.edit { putLong("cooldown_$key", now) }

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission POST_NOTIFICATIONS not granted (Android 13+)
        }
    }

    /**
     * Clears the cooldown for a specific alert key.
     * @param key The alert key to clear cooldown for
     */
    @Suppress("unused")
    fun clearCooldown(key: String) {
        val prefs = context.getSharedPreferences("alert_cooldowns", Context.MODE_PRIVATE)
        prefs.edit { remove("cooldown_$key") }
    }
}