package com.thomas.carspeed

import android.content.Context

data class TrackRecordingSettings(
    val enabled: Boolean,
    val triggerOnBluetoothSpeed: Boolean,
    val triggerOnBluetoothDistance: Boolean,
    val speedThresholdKmh: Float = TrackRecordingRules.DEFAULT_SPEED_THRESHOLD_KMH,
    val distanceThresholdMeters: Float = TrackRecordingRules.DEFAULT_DISTANCE_THRESHOLD_METERS,
    val bluetoothAddresses: Set<String>,
    val bluetoothNamesByAddress: Map<String, String> = emptyMap()
) {
    val hasTrigger: Boolean
        get() = triggerOnBluetoothSpeed || triggerOnBluetoothDistance

    val usesBluetooth: Boolean
        get() = hasTrigger
}

object TrackRecordingSettingsStore {
    const val PREF_TRACK_RECORDING_ENABLED = "track_recording_enabled"
    const val PREF_TRACK_TRIGGER_BT_SPEED = "track_trigger_bt_speed"
    const val PREF_TRACK_TRIGGER_BT_DISTANCE = "track_trigger_bt_distance"

    fun load(context: Context): TrackRecordingSettings {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val triggerSettings = TriggerSettings.load(context)
        return TrackRecordingSettings(
            enabled = prefs.getBoolean(PREF_TRACK_RECORDING_ENABLED, false),
            triggerOnBluetoothSpeed = prefs.getBoolean(PREF_TRACK_TRIGGER_BT_SPEED, false),
            triggerOnBluetoothDistance = prefs.getBoolean(PREF_TRACK_TRIGGER_BT_DISTANCE, false),
            bluetoothAddresses = triggerSettings.bluetoothAddresses,
            bluetoothNamesByAddress = triggerSettings.bluetoothNamesByAddress
        )
    }

    fun saveTriggers(
        context: Context,
        enabled: Boolean,
        triggerOnBluetoothSpeed: Boolean,
        triggerOnBluetoothDistance: Boolean
    ) {
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_TRACK_RECORDING_ENABLED, enabled)
            .putBoolean(PREF_TRACK_TRIGGER_BT_SPEED, triggerOnBluetoothSpeed)
            .putBoolean(PREF_TRACK_TRIGGER_BT_DISTANCE, triggerOnBluetoothDistance)
            .commit()
    }
}

object TrackRecordingRules {
    const val DEFAULT_SPEED_THRESHOLD_KMH = 10f
    const val DEFAULT_DISTANCE_THRESHOLD_METERS = 100f
    const val MAX_TRIGGER_ACCURACY_METERS = 100f

    fun shouldArmForBluetooth(settings: TrackRecordingSettings, connectedAddress: String?): Boolean {
        if (!settings.enabled || !settings.usesBluetooth) return false
        val actual = TriggerSettings.normalizeAddress(connectedAddress) ?: return false
        return actual in settings.bluetoothAddresses.mapNotNull { TriggerSettings.normalizeAddress(it) }.toSet()
    }

    fun shouldStartAfterSpeed(settings: TrackRecordingSettings, bluetoothMatched: Boolean, speedKmh: Float): Boolean {
        return settings.enabled && settings.triggerOnBluetoothSpeed && bluetoothMatched && speedKmh > settings.speedThresholdKmh
    }

    fun shouldStartAfterDistance(settings: TrackRecordingSettings, bluetoothMatched: Boolean, gpsReady: Boolean, distanceMeters: Float): Boolean {
        return settings.enabled && settings.triggerOnBluetoothDistance && bluetoothMatched && gpsReady &&
            distanceMeters > settings.distanceThresholdMeters
    }

    fun hasGpsSignal(accuracyMeters: Float?): Boolean {
        return accuracyMeters == null || accuracyMeters <= MAX_TRIGGER_ACCURACY_METERS
    }
}
