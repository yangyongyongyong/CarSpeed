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
                connectedSelectedAddresses = setOf("11:22:33:44:55:66"),
                speedKmh = 0f
            )
        )
        assertTrue(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(
                settings = settings,
                connectedSelectedAddresses = emptySet(),
                speedKmh = 0f
            )
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(
                settings = settings.copy(stopOnBluetoothDisconnected = false),
                connectedSelectedAddresses = emptySet(),
                speedKmh = 0f
            )
        )
    }

    @Test
    fun immediate_stop_only_when_the_car_is_actually_at_rest() {
        val settings = AutoTriggerSettings(
            bluetoothAddresses = setOf("AA:BB:CC:DD:EE:FF"),
            stopOnBluetoothDisconnected = true
        )

        // 车在跑：蓝牙闪断不能把录像切掉，这正是 10-26 20:02 那次停录的成因。
        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, emptySet(), speedKmh = 77.8f)
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, emptySet(), speedKmh = 10f)
        )
        // 停稳了才当场停。
        assertTrue(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, emptySet(), speedKmh = 0f)
        )
        assertTrue(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, emptySet(), speedKmh = 9.9f)
        )
        // 读不到车速时按「还在行驶」处理，宁可多录。
        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, emptySet(), speedKmh = null)
        )
        assertFalse(
            TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, emptySet(), speedKmh = Float.NaN)
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

    @Test
    fun disconnect_stop_needs_sustained_low_speed() {
        var since = 0L
        since = TriggerRules.updateLowSpeedSince(since, 3f, nowMs = 1_000L)
        assertFalse(TriggerRules.shouldStopAfterSustainedLowSpeed(since, nowMs = 30_000L))
        since = TriggerRules.updateLowSpeedSince(since, null, nowMs = 40_000L)
        since = TriggerRules.updateLowSpeedSince(since, 0f, nowMs = 50_000L)
        assertTrue(TriggerRules.shouldStopAfterSustainedLowSpeed(since, nowMs = 61_000L))
    }

    @Test
    fun moving_again_resets_disconnect_low_speed_timer() {
        var since = TriggerRules.updateLowSpeedSince(0L, 2f, nowMs = 1_000L)
        since = TriggerRules.updateLowSpeedSince(since, 25f, nowMs = 20_000L)
        since = TriggerRules.updateLowSpeedSince(since, 1f, nowMs = 30_000L)
        assertFalse(TriggerRules.shouldStopAfterSustainedLowSpeed(since, nowMs = 70_000L))
        assertTrue(TriggerRules.shouldStopAfterSustainedLowSpeed(since, nowMs = 90_000L))
    }
}
