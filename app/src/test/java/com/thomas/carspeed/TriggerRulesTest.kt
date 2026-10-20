package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRulesTest {
    @Test
    fun bluetooth_trigger_requires_enabled_and_matching_address() {
        val settings = AutoTriggerSettings(
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            bluetoothNamesByAddress = mapOf("AA:BB:CC:DD:EE:FF" to "Car"),
            startOnBluetoothConnected = true
        )

        assertTrue(TriggerRules.shouldArmForBluetooth(settings, "aa:bb:cc:dd:ee:ff"))
        assertTrue(TriggerRules.shouldArmForBluetooth(settings, "11:22:33:44:55:66"))
        assertFalse(TriggerRules.shouldArmForBluetooth(settings, "22:33:44:55:66:77"))
        assertFalse(TriggerRules.shouldArmForBluetooth(settings.copy(startOnBluetoothConnected = false), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun recording_requires_bluetooth_match_and_speed_over_threshold() {
        assertFalse(TriggerRules.shouldStartRecording(bluetoothMatched = false, speedKmh = 30f))
        assertFalse(TriggerRules.shouldStartRecording(bluetoothMatched = true, speedKmh = 10f))
        assertTrue(TriggerRules.shouldStartRecording(bluetoothMatched = true, speedKmh = 10.1f))
    }

    @Test
    fun bluetooth_connect_can_start_recording_immediately_when_enabled() {
        val settings = AutoTriggerSettings(
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            startOnBluetoothConnected = true
        )

        assertTrue(TriggerRules.shouldStartRecordingOnBluetoothConnected(settings, "aa:bb:cc:dd:ee:ff"))
        assertFalse(TriggerRules.shouldStartRecordingOnBluetoothConnected(settings, "11:22:33:44:55:66"))
        assertFalse(TriggerRules.shouldStartRecordingOnBluetoothConnected(settings.copy(startOnBluetoothConnected = false), "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun bluetooth_speed_can_start_recording_independently() {
        val settings = AutoTriggerSettings(
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            startOnBluetoothConnectedAndSpeed = true
        )

        assertFalse(TriggerRules.shouldStartRecordingAfterBluetoothConnectedAndSpeed(settings, "AA:BB:CC:DD:EE:FF", 10f))
        assertTrue(TriggerRules.shouldStartRecordingAfterBluetoothConnectedAndSpeed(settings, "AA:BB:CC:DD:EE:FF", 10.1f))
        assertFalse(TriggerRules.shouldStartRecordingAfterBluetoothConnectedAndSpeed(settings, "11:22:33:44:55:66", 30f))
        assertFalse(
            TriggerRules.shouldStartRecordingAfterBluetoothConnectedAndSpeed(
                settings.copy(startOnBluetoothConnectedAndSpeed = false),
                "AA:BB:CC:DD:EE:FF",
                30f
            )
        )
    }

    @Test
    fun recording_stops_when_all_selected_bluetooth_devices_are_disconnected() {
        val settings = AutoTriggerSettings(
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"),
            stopOnBluetoothDisconnected = true
        )

        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(
                settings = settings,
                connectedSelectedAddresses = setOf("11:22:33:44:55:66")
            )
        )
        assertTrue(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(
                settings = settings,
                connectedSelectedAddresses = emptySet()
            )
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(
                settings = settings.copy(stopOnBluetoothDisconnected = false),
                connectedSelectedAddresses = emptySet()
            )
        )
    }

    @Test
    fun bluetooth_disconnect_stationary_monitor_is_independent() {
        val settings = AutoTriggerSettings(
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            stopOnBluetoothDisconnectedAndStationary = true
        )

        assertTrue(TriggerRules.shouldMonitorStopAfterBluetoothDisconnectedAndStationary(settings, emptySet()))
        assertFalse(TriggerRules.shouldMonitorStopAfterBluetoothDisconnectedAndStationary(settings, setOf("AA:BB:CC:DD:EE:FF")))
        assertFalse(
            TriggerRules.shouldMonitorStopAfterBluetoothDisconnectedAndStationary(
                settings.copy(stopOnBluetoothDisconnectedAndStationary = false),
                emptySet()
            )
        )
    }

    @Test
    fun disconnected_stop_when_speed_stays_under_10_kph() {
        assertFalse(
            TriggerRules.shouldStopRecordingAfterDisconnectStationary(bluetoothDisconnected = false, speedKmh = 0f)
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterDisconnectStationary(bluetoothDisconnected = true, speedKmh = Float.NaN)
        )
        assertTrue(
            TriggerRules.shouldStopRecordingAfterDisconnectStationary(bluetoothDisconnected = true, speedKmh = 0f)
        )
        assertTrue(
            TriggerRules.shouldStopRecordingAfterDisconnectStationary(bluetoothDisconnected = true, speedKmh = 9.9f)
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterDisconnectStationary(bluetoothDisconnected = true, speedKmh = 10f)
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterDisconnectStationary(bluetoothDisconnected = true, speedKmh = 11f)
        )
    }
}
