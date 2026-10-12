package com.thomas.carspeed

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PairedBluetoothDevice(
    val name: String,
    val address: String
) {
    val label: String
        get() = "$name ($address)"
}

data class BluetoothDeviceDebugInfo(
    val name: String,
    val address: String,
    val selected: Boolean,
    val bonded: Boolean,
    val connected: Boolean
)

object BluetoothDeviceList {
    fun hasPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    fun pairedDevices(context: Context): List<PairedBluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        return try {
            adapter.bondedDevices
                .map { device ->
                    PairedBluetoothDevice(
                        name = device.name ?: context.getString(R.string.bluetooth_unknown_device),
                        address = device.address
                    )
                }
                .sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun debugDevices(context: Context, settings: AutoTriggerSettings = TriggerSettings.load(context)): List<BluetoothDeviceDebugInfo> {
        if (!hasPermission(context)) return emptyList()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return emptyList()
        val selected = settings.bluetoothAddresses.mapNotNull { TriggerSettings.normalizeAddress(it) }.toSet()
        return try {
            adapter.bondedDevices.map { device ->
                val address = TriggerSettings.normalizeAddress(device.address).orEmpty()
                BluetoothDeviceDebugInfo(
                    name = device.name ?: context.getString(R.string.bluetooth_unknown_device),
                    address = address,
                    selected = address in selected,
                    bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                    connected = isConnectedByReflection(device)
                )
            }.sortedWith(compareByDescending<BluetoothDeviceDebugInfo> { it.selected }.thenBy { it.name.lowercase() })
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun isConnectedByReflection(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
