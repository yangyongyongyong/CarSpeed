package com.thomas.carspeed

import android.content.Context

data class AutoTriggerSettings(
    val enabled: Boolean,
    val bluetoothAddresses: Set<String>,
    val bluetoothNamesByAddress: Map<String, String> = emptyMap(),
    val speedThresholdKmh: Float = TriggerRules.DEFAULT_SPEED_THRESHOLD_KMH
) {
    val bluetoothAddress: String?
        get() = bluetoothAddresses.firstOrNull()

    val bluetoothName: String?
        get() = bluetoothAddress?.let { bluetoothNamesByAddress[it] }
}

object TriggerSettings {
    const val PREF_AUTO_TRIGGER_ENABLED = "auto_trigger_enabled"
    const val PREF_AUTO_TRIGGER_BT_ADDRESS = "auto_trigger_bt_address"
    const val PREF_AUTO_TRIGGER_BT_NAME = "auto_trigger_bt_name"
    const val PREF_AUTO_TRIGGER_BT_ADDRESSES = "auto_trigger_bt_addresses"
    const val PREF_AUTO_TRIGGER_BT_NAMES = "auto_trigger_bt_names"

    fun load(context: Context): AutoTriggerSettings {
        val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val savedAddresses = prefs.getStringSet(PREF_AUTO_TRIGGER_BT_ADDRESSES, null)
            ?.mapNotNull { normalizeAddress(it) }
            ?.toSet()
        val legacyAddress = normalizeAddress(prefs.getString(PREF_AUTO_TRIGGER_BT_ADDRESS, null))
        val addresses = savedAddresses ?: legacyAddress?.let { setOf(it) }.orEmpty()
        val namesByAddress = prefs.getStringSet(PREF_AUTO_TRIGGER_BT_NAMES, null)
            ?.mapNotNull { entry ->
                val parts = entry.split('\t', limit = 2)
                val address = normalizeAddress(parts.firstOrNull()) ?: return@mapNotNull null
                address to parts.getOrNull(1).orEmpty()
            }
            ?.toMap()
            ?: legacyAddress?.let { address ->
                prefs.getString(PREF_AUTO_TRIGGER_BT_NAME, null)?.let { name -> mapOf(address to name) }
            }.orEmpty()
        return AutoTriggerSettings(
            enabled = prefs.getBoolean(PREF_AUTO_TRIGGER_ENABLED, false),
            bluetoothAddresses = addresses,
            bluetoothNamesByAddress = namesByAddress
        )
    }

    fun save(context: Context, settings: AutoTriggerSettings) {
        val addresses = settings.bluetoothAddresses.mapNotNull { normalizeAddress(it) }.toSet()
        val names = settings.bluetoothNamesByAddress.mapNotNull { (address, name) ->
            val normalized = normalizeAddress(address) ?: return@mapNotNull null
            "$normalized\t$name"
        }.toSet()
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_AUTO_TRIGGER_ENABLED, settings.enabled)
            .putStringSet(PREF_AUTO_TRIGGER_BT_ADDRESSES, addresses)
            .putStringSet(PREF_AUTO_TRIGGER_BT_NAMES, names)
            .putString(PREF_AUTO_TRIGGER_BT_ADDRESS, addresses.firstOrNull())
            .putString(PREF_AUTO_TRIGGER_BT_NAME, settings.bluetoothName)
            .apply()
    }

    fun normalizeAddress(address: String?): String? {
        val normalized = address?.trim()?.uppercase().orEmpty()
        return normalized.ifBlank { null }
    }
}

object TriggerRules {
    const val DEFAULT_SPEED_THRESHOLD_KMH = 10f

    fun shouldArmForBluetooth(
        settings: AutoTriggerSettings,
        connectedAddress: String?
    ): Boolean {
        if (!settings.enabled) return false
        val actual = TriggerSettings.normalizeAddress(connectedAddress) ?: return false
        return actual in settings.bluetoothAddresses.mapNotNull { TriggerSettings.normalizeAddress(it) }.toSet()
    }

    fun shouldStartRecording(
        bluetoothMatched: Boolean,
        speedKmh: Float,
        thresholdKmh: Float = DEFAULT_SPEED_THRESHOLD_KMH
    ): Boolean {
        return bluetoothMatched && speedKmh > thresholdKmh
    }
}
