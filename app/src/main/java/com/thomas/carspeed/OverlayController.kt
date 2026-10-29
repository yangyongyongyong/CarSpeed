package com.thomas.carspeed

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat

object OverlayController {
    private const val LEGACY_KEY_RUNNING = "overlay_service_running"
    private const val START_PENDING_WINDOW_MS = 5_000L

    // 运行状态以进程内的服务实例为准；以前存在 SharedPreferences 里，进程被杀后会一直显示"运行中"。
    @Volatile private var startPendingUntilElapsedMs = 0L

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun canStart(context: Context): Boolean {
        return hasLocationPermission(context) && hasNotificationPermission(context) && hasOverlayPermission(context)
    }

    fun isRunning(context: Context): Boolean {
        return OverlayService.isAlive || SystemClock.elapsedRealtime() < startPendingUntilElapsedMs
    }

    fun setRunning(context: Context, running: Boolean) {
        startPendingUntilElapsedMs = 0L
        val prefs = prefs(context)
        if (prefs.contains(LEGACY_KEY_RUNNING)) prefs.edit().remove(LEGACY_KEY_RUNNING).apply()
    }

    fun start(context: Context): Boolean {
        if (!canStart(context)) return false
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        startPendingUntilElapsedMs = SystemClock.elapsedRealtime() + START_PENDING_WINDOW_MS
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: RuntimeException) {
            // 后台被系统拦截（ForegroundServiceStartNotAllowedException 等）。
            startPendingUntilElapsedMs = 0L
            false
        }
    }

    fun stop(context: Context) {
        val pending = SystemClock.elapsedRealtime() < startPendingUntilElapsedMs
        startPendingUntilElapsedMs = 0L
        if (!OverlayService.isAlive && !pending) return
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: RuntimeException) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    fun toggle(context: Context): Boolean {
        return if (isRunning(context)) {
            stop(context)
            false
        } else {
            start(context)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
}
