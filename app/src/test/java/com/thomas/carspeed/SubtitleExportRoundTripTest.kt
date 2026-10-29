package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出链路往返测试。
 *
 * 导出不是直接用白栏：它先把字幕写成 SRT 文本，再由 [ShareSubtitleFormatter] 反解析成白栏去渲染。
 * 所以「设置 → SRT 文本 → 白栏」是一条会被拆开维护的链，任何一环改了格式都可能让导出丢内容。
 * 用户第一次报的「经纬度不见了」就出在这附近。
 */
class SubtitleExportRoundTripTest {

    private val fields = ShareSubtitleFields()
    private val time = "2026-10-23 18:01:00"

    private fun fullFacts(fields: ShareSubtitleFields = this.fields) = SubtitleContent.Facts(
        fields = fields,
        timeText = if (fields.showTime) time else null,
        speedKmh = 86.4f,
        stationaryWaitMs = 90_000L,
        latitude = 30.2084,
        longitude = 120.2123,
        satelliteCount = 15,
        address = "杭州市滨江区",
        roadLayer = "滨康路",
        radarLimit = "测速 80kph×2",
        videoModeLabel = "1080p 1920x1080 · 30fps",
        fps = 30,
        hdrActive = false,
        manualFocus = true,
        focusDistanceDiopters = 0.07f,
        autofocusState = "AF",
        placeholdersWhenMissing = true
    )

    /** 录像时写进 SRT 的文本，就是一个换行一项。 */
    private fun srtText(fields: ShareSubtitleFields = this.fields): String =
        SubtitleContent.lines(SubtitleContent.Facts(
            fields = fields,
            timeText = if (fields.showTime) time else null,
            speedKmh = 86.4f,
            stationaryWaitMs = 90_000L,
            latitude = 30.2084,
            longitude = 120.2123,
            satelliteCount = 15,
            address = "杭州市滨江区",
            roadLayer = "滨康路",
            radarLimit = "测速 80kph×2",
            videoModeLabel = "1080p 1920x1080 · 30fps",
            fps = 30,
            hdrActive = false,
            manualFocus = true,
            focusDistanceDiopters = 0.07f,
            autofocusState = "AF",
            placeholdersWhenMissing = true
        )).joinToString("\n")

    @Test
    fun srt_text_never_loses_the_coordinates_on_the_way_to_the_export_bar() {
        val info = ShareSubtitleFormatter.infoBar(srtText())
        assertTrue("导出的白栏必须带纬度，实际=$info", info.caption.contains("30.20840°N"))
        assertTrue("导出的白栏必须带经度，实际=$info", info.caption.contains("120.21230°E"))
        assertTrue("导出的白栏必须带地址，实际=$info", info.caption.contains("杭州市滨江区"))
    }

    @Test
    fun every_selected_field_survives_the_srt_round_trip() {
        // 白栏直接生成的文本与「写 SRT 再反解析」必须表达同一组内容。
        // 只比较「有没有」而不是逐字相等：白栏用短格式（停留 / 卫星 15），SRT 用长格式。
        val info = ShareSubtitleFormatter.infoBar(srtText())
        val direct = SubtitleContent.infoBar(fullFacts())

        assertEquals("速度值必须一致", direct.title, info.title)
        assertEquals("速度单位必须一致", direct.label, info.label)
        for (key in listOf("停留", "卫星", "测速", "fps", "MF")) {
            assertTrue(
                "导出白栏丢了「$key」，实际=$info",
                info.parameters.contains(key)
            )
        }
        for (key in listOf("杭州市滨江区", "30.20840°N", "2026.10.23")) {
            assertTrue("导出白栏丢了「$key」，实际=$info", info.caption.contains(key))
        }
    }

    @Test
    fun exported_coordinates_own_their_own_line() {
        // 导出成片上的经纬度也必须整体占一行：它跟地址挤在一起就会被拆开。
        val info = ShareSubtitleFormatter.infoBar(srtText())
        val lines = info.caption.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val line = lines.lastOrNull { it.contains("°N") }
        assertTrue("经纬度要单独成行，实际=$lines", line != null)
        assertTrue("经纬度的两个半段必须在同一行，实际=$lines", line!!.contains("°E"))
        assertFalse("经纬度那行不该混进地址，实际=$lines", line.contains("杭州市"))
    }

    @Test
    fun unchecked_fields_stay_out_after_the_round_trip() {
        val noGps = srtText(fields.copy(showGps = false))
        val info = ShareSubtitleFormatter.infoBar(noGps, fields.copy(showGps = false))
        assertFalse("关掉经纬度后导出的白栏不该再有坐标，实际=$info", info.caption.contains("°N"))

        val noSpeed = srtText(fields.copy(showSpeed = false))
        val noSpeedInfo = ShareSubtitleFormatter.infoBar(noSpeed, fields.copy(showSpeed = false))
        assertEquals("关掉速度后左侧必须留空", "", noSpeedInfo.title)
        assertFalse("关掉速度后不该再画 km/h", noSpeedInfo.label.contains("km/h"))

        val noWait = srtText(fields.copy(showStationaryWait = false))
        val noWaitInfo = ShareSubtitleFormatter.infoBar(noWait, fields.copy(showStationaryWait = false))
        assertFalse("关掉停留后不该再出现停留，实际=${noWaitInfo.parameters}",
            noWaitInfo.parameters.contains("停留"))
    }

    @Test
    fun missing_fix_survives_the_round_trip_as_a_single_placeholder() {
        // SRT 里地址和经纬度都没数据，各自都写「等待定位」/「GPS --」。
        // 反解析后不能变成空白，也不能并排出现两个「等待定位」。
        val text = SubtitleContent.lines(SubtitleContent.Facts(
            fields = fields,
            timeText = time,
            speedKmh = 0f,
            latitude = null,
            longitude = null,
            address = null,
            placeholdersWhenMissing = true
        )).joinToString("\n")

        assertTrue("SRT 必须有经纬度占位，实际=$text", text.contains("GPS --"))
        val info = ShareSubtitleFormatter.infoBar(text)
        assertTrue("导出的白栏必须保留占位，实际=$info", info.caption.contains("等待定位"))
        assertEquals("「等待定位」只该出现一次，实际=$info",
            1, Regex("等待定位").findAll(info.caption).count())
        assertTrue("占位不能被当成地址顶掉其他内容，实际=$info", info.caption.contains(time.replace('-', '.')))
    }

    @Test
    fun radar_summary_is_not_mistaken_for_speed_after_the_round_trip() {
        // 「测速 80kph×2」里含 kph，反解析时不能被归成速度把真实速度顶掉。
        val text = srtText()
        val info = ShareSubtitleFormatter.infoBar(text)
        assertEquals("真实速度必须来自速度那一行", "86.4", info.title)
        assertTrue("雷达摘要必须出现在参数里，实际=${info.parameters}",
            info.parameters.contains("测速"))
    }

    @Test
    fun stationary_wait_is_not_mistaken_for_address_after_the_round_trip() {
        val text = srtText()
        val info = ShareSubtitleFormatter.infoBar(text)
        assertTrue("清空地址开关后停留不该被当成地址留下",
            !ShareSubtitleFormatter.infoBar(text, fields.copy(showAddress = false, showGps = false))
                .caption.contains("静止等待"))
    }

    @Test
    fun video_mode_and_hdr_survive_the_round_trip() {
        val hdrText = SubtitleContent.lines(SubtitleContent.Facts(
            fields = fields,
            videoModeLabel = "1080p 1920x1080 · 30fps",
            fps = 30,
            hdrActive = true,
            placeholdersWhenMissing = true
        )).joinToString("\n")

        assertTrue("SRT 里要有 HDR，实际=$hdrText", hdrText.contains("HDR"))
        val info = ShareSubtitleFormatter.infoBar(hdrText)
        assertTrue("导出的白栏要保留 HDR，实际=${info.parameters}", info.parameters.contains("HDR"))
        assertTrue("导出的白栏要保留帧率，实际=${info.parameters}", info.parameters.contains("30fps"))
        assertFalse("分辨率尺寸串不该漏进白栏，实际=${info.parameters}",
            info.parameters.contains("1920x1080"))
    }

    @Test
    fun all_fields_off_produces_no_bar_at_all() {
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
        assertFalse("十项全关时不该预留白栏高度", none.hasAnyField())
        assertEquals("", srtText(none))
        assertTrue(ShareSubtitleFormatter.infoBar(srtText(none), none).isEmpty())
    }
}
