package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSubtitleStyleTest {
    @Test
    fun footer_scales_with_the_short_edge_and_is_even_for_video_encoding() {
        // 明细最多三行，白栏按三行预留，比旧的一行版本更高；短边越大栏越高。
        val hd = ExportSubtitleStyle.barHeightPx(1920, 1080)
        assertEquals(ExportSubtitleStyle.barHeightPx(1080, 1920), hd)
        assertTrue("1080p 的白栏应高于旧的一行版本: $hd", hd > 130)
        assertTrue(ExportSubtitleStyle.barHeightPx(3840, 2160) > hd)
        assertEquals(2, ExportSubtitleStyle.barHeightPx(1, 1))
    }

    @Test
    fun bar_is_even_so_the_video_encoder_does_not_reject_it() {
        listOf(1920 to 1080, 1280 to 720, 640 to 480, 2160 to 3840).forEach { (w, h) ->
            assertEquals(0, ExportSubtitleStyle.barHeightPx(w, h) % 2)
        }
    }

    @Test
    fun preview_and_export_use_the_same_compact_type_scale() {
        assertTrue(ExportSubtitleStyle.CAPTION_SP < ExportSubtitleStyle.PARAMETERS_SP)
        assertTrue(ExportSubtitleStyle.PARAMETERS_SP < ExportSubtitleStyle.TITLE_SP)
    }

    @Test
    fun bar_grows_by_one_line_for_each_extra_detail_line() {
        // 一行时还是老的 62dp，三行时正好多出两行的高度。
        assertEquals(ExportSubtitleStyle.ONE_LINE_BAR_DP, ExportSubtitleStyle.barHeightDp(1))
        assertEquals(
            ExportSubtitleStyle.ONE_LINE_BAR_DP + ExportSubtitleStyle.DETAIL_LINE_DP * 2,
            ExportSubtitleStyle.barHeightDp(3)
        )
        // 超出范围的行数不该把白栏撑到天上去。
        assertEquals(ExportSubtitleStyle.barHeightDp(1), ExportSubtitleStyle.barHeightDp(0))
        assertEquals(ExportSubtitleStyle.barHeightDp(3), ExportSubtitleStyle.barHeightDp(9))
    }

    @Test
    fun text_scale_is_independent_of_how_tall_the_bar_is() {
        // 加高白栏只是为了放第三行，字号基准必须还是一行的那个高度，否则字会跟着变大。
        assertEquals(130, ExportSubtitleStyle.textScalePx(1920, 1080))
        assertEquals(130, ExportSubtitleStyle.textScalePx(1080, 1920))
    }

    @Test
    fun video_without_a_map_still_appends_footer_without_cropping() {
        val bar = ExportSubtitleStyle.barHeightPx(1920, 1080)
        val layout = ShareVideoExporter.resolveExportLayout(1920, 1080, ShareExportOptions(includeRouteMap = false), false)
        assertEquals(1920, layout.videoWidth)
        assertEquals(1080, layout.videoHeight)
        assertEquals(bar, layout.infoBarHeight)
        assertEquals(1080 + bar, layout.height)
        assertEquals(0, layout.mapHeight)
    }

    @Test
    fun all_subtitle_fields_off_drops_the_footer_height_entirely() {
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
        val layout = ShareVideoExporter.resolveExportLayout(
            1920,
            1080,
            ShareExportOptions(includeRouteMap = false, subtitleFields = none),
            false
        )
        assertEquals(0, layout.infoBarHeight)
        // 没有额外白栏，成片高度就等于画面高度。
        assertEquals(1080, layout.height)
    }
}
