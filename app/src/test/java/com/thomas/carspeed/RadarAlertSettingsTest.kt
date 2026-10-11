package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class RadarAlertSettingsTest {
    @Test
    fun parseThresholds_acceptsCommonSeparatorsAndSortsDescending() {
        assertEquals(
            listOf(30, 20, 10, 5),
            RadarAlertSettingsStore.parseThresholds("20, 5，30 10")
        )
    }

    @Test
    fun parseThresholds_fallsBackWhenInputIsEmpty() {
        assertEquals(
            listOf(20, 10, 5),
            RadarAlertSettingsStore.parseThresholds("abc")
        )
    }
}
