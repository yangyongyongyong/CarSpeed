package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggerSpeedTest {
    @Test
    fun fresh_fix_reports_the_speed_it_carries() {
        // 77.8 km/h 约等于 21.6 m/s，正是 10-26 20:02:52 那次停录时的车速。
        assertEquals(77.76f, TriggerSpeed.kmhIfFresh(21.6f, 1_000L, 2_000L)!!, 0.05f)
        assertEquals(0f, TriggerSpeed.kmhIfFresh(0f, 1_000L, 2_000L)!!, 0.001f)
    }

    @Test
    fun stale_fix_is_not_trusted_as_current_speed() {
        // 高速上 16 秒能跑出去几百米，旧定位不能代表「现在停车了」。
        assertNull(TriggerSpeed.kmhIfFresh(0f, 1_000L, 1_000L + TriggerSpeed.MAX_AGE_MS + 1L))
        assertEquals(0f, TriggerSpeed.kmhIfFresh(0f, 1_000L, 1_000L + TriggerSpeed.MAX_AGE_MS)!!, 0.001f)
    }

    @Test
    fun missing_speed_or_missing_timestamp_yields_null() {
        assertNull(TriggerSpeed.kmhIfFresh(null, 1_000L, 2_000L))
        assertNull(TriggerSpeed.kmhIfFresh(10f, 0L, 2_000L))
        // 时间戳在未来说明这条定位不可信，和过期一样不采用。
        assertNull(TriggerSpeed.kmhIfFresh(10f, 5_000L, 2_000L))
    }
}
