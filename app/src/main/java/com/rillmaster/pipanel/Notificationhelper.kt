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
    const val CHANNEL_SERVICES   = "channel_services"
    const val CHANNEL_TRANSFER   = "channel_transfer"

    private var notifId = 1000

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        listOf(
            Triple(CHANNEL_SYSTEM,   context.getString(R.string.notif_channel_system_name),  context.getString(R.string.notif_channel_system_desc)),
            Triple(CHANNEL_WATCHDOG, context.getString(R.string.notif_channel_watchdog_name), context.getString(R.string.notif_channel_watchdog_desc)),
            Triple(CHANNEL_DOCKER,   context.getString(R.string.notif_channel_docker_name),   context.getString(R.string.notif_channel_docker_desc)),
            Triple(CHANNEL_SERVICES, context.getString(R.string.notif_channel_services_name), context.getString(R.string.notif_channel_services_desc)),
            Triple(CHANNEL_TRANSFER, "File Transfer", "Background upload/download progress"),
        ).forEach { (id, name, desc) ->
            val importance = if (id == CHANNEL_TRANSFER) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_HIGH
            NotificationChannel(id, name, importance).apply {
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

    fun updateProgress(
        context: Context,
        notificationId: Int,
        title: String,
        progress: Int,
        isDone: Boolean = false
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(!isDone)
            .setOnlyAlertOnce(true)

        if (isDone) {
            builder.setContentText(context.getString(R.string.update_success))
                .setProgress(0, 0, false)
                .setAutoCancel(true)
        } else {
            builder.setProgress(100, progress, progress == -1)
                .setContentText(if (progress == -1) "Working..." else "$progress%")
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {}
    }
}