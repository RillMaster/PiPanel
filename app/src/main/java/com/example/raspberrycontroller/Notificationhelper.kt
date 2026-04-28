package com.example.raspberrycontroller

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
            Triple(CHANNEL_SYSTEM,   "Alertes Système",  "Alertes CPU / RAM dépassant le seuil défini"),
            Triple(CHANNEL_WATCHDOG, "Watchdog Pi",      "Alerte si le Raspberry Pi devient injoignable"),
            Triple(CHANNEL_DOCKER,   "Services Docker",  "Alerte si un conteneur Docker tombe"),
        ).forEach { (id, name, desc) ->
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableLights(true)
                enableVibration(true)
                manager.createNotificationChannel(this)
            }
        }
    }

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

        NotificationManagerCompat.from(context).notify(notifId++, notification)
    }
}