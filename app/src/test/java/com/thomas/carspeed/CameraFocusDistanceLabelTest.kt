package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFocusDistanceLabelTest {
    @Test
    fun zero_percent_is_infinity() {
        assertEquals("远处/∞", CameraFocus.distanceLabel(0, 10f, "远处/∞"))
        assertEquals("远处/∞", CameraFocus.distanceLabel(0, null, "远处/∞"))
    }

    @Test
    fun percent_maps_to_focus_distance() {
        assertEquals("10cm", CameraFocus.distanceLabel(100, 10f, "远处/∞"))
        assertEquals("20cm", CameraFocus.distanceLabel(50, 10f, "远处/∞"))
        assertEquals("1.0m", CameraFocus.distanceLabel(10, 10f, "远处/∞"))
        assertEquals("2.0m", CameraFocus.distanceLabel(50, 1f, "远处/∞"))
        assertEquals("50m", CameraFocus.distanceLabel(2, 1f, "远处/∞"))
    }

    @Test
    fun unknown_lens_does_not_repeat_the_percent() {
        assertEquals("—", CameraFocus.distanceLabel(40, null, "远处/∞"))
        assertEquals("—", CameraFocus.distanceLabel(40, 0f, "远处/∞"))
    }
}
