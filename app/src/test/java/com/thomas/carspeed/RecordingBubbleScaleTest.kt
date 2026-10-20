package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBubbleScaleTest {
    @Test
    fun tap_cycles_larger_then_wraps_to_smaller() {
        assertEquals(130, RecordingBubbleScale.next(100))
        assertEquals(160, RecordingBubbleScale.next(130))
        assertEquals(80, RecordingBubbleScale.next(160))
        assertEquals(100, RecordingBubbleScale.next(80))
    }

    @Test
    fun unknown_percent_snaps_to_the_nearest_step_before_advancing() {
        assertEquals(130, RecordingBubbleScale.next(90))
        assertEquals(160, RecordingBubbleScale.next(140))
        assertEquals(100, RecordingBubbleScale.coerce(103))
    }
}
