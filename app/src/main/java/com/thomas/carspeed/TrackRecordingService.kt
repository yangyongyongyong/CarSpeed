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
import org.json.JSONObject

class TrackRecordingService : Service(), LocationListener {
    private var mode = Mode.IDLE
    private var manual = false
    private var bluetoothAddress: String? = null
    private var bluetoothName: String? = null
    private var locationManager: LocationManager? = null
    private var sessionStartWallMs = 0L
    private var sessionStartElapsedMs = 0L
    private val pendingPoints = mutableListOf<VideoTrackPoint>()
    private val activePoints = mutableListOf<VideoTrackPoint>()
    private var lastCheckpointElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NOW -> {
                recoverInterruptedTrack()
                startNow()
            }
            ACTION_ARM -> {
                recoverInterruptedTrack()
                arm(intent)
            }
            ACTION_STOP -> stopAndFinish()
            null -> if (mode == Mode.IDLE) resumeAfterRestart()
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
            Mode.RECORDING -> {
                appendActivePoint(point)
                maybeSaveCheckpoint()
            }
            Mode.IDLE -> Unit
        }
    }

    private fun enterForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun startNow() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.track_location_permission_required, Toast.LENGTH_SHORT).show()
            if (mode == Mode.IDLE) {
                enterForeground()
                stopSelf()
            }
            return
        }
        manual = true
        if (mode == Mode.RECORDING) {
            enterForeground()
            return
        }
        if (mode == Mode.MONITORING && pendingPoints.isNotEmpty()) {
            startRecordingFromPending()
            return
        }
        mode = Mode.RECORDING
        pendingPoints.clear()
        activePoints.clear()
        sessionStartWallMs = System.currentTimeMillis()
        sessionStartElapsedMs = SystemClock.elapsedRealtime()
        if (!enterForeground()) {
            mode = Mode.IDLE
            manual = false
            stopSelf()
            return
        }
        startLocationWatch()
        Toast.makeText(this, R.string.track_recording_started, Toast.LENGTH_SHORT).show()
    }

    private fun arm(intent: Intent) {
        if (manual) {
            enterForeground()
            return
        }
        val settings = TrackRecordingSettingsStore.load(this)
        val address = TriggerSettings.normalizeAddress(intent.getStringExtra(EXTRA_BT_ADDRESS))
        if (!TrackRecordingRules.shouldArmForBluetooth(settings, address)) {
            // 由 startForegroundService 启动，先进前台再停，避免系统判定超时崩溃。
            enterForeground()
            if (mode == Mode.IDLE) stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.track_location_permission_required, Toast.LENGTH_SHORT).show()
            enterForeground()
            stopSelf()
            return
        }
        bluetoothAddress = address
        bluetoothName = intent.getStringExtra(EXTRA_BT_NAME)
        if (mode == Mode.IDLE) {
            mode = Mode.MONITORING
            pendingPoints.clear()
            activePoints.clear()
            sessionStartWallMs = 0L
            sessionStartElapsedMs = 0L
        }
        if (!enterForeground()) {
            if (mode == Mode.MONITORING) mode = Mode.IDLE
            stopSelf()
            return
        }
        startLocationWatch()
    }

    private fun handleMonitoringPoint(point: VideoTrackPoint) {
        appendPoint(pendingPoints, point)
        // 等待触发期间只保留最近一段，避免停车很久时列表无限增长、每秒全量重算距离。
        while (pendingPoints.size > MAX_PENDING_POINTS) pendingPoints.removeAt(0)
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
        // 行程从开始移动前的最后一个点算起，前面原地等待的时间不计入。
        val kept = TrackRecordingRules.trimLeadingStationary(pendingPoints)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        sessionStartWallMs = kept.firstOrNull()?.timeMs?.takeIf { it in 1L..nowWall } ?: nowWall
        sessionStartElapsedMs = nowElapsed - (nowWall - sessionStartWallMs).coerceAtLeast(0L)
        activePoints.clear()
        kept.forEachIndexed { index, point ->
            val elapsed = (point.timeMs - sessionStartWallMs).coerceAtLeast(index * 1_000L)
            activePoints += point.copy(elapsedMs = elapsed)
        }
        pendingPoints.clear()
        enterForeground()
        Toast.makeText(this, R.string.track_recording_started, Toast.LENGTH_SHORT).show()
    }

    private fun appendActivePoint(point: VideoTrackPoint) {
        appendPoint(activePoints, point.copy(elapsedMs = (SystemClock.elapsedRealtime() - sessionStartElapsedMs).coerceAtLeast(0L)))
    }

    private fun liveSnapshot(): List<VideoTrackPoint> = activePoints.toList()

    private fun liveSessionElapsedMs(): Long =
        sessionStartElapsedMs.takeIf { it > 0L }
            ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
            ?: 0L

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

    private fun buildRecord(points: List<VideoTrackPoint>): DriveRecord? {
        if (points.size < 2) return null
        val summary = TrackMetrics.summary(points)
        if (summary.durationMs < MIN_RECORD_DURATION_MS && summary.totalDistanceMeters < MIN_RECORD_DISTANCE_METERS) return null
        val maxPoint = points.maxByOrNull { it.speedKmh }
        val startTimeMs = sessionStartWallMs.takeIf { it > 0L } ?: points.first().timeMs
        val endTimeMs = points.last().timeMs.takeIf { it > 0L } ?: (startTimeMs + summary.durationMs)
        return DriveRecord(
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
    }

    private fun finishActiveRecord() {
        val record = buildRecord(activePoints.toList())
        activePoints.clear()
        // 文件读写放到后台线程，服务销毁时主线程不会被大文件卡住。
        if (record != null) DriveRecordStore.addRecordAsync(this, record)
        DriveCheckpointStore.clearAsync(this, DriveCheckpointStore.GPS_TRACK)
        lastCheckpointElapsedMs = 0L
    }

    private fun maybeSaveCheckpoint() {
        val now = SystemClock.elapsedRealtime()
        if (lastCheckpointElapsedMs > 0L && now - lastCheckpointElapsedMs < CHECKPOINT_INTERVAL_MS) return
        lastCheckpointElapsedMs = now
        val record = buildRecord(activePoints.toList()) ?: return
        val meta = JSONObject()
            .put("manual", manual)
            .put("btAddress", bluetoothAddress ?: JSONObject.NULL)
            .put("btName", bluetoothName ?: JSONObject.NULL)
            .put("sessionStartWallMs", sessionStartWallMs)
        DriveCheckpointStore.saveAsync(this, DriveCheckpointStore.GPS_TRACK, record, meta)
    }

    /** 主动开始/布防前，把上次异常中断留下的轨迹先存成记录。 */
    private fun recoverInterruptedTrack() {
        if (mode != Mode.IDLE) return
        DriveCheckpointStore.recoverAsRecordAsync(this, DriveCheckpointStore.GPS_TRACK, MIN_RECORD_DURATION_MS)
    }

    /** 被系统杀掉后 START_STICKY 重启（intent 为 null）：最近的断点接着记录，太旧的存成记录。 */
    private fun resumeAfterRestart() {
        val appContext = applicationContext
        DriveCheckpointStore.takeAsync(appContext, DriveCheckpointStore.GPS_TRACK) { checkpoint ->
            mainHandler.post {
                val fresh = checkpoint != null &&
                    System.currentTimeMillis() - checkpoint.savedAtMs in 0L..RESUME_MAX_GAP_MS
                val resumed = checkpoint != null && fresh && instance === this && mode == Mode.IDLE &&
                    restoreFrom(checkpoint)
                if (!resumed) {
                    checkpoint?.let { DriveRecordStore.addRecordAsync(appContext, it.record) }
                    if (instance === this && mode == Mode.IDLE) stopSelf()
                }
            }
        }
    }

    private fun restoreFrom(checkpoint: DriveCheckpointStore.Checkpoint): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val meta = checkpoint.meta
        val startWall = meta.optLong("sessionStartWallMs", 0L).takeIf { it > 0L } ?: checkpoint.record.startTimeMs
        if (startWall <= 0L) return false
        manual = meta.optBoolean("manual", false)
        bluetoothAddress = meta.optString("btAddress").takeIf { !meta.isNull("btAddress") && it.isNotBlank() }
        bluetoothName = meta.optString("btName").takeIf { !meta.isNull("btName") && it.isNotBlank() }
        mode = Mode.RECORDING
        sessionStartWallMs = startWall
        sessionStartElapsedMs = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - startWall).coerceAtLeast(0L)
        activePoints.clear()
        checkpoint.record.routePoints.forEach { point ->
            activePoints += VideoTrackPoint(
                elapsedMs = (point.timeMs - startWall).coerceAtLeast(0L),
                timeMs = point.timeMs,
                latitude = point.latitude,
                longitude = point.longitude,
                speedKmh = point.speedKmh,
                accuracyMeters = point.accuracyMeters,
                bearingDegrees = point.bearingDegrees
            )
        }
        if (!enterForeground()) {
            mode = Mode.IDLE
            return false
        }
        startLocationWatch()
        return true
    }

    private fun stopAndFinish() {
        finishActiveRecord()
        mode = Mode.IDLE
        manual = false
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
            Intent(this, if (manual) CameraRecordActivity::class.java else TrackRecordingSettingsActivity::class.java),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackRecordingService::class.java).apply { action = ACTION_STOP },
            flags
        )
        val title = if (mode == Mode.RECORDING) R.string.track_notif_recording_title else R.string.track_notif_monitoring_title
        val text = when {
            manual && mode == Mode.RECORDING -> getString(R.string.track_only_notif_text)
            mode == Mode.RECORDING -> getString(R.string.track_notif_recording_text, bluetoothName ?: getString(R.string.trigger_unknown_car))
            else -> getString(R.string.track_notif_monitoring_text, bluetoothName ?: getString(R.string.trigger_unknown_car))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .addAction(android.R.drawable.ic_lock_power_off, getString(R.string.notif_action_exit_background), AppBackgroundExit.pendingIntent(this))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_ARM = "com.thomas.carspeed.action.TRACK_RECORDING_ARM"
        private const val ACTION_START_NOW = "com.thomas.carspeed.action.TRACK_RECORDING_START_NOW"
        private const val ACTION_STOP = "com.thomas.carspeed.action.TRACK_RECORDING_STOP"
        private const val EXTRA_BT_ADDRESS = "bt_address"
        private const val EXTRA_BT_NAME = "bt_name"
        private const val CHANNEL_ID = "track_recording_channel"
        private const val NOTIFICATION_ID = 5001
        private const val TRACK_POINT_INTERVAL_MS = 1_000L
        private const val MAX_TRACK_POINTS = 24 * 60 * 60
        private const val MIN_RECORD_DURATION_MS = 10_000L
        private const val MIN_RECORD_DISTANCE_METERS = 20f
        private const val MAX_PENDING_POINTS = 120
        private const val CHECKPOINT_INTERVAL_MS = 60_000L
        private const val RESUME_MAX_GAP_MS = 5 * 60_000L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile var instance: TrackRecordingService? = null
            private set

        fun arm(context: Context, bluetoothAddress: String?, bluetoothName: String?) {
            if (!hasLocationPermission(context)) return
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_ARM
                putExtra(EXTRA_BT_ADDRESS, bluetoothAddress)
                putExtra(EXTRA_BT_NAME, bluetoothName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 系统不允许从后台拉起定位前台服务；提醒用户点开 App，前台同步时会重新布防。
                AmapWatchNotifier.showBackgroundStartBlocked(
                    context,
                    "已连接车辆蓝牙",
                    "系统拦截了后台 GPS 轨迹记录，点一下打开 App 即可继续。"
                )
            }
        }

        fun stop(context: Context) {
            if (instance == null) return
            val intent = Intent(context, TrackRecordingService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (_: RuntimeException) {
                context.stopService(Intent(context, TrackRecordingService::class.java))
            }
        }

        fun isRunning(): Boolean = instance != null

        fun isManual(): Boolean = instance?.manual == true

        /** 当前这一段手动轨迹的已采点快照，给主页地图框实时画线用。 */
        fun livePoints(): List<VideoTrackPoint> = instance?.liveSnapshot() ?: emptyList()

        /** 当前轨迹段已进行的毫秒数，用来定位地图上的当前点。 */
        fun liveElapsedMs(): Long = instance?.liveSessionElapsedMs() ?: 0L

        fun startNow(context: Context) {
            if (!hasLocationPermission(context)) {
                Toast.makeText(context, R.string.track_location_permission_required, Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, TrackRecordingService::class.java).apply {
                action = ACTION_START_NOW
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                // 后台限制时下一次回到前台可以再点一次。
            }
        }

        private fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private enum class Mode {
        IDLE,
        MONITORING,
        RECORDING
    }
}
