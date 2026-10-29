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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TriggerMonitorService : Service(), LocationListener {
    private var mode: MonitorMode = MonitorMode.IDLE
    private var bluetoothAddress: String? = null
    private var bluetoothName: String? = null
    private var locationManager: LocationManager? = null
    private var lowSpeedSinceMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val disconnectStopTicker = object : Runnable {
        override fun run() {
            if (mode != MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED) return
            updateDisconnectedStopTrigger(null)
            handler.postDelayed(this, DISCONNECT_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                if (DashcamService.isRecording(this)) {
                    // 通过 startForegroundService 启动的服务必须先进前台再停，否则系统直接让 App 崩溃。
                    enterForeground()
                    stopSelf()
                    return START_NOT_STICKY
                }
                mode = MonitorMode.START_WHEN_MOVING
                lowSpeedSinceMs = 0L
                bluetoothAddress = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
                bluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLocationWatch()
            }
            ACTION_DISARM -> handleDisarm(TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS)))
            ACTION_MONITOR_DISCONNECTED_STOP -> {
                if (!DashcamService.isRecording(this)) {
                    enterForeground()
                    stopSelf()
                    return START_NOT_STICKY
                }
                val wasMonitoring = mode == MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED
                mode = MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED
                if (!wasMonitoring) lowSpeedSinceMs = 0L
                bluetoothAddress = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
                bluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLocationWatch()
                handler.removeCallbacks(disconnectStopTicker)
                handler.postDelayed(disconnectStopTicker, DISCONNECT_CHECK_INTERVAL_MS)
            }
            ACTION_CANCEL_DISCONNECTED_STOP -> handleCancelDisconnectedStop(
                TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
            )
            else -> if (mode == MonitorMode.IDLE) stopSelf()
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun handleDisarm(address: String?) {
        if (mode == MonitorMode.IDLE || address == null || address == bluetoothAddress) {
            stopSelf()
        }
    }

    private fun handleCancelDisconnectedStop(address: String?) {
        val shouldCancelStopMonitor = mode == MonitorMode.STOP_AFTER_DISCONNECT_LOW_SPEED &&
            (address == null || address == bluetoothAddress)
        val shouldClearEmptyService = mode == MonitorMode.IDLE ||
            (mode == MonitorMode.START_WHEN_MOVING && DashcamService.isRecording(this))
        if (shouldCancelStopMonitor || shouldClearEmptyService) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(disconnectStopTicker)
        stopLocationWatch()
        mode = MonitorMode.IDLE
        if (instance === this) instance = null
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
        if (!PrivacyConsent.isAgreed(this)) {
            stopSelf()
            return
        }
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
        val settings = TriggerSettings.load(this)
        if (!settings.stopOnBluetoothDisconnectedAndStationary) {
            stopSelf()
            return
        }
        if (bluetoothAddress == null) return
        val now = SystemClock.elapsedRealtime()
        lowSpeedSinceMs = TriggerRules.updateLowSpeedSince(lowSpeedSinceMs, speedKmh, now)
        if (TriggerRules.shouldStopAfterSustainedLowSpeed(lowSpeedSinceMs, now)) {
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
                LOCATION_INTERVAL_MS,
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
        /** 只用来判断"开起来了没/停下来了没"，不需要 1 秒一次。 */
        private const val LOCATION_INTERVAL_MS = 2_000L
        private const val DISCONNECT_CHECK_INTERVAL_MS = 5_000L

        @Volatile private var instance: TriggerMonitorService? = null

        fun arm(context: Context, bluetoothAddress: String?, bluetoothName: String?) {
            if (!hasLocationPermission(context)) return
            if (!PrivacyConsent.isAgreed(context)) return
            val intent = Intent(context, TriggerMonitorService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
                putExtra(EXTRA_BT_NAME, bluetoothName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 系统不允许从后台拉起定位前台服务（Android 12+）。提醒用户点一下回到前台重新检查。
                AmapWatchNotifier.showBackgroundStartBlocked(
                    context,
                    "已连接车辆蓝牙",
                    "系统拦截了后台车速监测，点一下打开 App，满足条件后会自动开始录像。"
                )
            }
        }

        fun disarm(context: Context, bluetoothAddress: String?) {
            // 服务没在运行就没有什么可解除的；直接调用实例，避免在后台 startService 抛异常。
            val service = instance ?: return
            service.handleDisarm(TriggerSettings.normalizeAddress(bluetoothAddress))
        }

        fun monitorDisconnectedStop(context: Context, bluetoothAddress: String?, bluetoothName: String?) {
            if (!hasLocationPermission(context)) return
            val intent = Intent(context, TriggerMonitorService::class.java).apply {
                action = ACTION_MONITOR_DISCONNECTED_STOP
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
                putExtra(EXTRA_BT_NAME, bluetoothName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 录像服务在前台时一般允许启动；真被拦截时只是少了"断开后低速停录"，录像本身不受影响。
            }
        }

        fun cancelDisconnectedStop(context: Context, bluetoothAddress: String?) {
            val service = instance ?: return
            service.handleCancelDisconnectedStop(TriggerSettings.normalizeAddress(bluetoothAddress))
        }

        private fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private enum class MonitorMode {
        IDLE,
        START_WHEN_MOVING,
        STOP_AFTER_DISCONNECT_LOW_SPEED
    }
}
