package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrackRowsTest {
    @Test
    fun track_hidden_when_a_video_covers_the_same_drive() {
        val track = record(start = 1_000L, end = 80_000L)
        val covered = HistoryTrackRows.coveredByVideo(track.startTimeMs, track.endTimeMs, listOf(5_000L to 70_000L))
        assertTrue(covered)
        val visible = HistoryTrackRows.uncovered(listOf(track), listOf(5_000L to 70_000L))
        assertEquals(emptyList<DriveRecord>(), visible)
    }

    @Test
    fun track_stays_when_there_is_no_video() {
        val track = record(start = 1_000L, end = 80_000L)
        val visible = HistoryTrackRows.uncovered(listOf(track), emptyList())
        assertEquals(listOf(track), visible)
        assertFalse(HistoryTrackRows.coveredByVideo(track.startTimeMs, track.endTimeMs, listOf(200_000L to 210_000L)))
    }

    @Test
    fun favorite_or_photo_filter_hides_track_only_rows() {
        val track = record(start = 1_000L, end = 80_000L)
        assertEquals(emptyList<DriveRecord>(), HistoryTrackRows.applyWindow(listOf(track), HistoryTrackWindow(favoriteOnly = true)))
        assertEquals(emptyList<DriveRecord>(), HistoryTrackRows.applyWindow(listOf(track), HistoryTrackWindow(minPhotoCount = 1)))
    }

    @Test
    fun duration_filter_keeps_a_long_enough_track() {
        val track = record(start = 1_000L, end = 80_000L, duration = 70_000L, distance = 1_200f)
        val kept = HistoryTrackRows.applyWindow(
            listOf(track),
            HistoryTrackWindow(minDurationMs = 60_000L, minDistanceMeters = 1_000f, rangeStartMs = 0L, rangeEndMs = 90_000L)
        )
        assertEquals(listOf(track), kept)
        val dropped = HistoryTrackRows.applyWindow(listOf(track), HistoryTrackWindow(maxDurationMs = 10_000L))
        assertEquals(emptyList<DriveRecord>(), dropped)
    }

    private fun record(start: Long, end: Long, duration: Long = end - start, distance: Float = 100f): DriveRecord {
        return DriveRecord(
            startTimeMs = start,
            endTimeMs = end,
            durationMs = duration,
            maxSpeedKmh = 40f,
            distanceMeters = distance,
            source = DriveRecordStore.SOURCE_GPS_TRACK,
            routePoints = listOf(
                DriveRoutePoint(start, 30.0, 120.0, 10f, 5f, 0f),
                DriveRoutePoint(end, 30.1, 120.1, 20f, 5f, 0f)
            )
        )
    }
}
