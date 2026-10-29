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
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class RadarAlertService : Service(), LocationListener {
    private var locationManager: LocationManager? = null
    private var roadLayerClient = false
    private var attitudeSensorsAvailable = false
    private var lastRoadNotifKey: String? = null
    @Volatile private var networkAvailable = true
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
            ACTION_SYNC -> {
                // 由 startForegroundService 启动：无论接下来是否要停，都必须先进入前台，否则系统会让 App 崩溃。
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startIfEnabled()) return START_NOT_STICKY
            }
            else -> if (!startIfEnabled()) return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        stopAttitudeWatch()
        stopLocationWatch()
        RadarAlertController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        val now = SystemClock.elapsedRealtime()
        RoadLayerEngine.onGpsFix(
            now,
            location.accuracy.takeIf { location.hasAccuracy() },
            location.bearing.takeIf { location.hasBearing() }
        )
        if (!location.hasSpeed()) return
        val speedKmh = SpeedRules.toKmhClamped(location.speed)
        RadarAlertController.updateSpeed(speedKmh)
        RadarAlertController.checkDataAvailability(networkAvailable)
        RoadLayerEngine.onSpeed(now, speedKmh)
    }

    private fun startIfEnabled(): Boolean {
        if (!RadarAlertSettingsStore.load(this).enabled || !PrivacyConsent.isAgreed(this)) {
            stopSelf()
            return false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.radar_location_permission_required, Toast.LENGTH_SHORT).show()
            stopSelf()
            return false
        }
        startAttitudeWatch()
        if (!enterForeground()) {
            stopSelf()
            return false
        }
        registerNetworkCallback()
        startLocationWatch()
        RadarAlertController.refresh(this)
        return true
    }

    private fun startAttitudeWatch() {
        if (!roadLayerClient) {
            roadLayerClient = true
            attitudeSensorsAvailable = RoadLayerEngine.start(this)
            RoadLayerEngine.setDecisionListener { decision -> publishRoadLayerNotification(decision) }
        }
        RadarAlertController.setAttitudeSensorsAvailable(RoadLayerEngine.sensorsAvailable)
        attitudeSensorsAvailable = RoadLayerEngine.sensorsAvailable
    }

    private fun stopAttitudeWatch() {
        if (!roadLayerClient) return
        roadLayerClient = false
        RoadLayerEngine.setDecisionListener(null)
        RoadLayerEngine.setAmapElevatedFlag(RoadLayerAlertGate.ELEVATED_NONE)
        RoadLayerEngine.stop()
    }

    private fun publishRoadLayerNotification(decision: RoadLayerDecision) {
        val key = "${decision.alertLayer}:${decision.usingDefault}:${decision.correctedByDivergence}"
        if (key == lastRoadNotifKey) return
        lastRoadNotifKey = key
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
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

    private fun enterForeground(): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // 网络状态改用回调维护，避免每次定位回调都跨进程查询一次。
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkAvailable = runCatching { isNetworkAvailable() }.getOrDefault(true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                networkAvailable = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            override fun onLost(network: Network) {
                networkAvailable = false
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            // 注册失败时保留上面查到的一次结果。
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
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
            .setContentText(roadNotificationText())
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .addAction(android.R.drawable.ic_lock_power_off, getString(R.string.notif_action_exit_background), AppBackgroundExit.pendingIntent(this))
            .setOngoing(true)
            .build()
    }

    private fun roadNotificationText(): String {
        if (!attitudeSensorsAvailable) return getString(R.string.radar_road_layer_no_gyro)
        val state = RadarAlertController.currentState()
        if (state.active && state.alertRoadInTunnel && !state.roadLayerUsingDefault) {
            return getString(R.string.radar_road_layer_tunnel)
        }
        if (state.active && state.roadLayerCorrectedByDivergence && !state.roadLayerUsingDefault) {
            return getString(
                if (state.alertRoadLayerElevated) {
                    R.string.radar_road_layer_divergence_elevated
                } else {
                    R.string.radar_road_layer_divergence_ground
                }
            )
        }
        val elevated = if (state.active) state.alertRoadLayerElevated else RadarAlertSettingsStore.load(this).unknownRoadLayerElevated
        val usingDefault = !state.active || state.roadLayerUsingDefault
        return when {
            usingDefault && elevated -> getString(R.string.radar_road_layer_default_elevated)
            usingDefault -> getString(R.string.radar_road_layer_default_ground)
            elevated -> getString(R.string.radar_road_layer_elevated)
            else -> getString(R.string.radar_road_layer_ground)
        }
    }

    companion object {
        private const val ACTION_SYNC = "com.thomas.carspeed.action.RADAR_ALERT_SYNC"
        private const val ACTION_STOP = "com.thomas.carspeed.action.RADAR_ALERT_STOP"
        private const val CHANNEL_ID = "radar_alert_channel"
        private const val NOTIFICATION_ID = 4001

        fun sync(context: Context) {
            val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasLocation || !PrivacyConsent.isAgreed(context)) {
                // 没有定位权限时启动定位类前台服务会直接抛 SecurityException（Android 14+）。
                stop(context)
                return
            }
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
