package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetricsTest {
    @Test
    fun total_distance_accumulates_reasonable_segments() {
        val points = listOf(
            point(0L, 30.0, 120.0),
            point(10_000L, 30.0, 120.001),
            point(20_000L, 30.0, 120.002)
        )

        val total = TrackMetrics.totalDistance(points)

        assertTrue(total in 160f..175f)
    }

    @Test
    fun distance_at_interpolates_within_segment() {
        val points = listOf(
            point(0L, 30.0, 120.0),
            point(10_000L, 30.0, 120.001)
        )

        val halfway = TrackMetrics.distanceAt(points, 5_000L)
        val total = TrackMetrics.totalDistance(points)

        assertEquals(total / 2f, halfway, 2f)
    }

    @Test
    fun total_distance_filters_obvious_jumps() {
        val points = listOf(
            point(0L, 30.0, 120.0, speedKmh = 0f),
            point(1_000L, 31.0, 121.0, speedKmh = 0f)
        )

        assertEquals(0f, TrackMetrics.totalDistance(points), 0.0001f)
    }

    @Test
    fun low_speed_gps_jitter_uses_speed_integration_for_average() {
        val points = listOf(
            point(0L, 30.0, 120.0, speedKmh = 4.2f),
            point(60_000L, 30.0, 120.001, speedKmh = 4.2f)
        )

        val summary = TrackMetrics.summary(points)

        assertEquals(4.2f, summary.maxSpeedKmh, 0.0001f)
        assertEquals(4.2f, summary.averageSpeedKmh, 0.05f)
    }

    private fun point(
        elapsedMs: Long,
        latitude: Double,
        longitude: Double,
        speedKmh: Float = 30f
    ): VideoTrackPoint {
        return VideoTrackPoint(
            elapsedMs = elapsedMs,
            timeMs = 1_700_000_000_000L + elapsedMs,
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            accuracyMeters = 5f,
            bearingDegrees = null
        )
    }
}
