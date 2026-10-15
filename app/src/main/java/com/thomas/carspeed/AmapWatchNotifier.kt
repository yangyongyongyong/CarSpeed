package com.thomas.carspeed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

internal object AmapWatchNotifier {
    private const val CHANNEL_ID = "amap_watch_channel"
    private const val NOTIFICATION_ID = 3002

    fun showManualStart(context: Context) {
        if (!OverlayController.hasNotificationPermission(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.amap_watch_notif_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, DashcamWakeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            flags
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(context.getString(R.string.amap_watch_notif_title))
            .setContentText(context.getString(R.string.amap_watch_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
