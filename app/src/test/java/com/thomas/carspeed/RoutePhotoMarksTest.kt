package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoutePhotoMarksTest {
    @Test
    fun photo_during_trip_is_placed_on_the_track() {
        val points = listOf(
            point(timeMs = 1_000L, latitude = 30.0, longitude = 120.0),
            point(timeMs = 11_000L, latitude = 30.2, longitude = 120.4)
        )

        val marks = RoutePhotoMarks.match(
            points,
            listOf(RoutePhotoCandidate(6_000L, "/photos/a.jpg"))
        )

        assertEquals(1, marks.size)
        assertEquals(30.1, marks[0].latitude, 0.000001)
        assertEquals(120.2, marks[0].longitude, 0.000001)
        assertEquals("/photos/a.jpg", marks[0].path)
    }

    @Test
    fun photo_outside_trip_is_left_out() {
        val points = listOf(
            point(timeMs = 10_000L, latitude = 30.0),
            point(timeMs = 20_000L, latitude = 31.0)
        )

        val marks = RoutePhotoMarks.match(
            points,
            listOf(
                RoutePhotoCandidate(10_000L - RoutePhotoMarks.MATCH_SLACK_MS - 1L, "/photos/before.jpg"),
                RoutePhotoCandidate(20_000L + RoutePhotoMarks.MATCH_SLACK_MS + 1L, "/photos/after.jpg")
            )
        )

        assertTrue(marks.isEmpty())
    }

    @Test
    fun photo_just_outside_endpoints_snaps_to_the_end() {
        val points = listOf(
            point(timeMs = 10_000L, latitude = 30.0, longitude = 120.0),
            point(timeMs = 20_000L, latitude = 31.0, longitude = 121.0)
        )

        val marks = RoutePhotoMarks.match(
            points,
            listOf(RoutePhotoCandidate(20_000L + 2_000L, "/photos/late.jpg"))
        )

        assertEquals(31.0, marks.single().latitude, 0.000001)
        assertEquals(121.0, marks.single().longitude, 0.000001)
    }

    @Test
    fun missing_point_time_uses_track_start() {
        val points = listOf(
            point(timeMs = 0L, elapsedMs = 0L, latitude = 30.0),
            point(timeMs = 0L, elapsedMs = 10_000L, latitude = 30.2)
        )

        val marks = RoutePhotoMarks.match(
            points,
            listOf(RoutePhotoCandidate(1_705_000_005_000L, "/photos/mid.jpg")),
            trackStartMs = 1_705_000_000_000L
        )

        assertEquals(30.1, marks.single().latitude, 0.000001)
    }

    @Test
    fun count_includes_photos_inside_the_trip_window() {
        val count = RoutePhotoMarks.countBetween(
            photoTimesMs = listOf(4_000L, 12_000L, 30_000L),
            startMs = 10_000L,
            endMs = 20_000L
        )

        assertEquals(1, count)
    }

    @Test
    fun count_keeps_a_photo_within_the_edge_slack() {
        val count = RoutePhotoMarks.countBetween(
            photoTimesMs = listOf(10_000L - RoutePhotoMarks.MATCH_SLACK_MS),
            startMs = 10_000L,
            endMs = 20_000L
        )

        assertEquals(1, count)
    }

    @Test
    fun file_name_round_trips_to_the_capture_second() {
        val timeMs = 1_705_000_123_456L
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMs))
        val parsed = RoutePhotoMarks.timeFromFileName("carspeed-photo-$stamp.jpg")

        assertEquals(timeMs / 1000L, parsed!! / 1000L)
        assertNull(RoutePhotoMarks.timeFromFileName("carspeed-photo.jpg"))
    }

    private fun point(
        timeMs: Long,
        latitude: Double,
        longitude: Double = 120.0,
        elapsedMs: Long = 0L
    ): VideoTrackPoint {
        return VideoTrackPoint(
            elapsedMs = elapsedMs,
            timeMs = timeMs,
            latitude = latitude,
            longitude = longitude,
            speedKmh = 0f,
            accuracyMeters = null,
            bearingDegrees = null
        )
    }
}
