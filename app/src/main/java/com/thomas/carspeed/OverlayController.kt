package com.thomas.carspeed

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object OverlayController {
    private const val KEY_RUNNING = "overlay_service_running"

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
        return prefs(context).getBoolean(KEY_RUNNING, false)
    }

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun start(context: Context): Boolean {
        if (!canStart(context)) return false
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
        setRunning(context, true)
        return true
    }

    fun stop(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        context.startService(intent)
        setRunning(context, false)
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
