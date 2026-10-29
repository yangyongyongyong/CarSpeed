package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarTraceCsvTest {
    @Test
    fun the_csv_header_matches_the_number_of_written_columns() {
        val row = RadarAlertTrace.csvRow(sampleEvent())
        val headerColumns = RadarAlertTrace.HEADER.split(",").size
        // 表头和每行列数必须一致，否则 Excel 里整张表都是错位的。
        // 逗号可能出现在带引号的单元格里，所以按 CSV 规则解析，不能简单 split。
        assertEquals(headerColumns, RadarAlertTrace.parseCsv(row).size)
    }

    @Test
    fun a_missing_position_writes_an_empty_cell_not_a_dash() {
        val row = RadarAlertTrace.csvRow(
            sampleEvent().copy(
                carLatitude = Double.NaN,
                carLongitude = Double.NaN
            )
        )
        val cells = RadarAlertTrace.parseCsv(row)
        // 「-」会让 Excel 把整列当文本，空着才是对的。
        assertTrue(cells.none { it == "-" })
        assertTrue(cells.contains(""))
    }

    @Test
    fun coordinates_are_written_as_doubles_without_float_rounding() {
        // 经纬度转 Float 会丢到米级（120.2010 → 120.200996）。
        // 留档就是拿来跟现场雷达对位置的，自己先引入一米误差等于白记。
        val cells = RadarAlertTrace.csvRow(
            sampleEvent().copy(radarLongitude = 120.2010, radarLatitude = 30.1010)
        ).let { RadarAlertTrace.parseCsv(it) }
        assertTrue(cells.contains("120.201000"))
        assertTrue(cells.contains("30.101000"))
    }

    @Test
    fun the_used_and_reported_radar_points_are_both_written() {
        val cells = RadarAlertTrace.csvRow(
            sampleEvent().copy(
                radarLatitude = 30.1010,
                radarLongitude = 120.2010,
                reportedRadarLatitude = 30.1050,
                reportedRadarLongitude = 120.2050
            )
        ).let { RadarAlertTrace.parseCsv(it) }
        assertTrue(cells.contains("30.101000"))
        assertTrue(cells.contains("120.201000"))
        assertTrue(cells.contains("30.105000"))
        assertTrue(cells.contains("120.205000"))
    }

    @Test
    fun a_text_with_a_comma_does_not_shift_the_columns() {
        val cells = RadarAlertTrace.csvRow(
            sampleEvent().copy(text = "测速雷达，20秒，480米")
        ).let { RadarAlertTrace.parseCsv(it) }
        assertEquals("测速雷达，20秒，480米", cells.last())
    }

    private fun sampleEvent(): RadarAlertLog.Event = RadarAlertLog.Event(
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
}
