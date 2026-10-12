package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoTrackSyncTest {
    @Test
    fun point_at_interpolates_between_track_points() {
        val points = listOf(
            point(elapsedMs = 0L, latitude = 30.0, longitude = 120.0, speedKmh = 10f),
            point(elapsedMs = 10_000L, latitude = 30.1, longitude = 120.2, speedKmh = 30f)
        )

        val current = VideoTrackSync.pointAt(points, 5_000L)!!

        assertEquals(30.05, current.latitude, 0.000001)
        assertEquals(120.1, current.longitude, 0.000001)
        assertEquals(20f, current.speedKmh, 0.0001f)
    }

    @Test
    fun point_at_clamps_to_known_range() {
        val points = listOf(
            point(elapsedMs = 1_000L, latitude = 30.0),
            point(elapsedMs = 2_000L, latitude = 31.0)
        )

        assertEquals(30.0, VideoTrackSync.pointAt(points, 0L)!!.latitude, 0.000001)
        assertEquals(31.0, VideoTrackSync.pointAt(points, 3_000L)!!.latitude, 0.000001)
        assertNull(VideoTrackSync.pointAt(emptyList(), 0L))
    }

    private fun point(
        elapsedMs: Long,
        latitude: Double,
        longitude: Double = 120.0,
        speedKmh: Float = 0f
    ): VideoTrackPoint {
        return VideoTrackPoint(
            elapsedMs = elapsedMs,
            timeMs = 1_700_000_000_000L + elapsedMs,
            latitude = latitude,
            longitude = longitude,
            speedKmh = speedKmh,
            accuracyMeters = null,
            bearingDegrees = null
        )
    }
}
