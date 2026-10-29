package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无雷达路段播报。这种循环播报最容易出的错是「到了雷达还在喊」「剩余 0 米还在报」，
 * 靠实车碰不全，所以边界一条条测。
 */
class RadarClearStretchTest {
    private val config = RadarClearStretch.Settings(
        enabled = true,
        spanMeters = 5000,
        intervalSeconds = 5,
        referenceSpeedKmh = 100
    )

    private fun session(
        span: Int = 5000,
        lastSpokenAtMs: Long = 0L,
        lastSpokenMeters: Int = span,
        startedAtMs: Long = 0L
    ) = RadarClearStretch.Session(
        startedAtMs = startedAtMs,
        spanMeters = span,
        lastSpokenAtMs = lastSpokenAtMs,
        lastSpokenMeters = lastSpokenMeters
    )

    @Test
    fun a_long_empty_road_starts_the_loop() {
        // 后面 5 公里没雷达，正好达到门槛，应该报。
        assertTrue(RadarClearStretch.shouldStart(nextRadarMeters = 5000, settings = config))
        assertTrue(RadarClearStretch.shouldStart(nextRadarMeters = 8000, settings = config))
    }

    @Test
    fun a_radar_closer_than_the_threshold_does_not_start_the_loop() {
        // 才空 2 公里就又来了雷达，不满足「5 公里没测速」，不报。
        assertFalse(RadarClearStretch.shouldStart(nextRadarMeters = 2000, settings = config))
        assertFalse(RadarClearStretch.shouldStart(nextRadarMeters = 4999, settings = config))
    }

    @Test
    fun no_radar_at_all_counts_as_a_full_empty_stretch() {
        // 后面整段都查不到雷达，就是最典型的「连续 N 公里没有测速」。
        assertTrue(RadarClearStretch.shouldStart(nextRadarMeters = null, settings = config))
    }

    @Test
    fun turning_the_feature_off_never_starts_the_loop() {
        assertFalse(
            RadarClearStretch.shouldStart(
                nextRadarMeters = null,
                settings = config.copy(enabled = false)
            )
        )
    }

    @Test
    fun the_first_announcement_fires_immediately_then_every_interval() {
        val first = session()
        assertTrue(RadarClearStretch.shouldSpeakNow(first, 5000, nowMs = 1_000L, settings = config))

        val spoken = first.copy(lastSpokenAtMs = 1_000L, lastSpokenMeters = 5000)
        // 还不到 5 秒，不重复。
        assertFalse(RadarClearStretch.shouldSpeakNow(spoken, 4800, nowMs = 3_000L, settings = config))
        assertFalse(RadarClearStretch.shouldSpeakNow(spoken, 4800, nowMs = 5_999L, settings = config))
        // 满 5 秒，再说一句。
        assertTrue(RadarClearStretch.shouldSpeakNow(spoken, 4600, nowMs = 6_000L, settings = config))
    }

    @Test
    fun the_interval_is_configurable() {
        val fast = config.copy(intervalSeconds = 10)
        val spoken = session(lastSpokenAtMs = 1_000L)
        assertFalse(RadarClearStretch.shouldSpeakNow(spoken, 4000, nowMs = 9_000L, settings = fast))
        assertTrue(RadarClearStretch.shouldSpeakNow(spoken, 4000, nowMs = 11_000L, settings = fast))
    }

    @Test
    fun the_loop_stops_once_less_than_100_meters_remain() {
        val s = session()
        assertFalse(RadarClearStretch.shouldStop(s, remainingMeters = 100, nowMs = 1_000L))
        assertTrue(RadarClearStretch.shouldStop(s, remainingMeters = 99, nowMs = 1_000L))
        assertTrue(RadarClearStretch.shouldStop(s, remainingMeters = 0, nowMs = 1_000L))
    }

    @Test
    fun nothing_is_spoken_once_the_remaining_stretch_is_too_short() {
        // 剩 80 米还要不要喊「80 米内无测速」？不要，太近了没意义。
        val spoken = session(lastSpokenAtMs = 0L)
        assertFalse(RadarClearStretch.shouldSpeakNow(spoken, 80, nowMs = 1_000L, settings = config))
    }

    @Test
    fun the_loop_does_not_run_forever_on_a_stuck_route() {
        val s = session(startedAtMs = 0L)
        assertFalse(RadarClearStretch.shouldStop(s, remainingMeters = 3000, nowMs = 60_000L))
        // 超过 30 分钟，即便还显示剩很多也收尾，防止导航卡死时无限播。
        assertTrue(
            RadarClearStretch.shouldStop(
                s,
                remainingMeters = 3000,
                nowMs = 31 * 60 * 1000L
            )
        )
    }

    @Test
    fun kilometres_are_used_above_one_kilometre_and_metres_below() {
        assertEquals("5公里内无测速", RadarClearStretch.text(5000))
        assertEquals("4.3公里内无测速", RadarClearStretch.text(4300))
        assertEquals("1公里内无测速", RadarClearStretch.text(1000))
        assertEquals("999米内无测速", RadarClearStretch.text(999))
        assertEquals("100米内无测速", RadarClearStretch.text(100))
    }

    @Test
    fun the_text_never_goes_negative_or_shows_0_0_km() {
        assertEquals("0米内无测速", RadarClearStretch.text(0))
        assertEquals("0米内无测速", RadarClearStretch.text(-500))
        // 剩 1 公里时不能显示成「1.0 公里」，多一个 .0 听着别扭。
        assertEquals("1公里内无测速", RadarClearStretch.text(1000))
    }

    @Test
    fun the_estimate_converts_distance_and_speed_to_seconds() {
        // 5 公里、150 km/h ≈ 41.7 米/秒，约 120 秒。
        assertEquals(120, RadarClearStretch.estimatedSeconds(5000, 150))
        // 5 公里、100 km/h ≈ 27.8 米/秒，约 180 秒。
        assertEquals(180, RadarClearStretch.estimatedSeconds(5000, 100))
        // 1 公里、60 km/h ≈ 16.7 米/秒，约 60 秒。
        assertEquals(60, RadarClearStretch.estimatedSeconds(1000, 60))
    }

    @Test
    fun an_invalid_estimate_input_returns_nothing_instead_of_guessing() {
        assertNull(RadarClearStretch.estimatedSeconds(0, 100))
        assertNull(RadarClearStretch.estimatedSeconds(5000, 0))
        assertNull(RadarClearStretch.estimatedSeconds(-100, 100))
    }

    @Test
    fun the_interval_is_never_zero_which_would_speak_non_stop() {
        val weird = config.copy(intervalSeconds = 0)
        assertTrue(weird.intervalMs >= RadarClearStretch.MIN_INTERVAL_SECONDS * 1000L)
    }

    @Test
    fun an_out_of_range_span_is_clamped_instead_of_used_as_typed() {
        // 用户输 10 米没意义：比收尾阈值还短，永远走不到播报那一步。
        // 钳到最短跨度，行为可预期，而不是悄悄什么都不做。
        val tooSmall = RadarClearStretch.Settings(enabled = true, spanMeters = 10)
        assertTrue(RadarClearStretch.shouldStart(nextRadarMeters = null, settings = tooSmall))
        assertTrue(RadarClearStretch.shouldStart(nextRadarMeters = 5000, settings = tooSmall))
    }
}
