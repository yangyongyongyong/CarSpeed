package com.thomas.carspeed

import android.Manifest
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
}
