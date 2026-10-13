package com.thomas.carspeed

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

internal object AppBackgroundExit {
    const val ACTION = "com.thomas.carspeed.action.EXIT_BACKGROUND"

    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AppBackgroundExitReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun perform(context: Context) {
        val app = context.applicationContext
        runCatching {
            if (OverlayController.isRunning(app)) OverlayController.stop(app)
        }
        runCatching {
            if (TrackRecordingService.isRunning()) TrackRecordingService.stop(app)
        }
        runCatching { RadarAlertService.stop(app) }
        runCatching { app.stopService(Intent(app, TriggerMonitorService::class.java)) }
        runCatching { DashcamService.exit(app) }
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        manager.appTasks.forEach { task ->
            runCatching { task.finishAndRemoveTask() }
        }
    }

    private const val REQUEST_CODE = 41
}

class AppBackgroundExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AppBackgroundExit.ACTION) return
        AppBackgroundExit.perform(context)
    }
}
