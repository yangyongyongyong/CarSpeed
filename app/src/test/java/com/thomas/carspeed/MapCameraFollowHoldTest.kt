package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraFollowHoldTest {
    @Test
    fun hold_lasts_ten_seconds_after_the_finger_leaves() {
        val endedAt = 50_000L
        assertEquals(10_000L, MapCameraFollowHold.remainingMs(endedAt, endedAt))
        assertEquals(1L, MapCameraFollowHold.remainingMs(endedAt, endedAt + 9_999L))
        assertEquals(0L, MapCameraFollowHold.remainingMs(endedAt, endedAt + 10_000L))
        assertEquals(0L, MapCameraFollowHold.remainingMs(endedAt, endedAt + 60_000L))
    }

    @Test
    fun follow_resumes_only_when_the_hold_is_over() {
        val endedAt = 1_000L
        assertFalse(MapCameraFollowHold.shouldResume(endedAt, 5_000L))
        assertFalse(MapCameraFollowHold.shouldResume(endedAt, 10_999L))
        assertTrue(MapCameraFollowHold.shouldResume(endedAt, 11_000L))
    }

    @Test
    fun a_second_gesture_restarts_the_hold() {
        val firstEnd = 0L
        assertFalse(MapCameraFollowHold.shouldResume(firstEnd, 8_000L))
        val secondEnd = 8_000L
        assertFalse(MapCameraFollowHold.shouldResume(secondEnd, 12_000L))
        assertTrue(MapCameraFollowHold.shouldResume(secondEnd, 18_000L))
    }
}
