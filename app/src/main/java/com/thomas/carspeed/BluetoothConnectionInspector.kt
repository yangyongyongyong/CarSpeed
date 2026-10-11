package com.thomas.carspeed

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper

object BluetoothConnectionInspector {
    private val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    @SuppressLint("MissingPermission")
    fun queryConnectedSelectedDevices(
        context: Context,
        settings: AutoTriggerSettings = TriggerSettings.load(context),
        callback: (List<PairedBluetoothDevice>) -> Unit
    ) {
        val appContext = context.applicationContext
        if (!settings.enabled || settings.bluetoothAddresses.isEmpty() || !BluetoothDeviceList.hasPermission(appContext)) {
            callback(emptyList())
            return
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            callback(emptyList())
            return
        }

        val selected = settings.bluetoothAddresses.mapNotNull { TriggerSettings.normalizeAddress(it) }.toSet()
        val connected = linkedMapOf<String, PairedBluetoothDevice>()
        try {
            BluetoothDeviceList.debugDevices(appContext, settings).filter { it.selected && it.connected }.forEach { device ->
                connected[device.address] = PairedBluetoothDevice(
                    name = device.name,
                    address = device.address
                )
            }
        } catch (_: SecurityException) {
            // 权限被系统撤销时继续走公开 profile 查询。
        }
        val mainHandler = Handler(Looper.getMainLooper())
        var remaining = profiles.size
        var delivered = false

        lateinit var timeout: Runnable
        fun deliver() {
            if (delivered) return
            delivered = true
            mainHandler.removeCallbacks(timeout)
            callback(connected.values.toList())
        }
        fun finishOne() {
            remaining -= 1
            if (remaining <= 0) deliver()
        }

        timeout = Runnable { deliver() }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.connectedDevices.forEach { device ->
                        val address = TriggerSettings.normalizeAddress(device.address) ?: return@forEach
                        if (address in selected) {
                            connected[address] = PairedBluetoothDevice(
                                name = device.name ?: appContext.getString(R.string.bluetooth_unknown_device),
                                address = address
                            )
                        }
                    }
                } catch (_: SecurityException) {
                    // 权限被系统撤销时当作未连接处理。
                } finally {
                    try {
                        adapter.closeProfileProxy(profile, proxy)
                    } catch (_: RuntimeException) {
                        // ignore close failure
                    }
                    mainHandler.post { finishOne() }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                mainHandler.post { finishOne() }
            }
        }

        mainHandler.postDelayed(timeout, QUERY_TIMEOUT_MS)
        profiles.forEach { profile ->
            val requested = try {
                adapter.getProfileProxy(appContext, listener, profile)
            } catch (_: RuntimeException) {
                false
            } catch (_: SecurityException) {
                false
            }
            if (!requested) finishOne()
        }
    }

    private const val QUERY_TIMEOUT_MS = 2_000L
}
