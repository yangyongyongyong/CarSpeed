package com.thomas.carspeed

import java.util.Locale
import kotlin.math.roundToInt

internal data class RadarCameraSnapshot(
    val key: String,
    val distanceMeters: Int,
    val cameraType: Int,
    val speedLimit: Int?
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
    private const val MIN_PASS_ZONE_METERS = 30
    private const val PASS_DISTANCE_INCREASE_METERS = 40
    private const val PASS_ZONE_EXTRA_SECONDS = 1
    private const val FAR_UNKNOWN_LIMIT_DISTANCE_METERS = 3_000

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
                val enteredPassZone = currentLock.enteredPassZone || isInPassZone(lockedCamera.distanceMeters, speedKmh, settings)
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
                enteredPassZone = isInPassZone(nearest.distanceMeters, speedKmh, settings)
            ),
            passedCameraKey = passedCameraKey
        )
    }

    fun etaSeconds(distanceMeters: Int, speedKmh: Float): Int? {
        if (speedKmh <= 0f) return null
        val speedMps = speedKmh / 3.6f
        return (distanceMeters / speedMps).roundToInt().coerceAtLeast(0)
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

    private fun isInPassZone(distanceMeters: Int, speedKmh: Float, settings: RadarAlertSettings): Boolean {
        val minThresholdSeconds = settings.thresholdsSeconds.minOrNull() ?: 0
        val speedMps = speedKmh.coerceAtLeast(0f) / 3.6f
        val dynamicPassDistance = (speedMps * (minThresholdSeconds + settings.leadSeconds + PASS_ZONE_EXTRA_SECONDS))
            .roundToInt()
        val passDistance = maxOf(MIN_PASS_ZONE_METERS, dynamicPassDistance)
        return distanceMeters <= passDistance
    }

    const val DATA_STALE_WARNING_MS = 60_000L
    const val DATA_WARNING_REPEAT_MS = 180_000L
}
