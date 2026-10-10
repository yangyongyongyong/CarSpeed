package com.thomas.carspeed

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class CarSpeedWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            if (!OverlayController.canStart(context) && !OverlayController.isRunning(context)) {
                openStatus(context)
            } else {
                OverlayController.toggle(context)
                updateAll(context)
                CarSpeedTileService.requestListeningState(context)
            }
        }
    }

    private fun openStatus(context: Context) {
        val intent = Intent(context, StatusActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    companion object {
        private const val ACTION_TOGGLE = "com.thomas.carspeed.action.WIDGET_TOGGLE"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CarSpeedWidgetProvider::class.java))
            ids.forEach { id ->
                manager.updateAppWidget(id, buildViews(context))
            }
        }

        fun buildViews(context: Context): RemoteViews {
            val running = OverlayController.isRunning(context)
            val views = RemoteViews(context.packageName, R.layout.widget_toggle)
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title))
            views.setTextViewText(
                R.id.widgetState,
                context.getString(if (running) R.string.widget_running else R.string.widget_idle)
            )
            views.setTextViewText(R.id.widgetAction, context.getString(if (running) R.string.widget_stop else R.string.widget_start))
            views.setInt(
                R.id.widgetRoot,
                "setBackgroundResource",
                if (running) R.drawable.widget_on_bg else R.drawable.widget_off_bg
            )
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, CarSpeedWidgetProvider::class.java).apply { action = ACTION_TOGGLE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            return views
        }
    }
}
