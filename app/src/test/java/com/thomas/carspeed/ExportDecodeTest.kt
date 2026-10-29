package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDecodeTest {
    @Test
    fun keeps_the_frame_once_it_reaches_the_target() {
        assertFalse(ExportDecode.shouldKeep(presentationUs = 10_000L, targetUs = 33_000L, endOfStream = false))
        assertTrue(ExportDecode.shouldKeep(presentationUs = 33_000L, targetUs = 33_000L, endOfStream = false))
        assertTrue(ExportDecode.shouldKeep(presentationUs = 50_000L, targetUs = 33_000L, endOfStream = false))
    }

    @Test
    fun keeps_the_last_frame_when_the_file_ends_early() {
        assertTrue(ExportDecode.shouldKeep(presentationUs = 1_000L, targetUs = 90_000L, endOfStream = true))
    }

    @Test
    fun presentation_clock_stays_in_order() {
        val clock = ExportPresentationClock()
        clock.note(0L)
        clock.note(33_333L)
        clock.note(66_666L)
        assertEquals(0L, clock.take())
        assertEquals(33_333L, clock.take())
        assertEquals(66_666L, clock.take())
        assertEquals(66_667L, clock.take())
    }
}
