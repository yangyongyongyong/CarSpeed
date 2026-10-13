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
}
