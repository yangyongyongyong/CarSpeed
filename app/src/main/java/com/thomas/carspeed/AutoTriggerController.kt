package com.thomas.carspeed

import android.content.Context

object AutoTriggerController {
    fun sync(context: Context, stopUnavailable: Boolean = false) {
        val appContext = context.applicationContext
        val settings = TriggerSettings.load(appContext)
        if (!settings.usesBluetooth || settings.bluetoothAddresses.isEmpty() || !BluetoothDeviceList.hasPermission(appContext)) {
            TriggerConnectionState.replaceConnectedSelected(appContext, emptySet())
            if (stopUnavailable) TriggerMonitorService.disarm(appContext, null)
            if (stopUnavailable) TriggerMonitorService.cancelDisconnectedStop(appContext, null)
            return
        }

        BluetoothConnectionInspector.queryConnectedSelectedDevices(appContext, settings) { connectedDevices ->
            val connectedAddresses = connectedDevices.mapNotNull { TriggerSettings.normalizeAddress(it.address) }.toSet()
            TriggerConnectionState.replaceConnectedSelected(appContext, connectedAddresses)
            if (DashcamService.isRecording(appContext)) {
                if (connectedAddresses.isNotEmpty()) {
                    TriggerMonitorService.cancelDisconnectedStop(appContext, null)
                }
                return@queryConnectedSelectedDevices
            }

            val device = connectedDevices.firstOrNull()
            if (device != null) {
                if (settings.startOnBluetoothConnected) {
                    DashcamService.startRecording(appContext)
                    if (OverlayController.canStart(appContext) && !OverlayController.isRunning(appContext)) {
                        OverlayController.start(appContext)
                    }
                } else if (settings.startOnBluetoothConnectedAndSpeed) {
                    TriggerMonitorService.arm(appContext, device.address, device.name)
                } else {
                    TriggerMonitorService.disarm(appContext, null)
                }
            } else {
                TriggerMonitorService.disarm(appContext, null)
            }
        }
    }
}
