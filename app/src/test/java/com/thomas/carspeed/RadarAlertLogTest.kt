package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(line.contains("fixGap="))
        assertTrue(line.contains("coord=GCJ02"))
        assertTrue(line.contains("pos=cruise"))
        assertTrue(line.contains("text=测速雷达，20秒，480米，限速80"))
        assertTrue(line.contains("distCalc="))
    }

    /**
     * 事后对数据靠的就是这几个字段：车在哪、车头朝哪、路朝哪、
     * 我们用的点在哪、高德原本给的点在哪、两者差多少。少任何一个都对不上。
     */
    @Test
    fun the_line_carries_everything_needed_to_compare_against_a_real_radar() {
        val line = RadarAlertLog.line(sampleEvent())
        listOf(
            "t=", "gpsTime=", "car=", "bearing=", "roadBearing=", "acc=",
            "radar=", "radarRaw=", "fixGap=", "distAmap=", "distCalc=",
            "layer=", "default=", "corrected=", "pos=", "key=",
            "speed=", "src=", "eta=", "rawEta="
        ).forEach { field ->
            assertTrue("缺少 $field", line.contains(field))
        }
    }

    @Test
    fun the_wall_clock_time_is_readable_and_not_just_a_timestamp() {
        val event = sampleEvent(timeMs = 1_700_000_000_000L)
        val line = RadarAlertLog.line(event)
        // 光有毫秒时间戳没法跟「我几点听到的」对上，留档要有人能读的时间。
        assertTrue(line.contains("t=2023-11-14") || line.contains("t=2023-11-15"))
        assertFalse(line.startsWith("t=1700000000000"))
    }

    @Test
    fun the_reported_point_and_the_used_point_are_both_kept_with_their_gap() {
        val line = RadarAlertLog.line(
            sampleEvent().copy(
                radarLatitude = 30.1010,
                radarLongitude = 120.2010,
                reportedRadarLatitude = 30.1050,
                reportedRadarLongitude = 120.2050
            )
        )
        assertTrue(line.contains("radar=30.101000,120.201000"))
        assertTrue(line.contains("radarRaw=30.105000,120.205000"))
        // 约 0.004 度纬度差 ≈ 440 米、经度差 ≈ 385 米，合成约 580 米。
        val gap = line.substringAfter("fixGap=").substringBefore(' ').toInt()
        assertTrue("修正偏移要能看出量级，实际是 $gap", gap in 500..650)
    }

    @Test
    fun the_road_bearing_is_logged_separately_from_the_car_bearing() {
        val line = RadarAlertLog.line(
            sampleEvent().copy(carBearing = 92f, roadBearing = 5f)
        )
        assertTrue(line.contains("bearing=92.0"))
        assertTrue(line.contains("roadBearing=5.0"))
    }

    private fun sampleEvent(
        timeMs: Long = 1_700_000_000_000L
    ): RadarAlertLog.Event = RadarAlertLog.Event(
        kind = "预警",
        speech = "已播",
        text = "测速雷达，20秒，480米，限速80",
        timeMs = timeMs,
        gpsTimeMs = timeMs + 100L,
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
        roadBearing = 90f,
        carAccuracyMeters = 8f,
        carAgeMs = 400L,
        radarLatitude = 30.101,
        radarLongitude = 120.201,
        reportedRadarLatitude = 30.101,
        reportedRadarLongitude = 120.201,
        cameraType = 4,
        speedLimit = 80,
        roadLayer = "高架",
        usingDefault = false,
        correctedByDivergence = false,
        cameraKey = "12020000:3010000:4:80"
    )

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
