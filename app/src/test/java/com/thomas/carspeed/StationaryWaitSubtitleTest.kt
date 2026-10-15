package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class StationaryWaitSubtitleTest {
    @Test
    fun formats_accumulated_wait_as_clock() {
        assertEquals("静止等待：00:00:00", DashcamOverlayText.formatStationaryWait(0L))
        assertEquals("静止等待：00:00:00", DashcamOverlayText.formatStationaryWait(-1_500L))
        assertEquals("静止等待：01:01:01", DashcamOverlayText.formatStationaryWait(3_661_000L))
    }

    @Test
    fun wait_starts_after_the_trip_moves_then_stops() {
        var state = SpeedRules.WaitState(
            tripStarted = false,
            tripStartTimeMs = 0L,
            waitAtZeroAccumMs = 0L,
            waitAtZeroStartMs = 0L
        )
        state = SpeedRules.updateTripAndWait(state, 0f, 1_000L)
        assertEquals(
            "静止等待：00:00:00",
            DashcamOverlayText.formatStationaryWait(SpeedRules.currentWaitDurationMs(state, 10_000L))
        )

        state = SpeedRules.updateTripAndWait(state, 20f, 10_000L)
        state = SpeedRules.updateTripAndWait(state, 0f, 15_000L)
        assertEquals(
            "静止等待：00:01:00",
            DashcamOverlayText.formatStationaryWait(SpeedRules.currentWaitDurationMs(state, 75_000L))
        )
    }
}
