package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareExportPreviewSplitTest {
    @Test
    fun video_weight_matches_map_percent() {
        assertEquals(0.72f, ShareExportPreviewSplit.videoWeight(28), 0.001f)
        assertEquals(0.82f, ShareExportPreviewSplit.videoWeight(18), 0.001f)
        assertEquals(0.55f, ShareExportPreviewSplit.videoWeight(45), 0.001f)
        assertEquals(0.55f, ShareExportPreviewSplit.videoWeight(90), 0.001f)
    }

    @Test
    fun drag_converts_back_to_map_percent_inside_export_limits() {
        assertEquals(28, ShareExportPreviewSplit.mapPercent(720, 1000))
        assertEquals(18, ShareExportPreviewSplit.mapPercent(900, 1000))
        assertEquals(45, ShareExportPreviewSplit.mapPercent(100, 1000))
        assertEquals(18, ShareExportPreviewSplit.mapPercent(1, 0))
    }

    @Test
    fun split_sections_stack_video_divider_and_map() {
        val (video, divider, map) = ShareExportPreviewSplit.splitSections(
            parentHeight = 1000,
            mapPercent = 28,
            dividerPx = 18
        )
        assertEquals(0, video.top)
        assertEquals(video.height, divider.top)
        assertEquals(18, divider.height)
        assertEquals(divider.top + divider.height, map.top)
        assertEquals(1000, map.top + map.height)
        assertEquals(707, video.height)
    }

    @Test
    fun drag_cannot_leave_the_export_map_range() {
        assertEquals(550, ShareExportPreviewSplit.clampVideoHeight(100, 1000))
        assertEquals(820, ShareExportPreviewSplit.clampVideoHeight(950, 1000))
        assertEquals(720, ShareExportPreviewSplit.clampVideoHeight(720, 1000))
    }
}
