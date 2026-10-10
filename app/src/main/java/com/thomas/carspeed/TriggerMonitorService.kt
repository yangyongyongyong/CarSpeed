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
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TriggerMonitorService : Service(), LocationListener {
    private var armedBluetoothAddress: String? = null
    private var armedBluetoothName: String? = null
    private var locationManager: LocationManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                armedBluetoothAddress = intent.getStringExtra(EXTRA_BT_ADDRESS)
                armedBluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationWatch()
            }
            ACTION_DISARM -> {
                val address = intent.getStringExtra(EXTRA_BT_ADDRESS)
                if (address == null || address == armedBluetoothAddress) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationWatch()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val speedKmh = if (location.hasSpeed()) {
            SpeedRules.toKmhClamped(location.speed)
        } else {
            0f
        }
        val bluetoothMatched = armedBluetoothAddress != null
        if (TriggerRules.shouldStartRecording(bluetoothMatched, speedKmh)) {
            DashcamService.startRecording(this)
            if (OverlayController.canStart(this) && !OverlayController.isRunning(this)) {
                OverlayController.start(this)
            }
            stopSelf()
        }
    }

    private fun startLocationWatch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
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
            stopSelf()
        }
    }

    private fun stopLocationWatch() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: RuntimeException) {
            // ignore teardown exception
        }
        locationManager = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.trigger_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CameraRecordActivity::class.java),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TriggerMonitorService::class.java).apply { action = ACTION_DISARM },
            flags
        )
        val name = armedBluetoothName ?: getString(R.string.trigger_unknown_car)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.trigger_notif_title))
            .setContentText(getString(R.string.trigger_notif_text, name))
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_ARM = "com.thomas.carspeed.action.TRIGGER_ARM"
        private const val ACTION_DISARM = "com.thomas.carspeed.action.TRIGGER_DISARM"
        private const val EXTRA_BT_ADDRESS = "bt_address"
        private const val EXTRA_BT_NAME = "bt_name"
        private const val CHANNEL_ID = "dashcam_trigger_channel"
        private const val NOTIFICATION_ID = 3001

        fun arm(context: Context, bluetoothAddress: String?, bluetoothName: String?) {
            val intent = Intent(context, TriggerMonitorService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
                putExtra(EXTRA_BT_NAME, bluetoothName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 部分系统限制后台广播直接启动前台服务；避免广播接收器崩溃。
            }
        }

        fun disarm(context: Context, bluetoothAddress: String?) {
            val intent = Intent(context, TriggerMonitorService::class.java).apply {
                action = ACTION_DISARM
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
            }
            context.startService(intent)
        }
    }
}
