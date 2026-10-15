package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapWatchRulesTest {
    @Test
    fun amap_in_foreground_wakes_the_app_when_enabled() {
        assertTrue(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = "com.autonavi.minimap",
                recording = false,
                nowMs = 30_000L,
                lastWakeAtMs = 0L
            )
        )
        assertTrue(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = "com.autonavi.amapauto",
                recording = false,
                nowMs = 30_000L,
                lastWakeAtMs = 0L
            )
        )
    }

    @Test
    fun other_apps_and_disabled_switch_do_not_wake() {
        assertFalse(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = "com.android.settings",
                recording = false,
                nowMs = 30_000L,
                lastWakeAtMs = 0L
            )
        )
        assertFalse(
            AmapWatchRules.shouldWake(
                enabled = false,
                foregroundPackage = "com.autonavi.minimap",
                recording = false,
                nowMs = 30_000L,
                lastWakeAtMs = 0L
            )
        )
        assertFalse(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = null,
                recording = false,
                nowMs = 30_000L,
                lastWakeAtMs = 0L
            )
        )
    }

    @Test
    fun already_recording_or_recent_wake_does_not_wake_again() {
        assertFalse(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = "com.autonavi.minimap",
                recording = true,
                nowMs = 30_000L,
                lastWakeAtMs = 0L
            )
        )
        assertFalse(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = "com.autonavi.minimap",
                recording = false,
                nowMs = 20_000L,
                lastWakeAtMs = 1L
            )
        )
        assertTrue(
            AmapWatchRules.shouldWake(
                enabled = true,
                foregroundPackage = "com.autonavi.minimap",
                recording = false,
                nowMs = 20_000L,
                lastWakeAtMs = 0L
            )
        )
    }
}
