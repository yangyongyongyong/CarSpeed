package com.thomas.carspeed

import org.junit.Assert.assertTrue
import org.junit.Test

class GpxExporterTest {
    @Test
    fun build_gpx_contains_track_points_and_speed_extensions() {
        val record = DriveRecord(
            startTimeMs = 1_700_000_000_000L,
            endTimeMs = 1_700_000_010_000L,
            durationMs = 10_000L,
            maxSpeedKmh = 80f,
            routePoints = listOf(
                DriveRoutePoint(
                    timeMs = 1_700_000_000_000L,
                    latitude = 30.123456,
                    longitude = 120.123456,
                    speedKmh = 42.5f,
                    accuracyMeters = 6.2f,
                    bearingDegrees = 88f
                )
            )
        )

        val gpx = GpxExporter.buildGpx(record)

        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<trkpt lat=\"30.123456\" lon=\"120.123456\">"))
        assertTrue(gpx.contains("<carspeed:speedKmh>42.50</carspeed:speedKmh>"))
        assertTrue(gpx.contains("<carspeed:accuracyMeters>6.20</carspeed:accuracyMeters>"))
        assertTrue(gpx.contains("<carspeed:bearingDegrees>88.0</carspeed:bearingDegrees>"))
    }
}
