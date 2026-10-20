package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareSubtitleFormatterTest {
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
}
