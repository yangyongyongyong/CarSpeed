package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class TripStatsFormatTest {
    @Test
    fun labels_distance_duration_and_speed() {
        assertEquals("860米", TripStatsFormat.distance(860.4))
        assertEquals("12.4公里", TripStatsFormat.distance(12_400.0))
        assertEquals("0分钟", TripStatsFormat.duration(0L))
        assertEquals("1分钟以内", TripStatsFormat.duration(20_000L))
        assertEquals("20分钟", TripStatsFormat.duration(20 * 60_000L))
        assertEquals("1小时5分", TripStatsFormat.duration(3_900_000L))
        assertEquals("37.2 km/h", TripStatsFormat.speed(37.24))
    }
}
