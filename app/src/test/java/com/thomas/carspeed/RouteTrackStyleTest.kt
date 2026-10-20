package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteTrackStyleTest {
    @Test
    fun speed_at_180_keeps_normal_width() {
        assertEquals(1f, RouteTrackStyle.widthScale(180f))
        assertEquals(1f, RouteTrackStyle.widthScale(0f))
    }

    @Test
    fun speed_above_180_widens_by_half() {
        assertEquals(1.5f, RouteTrackStyle.widthScale(180.1f))
        assertEquals(1.5f, RouteTrackStyle.widthScale(260f))
    }

    @Test
    fun played_line_is_half_the_green_width() {
        assertEquals(0.5f, RouteTrackStyle.playedWidthScale(1f))
        assertEquals(0.75f, RouteTrackStyle.playedWidthScale(RouteTrackStyle.FAST_WIDTH_SCALE))
    }

    @Test
    fun segment_speed_is_the_average_of_its_endpoints() {
        assertEquals(181f, RouteTrackStyle.segmentSpeedKmh(162f, 200f))
    }

    @Test
    fun same_width_segments_join_into_one_run() {
        val runs = RouteTrackStyle.groupRuns(listOf(1f, 1f, 1.5f, 1f))
        assertEquals(3, runs.size)
        assertEquals(RouteRun(1f, 0, 2), runs[0])
        assertEquals(RouteRun(1.5f, 2, 3), runs[1])
        assertEquals(RouteRun(1f, 3, 4), runs[2])
    }

    @Test
    fun track_alpha_replaces_the_existing_opacity() {
        val green = 0xFF4FD697.toInt()
        assertEquals(0xFF4FD697.toInt(), RouteTrackStyle.withAlpha(green, 100))
        assertEquals(0x004FD697, RouteTrackStyle.withAlpha(green, 0))
        assertEquals(0x804FD697.toInt(), RouteTrackStyle.withAlpha(green, 50))
        assertEquals(0xFF4FD697.toInt(), RouteTrackStyle.withAlpha(green, 140))
    }
}
