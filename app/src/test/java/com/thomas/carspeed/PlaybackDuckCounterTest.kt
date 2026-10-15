package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDuckCounterTest {
    @Test
    fun music_stays_ducked_until_every_cue_finishes() {
        val counter = PlaybackDuckCounter()

        assertTrue(counter.acquire())
        assertFalse(counter.acquire())
        assertFalse(counter.release())
        assertTrue(counter.release())
        assertFalse(counter.release())
    }
}
