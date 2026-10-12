package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashcamHdrSettingsTest {
    @Test
    fun off_mode_never_enables_hdr() {
        val settings = HdrSettings(
            mode = DashcamSettings.HDR_MODE_OFF,
            startMinute = 18 * 60,
            endMinute = 6 * 60
        )

        assertFalse(DashcamHdrSettings.shouldEnableAt(settings, 23 * 60))
    }

    @Test
    fun always_mode_enables_hdr_all_day() {
        val settings = HdrSettings(
            mode = DashcamSettings.HDR_MODE_ALWAYS,
            startMinute = 18 * 60,
            endMinute = 6 * 60
        )

        assertTrue(DashcamHdrSettings.shouldEnableAt(settings, 12 * 60))
    }

    @Test
    fun schedule_mode_supports_cross_midnight_night_range() {
        val settings = HdrSettings(
            mode = DashcamSettings.HDR_MODE_SCHEDULE,
            startMinute = 18 * 60,
            endMinute = 6 * 60
        )

        assertFalse(DashcamHdrSettings.shouldEnableAt(settings, 17 * 60 + 59))
        assertTrue(DashcamHdrSettings.shouldEnableAt(settings, 18 * 60))
        assertTrue(DashcamHdrSettings.shouldEnableAt(settings, 23 * 60))
        assertTrue(DashcamHdrSettings.shouldEnableAt(settings, 5 * 60 + 59))
        assertFalse(DashcamHdrSettings.shouldEnableAt(settings, 6 * 60))
    }

    @Test
    fun schedule_mode_supports_same_day_range() {
        val settings = HdrSettings(
            mode = DashcamSettings.HDR_MODE_SCHEDULE,
            startMinute = 8 * 60,
            endMinute = 20 * 60
        )

        assertFalse(DashcamHdrSettings.shouldEnableAt(settings, 7 * 60 + 59))
        assertTrue(DashcamHdrSettings.shouldEnableAt(settings, 8 * 60))
        assertTrue(DashcamHdrSettings.shouldEnableAt(settings, 19 * 60 + 59))
        assertFalse(DashcamHdrSettings.shouldEnableAt(settings, 20 * 60))
    }
}
