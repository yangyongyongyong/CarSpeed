package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBubbleClockTest {
    @Test
    fun format_counts_the_whole_trip() {
        assertEquals("00:00:00", RecordingBubbleClock.format(0L))
        assertEquals("00:00:00", RecordingBubbleClock.format(999L))
        assertEquals("00:00:01", RecordingBubbleClock.format(1_000L))
        assertEquals("00:01:01", RecordingBubbleClock.format(61_000L))
        assertEquals("01:02:03", RecordingBubbleClock.format(3_723_000L))
        assertEquals("100:00:00", RecordingBubbleClock.format(360_000_000L))
    }

    @Test
    fun format_does_not_go_backwards_for_a_negative_clock() {
        assertEquals("00:00:00", RecordingBubbleClock.format(-1_500L))
    }
}
