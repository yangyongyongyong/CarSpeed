package com.thomas.carspeed

import java.util.Locale
import kotlin.math.roundToInt

internal data class RadarCameraSnapshot(
    val key: String,
    val distanceMeters: Int,
    val cameraType: Int,
    val speedLimit: Int?,
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN
)

internal data class RadarCameraLock(
    val key: String,
    val lastDistanceMeters: Int,
    val closestDistanceMeters: Int,
    val lastSeenAtMs: Long,
    val enteredPassZone: Boolean
)

internal data class RadarCameraSelection(
    val camera: RadarCameraSnapshot?,
    val lock: RadarCameraLock?,
    val passedCameraKey: String? = null
)

internal data class RadarDataHealthState(
    val serviceStartedAtMs: Long,
    val lastDataAtMs: Long,
    val lastNetworkWarningAtMs: Long = 0L,
    val lastDataStaleWarningAtMs: Long = 0L
)

internal enum class RadarDataWarningType {
    NETWORK_UNAVAILABLE,
    DATA_STALE
}

internal object RadarAlertPlanner {
    const val PASS_UNLOCK_MISSING_MS = 8_000L
    const val LOST_UNLOCK_MS = 30_000L
    const val FAR_UNKNOWN_LIMIT_ALERT_MARKER = -3_000
    const val SPEED_CAMERA_TYPE = 4
    const val INTERVAL_CAMERA_TYPE = 11
    const val SPEEDING_VIOLATION_TYPE = 102
    const val MIN_SPEED_FOR_ETA_KMH = 5f
    const val NAVI_SPEED_FRESH_MS = 3_000L
    private const val MIN_PASS_ZONE_METERS = 30
    private const val PASS_AT_CAMERA_MAX_METERS = 60
    private const val PASS_APPROACH_SECONDS = 2
    private const val PASS_DISTANCE_INCREASE_METERS = 40
    private const val FAR_UNKNOWN_LIMIT_DISTANCE_METERS = 3_000

    @Suppress("UNUSED_PARAMETER")
    fun selectActiveCamera(
        cameras: List<RadarCameraSnapshot>,
        currentLock: RadarCameraLock?,
        nowMs: Long,
        speedKmh: Float,
        settings: RadarAlertSettings
    ): RadarCameraSelection {
        val validCameras = cameras.filter { it.distanceMeters > 0 }
        var passedCameraKey: String? = null
        var selectableCameras = validCameras
        if (currentLock != null) {
            val lockedCamera = validCameras.firstOrNull { it.key == currentLock.key }
            if (lockedCamera != null) {
                val closestDistance = minOf(currentLock.closestDistanceMeters, lockedCamera.distanceMeters)
                val enteredPassZone = currentLock.enteredPassZone || isInPassZone(lockedCamera.distanceMeters, speedKmh)
                val passedByDistanceIncrease = enteredPassZone &&
                    lockedCamera.distanceMeters >= closestDistance + PASS_DISTANCE_INCREASE_METERS
                if (!passedByDistanceIncrease) {
                    return RadarCameraSelection(
                        camera = lockedCamera,
                        lock = RadarCameraLock(
                            key = currentLock.key,
                            lastDistanceMeters = lockedCamera.distanceMeters,
                            closestDistanceMeters = closestDistance,
                            lastSeenAtMs = nowMs,
                            enteredPassZone = enteredPassZone
                        )
                    )
                }
                passedCameraKey = currentLock.key
                selectableCameras = validCameras.filter { it.key != currentLock.key }
            } else {
                val missingMs = nowMs - currentLock.lastSeenAtMs
                val passedAfterClose = currentLock.enteredPassZone && missingMs >= PASS_UNLOCK_MISSING_MS
                val lostForTooLong = missingMs >= LOST_UNLOCK_MS
                if (!passedAfterClose && !lostForTooLong) {
                    return RadarCameraSelection(camera = null, lock = currentLock)
                }
                if (passedAfterClose) passedCameraKey = currentLock.key
            }
        }

        val nearest = selectableCameras.minByOrNull { it.distanceMeters }
            ?: return RadarCameraSelection(null, null, passedCameraKey)
        return RadarCameraSelection(
            camera = nearest,
            lock = RadarCameraLock(
                key = nearest.key,
                lastDistanceMeters = nearest.distanceMeters,
                closestDistanceMeters = nearest.distanceMeters,
                lastSeenAtMs = nowMs,
                enteredPassZone = isInPassZone(nearest.distanceMeters, speedKmh)
            ),
            passedCameraKey = passedCameraKey
        )
    }

    /**
     * 测速只用官方类型：4 测速摄像头、11 连续拍照（区间测速）、102 超速违章。
     * 0 是未知道路设施，不能当成雷达。
     */
    fun isSpeedCamera(broadcastType: Int): Boolean {
        return broadcastType == SPEED_CAMERA_TYPE ||
            broadcastType == INTERVAL_CAMERA_TYPE ||
            broadcastType == SPEEDING_VIOLATION_TYPE
    }

    /**
     * 剩余距离来自高德绑路。到达时间优先用同一次巡航定位的车速，
     * 这套速度超过 3 秒没更新时，才退回系统 GPS 速度。
     */
    fun etaSpeedKmh(
        naviSpeedKmh: Float,
        naviSpeedAtMs: Long,
        vehicleSpeedKmh: Float,
        nowMs: Long
    ): Float {
        val ageMs = nowMs - naviSpeedAtMs
        val naviUsable = naviSpeedKmh >= MIN_SPEED_FOR_ETA_KMH &&
            naviSpeedAtMs > 0L &&
            ageMs in 0..NAVI_SPEED_FRESH_MS
        return if (naviUsable) naviSpeedKmh else vehicleSpeedKmh
    }

    /**
     * 播报里的米数和秒数是同一件事：说「10 秒」就说这段时间按当前车速能开过的距离。
     * 不用当次回调里可能已经跳变的原始米数，避免「10 秒，80 米」。
     */
    fun spokenDistanceMeters(thresholdSeconds: Int, speedKmh: Float): Int {
        if (thresholdSeconds <= 0 || speedKmh <= 0f) return 0
        val speedMps = speedKmh / 3.6f
        return (thresholdSeconds * speedMps).roundToInt().coerceAtLeast(0)
    }

    fun etaSeconds(distanceMeters: Int, speedKmh: Float): Int? {
        if (speedKmh <= 0f) return null
        val speedMps = speedKmh / 3.6f
        return (distanceMeters / speedMps).roundToInt().coerceAtLeast(0)
    }

    /**
     * 高德距离偶尔会一下子缩短。匀速时到达时间最多跟着真实时间往下走，
     * 不能刚报完 20 秒、过几秒又报 5 秒。测量值和这条底线差不多时，改信新的测量。
     */
    fun reconcileEta(anchor: EtaAnchor?, rawEtaSeconds: Int, nowMs: Long): EtaReconcile {
        val prior = anchor ?: return EtaReconcile(rawEtaSeconds, EtaAnchor(rawEtaSeconds, nowMs))
        val elapsedSec = (nowMs - prior.atMs) / 1_000f
        if (elapsedSec < 0f) return EtaReconcile(rawEtaSeconds, EtaAnchor(rawEtaSeconds, nowMs))
        val floorEta = (prior.etaSeconds - elapsedSec - ETA_DROP_SLACK_SEC).roundToInt().coerceAtLeast(0)
        return if (rawEtaSeconds >= floorEta) {
            EtaReconcile(rawEtaSeconds, EtaAnchor(rawEtaSeconds, nowMs))
        } else {
            EtaReconcile(floorEta, prior)
        }
    }

    fun dueThresholds(etaSeconds: Int, settings: RadarAlertSettings, fired: Set<Int>): List<Int> {
        return settings.thresholdsSeconds.filter { threshold ->
            threshold !in fired && etaSeconds <= threshold + settings.leadSeconds
        }
    }

    fun thresholdToSpeak(dueThresholds: List<Int>, etaSeconds: Int, settings: RadarAlertSettings): Int? {
        return dueThresholds.minByOrNull { threshold ->
            kotlin.math.abs(etaSeconds - (threshold + settings.leadSeconds))
        }
    }

    fun thresholdsToMark(dueThresholds: List<Int>, spokenThreshold: Int?): Set<Int> {
        if (spokenThreshold == null) return emptySet()
        return dueThresholds.filter { it >= spokenThreshold }.toSet()
    }

    fun shouldSpeakFarUnknownLimitAlert(camera: RadarCameraSnapshot, fired: Set<Int>): Boolean {
        return camera.speedLimit == null &&
            camera.distanceMeters > FAR_UNKNOWN_LIMIT_DISTANCE_METERS &&
            FAR_UNKNOWN_LIMIT_ALERT_MARKER !in fired
    }

    fun farUnknownLimitAlertText(distanceMeters: Int): String {
        return "前方${distanceText(distanceMeters)}有测速雷达，暂无限速信息"
    }

    fun distanceText(distanceMeters: Int): String {
        return if (distanceMeters >= 1000) {
            String.format(Locale.CHINA, "%.1f公里", distanceMeters / 1000f)
        } else {
            "${distanceMeters.coerceAtLeast(0)}米"
        }
    }

    fun dataWarningType(
        networkAvailable: Boolean,
        speedKmh: Float,
        nowMs: Long,
        health: RadarDataHealthState
    ): RadarDataWarningType? {
        if (speedKmh < 5f) return null
        if (!networkAvailable) {
            return if (canRepeatWarning(health.lastNetworkWarningAtMs, nowMs)) {
                RadarDataWarningType.NETWORK_UNAVAILABLE
            } else {
                null
            }
        }
        val lastDataAtMs = health.lastDataAtMs.takeIf { it > 0L } ?: health.serviceStartedAtMs
        if (nowMs - lastDataAtMs < DATA_STALE_WARNING_MS) return null
        return if (canRepeatWarning(health.lastDataStaleWarningAtMs, nowMs)) {
            RadarDataWarningType.DATA_STALE
        } else {
            null
        }
    }

    fun markDataWarning(
        health: RadarDataHealthState,
        warningType: RadarDataWarningType,
        nowMs: Long
    ): RadarDataHealthState {
        return when (warningType) {
            RadarDataWarningType.NETWORK_UNAVAILABLE -> health.copy(lastNetworkWarningAtMs = nowMs)
            RadarDataWarningType.DATA_STALE -> health.copy(lastDataStaleWarningAtMs = nowMs)
        }
    }

    fun shouldSpeakDataWarning(warningType: RadarDataWarningType): Boolean {
        return warningType == RadarDataWarningType.NETWORK_UNAVAILABLE
    }

    private fun canRepeatWarning(lastWarningAtMs: Long, nowMs: Long): Boolean {
        return lastWarningAtMs <= 0L || nowMs - lastWarningAtMs >= DATA_WARNING_REPEAT_MS
    }

    /**
     * 经过区贴着电子眼，大约 2 秒车程，限制在 30 到 60 米。
     * 按最后一次语音阈值去估，高速上会在两百米外就响提示音。
     */
    fun passZoneMeters(speedKmh: Float): Int {
        val speedMps = speedKmh.coerceAtLeast(0f) / 3.6f
        val bySpeed = (speedMps * PASS_APPROACH_SECONDS).roundToInt()
        return bySpeed.coerceIn(MIN_PASS_ZONE_METERS, PASS_AT_CAMERA_MAX_METERS)
    }

    private fun isInPassZone(distanceMeters: Int, speedKmh: Float): Boolean {
        return distanceMeters <= passZoneMeters(speedKmh)
    }

    const val DATA_STALE_WARNING_MS = 60_000L
    const val DATA_WARNING_REPEAT_MS = 180_000L
    const val ETA_DROP_SLACK_SEC = 2f
}

internal data class EtaAnchor(
    val etaSeconds: Int,
    val atMs: Long
)

internal data class EtaReconcile(
    val etaSeconds: Int,
    val anchor: EtaAnchor
)
