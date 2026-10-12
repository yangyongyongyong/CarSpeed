package com.thomas.carspeed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object DashcamNotificationGuard {
    const val CHANNEL_ID = "dashcam_recording_channel"

    fun ensureRecordingChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.dashcam_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canShowRecordingNotification(context: Context): Boolean {
        return hasRuntimeNotificationPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            isRecordingChannelEnabled(context)
    }

    fun hasRuntimeNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isRecordingChannelEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
            ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
