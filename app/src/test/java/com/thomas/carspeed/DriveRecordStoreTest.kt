package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveRecordStoreTest {
    @Test
    fun average_speed_uses_moving_time_when_wait_exists() {
        val speed = DriveRecordStore.averageSpeedKmh(
            distanceMeters = 1_000f,
            durationMs = 100_000L,
            waitDurationMs = 50_000L
        )
        assertEquals(72f, speed)
    }

    @Test
    fun compacting_records_keeps_gps_tracks_and_strips_old_video_route_points() {
        val samplePoint = DriveRoutePoint(1_000L, 30.2, 120.2, 60f, 3f, 90f)
        val gpsTrack = DriveRecord(
            startTimeMs = 1_000L,
            endTimeMs = 2_000L,
            durationMs = 1_000L,
            maxSpeedKmh = 60f,
            routePoints = listOf(samplePoint),
            source = DriveRecordStore.SOURCE_GPS_TRACK
        )
        val olderVideo = DriveRecord(
            startTimeMs = 3_000L,
            endTimeMs = 4_000L,
            durationMs = 1_000L,
            maxSpeedKmh = 70f,
            routePoints = listOf(samplePoint),
            source = DriveRecordStore.SOURCE_VIDEO
        )
        val newestVideo = DriveRecord(
            startTimeMs = 5_000L,
            endTimeMs = 6_000L,
            durationMs = 1_000L,
            maxSpeedKmh = 80f,
            routePoints = listOf(samplePoint),
            source = DriveRecordStore.SOURCE_VIDEO
        )

        val compacted = DriveRecordStore.compactRecords(listOf(newestVideo, olderVideo, gpsTrack))
        val keptGps = compacted.first { it.source == DriveRecordStore.SOURCE_GPS_TRACK }
        val keptNewestVideo = compacted.first { it.startTimeMs == 5_000L }
        val keptOlderVideo = compacted.first { it.startTimeMs == 3_000L }

        assertEquals(1, keptGps.routePoints.size)
        assertEquals(1, keptNewestVideo.routePoints.size)
        assertEquals(0, keptOlderVideo.routePoints.size)
    }
}
