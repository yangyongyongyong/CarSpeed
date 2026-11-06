package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashcamShutterPolicyTest {
    @Test
    fun speed_tiers_increase_negative_ev_to_shorten_shutter_time() {
        val stationaryEv = DashcamShutterPolicy.targetEvForSpeed(0f)
        val cityEv = DashcamShutterPolicy.targetEvForSpeed(50f)
        val highwayEv = DashcamShutterPolicy.targetEvForSpeed(100f)

        assertEquals(-0.5f, stationaryEv, 1e-4f)
        assertEquals(-0.8f, cityEv, 1e-4f)
        assertEquals(-1.1f, highwayEv, 1e-4f)
        assertTrue(highwayEv < cityEv && cityEv < stationaryEv)
    }

    @Test
    fun dark_moving_scene_with_slow_shutter_adds_extra_negative_ev_within_bounds() {
        // 20ms (1/50s) 慢快门 + 80km/h 高速行驶 -> 额外压低 -0.3 EV 促成快门提速
        val highwayDarkEv = DashcamShutterPolicy.targetEvForSpeed(80f, lastExposureTimeNs = 20_000_000L)
        assertEquals(-1.4f, highwayDarkEv, 1e-4f)

        // 步进换算（如 1/6 EV 一档，范围 -24..24）
        val steps = DashcamShutterPolicy.computeAeCompensationSteps(
            targetEv = highwayDarkEv,
            stepRatio = 1.0 / 6.0,
            minIndex = -24,
            maxIndex = 24
        )
        assertEquals(-8, steps)
    }

    @Test
    fun formats_exposure_time_and_iso_into_human_readable_shutter_summary() {
        assertEquals("1/500s", DashcamShutterPolicy.formatShutterSpeed(2_000_000L))
        assertEquals("1/30s", DashcamShutterPolicy.formatShutterSpeed(33_333_333L))
        assertEquals(
            "快门 1/500s ISO200",
            DashcamShutterPolicy.formatExposureSummary(2_000_000L, 200)
        )
    }
}
