package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedDisplayTest {
    @Test
    fun mph_conversion_uses_standard_ratio() {
        assertEquals(62.1371f, SpeedDisplay.toDisplaySpeed(100f, SpeedDisplay.UNIT_MPH), 0.0002f)
    }

    @Test
    fun unknown_unit_falls_back_to_kmh() {
        assertEquals("km/h", SpeedDisplay.unitLabel("bad"))
        assertEquals(88f, SpeedDisplay.toDisplaySpeed(88f, "bad"), 0.001f)
    }
}
