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
}
