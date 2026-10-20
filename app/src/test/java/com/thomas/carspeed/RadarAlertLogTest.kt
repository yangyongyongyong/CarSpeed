package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarAlertLogTest {
    @Test
    fun alert_line_keeps_timing_and_both_positions() {
        val line = RadarAlertLog.line(
            RadarAlertLog.Event(
                kind = "预警",
                speech = "已播",
                text = "测速雷达，20秒，480米，限速80",
                timeMs = 1_700_000_000_000L,
                gpsTimeMs = 1_700_000_000_100L,
                thresholdSeconds = 20,
                leadSeconds = 2,
                rawEtaSeconds = 18,
                etaSeconds = 20,
                speedKmh = 86.4f,
                speedSource = "navi",
                naviSpeedAgeMs = 320L,
                gpsSpeedKmh = 80.1f,
                amapDistanceMeters = 520,
                carLatitude = 30.1,
                carLongitude = 120.2,
                carBearing = 92f,
                carAccuracyMeters = 8f,
                carAgeMs = 400L,
                radarLatitude = 30.101,
                radarLongitude = 120.201,
                cameraType = 4,
                speedLimit = 80,
                roadLayer = "高架",
                usingDefault = false,
                correctedByDivergence = false,
                cameraKey = "12020000:3010000:4:80"
            )
        )
        assertTrue(line.contains("kind=预警"))
        assertTrue(line.contains("speech=已播"))
        assertTrue(line.contains("threshold=20"))
        assertTrue(line.contains("lead=2"))
        assertTrue(line.contains("rawEta=18"))
        assertTrue(line.contains("eta=20"))
        assertTrue(line.contains("src=navi"))
        assertTrue(line.contains("distAmap=520"))
        assertTrue(line.contains("car=30.100000,120.200000"))
        assertTrue(line.contains("radar=30.101000,120.201000"))
        assertTrue(line.contains("coord=GCJ02"))
        assertTrue(line.contains("pos=cruise"))
        assertTrue(line.contains("text=测速雷达，20秒，480米，限速80"))
        assertTrue(line.contains("distCalc="))
    }

    @Test
    fun missing_position_does_not_invent_a_distance() {
        assertNull(RadarAlertLog.distanceMeters(Double.NaN, 120.0, 30.0, 120.0))
        val line = RadarAlertLog.line(
            RadarAlertLog.Event(
                kind = "经过",
                speech = "提示音",
                text = "",
                timeMs = 1L,
                gpsTimeMs = 0L,
                thresholdSeconds = null,
                leadSeconds = 2,
                rawEtaSeconds = null,
                etaSeconds = null,
                speedKmh = Float.NaN,
                speedSource = "gps",
                naviSpeedAgeMs = -1L,
                gpsSpeedKmh = 0f,
                amapDistanceMeters = null,
                carLatitude = Double.NaN,
                carLongitude = Double.NaN,
                carBearing = Float.NaN,
                carAccuracyMeters = Float.NaN,
                carAgeMs = -1L,
                radarLatitude = Double.NaN,
                radarLongitude = Double.NaN,
                cameraType = -1,
                speedLimit = null,
                roadLayer = "地面",
                usingDefault = true,
                correctedByDivergence = false,
                cameraKey = "missing"
            )
        )
        assertTrue(line.contains("distCalc=-"))
        assertTrue(line.contains("car=-"))
        assertTrue(line.contains("radar=-"))
        assertTrue(line.contains("text=-"))
        assertTrue(line.contains("gpsTime=-"))
    }

    @Test
    fun one_thousandth_degree_of_latitude_is_about_111_meters() {
        val meters = RadarAlertLog.distanceMeters(30.0, 120.0, 30.001, 120.0)
        assertEquals(111, meters)
    }
}
