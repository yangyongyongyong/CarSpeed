package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRateTest {
    @Test
    fun ten_times_falls_back_through_speeds_the_player_may_accept() {
        assertEquals(listOf(10f, 8f, 4f, 2f, 1f), PlaybackRate.fallbackSpeeds(10f))
        assertEquals(listOf(1f), PlaybackRate.fallbackSpeeds(1f))
    }

    @Test
    fun catch_up_seek_covers_only_the_speed_the_player_refused() {
        assertEquals(1_800L, PlaybackRate.extraSeekMs(200L, requestedSpeed = 10f, appliedSpeed = 1f))
        assertEquals(1_200L, PlaybackRate.extraSeekMs(200L, requestedSpeed = 10f, appliedSpeed = 4f))
        assertEquals(0L, PlaybackRate.extraSeekMs(200L, requestedSpeed = 4f, appliedSpeed = 4f))
        assertEquals(0L, PlaybackRate.extraSeekMs(0L, requestedSpeed = 10f, appliedSpeed = 1f))
        assertEquals(false, PlaybackRate.shouldCatchUp(requestedSpeed = 10f, appliedSpeed = 8f))
        assertEquals(true, PlaybackRate.shouldCatchUp(requestedSpeed = 10f, appliedSpeed = 1f))
        assertEquals(true, PlaybackRate.shouldCatchUp(requestedSpeed = 10f, appliedSpeed = 4f))
    }
}
