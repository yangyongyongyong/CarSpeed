package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecordingRulesTest {
    private val settings = TrackRecordingSettings(
        enabled = true,
        triggerOnBluetoothSpeed = true,
        triggerOnBluetoothDistance = true,
        bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF")
    )

    @Test
    fun bluetooth_match_requires_enabled_trigger_and_selected_device() {
        assertTrue(TrackRecordingRules.shouldArmForBluetooth(settings, "aa:bb:cc:dd:ee:ff"))
        assertFalse(TrackRecordingRules.shouldArmForBluetooth(settings, "11:22:33:44:55:66"))
        assertFalse(TrackRecordingRules.shouldArmForBluetooth(settings.copy(enabled = false), "AA:BB:CC:DD:EE:FF"))
        assertFalse(
            TrackRecordingRules.shouldArmForBluetooth(
                settings.copy(triggerOnBluetoothSpeed = false, triggerOnBluetoothDistance = false),
                "AA:BB:CC:DD:EE:FF"
            )
        )
    }

    @Test
    fun speed_trigger_requires_bluetooth_and_speed_over_threshold() {
        assertFalse(TrackRecordingRules.shouldStartAfterSpeed(settings, bluetoothMatched = false, speedKmh = 50f))
        assertFalse(TrackRecordingRules.shouldStartAfterSpeed(settings, bluetoothMatched = true, speedKmh = 10f))
        assertTrue(TrackRecordingRules.shouldStartAfterSpeed(settings, bluetoothMatched = true, speedKmh = 10.1f))
    }

    @Test
    fun distance_trigger_requires_bluetooth_gps_and_distance() {
        assertFalse(TrackRecordingRules.shouldStartAfterDistance(settings, bluetoothMatched = false, gpsReady = true, distanceMeters = 120f))
        assertFalse(TrackRecordingRules.shouldStartAfterDistance(settings, bluetoothMatched = true, gpsReady = false, distanceMeters = 120f))
        assertFalse(TrackRecordingRules.shouldStartAfterDistance(settings, bluetoothMatched = true, gpsReady = true, distanceMeters = 100f))
        assertTrue(TrackRecordingRules.shouldStartAfterDistance(settings, bluetoothMatched = true, gpsReady = true, distanceMeters = 100.1f))
    }

    @Test
    fun gps_signal_accepts_missing_or_reasonable_accuracy() {
        assertTrue(TrackRecordingRules.hasGpsSignal(null))
        assertTrue(TrackRecordingRules.hasGpsSignal(30f))
        assertFalse(TrackRecordingRules.hasGpsSignal(150f))
    }

    @Test
    fun trim_leading_stationary_keeps_last_parked_point_as_start() {
        val points = listOf(0f, 0.5f, 0f, 1f, 12f, 30f).mapIndexed { i, speed ->
            VideoTrackPoint(i * 1_000L, 1_000L + i * 1_000L, 30.0, 120.0, speed, 5f, null)
        }
        val trimmed = TrackRecordingRules.trimLeadingStationary(points)
        org.junit.Assert.assertEquals(3, trimmed.size)
        org.junit.Assert.assertEquals(1f, trimmed.first().speedKmh)
    }

    @Test
    fun trim_leading_stationary_keeps_all_when_no_speed_info() {
        val points = (0 until 4).map { i -> VideoTrackPoint(i * 1_000L, i * 1_000L, 30.0, 120.0 + i * 0.001, 0f, 5f, null) }
        org.junit.Assert.assertEquals(4, TrackRecordingRules.trimLeadingStationary(points).size)
    }
}
