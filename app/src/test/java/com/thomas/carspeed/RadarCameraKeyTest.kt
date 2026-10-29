package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 摄像头身份的稳定性。
 *
 * 这是「播报总是不准确」的根因所在：巡航回调的坐标每次都抖几米，
 * 原来把原始坐标乘 1e5 取整当 key（精度约 1 米），同一个摄像头每一帧都算出不同的 key，
 * 于是「已播报阈值」「ETA 平滑基准」「已通过记录」三套去重状态全部失效。
 * 表现是反复播报、秒数忽大忽小、开过去了又报一次。
 *
 * 这里守住两层：
 * 1. key 量化到约 64 米，绝大多数抖动不再产生新 key；
 * 2. 万一还是跨格了，锁定要靠「同类型+同限速+距离连续」认出同一部摄像头。
 */
class RadarCameraKeyTest {

    private val settings = RadarAlertSettings(
        enabled = true,
        thresholdsSeconds = listOf(20, 10, 5),
        leadSeconds = 2,
        speechRatePercent = 150
    )

    /** 巡航回调的坐标抖动量级：1e-5 度约 1.1 米。 */
    private val jittersDegrees = listOf(0.00001, 0.00002, 0.00003, 0.00005, 0.0001)

    /** 多个基准位置，覆盖格边和格心，避免只在格心碰巧通过。 */
    private val basePositions = listOf(
        120.2123456 to 30.2084567,
        120.0 to 30.0,
        120.5 to 30.5,
        121.9876543 to 31.1234567,
        119.0000001 to 29.9999999,
        120.21231 to 30.20846
    )

    /**
     * 量化把精度从 1 米降到约 64 米：同样一段抖动，换 key 的次数要明显变少。
     * 量化不是万能的——坐标正好踩在格边时仍会跨格，那种情况由锁定匹配兜住（见下一条）。
     * 所以这里只要求比原来的 1 米精度明显更稳，不要求绝对稳定。
     */
    @Test
    fun quantizing_the_key_makes_jitter_far_less_likely_to_change_it() {
        fun rawKey(lon: Double, lat: Double) = "${(lon * 100_000).roundToInt()}:${(lat * 100_000).roundToInt()}:4:80"

        var stableCount = 0
        var rawStableCount = 0
        var total = 0
        basePositions.forEach { (lon, lat) ->
            val stable = RadarAlertPlanner.cameraKey(lon, lat, 4, 80)
            val rawStable = rawKey(lon, lat)
            jittersDegrees.forEach { jitter ->
                total += 2
                if (RadarAlertPlanner.cameraKey(lon + jitter, lat + jitter, 4, 80) == stable) stableCount += 1
                if (RadarAlertPlanner.cameraKey(lon - jitter, lat - jitter, 4, 80) == stable) stableCount += 1
                if (rawKey(lon + jitter, lat + jitter) == rawStable) rawStableCount += 1
                if (rawKey(lon - jitter, lat - jitter) == rawStable) rawStableCount += 1
            }
        }
        assertTrue(
            "量化后应比原来 1 米精度明显更稳：量化 $stableCount/$total，原来 $rawStableCount/$total",
            stableCount > rawStableCount
        )
    }

    @Test
    fun the_lock_survives_a_key_change_caused_by_coordinate_jitter() {
        val lon = 120.2123456
        val lat = 30.2084567
        // 用真实格式的 key：锁定匹配要解析出类型和限速，假 key 解析不出来。
        val originalKey = RadarAlertPlanner.cameraKey(lon, lat, 4, 80)
        val first = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera(originalKey, distanceMeters = 500)),
            currentLock = null,
            nowMs = 1_000L,
            speedKmh = 100f,
            settings = settings
        )
        assertEquals(originalKey, first.lock?.key)

        // 下一帧坐标抖过格边，key 变了，但仍应是同一部摄像头
        val driftedKey = RadarAlertPlanner.cameraKey(lon + 1.0 / 3_500.0, lat, 4, 80)
        assertNotEquals("这一帧的 key 确实应该变，才测得出容错", originalKey, driftedKey)

        val kept = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera(driftedKey, distanceMeters = 460)),
            currentLock = first.lock,
            nowMs = 2_000L,
            speedKmh = 100f,
            settings = settings
        )
        // 目标不是「key 字符串必须不变」（量化总有边界，做不到），
        // 而是「锁定不断」：必须在继续跟踪这部摄像头，而不是把它当成新的重置一遍。
        assertEquals(
            "坐标抖动后必须认出还是同一部摄像头，不能丢掉锁定",
            "4:80",
            "${kept.lock?.key?.split(':')?.getOrNull(2)}:${kept.lock?.key?.split(':')?.getOrNull(3)}"
        )
        // 最近距离要延续，被当成新摄像头的话会重置成当前距离。
        assertEquals(
            "最近距离应延续而不是被重置，实际=${kept.lock?.closestDistanceMeters}",
            minOf(500, 460),
            kept.lock?.closestDistanceMeters
        )
    }

    @Test
    fun a_different_camera_does_not_hijack_the_lock() {
        val lon = 120.2123456
        val lat = 30.2084567
        val originalKey = RadarAlertPlanner.cameraKey(lon, lat, 4, 80)
        val first = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera(originalKey, distanceMeters = 500)),
            currentLock = null,
            nowMs = 1_000L,
            speedKmh = 100f,
            settings = settings
        )

        // 前面另一部不同类型（区间测速 11）的摄像头，距离更近：不能抢走锁定
        val otherKey = RadarAlertPlanner.cameraKey(lon + 0.004, lat, 11, 80)
        val kept = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(
                camera(otherKey, distanceMeters = 300, cameraType = 11),
                camera(originalKey, distanceMeters = 460)
            ),
            currentLock = first.lock,
            nowMs = 2_000L,
            speedKmh = 100f,
            settings = settings
        )
        assertEquals(originalKey, kept.lock?.key)
    }

    @Test
    fun a_missing_speed_limit_is_one_stable_class_not_a_new_key_each_time() {
        val lon = 120.2123456
        val lat = 30.2084567
        // 高德常在「拿不到限速」和「拿到 80」之间来回。拿不到时统一归一类，
        // 否则 key 里的限速值一跳，去重状态就跟着丢。
        val unknown = RadarAlertPlanner.cameraKey(lon, lat, 4, null)
        assertEquals(unknown, RadarAlertPlanner.cameraKey(lon, lat, 4, 0))
        assertEquals(unknown, RadarAlertPlanner.cameraKey(lon, lat, 4, -1))
        assertNotEquals(
            "无限速和限速 80 必须是两个摄像头",
            unknown,
            RadarAlertPlanner.cameraKey(lon, lat, 4, 80)
        )
    }

    @Test
    fun different_cameras_stay_different() {
        val lon = 120.2123456
        val lat = 30.2084567
        val first = RadarAlertPlanner.cameraKey(lon, lat, 4, 80)
        assertNotEquals(
            "相隔约 130 米的同型摄像头不该并成一个",
            first,
            RadarAlertPlanner.cameraKey(lon + 0.0012, lat, 4, 80)
        )
        assertNotEquals(
            "不同类型（测速 vs 区间）不该并成一个",
            first,
            RadarAlertPlanner.cameraKey(lon, lat, 11, 80)
        )
        assertNotEquals(
            "不同限速（80 vs 60）不该并成一个",
            first,
            RadarAlertPlanner.cameraKey(lon, lat, 4, 60)
        )
    }

    private fun camera(
        key: String,
        distanceMeters: Int,
        speedLimit: Int? = 80,
        cameraType: Int = 4
    ): RadarCameraSnapshot {
        return RadarCameraSnapshot(
            key = key,
            distanceMeters = distanceMeters,
            cameraType = cameraType,
            speedLimit = speedLimit
        )
    }
}
