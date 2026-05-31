package com.rillmaster.pipanel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {

    const val CHANNEL_SYSTEM     = "channel_system"
    const val CHANNEL_WATCHDOG   = "channel_watchdog"
    const val CHANNEL_DOCKER     = "channel_docker"

    private var notifId = 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        listOf(
            Triple(CHANNEL_SYSTEM,   context.getString(R.string.notif_channel_system_name),  context.getString(R.string.notif_channel_system_desc)),
            Triple(CHANNEL_WATCHDOG, context.getString(R.string.notif_channel_watchdog_name), context.getString(R.string.notif_channel_watchdog_desc)),
            Triple(CHANNEL_DOCKER,   context.getString(R.string.notif_channel_docker_name),   context.getString(R.string.notif_channel_docker_desc)),
        ).forEach { (id, name, desc) ->
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableLights(true)
                enableVibration(true)
                manager.createNotificationChannel(this)
            }
        }
    }

    @Suppress("unused")
    fun sendAlert(
        context: Context,
        channelId: String,
        title: String,
        message: String,
        smallIconRes: Int = android.R.drawable.ic_dialog_alert
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifId++, notification)
        } catch (_: SecurityException) {
            // Permission POST_NOTIFICATIONS non accordée
        }
    }
}