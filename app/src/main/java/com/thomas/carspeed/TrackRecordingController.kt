package com.thomas.carspeed

import android.content.Context

object TrackRecordingController {
    fun sync(context: Context, stopUnavailable: Boolean = false) {
        if (TrackRecordingService.isManual()) return
        val appContext = context.applicationContext
        val settings = TrackRecordingSettingsStore.load(appContext)
        if (!settings.enabled || !settings.hasTrigger || settings.bluetoothAddresses.isEmpty() || !BluetoothDeviceList.hasPermission(appContext)) {
            if (stopUnavailable) TrackRecordingService.stop(appContext)
            return
        }
        BluetoothConnectionInspector.queryConnectedSelectedDevices(appContext, TriggerSettings.load(appContext)) { connectedDevices ->
            val device = connectedDevices.firstOrNull()
            if (device != null) {
                TrackRecordingService.arm(appContext, device.address, device.name)
            } else if (stopUnavailable) {
                TrackRecordingService.stop(appContext)
            }
        }
    }

    fun onBluetoothConnected(context: Context, address: String?, name: String?) {
        val settings = TrackRecordingSettingsStore.load(context)
        if (TrackRecordingRules.shouldArmForBluetooth(settings, address)) {
            TrackRecordingService.arm(context, address, name)
        }
    }

    fun onBluetoothDisconnected(context: Context) {
        if (TrackRecordingService.isManual()) return
        val settings = TrackRecordingSettingsStore.load(context)
        val connectedSelected = TriggerConnectionState.connectedSelectedAddresses(context, TriggerSettings.load(context))
        if (settings.enabled && settings.hasTrigger && connectedSelected.isEmpty()) {
            TrackRecordingService.stop(context)
        }
    }
}
