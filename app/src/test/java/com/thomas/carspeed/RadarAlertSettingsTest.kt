package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarAlertSettingsTest {
    @Test
    fun independent_radar_is_not_enabled_by_default() {
        assertFalse(RadarAlertSettingsStore.DEFAULT_ENABLED)
    }

    @Test
    fun unknown_overlap_defaults_to_elevated() {
        assertTrue(RadarAlertSettingsStore.DEFAULT_UNKNOWN_ROAD_LAYER_ELEVATED)
    }

    @Test
    fun divergence_correction_is_on_by_default() {
        assertTrue(RadarAlertSettingsStore.DEFAULT_ROAD_DIVERGENCE_CORRECTION)
        assertTrue(RoadLayerConfig().divergenceCorrectionEnabled)
    }

    @Test
    fun pitch_road_layer_recognition_is_on_by_default() {
        assertTrue(RadarAlertSettingsStore.DEFAULT_PITCH_ROAD_LAYER)
        assertTrue(RoadLayerConfig().pitchRecognitionEnabled)
    }

    @Test
    fun satellite_tunnel_recognition_is_on_by_default() {
        assertTrue(RadarAlertSettingsStore.DEFAULT_SATELLITE_TUNNEL)
        assertTrue(RoadLayerConfig().satelliteTunnelEnabled)
    }

    @Test
    fun parse_thresholds_sorts_descending_and_clamps_range() {
        assertEquals(listOf(300, 20, 10, 1), RadarAlertSettingsStore.parseThresholds("10,20,999,0"))
    }

    @Test
    fun format_thresholds_is_stable() {
        assertEquals("30,20,5", RadarAlertSettingsStore.formatThresholds(listOf(5, 30, 20, 20)))
    }
}
