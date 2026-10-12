package com.thomas.carspeed

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class RadarAlertService : Service(), LocationListener {
    private var locationManager: LocationManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, false)
                    .apply()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> if (!startIfEnabled()) return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationWatch()
        RadarAlertController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) {
            RadarAlertController.updateSpeed(SpeedRules.toKmhClamped(location.speed))
            RadarAlertController.checkDataAvailability(isNetworkAvailable())
        }
    }

    private fun startIfEnabled(): Boolean {
        if (!RadarAlertSettingsStore.load(this).enabled) {
            stopSelf()
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.radar_location_permission_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return false
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationWatch()
        RadarAlertController.refresh(this)
        return true
    }

    private fun startLocationWatch() {
        stopLocationWatch()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1_000L,
                0f,
                this,
                Looper.getMainLooper()
            )
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.radar_location_unavailable, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun stopLocationWatch() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: RuntimeException) {
            // 忽略服务销毁时的系统定位异常。
        }
        locationManager = null
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.radar_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RadarAlertSettingsActivity::class.java),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RadarAlertService::class.java).apply { action = ACTION_STOP },
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(getString(R.string.radar_notif_title))
            .setContentText(getString(R.string.radar_notif_text))
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_SYNC = "com.thomas.carspeed.action.RADAR_ALERT_SYNC"
        private const val ACTION_STOP = "com.thomas.carspeed.action.RADAR_ALERT_STOP"
        private const val CHANNEL_ID = "radar_alert_channel"
        private const val NOTIFICATION_ID = 4001

        fun sync(context: Context) {
            if (RadarAlertSettingsStore.load(context).enabled) {
                val intent = Intent(context, RadarAlertService::class.java).apply { action = ACTION_SYNC }
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (_: RuntimeException) {
                    // 后台启动被系统限制时忽略；下次打开 App 或设置页会再次同步。
                }
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadarAlertService::class.java))
            RadarAlertController.stop()
        }
    }
}
