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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TriggerMonitorService : Service(), LocationListener {
    private var mode: MonitorMode = MonitorMode.IDLE
    private var bluetoothAddress: String? = null
    private var bluetoothName: String? = null
    private var locationManager: LocationManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                if (DashcamService.isRecording(this)) {
                    stopSelf()
                    return START_STICKY
                }
                mode = MonitorMode.START_WHEN_MOVING
                bluetoothAddress = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
                bluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationWatch()
            }
            ACTION_DISARM -> {
                val address = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
                if (mode == MonitorMode.IDLE || address == null || address == bluetoothAddress) {
                    stopSelf()
                }
            }
            ACTION_MONITOR_DISCONNECTED_STOP -> {
                if (!DashcamService.isRecording(this)) {
                    stopSelf()
                    return START_STICKY
                }
                mode = MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED
                bluetoothAddress = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
                bluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
                startForeground(NOTIFICATION_ID, buildNotification())
                startLocationWatch()
            }
            ACTION_CANCEL_DISCONNECTED_STOP -> {
                val address = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
                val shouldCancelStopMonitor = mode == MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED &&
                    (address == null || address == bluetoothAddress)
                val shouldClearEmptyService = mode == MonitorMode.IDLE ||
                    (mode == MonitorMode.START_WHEN_MOVING && DashcamService.isRecording(this))
                if (shouldCancelStopMonitor || shouldClearEmptyService) {
                    stopSelf()
                }
            }
            else -> {
                if (mode == MonitorMode.IDLE) stopSelf()
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
        val speedKmh = if (location.hasSpeed()) SpeedRules.toKmhClamped(location.speed) else null
        when (mode) {
            MonitorMode.START_WHEN_MOVING -> updateStartTrigger(speedKmh)
            MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED -> updateDisconnectedStopTrigger(speedKmh)
            MonitorMode.IDLE -> Unit
        }
    }

    private fun updateStartTrigger(speedKmh: Float?) {
        val currentSpeedKmh = speedKmh ?: return
        val settings = TriggerSettings.load(this)
        if (TriggerRules.shouldStartRecordingAfterBluetoothConnectedAndSpeed(settings, bluetoothAddress, currentSpeedKmh)) {
            BackgroundRecordingStart.start(this)
            stopSelf()
        }
    }

    private fun updateDisconnectedStopTrigger(speedKmh: Float?) {
        if (!DashcamService.isRecording(this)) {
            stopSelf()
            return
        }
        if (speedKmh == null) return
        val settings = TriggerSettings.load(this)
        if (!settings.stopOnBluetoothDisconnectedAndStationary) {
            stopSelf()
            return
        }
        if (TriggerRules.shouldStopRecordingAfterDisconnectStationary(
                bluetoothDisconnected = bluetoothAddress != null,
                speedKmh = speedKmh
            )
        ) {
            Toast.makeText(this, R.string.trigger_bluetooth_disconnected_stationary_stop, Toast.LENGTH_SHORT).show()
            DashcamService.stopRecording(this)
            stopSelf()
        }
    }

    private fun startLocationWatch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
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
        val name = bluetoothName ?: getString(R.string.trigger_unknown_car)
        val title = if (mode == MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED) {
            getString(R.string.trigger_stop_notif_title)
        } else {
            getString(R.string.trigger_notif_title)
        }
        val text = if (mode == MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED) {
            getString(R.string.trigger_stop_stationary_notif_text, name)
        } else {
            getString(R.string.trigger_notif_text, name)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .addAction(android.R.drawable.ic_lock_power_off, getString(R.string.notif_action_exit_background), AppBackgroundExit.pendingIntent(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_ARM = "com.thomas.carspeed.action.TRIGGER_ARM"
        private const val ACTION_DISARM = "com.thomas.carspeed.action.TRIGGER_DISARM"
        private const val ACTION_MONITOR_DISCONNECTED_STOP = "com.thomas.carspeed.action.TRIGGER_MONITOR_DISCONNECTED_STOP"
        private const val ACTION_CANCEL_DISCONNECTED_STOP = "com.thomas.carspeed.action.TRIGGER_CANCEL_DISCONNECTED_STOP"
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

        fun monitorDisconnectedStop(context: Context, bluetoothAddress: String?, bluetoothName: String?) {
            val intent = Intent(context, TriggerMonitorService::class.java).apply {
                action = ACTION_MONITOR_DISCONNECTED_STOP
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
                putExtra(EXTRA_BT_NAME, bluetoothName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 部分系统限制后台广播直接启动前台服务；避免广播接收器崩溃。
            }
        }

        fun cancelDisconnectedStop(context: Context, bluetoothAddress: String?) {
            val intent = Intent(context, TriggerMonitorService::class.java).apply {
                action = ACTION_CANCEL_DISCONNECTED_STOP
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
            }
            context.startService(intent)
        }
    }

    private enum class MonitorMode {
        IDLE,
        START_WHEN_MOVING,
        STOP_AFTER_DISCONNECT_LOW_SPEED
    }
}
