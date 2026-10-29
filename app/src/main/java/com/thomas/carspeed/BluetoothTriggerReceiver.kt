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
        val trackSettings = TrackRecordingSettingsStore.load(context)
        val recordingBluetoothMatched = TriggerRules.shouldArmForBluetooth(settings, address)
        val trackBluetoothMatched = TrackRecordingRules.shouldArmForBluetooth(trackSettings, address)
        if (!recordingBluetoothMatched && !trackBluetoothMatched) return
        val normalizedAddress = TriggerSettings.normalizeAddress(address)
        val savedName = normalizedAddress?.let { settings.bluetoothNamesByAddress[it] }
        val displayName = name ?: savedName

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                TriggerConnectionState.markConnected(context, address)
                if (recordingBluetoothMatched) {
                    if (DashcamService.isRecording(context)) {
                        TriggerMonitorService.cancelDisconnectedStop(context, null)
                    } else if (!PrivacyConsent.isAgreed(context)) {
                        // 未同意隐私政策：不自动开录，只记录连接状态。
                    } else if (TriggerRules.shouldStartRecordingOnBluetoothConnected(settings, address)) {
                        BackgroundRecordingStart.start(context)
                    } else if (TriggerRules.shouldArmStartRecordingAfterBluetoothConnectedAndSpeed(settings, address)) {
                        TriggerMonitorService.arm(context, address, displayName)
                    }
                }
                if (trackBluetoothMatched) TrackRecordingController.onBluetoothConnected(context, address, displayName)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                TriggerConnectionState.markDisconnected(context, address)
                val connectedSelected = TriggerConnectionState.connectedSelectedAddresses(context, settings)
                if (recordingBluetoothMatched) {
                    if (DashcamService.isRecording(context)) {
                        // 断开时先看车速：车还在跑就继续录，不在高速上因为蓝牙闪断切掉整趟 footage。
                        val speedKmh = TriggerSpeed.currentKmh(context)
                        if (TriggerRules.shouldStopRecordingAfterSelectedBluetoothDisconnected(settings, connectedSelected, speedKmh)) {
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
                if (trackBluetoothMatched) TrackRecordingController.onBluetoothDisconnected(context)
            }
        }
    }
}
