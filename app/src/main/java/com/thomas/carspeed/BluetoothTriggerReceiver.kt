package com.thomas.carspeed

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

class BluetoothTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return
        val address = try {
            device.address
        } catch (_: SecurityException) {
            null
        }
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        }

        val settings = TriggerSettings.load(context)
        if (!TriggerRules.shouldArmForBluetooth(settings, address)) return
        val normalizedAddress = TriggerSettings.normalizeAddress(address)
        val savedName = normalizedAddress?.let { settings.bluetoothNamesByAddress[it] }
        val displayName = name ?: savedName

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                TriggerConnectionState.markConnected(context, address)
                if (DashcamService.isRecording(context)) {
                    TriggerMonitorService.cancelDisconnectedStop(context, null)
                } else if (TriggerRules.shouldStartRecordingOnBluetoothConnected(settings, address)) {
                    DashcamService.startRecording(context)
                    if (OverlayController.canStart(context) && !OverlayController.isRunning(context)) {
                        OverlayController.start(context)
                    }
                } else if (TriggerRules.shouldArmStartRecordingAfterBluetoothConnectedAndSpeed(settings, address)) {
                    TriggerMonitorService.arm(context, address, displayName)
                }
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                TriggerConnectionState.markDisconnected(context, address)
                val connectedSelected = TriggerConnectionState.connectedSelectedAddresses(context, settings)
                if (DashcamService.isRecording(context)) {
                    if (TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, connectedSelected)) {
                        Toast.makeText(context, R.string.trigger_bluetooth_disconnected_stop, Toast.LENGTH_SHORT).show()
                        DashcamService.stopRecording(context)
                    } else if (TriggerRules.shouldMonitorStopAfterBluetoothDisconnectedAndStationary(settings, connectedSelected)) {
                        TriggerMonitorService.monitorDisconnectedStop(context, address, displayName)
                    } else {
                        TriggerMonitorService.cancelDisconnectedStop(context, null)
                    }
                } else {
                    TriggerMonitorService.disarm(context, address)
                }
            }
        }
    }
}
