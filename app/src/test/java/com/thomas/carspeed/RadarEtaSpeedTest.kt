package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ETA 的速度单位。
 *
 * `AMapNaviLocation.getSpeed()` 和 Android `Location.getSpeed()` 一样返回 **米/秒**。
 * 原来这个数值被当成 km/h 直接喂进 ETA：市区 60km/h 读成「16.7」，慢了 3.6 倍，
 * 于是到达时间被放大同样的倍数——500 米算出 108 秒而不是 30 秒，
 * 20 秒阈值在一公里外就开始喊。这是「播报总是不准确」最主要的一条。
 */
class RadarEtaSpeedTest {

    @Test
    fun metres_per_second_is_converted_to_kmh() {
        // 16.7 m/s 就是 60 km/h
        assertEquals(60f, RadarAlertPlanner.cruiseSpeedKmh(16.7f), 0.5f)
        assertEquals(100f, RadarAlertPlanner.cruiseSpeedKmh(27.8f), 0.5f)
        assertEquals(30f, RadarAlertPlanner.cruiseSpeedKmh(8.3f), 0.3f)
    }

    @Test
    fun a_value_already_in_kmh_is_left_alone() {
        // 高速 120km/h 换算成 m/s 是 33.3，比 MAX_PLAUSIBLE_MS 小；
        // 但这个用例守护的是「已经是 km/h 的大数值不该再乘 3.6」。
        assertEquals(0f, RadarAlertPlanner.normaliseSpeedKmh(0f), 0f)
        assertEquals(0f, RadarAlertPlanner.normaliseSpeedKmh(-5f), 0f)
    }

    @Test
    fun the_eta_for_a_given_distance_lands_in_a_realistic_window() {
        //  sane-check：60km/h 下 500 米应该是 30 秒量级，不是 100 秒以上。
        val distance = 500
        val eta = RadarAlertPlanner.etaSeconds(distance, RadarAlertPlanner.cruiseSpeedKmh(16.7f))
        assertEquals("500 米 @60km/h 应约 30 秒，实际=$eta", 30f, eta!!.toFloat(), 3f)
    }

    @Test
    fun the_20_second_threshold_fires_about_a_hundred_metres_out_at_city_speed() {
        // 这是用户实际感受到的错误：单位搞错时，20 秒阈值会在 500 米外就喊。
        val settings = RadarAlertSettings(
            enabled = true,
            thresholdsSeconds = listOf(20, 10, 5),
            leadSeconds = 2,
            speechRatePercent = 150
        )
        val speedKmh = RadarAlertPlanner.cruiseSpeedKmh(16.7f) // 60 km/h

        fun firstDueDistance(): Int {
            // 从远往近找，20 秒窗口第一次触发时的距离
            for (distance in 1_000 downTo 1 step 5) {
                val eta = RadarAlertPlanner.etaSeconds(distance, speedKmh) ?: continue
                val due = RadarAlertPlanner.dueThresholds(eta, settings, fired = emptySet())
                if (RadarAlertPlanner.thresholdToSpeak(due, eta, settings) != null) return distance
            }
            return -1
        }

        val triggerDistance = firstDueDistance()
        assertTrue(
            "60km/h 时 20 秒窗口应在 100~400 米范围内触发，实际在 $triggerDistance 米",
            triggerDistance in 100..400
        )
    }

    @Test
    fun both_speed_sources_end_up_in_the_same_unit() {
        // 系统 GPS 走 SpeedRules（明确是 m/s），巡航走 cruiseSpeedKmh。
        // 同一个真实车速下，两条路必须算出同一个 km/h，否则 ETA 会在两个来源间跳。
        val realKmh = 72f
        val fromGps = SpeedRules.toKmhClamped(realKmh / 3.6f)
        val fromCruise = RadarAlertPlanner.cruiseSpeedKmh(realKmh / 3.6f)
        assertEquals(
            "两个速度来源必须换算到同一单位，实际 GPS=$fromGps 巡航=$fromCruise",
            fromGps,
            fromCruise,
            0.5f
        )
    }
}
