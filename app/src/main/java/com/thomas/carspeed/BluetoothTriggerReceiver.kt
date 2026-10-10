package com.thomas.carspeed

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

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

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> TriggerMonitorService.arm(context, address, name ?: savedName)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> TriggerMonitorService.disarm(context, address)
        }
    }
}
