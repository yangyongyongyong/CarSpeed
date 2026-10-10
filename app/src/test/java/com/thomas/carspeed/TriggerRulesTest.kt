package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRulesTest {
    @Test
    fun bluetooth_trigger_requires_enabled_and_matching_address() {
        val settings = AutoTriggerSettings(
            enabled = true,
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            bluetoothNamesByAddress = mapOf("AA:BB:CC:DD:EE:FF" to "Car")
        )

        assertTrue(TriggerRules.shouldArmForBluetooth(settings, "aa:bb:cc:dd:ee:ff"))
        assertTrue(TriggerRules.shouldArmForBluetooth(settings, "11:22:33:44:55:66"))
        assertFalse(TriggerRules.shouldArmForBluetooth(settings, "22:33:44:55:66:77"))
        assertFalse(TriggerRules.shouldArmForBluetooth(settings.copy(enabled = false), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun recording_requires_bluetooth_match_and_speed_over_threshold() {
        assertFalse(TriggerRules.shouldStartRecording(bluetoothMatched = false, speedKmh = 30f))
        assertFalse(TriggerRules.shouldStartRecording(bluetoothMatched = true, speedKmh = 10f))
        assertTrue(TriggerRules.shouldStartRecording(bluetoothMatched = true, speedKmh = 10.1f))
    }
}
