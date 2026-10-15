package com.thomas.carspeed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 行车录像和测速雷达共用一套倾角识别，避免两套传感器状态互相打架。
 * 时间戳使用 elapsedRealtime。
 */
internal object RoadLayerEngine {
    private val lock = Any()
    private var clients = 0
    private var appContext: Context? = null
    private var monitor = RoadLayerMonitor()
    private val divergenceTracker = RoadDivergenceTracker()
    private var sensorManager: SensorManager? = null
    private var locationManager: LocationManager? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var decisionListener: ((RoadLayerDecision) -> Unit)? = null
    private var pendingSubtitleCorrection: DivergenceCorrection? = null

    @Volatile var sensorsAvailable: Boolean = false
        private set

    @Volatile var currentDecision: RoadLayerDecision? = null
        private set

    @Volatile var amapElevatedOverlap: Boolean = false
        private set

    @Volatile var amapElevatedFlag: Int = RoadLayerAlertGate.ELEVATED_NONE
        private set

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val timeMs = event.timestamp / 1_000_000L
            val x = event.values.getOrNull(0) ?: return
            val y = event.values.getOrNull(1) ?: return
            val z = event.values.getOrNull(2) ?: return
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> monitor.onAccelerometer(timeMs, x, y, z)
                Sensor.TYPE_GYROSCOPE -> publishGyro(timeMs, x, y, z)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start(context: Context): Boolean {
        synchronized(lock) {
            clients += 1
            appContext = context.applicationContext
            if (clients > 1) return sensorsAvailable
            val sensors = appContext?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sensors == null) {
                sensorsAvailable = false
                return false
            }
            val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sensorsAvailable = accelerometer != null && gyroscope != null
            if (!sensorsAvailable) return false
            sensorManager = sensors
            sensors.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            sensors.registerListener(sensorListener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
            registerSatellites(context.applicationContext)
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (clients == 0) return
            clients -= 1
            if (clients > 0) return
            sensorManager?.unregisterListener(sensorListener)
            sensorManager = null
            unregisterSatellites()
            monitor = RoadLayerMonitor()
            divergenceTracker.clear()
            pendingSubtitleCorrection = null
            currentDecision = null
            amapElevatedOverlap = false
            amapElevatedFlag = RoadLayerAlertGate.ELEVATED_NONE
            sensorsAvailable = false
            appContext = null
        }
    }

    fun onSpeed(timeMs: Long, speedKmh: Float) {
        monitor.onSpeed(timeMs, speedKmh)
    }

    fun onGpsFix(timeMs: Long, accuracyMeters: Float?, bearingDegrees: Float? = null) {
        val corrected = synchronized(lock) {
            monitor.onGpsFix(timeMs, accuracyMeters)
            val config = roadLayerConfig()
            val decision = currentDecision
            val correction = divergenceTracker.update(
                DivergenceSample(
                    timeMs = timeMs,
                    bearingDegrees = bearingDegrees,
                    pitchFlat = decision?.phase == PitchPhase.FLAT,
                    assumedLayer = decision?.alertLayer ?: config.defaultLayer,
                    provisional = decision?.usingDefault ?: true,
                    elevatedFlag = amapElevatedFlag,
                    enabled = config.divergenceCorrectionEnabled
                )
            ) ?: return@synchronized null
            val updated = monitor.correctLayer(correction.layer, config)
            currentDecision = updated
            pendingSubtitleCorrection = correction
            updated
        } ?: return
        RadarAlertController.updateRoadLayer(corrected)
        decisionListener?.invoke(corrected)
    }

    fun onSatelliteCount(timeMs: Long, satelliteCount: Int?, meanCn0DbHz: Float? = null) {
        synchronized(lock) {
            if (clients == 0) return
            monitor.onSatelliteCount(timeMs, satelliteCount, meanCn0DbHz)
        }
    }

    fun noteOwnParallelSwitch(timeMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) { divergenceTracker.noteOwnSwitch(timeMs) }
    }

    fun consumeSubtitleCorrection(): DivergenceCorrection? {
        synchronized(lock) {
            val pending = pendingSubtitleCorrection
            pendingSubtitleCorrection = null
            return pending
        }
    }

    fun setAmapElevatedFlag(flag: Int) {
        amapElevatedFlag = flag
        amapElevatedOverlap = flag == RoadLayerAlertGate.ELEVATED_ON ||
            flag == RoadLayerAlertGate.ELEVATED_UNDER
    }

    fun setDecisionListener(listener: ((RoadLayerDecision) -> Unit)?) {
        decisionListener = listener
    }

    fun subtitleLabel(): String? {
        val decision = currentDecision ?: return null
        return RoadLayerLabels.subtitle(decision, amapElevatedOverlap)
    }

    private fun publishGyro(timeMs: Long, x: Float, y: Float, z: Float) {
        val decision = synchronized(lock) {
            val updated = monitor.onGyroscope(timeMs, x, y, z, roadLayerConfig()) ?: return
            currentDecision = updated
            updated
        }
        RadarAlertController.updateRoadLayer(decision)
        decisionListener?.invoke(decision)
    }

    private fun roadLayerConfig(): RoadLayerConfig {
        val settings = appContext?.let { RadarAlertSettingsStore.load(it) }
        return RoadLayerConfig(
            unknownDefaultsToElevated = settings?.unknownRoadLayerElevated
                ?: RadarAlertSettingsStore.DEFAULT_UNKNOWN_ROAD_LAYER_ELEVATED,
            divergenceCorrectionEnabled = settings?.roadDivergenceCorrectionEnabled
                ?: RadarAlertSettingsStore.DEFAULT_ROAD_DIVERGENCE_CORRECTION,
            pitchRecognitionEnabled = settings?.pitchRoadLayerEnabled
                ?: RadarAlertSettingsStore.DEFAULT_PITCH_ROAD_LAYER,
            satelliteTunnelEnabled = settings?.satelliteTunnelEnabled
                ?: RadarAlertSettingsStore.DEFAULT_SATELLITE_TUNNEL
        )
    }

    private fun registerSatellites(context: Context) {
        if (gnssStatusCallback != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                onSatelliteCount(
                    SystemClock.elapsedRealtime(),
                    GnssSatelliteCount.usedInFix(status.satelliteCount, status::usedInFix),
                    GnssSatelliteCount.meanCn0DbHz(status.satelliteCount, status::usedInFix, status::getCn0DbHz)
                )
            }

            override fun onStopped() {
                onSatelliteCount(SystemClock.elapsedRealtime(), null)
            }
        }
        try {
            val registered = manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            if (registered) {
                gnssStatusCallback = callback
                locationManager = manager
            }
        } catch (_: SecurityException) {
            // 没有定位权限时只是不用卫星数识别隧道。
        } catch (_: RuntimeException) {
            // 卫星状态不可用时，仍按定位精度判断隧道。
        }
    }

    private fun unregisterSatellites() {
        val callback = gnssStatusCallback ?: return
        gnssStatusCallback = null
        try {
            locationManager?.unregisterGnssStatusCallback(callback)
        } catch (_: RuntimeException) {
            // ignore teardown exception
        }
        locationManager = null
    }
}
