package com.thomas.carspeed

import android.content.Context

data class AutoTriggerSettings(
    val bluetoothAddresses: Set<String>,
    val bluetoothNamesByAddress: Map<String, String> = emptyMap(),
    val speedThresholdKmh: Float = TriggerRules.DEFAULT_SPEED_THRESHOLD_KMH,
    val startOnAppOpen: Boolean = false,
    val startOnAmap: Boolean = true,
    val startOnBluetoothConnected: Boolean = false,
    val startOnBluetoothConnectedAndSpeed: Boolean = false,
    val stopOnPowerDisconnected: Boolean = false,
    val stopOnBluetoothDisconnected: Boolean = false,
    val stopOnBluetoothDisconnectedAndStationary: Boolean = false
) {
    val enabled: Boolean
        get() = startOnAppOpen || startOnBluetoothConnected || startOnBluetoothConnectedAndSpeed ||
            stopOnPowerDisconnected || stopOnBluetoothDisconnected || stopOnBluetoothDisconnectedAndStationary

    val usesBluetooth: Boolean
        get() = startOnBluetoothConnected || startOnBluetoothConnectedAndSpeed ||
            stopOnBluetoothDisconnected || stopOnBluetoothDisconnectedAndStationary

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
    const val PREF_AUTO_TRIGGER_START_ON_CONNECT = "auto_trigger_start_on_connect"
    const val PREF_START_ON_APP_OPEN = "auto_trigger_start_on_app_open"
    const val PREF_START_ON_AMAP = "auto_trigger_start_on_amap"
    const val PREF_START_ON_BT_CONNECTED = "auto_trigger_start_on_bt_connected"
    const val PREF_START_ON_BT_CONNECTED_AND_SPEED = "auto_trigger_start_on_bt_connected_and_speed"
    const val PREF_STOP_ON_POWER_DISCONNECTED = "auto_trigger_stop_on_power_disconnected"
    const val PREF_STOP_ON_BT_DISCONNECTED = "auto_trigger_stop_on_bt_disconnected"
    const val PREF_STOP_ON_BT_DISCONNECTED_AND_STATIONARY = "auto_trigger_stop_on_bt_disconnected_and_stationary"
    const val PREF_SPEED_THRESHOLD_KMH = "auto_trigger_speed_threshold_kmh"

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
        val legacyEnabled = prefs.getBoolean(PREF_AUTO_TRIGGER_ENABLED, false)
        val legacyStartOnConnect = prefs.getBoolean(PREF_AUTO_TRIGGER_START_ON_CONNECT, false)
        val hasNewTriggerPrefs = prefs.contains(PREF_START_ON_BT_CONNECTED) ||
            prefs.contains(PREF_START_ON_BT_CONNECTED_AND_SPEED) ||
            prefs.contains(PREF_STOP_ON_POWER_DISCONNECTED) ||
            prefs.contains(PREF_STOP_ON_BT_DISCONNECTED) ||
            prefs.contains(PREF_STOP_ON_BT_DISCONNECTED_AND_STATIONARY)
        return AutoTriggerSettings(
            bluetoothAddresses = addresses,
            bluetoothNamesByAddress = namesByAddress,
            speedThresholdKmh = prefs.getFloat(PREF_SPEED_THRESHOLD_KMH, TriggerRules.DEFAULT_SPEED_THRESHOLD_KMH)
                .takeIf { it.isFinite() && it > 0f }
                ?: TriggerRules.DEFAULT_SPEED_THRESHOLD_KMH,
            startOnAppOpen = prefs.getBoolean(PREF_START_ON_APP_OPEN, prefs.getBoolean(DashcamSettings.PREF_AUTO_START_RECORDING, false)),
            startOnAmap = prefs.getBoolean(PREF_START_ON_AMAP, true),
            startOnBluetoothConnected = prefs.getBoolean(
                PREF_START_ON_BT_CONNECTED,
                legacyEnabled && legacyStartOnConnect
            ),
            startOnBluetoothConnectedAndSpeed = prefs.getBoolean(
                PREF_START_ON_BT_CONNECTED_AND_SPEED,
                legacyEnabled && !legacyStartOnConnect
            ),
            stopOnPowerDisconnected = prefs.getBoolean(
                PREF_STOP_ON_POWER_DISCONNECTED,
                if (hasNewTriggerPrefs) false else legacyEnabled
            ),
            stopOnBluetoothDisconnected = prefs.getBoolean(
                PREF_STOP_ON_BT_DISCONNECTED,
                legacyEnabled
            ),
            stopOnBluetoothDisconnectedAndStationary = prefs.getBoolean(PREF_STOP_ON_BT_DISCONNECTED_AND_STATIONARY, false)
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
            .putBoolean(PREF_AUTO_TRIGGER_START_ON_CONNECT, settings.startOnBluetoothConnected)
            .putBoolean(PREF_START_ON_APP_OPEN, settings.startOnAppOpen)
            .putBoolean(PREF_START_ON_AMAP, settings.startOnAmap)
            .putBoolean(PREF_START_ON_BT_CONNECTED, settings.startOnBluetoothConnected)
            .putBoolean(PREF_START_ON_BT_CONNECTED_AND_SPEED, settings.startOnBluetoothConnectedAndSpeed)
            .putBoolean(PREF_STOP_ON_POWER_DISCONNECTED, settings.stopOnPowerDisconnected)
            .putBoolean(PREF_STOP_ON_BT_DISCONNECTED, settings.stopOnBluetoothDisconnected)
            .putBoolean(PREF_STOP_ON_BT_DISCONNECTED_AND_STATIONARY, settings.stopOnBluetoothDisconnectedAndStationary)
            .putFloat(PREF_SPEED_THRESHOLD_KMH, settings.speedThresholdKmh)
            .apply()
    }

    fun normalizeAddress(address: String?): String? {
        val normalized = address?.trim()?.uppercase().orEmpty()
        return normalized.ifBlank { null }
    }
}

object TriggerConnectionState {
    private const val PREF_CONNECTED_TRIGGER_BT_ADDRESSES = "auto_trigger_connected_bt_addresses"

    fun markConnected(context: Context, address: String?): Set<String> {
        return update(context, address, connected = true)
    }

    fun markDisconnected(context: Context, address: String?): Set<String> {
        return update(context, address, connected = false)
    }

    fun replaceConnectedSelected(context: Context, addresses: Set<String>): Set<String> {
        val normalized = addresses.mapNotNull { TriggerSettings.normalizeAddress(it) }.toSet()
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_CONNECTED_TRIGGER_BT_ADDRESSES, normalized)
            .apply()
        return normalized
    }

    fun connectedSelectedAddresses(
        context: Context,
        settings: AutoTriggerSettings = TriggerSettings.load(context)
    ): Set<String> {
        val selected = settings.bluetoothAddresses.mapNotNull { TriggerSettings.normalizeAddress(it) }.toSet()
        return connectedAddresses(context).filter { it in selected }.toSet()
    }

    private fun update(context: Context, address: String?, connected: Boolean): Set<String> {
        val normalized = TriggerSettings.normalizeAddress(address) ?: return connectedAddresses(context)
        val updated = connectedAddresses(context).toMutableSet().apply {
            if (connected) add(normalized) else remove(normalized)
        }
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_CONNECTED_TRIGGER_BT_ADDRESSES, updated)
            .apply()
        return updated
    }

    fun connectedAddresses(context: Context): Set<String> {
        return context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getStringSet(PREF_CONNECTED_TRIGGER_BT_ADDRESSES, emptySet())
            .orEmpty()
            .mapNotNull { TriggerSettings.normalizeAddress(it) }
            .toSet()
    }
}

object TriggerRules {
    const val DEFAULT_SPEED_THRESHOLD_KMH = 10f
    /** 人拿着手机离开车时，步行速度通常到不了 10 km/h，定位速度也经常不是精确的 0。 */
    const val DISCONNECT_STOP_SPEED_KMH = 10f
    /** 蓝牙断开后要持续低速这么久才停录，避免等红灯时蓝牙闪断就把录像停了。 */
    const val DISCONNECT_STOP_HOLD_MS = 60_000L

    fun shouldArmForBluetooth(
        settings: AutoTriggerSettings,
        connectedAddress: String?
    ): Boolean {
        if (!settings.usesBluetooth) return false
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

    fun shouldStartRecordingOnBluetoothConnected(settings: AutoTriggerSettings, connectedAddress: String?): Boolean {
        return settings.startOnBluetoothConnected && shouldArmForBluetooth(settings, connectedAddress)
    }

    fun shouldArmStartRecordingAfterBluetoothConnectedAndSpeed(
        settings: AutoTriggerSettings,
        connectedAddress: String?
    ): Boolean {
        return settings.startOnBluetoothConnectedAndSpeed && shouldArmForBluetooth(settings, connectedAddress)
    }

    fun shouldStartRecordingAfterBluetoothConnectedAndSpeed(
        settings: AutoTriggerSettings,
        connectedAddress: String?,
        speedKmh: Float
    ): Boolean {
        return shouldArmStartRecordingAfterBluetoothConnectedAndSpeed(settings, connectedAddress) &&
            speedKmh > settings.speedThresholdKmh
    }

    /**
     * 勾了「断开立即停」时，只有车确实停住了才当场停。
     * 高速上蓝牙闪断一次就把录像切掉，丢的是整趟路的关键 footage，比多录几分钟贵得多。
     */
    fun shouldStopRecordingAfterSelectedBluetoothDisconnected(
        settings: AutoTriggerSettings,
        connectedSelectedAddresses: Set<String>,
        speedKmh: Float? = null
    ): Boolean {
        if (!settings.stopOnBluetoothDisconnected) return false
        if (settings.bluetoothAddresses.isEmpty()) return false
        if (connectedSelectedAddresses.isNotEmpty()) return false
        return isAtRest(speedKmh)
    }

    /**
     * 蓝牙断开后是否改走「等车速降下来再停」。只认「断开后低速停」这个开关。
     * 「断开立即停」在车还跑着的时候不做任何延时收尾：等红灯超过一分钟也属于中途断录，
     * 要停就当场停（见 {@link #isAtRest}），要跑就一直录到用户自己停。
     */
    fun shouldMonitorStopAfterBluetoothDisconnectedAndStationary(
        settings: AutoTriggerSettings,
        connectedSelectedAddresses: Set<String>
    ): Boolean {
        if (!settings.stopOnBluetoothDisconnectedAndStationary) return false
        if (settings.bluetoothAddresses.isEmpty()) return false
        return connectedSelectedAddresses.isEmpty()
    }

    /** 车速可信且低于阈值才算「已经停下」；拿不到车速时不能当作停车，按还在行驶处理。 */
    private fun isAtRest(speedKmh: Float?): Boolean {
        if (speedKmh == null || speedKmh.isNaN()) return false
        return speedKmh.coerceAtLeast(0f) < DISCONNECT_STOP_SPEED_KMH
    }

    fun shouldStopRecordingAfterDisconnectStationary(
        bluetoothDisconnected: Boolean,
        speedKmh: Float,
        thresholdKmh: Float = DISCONNECT_STOP_SPEED_KMH
    ): Boolean {
        if (!bluetoothDisconnected) return false
        if (speedKmh.isNaN()) return false
        return speedKmh.coerceAtLeast(0f) < thresholdKmh
    }

    /**
     * 返回新的"低速开始时间"：速度达到阈值时清零，低速时保留最早的开始时间。
     * 速度未知（null）时保持原状。
     */
    fun updateLowSpeedSince(lowSpeedSinceMs: Long, speedKmh: Float?, nowMs: Long, thresholdKmh: Float = DISCONNECT_STOP_SPEED_KMH): Long {
        if (speedKmh == null || speedKmh.isNaN()) return lowSpeedSinceMs
        if (speedKmh.coerceAtLeast(0f) >= thresholdKmh) return 0L
        return if (lowSpeedSinceMs > 0L) lowSpeedSinceMs else nowMs
    }

    fun shouldStopAfterSustainedLowSpeed(lowSpeedSinceMs: Long, nowMs: Long, holdMs: Long = DISCONNECT_STOP_HOLD_MS): Boolean {
        return lowSpeedSinceMs > 0L && nowMs - lowSpeedSinceMs >= holdMs
    }
}
