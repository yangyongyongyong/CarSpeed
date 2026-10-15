package com.thomas.carspeed

/**
 * 把定位速度和 IMU 样本合成道路层级。
 * 速度时间和传感器时间都使用 elapsedRealtime。
 */
internal class RoadLayerMonitor {
    private val pitchTracker = VehiclePitchTracker()
    private val resolver = RoadLayerResolver()

    private var ax = 0f
    private var ay = 0f
    private var az = 0f
    private var accelTimeMs = Long.MIN_VALUE
    private var lastSpeedTimeMs: Long? = null
    private var lastSpeedKmh = 0f
    private var speedIncreasingUntilMs = 0L
    private var speedBrakingUntilMs = 0L
    private var lastGoodFixMs = 0L
    private var lastAccuracyMeters: Float? = null
    private val satellites = SatelliteTunnelTracker()

    fun onSpeed(timeMs: Long, speedKmh: Float) {
        val previousSpeedMs = lastSpeedTimeMs
        val dtSeconds = if (previousSpeedMs == null) 0f else (timeMs - previousSpeedMs) / 1_000f
        if (previousSpeedMs != null && dtSeconds in 0.2f..5f) {
            val delta = speedKmh - lastSpeedKmh
            val rate = delta / dtSeconds
            if (rate >= SPEED_RATE_KMH_PER_S && delta >= SPEED_DELTA_KMH) {
                speedIncreasingUntilMs = timeMs + SPEED_FLAG_HOLD_MS
            }
            if (rate <= -SPEED_RATE_KMH_PER_S) {
                speedBrakingUntilMs = timeMs + SPEED_FLAG_HOLD_MS
            }
        }
        lastSpeedTimeMs = timeMs
        lastSpeedKmh = speedKmh
    }

    fun onAccelerometer(timeMs: Long, x: Float, y: Float, z: Float) {
        ax = x
        ay = y
        az = z
        accelTimeMs = timeMs
    }

    fun onGyroscope(
        timeMs: Long,
        x: Float,
        y: Float,
        z: Float,
        config: RoadLayerConfig = RoadLayerConfig()
    ): RoadLayerDecision? {
        if (accelTimeMs == Long.MIN_VALUE || timeMs - accelTimeMs > MAX_ACCEL_AGE_MS) return null
        val estimate = pitchTracker.update(
            ImuSample(
                timeMs = timeMs,
                ax = ax,
                ay = ay,
                az = az,
                gx = x,
                gy = y,
                gz = z,
                speedIncreasing = timeMs <= speedIncreasingUntilMs,
                speedBraking = timeMs <= speedBrakingUntilMs
            )
        )
        return resolver.update(
            RoadAttitudeSample(
                timeMs = timeMs,
                pitchDegrees = estimate.pitchDegrees,
                phoneMoved = estimate.phoneMoved,
                forwardKnown = estimate.forwardKnown,
                gpsWeak = gpsWeakAt(timeMs, config.satelliteTunnelEnabled)
            ),
            config
        )
    }

    fun correctLayer(layer: RoadLayer, config: RoadLayerConfig = RoadLayerConfig()): RoadLayerDecision {
        return resolver.applyCorrection(layer, config)
    }

    fun onGpsFix(timeMs: Long, accuracyMeters: Float?) {
        lastAccuracyMeters = accuracyMeters
        if (accuracyMeters != null && accuracyMeters < RoadLayerRules.WEAK_GPS_ACCURACY_METERS) {
            lastGoodFixMs = timeMs
        }
    }

    fun onSatelliteCount(timeMs: Long, satelliteCount: Int?, meanCn0DbHz: Float? = null) {
        satellites.update(timeMs, satelliteCount, meanCn0DbHz)
    }

    private fun gpsWeakAt(timeMs: Long, satelliteTunnelEnabled: Boolean): Boolean {
        val accuracyWeak = lastAccuracyMeters != null &&
            lastAccuracyMeters!! >= RoadLayerRules.WEAK_GPS_ACCURACY_METERS
        return tunnelGpsWeak(
            hadGoodFix = lastGoodFixMs != 0L,
            accuracyWeak = accuracyWeak,
            fixStale = lastGoodFixMs != 0L && timeMs - lastGoodFixMs >= RoadLayerRules.TUNNEL_ENTER_MS,
            satelliteScarce = satellites.scarce,
            skyConfirmsGround = satellites.skyConfirmsGround,
            satelliteTunnelEnabled = satelliteTunnelEnabled
        )
    }

    companion object {
        private const val MAX_ACCEL_AGE_MS = 200L
        private const val SPEED_RATE_KMH_PER_S = 2.5f
        private const val SPEED_DELTA_KMH = 3f
        private const val SPEED_FLAG_HOLD_MS = 1_500L
    }
}
