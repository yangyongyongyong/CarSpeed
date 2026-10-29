package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSubtitleFormatterTest {
    @Test
    fun info_bar_groups_recorded_values_like_the_camera_preview() {
        val info = ShareSubtitleFormatter.infoBar(
            "2026-10-23 18:01:00\n86.4 km/h\n静止等待：00:00:00\n30.20840°N 120.21230°E\nGPS卫星：15\n" +
                "杭州市滨江区\n对焦0.07D(14m) · MF\n1080p 1920x1080 · 30fps · HDR"
        )
        assertEquals("86.4", info.title)
        assertEquals("km/h", info.label)
        assertEquals("1080p  30fps  HDR  MF  停留 00:00:00  卫星 15", info.parameters)
        // 地址和经纬度各自独立，两者都在时都要显示，经纬度不能被地址顶掉。
        // 经纬度独占最后一行，所以它前面是硬换行而不是两个空格。
        assertEquals("2026.10.23 18:01:00  杭州市滨江区\n30.20840°N 120.21230°E", info.caption)
    }

    @Test
    fun zero_stationary_wait_still_shows_in_the_bar() {
        val info = ShareSubtitleFormatter.infoBar("60.0 km/h\n静止等待：00:00:00\nGPS卫星：12")
        assertEquals("60.0", info.title)
        assertEquals("停留 00:00:00  卫星 12", info.parameters)
    }

    @Test
    fun every_field_off_hides_the_whole_bar() {
        val text = "86.4 km/h | 2026-10-23 18:01:00 | 静止等待：00:01:30 | GPS卫星：15 | " +
            "30.20840°N 120.21230°E | 杭州市滨江区 | 对焦0.07D(14m) · MF | 1080p 1920x1080 · 30fps · HDR | 测速 80"
        val all = ShareSubtitleFields()
        assertTrue(all.hasAnyField())
        assertTrue(!ShareSubtitleFormatter.infoBar(text, all).isEmpty())

        val none = ShareSubtitleFields(
            showTime = false,
            showSpeed = false,
            showStationaryWait = false,
            showGps = false,
            showSatellites = false,
            showRadarSummary = false,
            showAddress = false,
            showFocus = false,
            showVideoMode = false,
            showRoadLayer = false
        )
        assertTrue(!none.hasAnyField())
        assertTrue(ShareSubtitleFormatter.infoBar(text, none).isEmpty())
    }

    @Test
    fun single_field_off_only_removes_that_piece() {
        val text = "86.4 km/h | 2026-10-23 18:01:00 | 静止等待：00:01:30 | GPS卫星：15 | 杭州市滨江区 | 1080p 1920x1080 · 30fps"

        assertEquals("", ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showSpeed = false)).title)

        val noWait = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showStationaryWait = false))
        assertTrue(!noWait.parameters.contains("停留"))

        val noSatellite = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showSatellites = false))
        assertTrue(!noSatellite.parameters.contains("卫星"))

        val noMode = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showVideoMode = false))
        assertTrue(!noMode.parameters.contains("fps"))

        val noAddress = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showAddress = false))
        assertTrue(!noAddress.caption.contains("杭州市"))

        val noTime = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showTime = false))
        assertTrue(!noTime.caption.contains("2026"))
    }

    @Test
    fun coordinates_and_address_are_shown_together() {
        // 经纬度和地址是两个独立开关，拿到地址不能把经纬度顶掉。
        val text = "30.20840°N 120.21230°E\n杭州市滨江区"

        val both = ShareSubtitleFormatter.infoBar(text)
        assertTrue("地址应在: ${both.caption}", both.caption.contains("杭州市"))
        assertTrue("纬度应在: ${both.caption}", both.caption.contains("30.20840°N"))
        assertTrue("经度应在: ${both.caption}", both.caption.contains("120.21230°E"))

        // 只关地址，经纬度还得在。
        val gpsOnly = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showAddress = false))
        assertTrue("关掉地址后经纬度仍应在: ${gpsOnly.caption}", gpsOnly.caption.contains("30.20840°N"))
        assertTrue(!gpsOnly.caption.contains("杭州市"))

        // 只关经纬度，地址还得在。
        val addressOnly = ShareSubtitleFormatter.infoBar(text, ShareSubtitleFields(showGps = false))
        assertTrue("关掉经纬度后地址仍应在: ${addressOnly.caption}", addressOnly.caption.contains("杭州市"))
        assertTrue(!addressOnly.caption.contains("°N"))
    }

    @Test
    fun info_bar_honors_export_field_selection_and_handles_missing_subtitles() {
        val info = ShareSubtitleFormatter.infoBar(
            "60.0 km/h | 2026-10-23 18:01:00 | 1080p 1920x1080 · 30fps | 杭州市",
            ShareSubtitleFields(showSpeed = false, showTime = false, showAddress = false)
        )
        assertEquals("", info.title)
        assertEquals("", info.label)
        assertEquals("1080p  30fps", info.parameters)
        assertEquals("", info.caption)
        // 字幕全空时整条白栏不显示，这是「全部关闭」的判定依据。
        assertEquals(DashcamInfoBarText("", "", "", ""), ShareSubtitleFormatter.infoBar(""))
        assertTrue(ShareSubtitleFormatter.infoBar("").isEmpty())
    }

    @Test
    fun road_layer_segment_can_be_kept_or_removed() {
        val text = "2026-10-13 11:00:00  |  60.0 km/h  |  桥上"
        val kept = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = true,
                showSpeed = false,
                showGps = false,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = true
            )
        )
        assertEquals("2026-10-13 11:00:00  |  桥上", kept)

        val removed = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = true,
                showSpeed = true,
                showGps = false,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false
            )
        )
        assertEquals("2026-10-13 11:00:00  |  60.0 km/h", removed)
    }

    @Test
    fun tunnel_and_under_bridge_are_road_layer_segments() {
        val fields = ShareSubtitleFields(
            showTime = false,
            showSpeed = false,
            showGps = false,
            showAddress = false,
            showFocus = false,
            showVideoMode = false,
            showRoadLayer = true
        )
        assertEquals("桥下", ShareSubtitleFormatter.filter("桥下", fields))
        assertEquals("隧道", ShareSubtitleFormatter.filter("隧道", fields))
    }

    @Test
    fun satellite_count_is_not_treated_as_address() {
        val text = "GPS卫星：8  |  浙江省  |  GPS卫星：--"
        val kept = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = false,
                showSpeed = false,
                showGps = false,
                showSatellites = true,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false
            )
        )
        assertEquals("GPS卫星：8  |  GPS卫星：--", kept)

        val removed = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = false,
                showSpeed = false,
                showGps = true,
                showSatellites = false,
                showAddress = true,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false
            )
        )
        assertEquals("浙江省", removed)
    }

    @Test
    fun stationary_wait_is_not_treated_as_address() {
        val text = "60.0 km/h  |  静止等待：00:01:23  |  杭州市余杭区天目山路"
        val kept = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = false,
                showSpeed = false,
                showStationaryWait = true,
                showGps = false,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false
            )
        )
        assertEquals("静止等待：00:01:23", kept)

        val removed = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = false,
                showSpeed = true,
                showStationaryWait = false,
                showGps = false,
                showAddress = true,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false
            )
        )
        assertEquals("60.0 km/h  |  杭州市余杭区天目山路", removed)
    }

    @Test
    fun radar_summary_is_not_treated_as_speed_or_address() {
        val text = "86.4 km/h  |  测速 100kph×4 · 120kph×3  |  杭州市余杭区天目山路"
        val onlyRadar = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = false,
                showSpeed = false,
                showGps = false,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false,
                showRadarSummary = true
            )
        )
        assertEquals("测速 100kph×4 · 120kph×3", onlyRadar)

        val withoutRadar = ShareSubtitleFormatter.filter(
            text,
            ShareSubtitleFields(
                showTime = false,
                showSpeed = true,
                showGps = false,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = false,
                showRadarSummary = false
            )
        )
        assertEquals("86.4 km/h", withoutRadar)
    }

    @Test
    fun overlay_keeps_playback_line_breaks_and_drops_unchecked_fields() {
        val text = "2026-10-13 11:00:00  |  60.0 km/h\n桥上  |  杭州市"
        val overlay = ShareSubtitleFormatter.overlayText(
            text,
            ShareSubtitleFields(
                showTime = true,
                showSpeed = false,
                showGps = false,
                showAddress = false,
                showFocus = false,
                showVideoMode = false,
                showRoadLayer = true
            )
        )
        assertEquals("2026-10-13 11:00:00\n桥上", overlay)
    }

    @Test
    fun canvas_lines_keep_more_than_four_lines_and_full_long_address() {
        val longAddress = "浙江省杭州市滨江区长河街道江南大道与时代高架互通立交桥辅道往南方向行驶路段名称超过四十二个字符依然完整保留"
        val raw = listOf(
            "2026-10-13 11:00:00",
            "86.4 km/h",
            "静止等待：00:01:20",
            "30.20840°N 120.21230°E",
            "GPS卫星：15",
            "测速 80kph×2",
            "$longAddress 桥上"
        ).joinToString("\n")

        val lines = ShareSubtitleFormatter.canvasLines(raw, ShareSubtitleFields(showAddress = true))
        assertEquals(7, lines.size)
        assertEquals("2026-10-13 11:00:00", lines.first())
        assertEquals("$longAddress 桥上", lines.last())
    }
}
