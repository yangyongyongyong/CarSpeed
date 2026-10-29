package com.thomas.carspeed

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
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

    companion object {
        internal const val ACTION_TOGGLE = "com.thomas.carspeed.action.WIDGET_TOGGLE"

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
                Intent(context, WidgetToggleReceiver::class.java).apply { action = ACTION_TOGGLE },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            return views
        }
    }
}

// 小部件的提供者必须对外导出，所以开关动作交给一个不导出的接收器，其他应用无法伪造。
class WidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CarSpeedWidgetProvider.ACTION_TOGGLE) return
        if (!OverlayController.canStart(context) && !OverlayController.isRunning(context)) {
            context.startActivity(
                Intent(context, StatusActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } else {
            OverlayController.toggle(context)
            CarSpeedWidgetProvider.updateAll(context)
            CarSpeedTileService.requestListeningState(context)
        }
    }
}
