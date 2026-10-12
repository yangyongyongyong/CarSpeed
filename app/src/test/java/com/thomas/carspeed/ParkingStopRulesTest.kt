package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingStopRulesTest {
    @Test
    fun stationary_speed_starts_timer_and_movement_resets_it() {
        assertEquals(1_000L, ParkingStopRules.nextStationaryStartMs(null, 0f, 1_000L))
        assertEquals(1_000L, ParkingStopRules.nextStationaryStartMs(1_000L, 0.4f, 2_000L))
        assertNull(ParkingStopRules.nextStationaryStartMs(1_000L, 1f, 3_000L))
    }

    @Test
    fun stop_requires_enabled_timer_and_elapsed_threshold() {
        assertFalse(ParkingStopRules.shouldStopRecording(false, 3, 0L, 180_000L))
        assertFalse(ParkingStopRules.shouldStopRecording(true, 3, null, 180_000L))
        assertFalse(ParkingStopRules.shouldStopRecording(true, 3, 0L, 179_999L))
        assertTrue(ParkingStopRules.shouldStopRecording(true, 3, 0L, 180_000L))
    }
}
