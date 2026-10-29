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
    /**
     * 摄像头身份的量化精度：每度切多少格。
     * 1 度约 111 公里，3500 格约合 32 米，比巡航坐标的抖动（几米）大一个量级，
     * 又远小于两个摄像头之间通常的间隔。
     */
    private const val KEY_CELLS_PER_DEGREE = 3_500.0
    /** 退化匹配时允许的回弹：摄像头不会一帧之间变远这么多。 */
    private const val LOCK_MATCH_SLACK_METERS = 120
    /** m/s 与 km/h 的换算。 */
    private const val MS_TO_KMH = 3.6f
    /** 超过这个数值就不可能是 m/s（将折合 180km/h 以上），判定单位时用它做上限。 */
    private const val MAX_PLAUSIBLE_MS = 50f
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
            val lockedCamera = matchLockedCamera(validCameras, currentLock)
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
     * 摄像头的身份。
     *
     * 原来直接把巡航回调的原始坐标乘 1e5 取整当 key，精度约 1 米。
     * 高德巡航每次回调的坐标都会抖几米，同一个摄像头每一帧都算出不同的 key，
     * 于是「已播报阈值」「ETA 平滑基准」「已通过记录」全部失效——
     * 表现就是反复播报、秒数忽大忽小、开过去又报一次。
     *
     * 改成量化到约 30 米一格：正常抖动落在同一格内，相隔 30 米以上的两个摄像头又不会并成一个。
     * 限速值同样要稳：拿不到时统一归到「无限速」一类，不要让它 0 和 80 来回跳。
     */
    fun cameraKey(
        longitude: Double,
        latitude: Double,
        broadcastType: Int,
        speedLimit: Int?
    ): String {
        val cellLon = stableCell(longitude)
        val cellLat = stableCell(latitude)
        val limit = (speedLimit?.takeIf { it > 0 } ?: 0).coerceAtLeast(0)
        return "$cellLon:$cellLat:$broadcastType:$limit"
    }

    /**
     * 把坐标归到一个宽格子里。
     *
     * 直接 `round(x * N)` 的话，坐标正好落在格边时抖 1 米就会跨格，
     * 于是同一个摄像头仍然会算出两把 key。这里向下取整并抹掉低两位：
     * 格宽约 64 米，而真实抖动只有几米，正常情况下推不过一整格。
     * 跨格的少数情况由 [matchLockedCamera] 的退化匹配兜住。
     */
    private fun stableCell(value: Double): Int {
        val fine = kotlin.math.floor(value * KEY_CELLS_PER_DEGREE).toInt()
        return fine - ((fine % 4) + 4) % 4
    }

    /**
     * 找回正在跟踪的那个摄像头。
     *
     * 巡航回调的坐标每次都抖几米，key 里的量化格偶尔会跨一格，
     * 光靠 key 相等会把同一部摄像头认成新的，锁定立刻断掉、去重状态全丢。
     *
     * 所以先用 key 精确对；对不上时，退化成「同类型、同限速、剩余距离连续」——
     * 摄像头只可能越来越近，不会一帧之间跳到别处，这条约束足以认定是同一部。
     */
    private fun matchLockedCamera(
        cameras: List<RadarCameraSnapshot>,
        lock: RadarCameraLock
    ): RadarCameraSnapshot? {
        cameras.firstOrNull { it.key == lock.key }?.let { return it }
        // 退化匹配：同类型、同限速、剩余距离没有跳远。摄像头只会越来越近，
        // 不会一帧之间跳到别处，这条约束足以认定是同一部。
        return cameras
            .filter { sameCamera(it, lock.key) }
            .filter { it.distanceMeters <= lock.lastDistanceMeters + LOCK_MATCH_SLACK_METERS }
            .minByOrNull { it.distanceMeters }
    }

    /** 类型和限速一致才算同一部摄像头；key 里的坐标部分允许对不上。 */
    private fun sameCamera(camera: RadarCameraSnapshot, lockKey: String): Boolean {
        val lockType = lockKey.split(':').getOrNull(2)?.toIntOrNull() ?: return false
        val lockLimit = lockKey.split(':').getOrNull(3)?.toIntOrNull() ?: return false
        return camera.cameraType == lockType &&
            ((camera.speedLimit?.takeIf { it > 0 } ?: 0)) == lockLimit
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
     * 把定位给的速度统一成 km/h。
     *
     * `AMapNaviLocation.getSpeed()` 和 Android `Location.getSpeed()` 一样是 **米/秒**，
     * 但历史上这一路数值被当成 km/h 直接用了：市区 60km/h 读成「16.7」，
     * 慢了 3.6 倍，ETA 被放大同样的倍数——500 米算出 108 秒而不是 30 秒，
     * 于是 20 秒阈值在一公里外就开始喊。这是「播报总是不准确」最主要的一条。
     *
     * 判定依据是数值范围：车辆速度换算成 km/h 之后不会低到和 m/s 数值混淆的程度。
     * 30km/h 以下二者会重叠（8.3 vs 30 差得远，但 20km/h=5.6m/s 与 5.6km/h 难分），
     * 所以只在这个阈值以上才纠正，以下一律按原值——低速时本来就不播报。
     */
    fun normaliseSpeedKmh(rawSpeed: Float): Float {
        if (rawSpeed <= 0f) return 0f
        // 已经是 km/h 的典型车速不会这么小；这么小的只可能是 m/s 或者真在挪车，
        // 后者本来也低于播报门限 [MIN_SPEED_FOR_ETA_KMH]，纠正与否都不影响结果。
        return if (rawSpeed <= MAX_PLAUSIBLE_MS) rawSpeed * MS_TO_KMH else rawSpeed
    }

    /**
     * 巡航定位速度的读取入口。传进来的可能是原始 m/s，这里统一换算。
     */
    fun cruiseSpeedKmh(rawSpeed: Float): Float = normaliseSpeedKmh(rawSpeed)

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
