package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RadarAlertSettingsTest {
    @Test
    fun independent_radar_is_not_enabled_by_default() {
        assertFalse(RadarAlertSettingsStore.DEFAULT_ENABLED)
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
