package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveRecordStoreTest {
    @Test
    fun average_speed_uses_moving_time_when_wait_exists() {
        val speed = DriveRecordStore.averageSpeedKmh(
            distanceMeters = 1_000f,
            durationMs = 100_000L,
            waitDurationMs = 50_000L
        )
        assertEquals(72f, speed)
    }
}
