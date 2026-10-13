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
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class TrackRecordingService : Service(), LocationListener {
    private var mode = Mode.IDLE
    private var bluetoothAddress: String? = null
    private var bluetoothName: String? = null
    private var locationManager: LocationManager? = null
    private var sessionStartWallMs = 0L
    private var sessionStartElapsedMs = 0L
    private var pendingStartElapsedMs = 0L
    private val pendingPoints = mutableListOf<VideoTrackPoint>()
    private val activePoints = mutableListOf<VideoTrackPoint>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> arm(intent)
            ACTION_STOP -> stopAndFinish()
            else -> if (mode == Mode.IDLE) stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        finishActiveRecord()
        stopLocationWatch()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val point = pointFromLocation(location) ?: return
        when (mode) {
            Mode.MONITORING -> handleMonitoringPoint(point)
            Mode.RECORDING -> appendActivePoint(point)
            Mode.IDLE -> Unit
        }
    }

    private fun arm(intent: Intent) {
        val settings = TrackRecordingSettingsStore.load(this)
        val address = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
        if (!TrackRecordingRules.shouldArmForBluetooth(settings, address)) {
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.track_location_permission_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        bluetoothAddress = address
        bluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
        if (mode == Mode.IDLE) {
            mode = Mode.MONITORING
            pendingPoints.clear()
            activePoints.clear()
            pendingStartElapsedMs = 0L
            sessionStartWallMs = 0L
            sessionStartElapsedMs = 0L
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationWatch()
    }

    private fun handleMonitoringPoint(point: VideoTrackPoint) {
        if (pendingPoints.isEmpty()) pendingStartElapsedMs = SystemClock.elapsedRealtime()
        appendPoint(pendingPoints, point)
        val settings = TrackRecordingSettingsStore.load(this)
        val distanceMeters = TrackMetrics.totalDistance(pendingPoints)
        val gpsReady = TrackRecordingRules.hasGpsSignal(point.accuracyMeters)
        val bluetoothMatched = TrackRecordingRules.shouldArmForBluetooth(settings, bluetoothAddress)
        val shouldStart = TrackRecordingRules.shouldStartAfterSpeed(settings, bluetoothMatched, point.speedKmh) ||
            TrackRecordingRules.shouldStartAfterDistance(settings, bluetoothMatched, gpsReady, distanceMeters)
        if (shouldStart) startRecordingFromPending()
    }

    private fun startRecordingFromPending() {
        if (mode == Mode.RECORDING) return
        mode = Mode.RECORDING
        sessionStartWallMs = pendingPoints.firstOrNull()?.timeMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        sessionStartElapsedMs = pendingStartElapsedMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        activePoints.clear()
        pendingPoints.forEachIndexed { index, point ->
            val elapsed = (point.timeMs - sessionStartWallMs).coerceAtLeast(index * 1_000L)
            activePoints += point.copy(elapsedMs = elapsed)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        Toast.makeText(this, R.string.track_recording_started, Toast.LENGTH_SHORT).show()
    }

    private fun appendActivePoint(point: VideoTrackPoint) {
        appendPoint(activePoints, point.copy(elapsedMs = (SystemClock.elapsedRealtime() - sessionStartElapsedMs).coerceAtLeast(0L)))
    }

    private fun appendPoint(points: MutableList<VideoTrackPoint>, point: VideoTrackPoint) {
        if (points.size >= MAX_TRACK_POINTS) return
        if (points.isNotEmpty() && point.elapsedMs - points.last().elapsedMs < TRACK_POINT_INTERVAL_MS) return
        points += point
    }

    private fun pointFromLocation(location: Location): VideoTrackPoint? {
        if (location.latitude == 0.0 && location.longitude == 0.0) return null
        val nowElapsed = SystemClock.elapsedRealtime()
        val elapsedMs = when (mode) {
            Mode.RECORDING -> (nowElapsed - sessionStartElapsedMs).coerceAtLeast(0L)
            Mode.MONITORING -> pendingPoints.lastOrNull()?.let { (it.elapsedMs + TRACK_POINT_INTERVAL_MS).coerceAtLeast(0L) } ?: 0L
            Mode.IDLE -> 0L
        }
        return VideoTrackPoint(
            elapsedMs = elapsedMs,
            timeMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = if (location.hasSpeed()) SpeedRules.toKmhClamped(location.speed) else 0f,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() }
        )
    }

    private fun finishActiveRecord() {
        if (activePoints.size < 2) return
        val points = activePoints.toList()
        val summary = TrackMetrics.summary(points)
        if (summary.durationMs < MIN_RECORD_DURATION_MS && summary.totalDistanceMeters < MIN_RECORD_DISTANCE_METERS) return
        val maxPoint = points.maxByOrNull { it.speedKmh }
        val startTimeMs = sessionStartWallMs.takeIf { it > 0L } ?: points.first().timeMs
        val endTimeMs = points.last().timeMs.takeIf { it > 0L } ?: (startTimeMs + summary.durationMs)
        DriveRecordStore.addRecord(
            this,
            DriveRecord(
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                durationMs = summary.durationMs,
                maxSpeedKmh = summary.maxSpeedKmh,
                distanceMeters = summary.totalDistanceMeters,
                averageSpeedKmh = summary.averageSpeedKmh,
                maxSpeedLatitude = maxPoint?.latitude,
                maxSpeedLongitude = maxPoint?.longitude,
                maxSpeedAccuracyMeters = maxPoint?.accuracyMeters,
                averageAccuracyMeters = summary.averageAccuracyMeters,
                goodFixRatio = summary.goodFixRatio,
                source = DriveRecordStore.SOURCE_GPS_TRACK,
                routePoints = points.map { point ->
                    DriveRoutePoint(
                        timeMs = point.timeMs,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        speedKmh = point.speedKmh,
                        accuracyMeters = point.accuracyMeters,
                        bearingDegrees = point.bearingDegrees
                    )
                }
            )
        )
    }

    private fun stopAndFinish() {
        finishActiveRecord()
        mode = Mode.IDLE
        pendingPoints.clear()
        activePoints.clear()
        stopSelf()
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
            Toast.makeText(this, R.string.track_location_unavailable, Toast.LENGTH_SHORT).show()
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.track_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TrackRecordingSettingsActivity::class.java),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackRecordingService::class.java).apply { action = ACTION_STOP },
            flags
        )
        val title = if (mode == Mode.RECORDING) R.string.track_notif_recording_title else R.string.track_notif_monitoring_title
        val text = if (mode == Mode.RECORDING) R.string.track_notif_recording_text else R.string.track_notif_monitoring_text
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(title))
            .setContentText(getString(text, bluetoothName ?: getString(R.string.trigger_unknown_car)))
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .addAction(android.R.drawable.ic_lock_power_off, getString(R.string.notif_action_exit_background), AppBackgroundExit.pendingIntent(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_ARM = "com.thomas.carspeed.action.TRACK_RECORDING_ARM"
        private const val ACTION_STOP = "com.thomas.carspeed.action.TRACK_RECORDING_STOP"
        private const val EXTRA_BT_ADDRESS = "bt_address"
        private const val EXTRA_BT_NAME = "bt_name"
        private const val CHANNEL_ID = "track_recording_channel"
        private const val NOTIFICATION_ID = 5001
        private const val TRACK_POINT_INTERVAL_MS = 1_000L
        private const val MAX_TRACK_POINTS = 24 * 60 * 60
        private const val MIN_RECORD_DURATION_MS = 10_000L
        private const val MIN_RECORD_DISTANCE_METERS = 20f

        @Volatile var instance: TrackRecordingService? = null
            private set

        fun arm(context: Context, bluetoothAddress: String?, bluetoothName: String?) {
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
                putExtra(EXTRA_BT_NAME, bluetoothName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 后台广播可能被系统限制，下一次 App 前台同步时会重新检查。
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TrackRecordingService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (_: RuntimeException) {
                context.stopService(Intent(context, TrackRecordingService::class.java))
            }
        }

        fun isRunning(): Boolean = instance != null
    }

    private enum class Mode {
        IDLE,
        MONITORING,
        RECORDING
    }
}
