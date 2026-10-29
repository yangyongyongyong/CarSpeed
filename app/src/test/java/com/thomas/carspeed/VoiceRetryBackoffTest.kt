package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceRetryBackoffTest {
    @Test
    fun doubles_from_five_seconds_and_caps_at_ten_minutes() {
        assertEquals(5_000L, VoiceRetryBackoff.delayMs(1))
        assertEquals(10_000L, VoiceRetryBackoff.delayMs(2))
        assertEquals(20_000L, VoiceRetryBackoff.delayMs(3))
        assertEquals(320_000L, VoiceRetryBackoff.delayMs(7))
        assertEquals(600_000L, VoiceRetryBackoff.delayMs(8))
        assertEquals(600_000L, VoiceRetryBackoff.delayMs(100))
    }

    @Test
    fun non_positive_failure_count_uses_base_delay() {
        assertEquals(5_000L, VoiceRetryBackoff.delayMs(0))
    }
}
