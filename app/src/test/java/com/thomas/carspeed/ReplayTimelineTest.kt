package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayTimelineTest {
    @Test
    fun loading_and_start_stay_at_left_edge() {
        assertEquals(0, ReplayTimeline.progress(0L, 0L))
        assertEquals(0, ReplayTimeline.progress(0L, 71_000L))
        assertEquals(0, ReplayTimeline.progress(-1L, 71_000L))
        assertEquals(0L, ReplayTimeline.position(0, 71_000L))
    }

    @Test
    fun progress_uses_a_fixed_range_without_long_duration_overflow() {
        val duration = Int.MAX_VALUE.toLong() * 2L
        assertEquals(5_000, ReplayTimeline.progress(duration / 2L, duration))
        assertEquals(duration / 2L, ReplayTimeline.position(5_000, duration))
        assertEquals(ReplayTimeline.SEEK_MAX, ReplayTimeline.progress(duration, duration))
        assertEquals(duration, ReplayTimeline.position(ReplayTimeline.SEEK_MAX, duration))
    }

    @Test
    fun labels_are_elapsed_time_not_wall_clock() {
        assertEquals("00:00", ReplayTimeline.formatDuration(0L))
        assertEquals("01:11", ReplayTimeline.formatDuration(71_000L))
        assertEquals("1:01:01", ReplayTimeline.formatDuration(3_661_000L))
    }

    @Test
    fun audio_sleep_offset_is_detected_in_the_observed_recording() {
        assertEquals(59_586_628_503L, ReplayTimeline.audioOffsetUs(0L, 70_797_933L, 59_586_628_503L))
    }

    @Test
    fun valid_audio_delay_is_preserved() {
        assertEquals(0L, ReplayTimeline.audioOffsetUs(0L, 71_000_000L, 120_000L))
        assertEquals(0L, ReplayTimeline.audioOffsetUs(0L, 71_000_000L, 40_000_000L))
        assertEquals(0L, ReplayTimeline.audioOffsetUs(-1L, 0L, -1L))
    }

    @Test
    fun nonzero_video_origin_is_accounted_for() {
        assertEquals(59_000_000_000L, ReplayTimeline.audioOffsetUs(1_000_000L, 70_000_000L, 59_001_000_000L))
    }
}
