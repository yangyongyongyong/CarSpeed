package com.thomas.carspeed

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class TrackRecordingSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvTrackStatus: TextView
    private lateinit var cbTrackRecordingEnabled: CheckBox
    private lateinit var cbTrackTriggerBtSpeed: CheckBox
    private lateinit var cbTrackTriggerBtDistance: CheckBox
    private lateinit var btnBluetoothDevices: Button
    private lateinit var tvBluetoothDeviceSummary: TextView
    private var pairedBluetoothDevices: List<PairedBluetoothDevice> = emptyList()
    private var selectedBluetoothAddresses: MutableSet<String> = mutableSetOf()
    private var updating = false

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
                cbTrackRecordingEnabled.isChecked = false
                persistSettings()
                Toast.makeText(this, R.string.track_location_permission_required, Toast.LENGTH_SHORT).show()
            }
            syncTrackRecording()
        }

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                selectedBluetoothAddresses.clear()
                persistSelectedBluetoothDevices()
                Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show()
            }
            setupBluetoothDevices()
            syncTrackRecording()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_track_recording_settings)
        toolbar = findViewById(R.id.toolbar)
        tvTrackStatus = findViewById(R.id.tvTrackStatus)
        cbTrackRecordingEnabled = findViewById(R.id.cbTrackRecordingEnabled)
        cbTrackTriggerBtSpeed = findViewById(R.id.cbTrackTriggerBtSpeed)
        cbTrackTriggerBtDistance = findViewById(R.id.cbTrackTriggerBtDistance)
        btnBluetoothDevices = findViewById(R.id.btnBluetoothDevices)
        tvBluetoothDeviceSummary = findViewById(R.id.tvBluetoothDeviceSummary)

        toolbar.setNavigationOnClickListener { finish() }
        setupBluetoothDevices()
        loadSettings()
        setupControls()
    }

    override fun onResume() {
        super.onResume()
        syncTrackRecording()
        renderStatus()
    }

    private fun setupControls() {
        val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, _ ->
            if (updating) return@OnCheckedChangeListener
            if (cbTrackRecordingEnabled.isChecked && !OverlayController.hasLocationPermission(this)) {
                requestLocationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return@OnCheckedChangeListener
            }
            persistSettings()
            syncTrackRecording()
            renderStatus()
        }
        cbTrackRecordingEnabled.setOnCheckedChangeListener(listener)
        cbTrackTriggerBtSpeed.setOnCheckedChangeListener(listener)
        cbTrackTriggerBtDistance.setOnCheckedChangeListener(listener)
        btnBluetoothDevices.setOnClickListener { showBluetoothDevicePicker() }
    }

    private fun loadSettings() {
        val settings = TrackRecordingSettingsStore.load(this)
        updating = true
        cbTrackRecordingEnabled.isChecked = settings.enabled
        cbTrackTriggerBtSpeed.isChecked = settings.triggerOnBluetoothSpeed
        cbTrackTriggerBtDistance.isChecked = settings.triggerOnBluetoothDistance
        updating = false
        renderStatus()
    }

    private fun setupBluetoothDevices() {
        pairedBluetoothDevices = BluetoothDeviceList.pairedDevices(this)
        selectedBluetoothAddresses = TriggerSettings.load(this).bluetoothAddresses
            .mapNotNull { TriggerSettings.normalizeAddress(it) }
            .toMutableSet()
        btnBluetoothDevices.isEnabled = BluetoothDeviceList.hasPermission(this) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        updateBluetoothSummary()
    }

    private fun showBluetoothDevicePicker() {
        if (!BluetoothDeviceList.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                Toast.makeText(this, R.string.bluetooth_permission_required, Toast.LENGTH_SHORT).show()
            }
            return
        }
        pairedBluetoothDevices = BluetoothDeviceList.pairedDevices(this)
        if (pairedBluetoothDevices.isEmpty()) {
            Toast.makeText(this, R.string.bluetooth_no_paired_devices, Toast.LENGTH_SHORT).show()
            updateBluetoothSummary()
            return
        }
        val labels = pairedBluetoothDevices.map { it.label }.toTypedArray()
        val nextSelection = selectedBluetoothAddresses.toMutableSet()
        val checked = pairedBluetoothDevices.map { device ->
            TriggerSettings.normalizeAddress(device.address) in nextSelection
        }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_car_devices_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val address = TriggerSettings.normalizeAddress(pairedBluetoothDevices[which].address) ?: return@setMultiChoiceItems
                if (isChecked) nextSelection += address else nextSelection -= address
            }
            .setPositiveButton(R.string.bluetooth_select_done) { _, _ ->
                selectedBluetoothAddresses = nextSelection
                persistSelectedBluetoothDevices()
                updateBluetoothSummary()
                syncTrackRecording()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persistSettings() {
        TrackRecordingSettingsStore.saveTriggers(
            context = this,
            enabled = cbTrackRecordingEnabled.isChecked,
            triggerOnBluetoothSpeed = cbTrackTriggerBtSpeed.isChecked,
            triggerOnBluetoothDistance = cbTrackTriggerBtDistance.isChecked
        )
    }

    private fun persistSelectedBluetoothDevices() {
        val existing = TriggerSettings.load(this)
        val selectedBluetoothNames = selectedBluetoothAddresses.associateWith { address ->
            pairedBluetoothDevices.firstOrNull { device ->
                TriggerSettings.normalizeAddress(device.address) == address
            }?.name ?: existing.bluetoothNamesByAddress[address].orEmpty()
        }
        TriggerSettings.save(
            this,
            existing.copy(
                bluetoothAddresses = selectedBluetoothAddresses,
                bluetoothNamesByAddress = selectedBluetoothNames
            )
        )
        AutoTriggerController.sync(this, stopUnavailable = true)
    }

    private fun updateBluetoothSummary() {
        val selectedLabels = selectedBluetoothAddresses.map { address ->
            pairedBluetoothDevices.firstOrNull { device ->
                TriggerSettings.normalizeAddress(device.address) == address
            }?.label ?: address
        }
        tvBluetoothDeviceSummary.text = when {
            !BluetoothDeviceList.hasPermission(this) -> getString(R.string.bluetooth_permission_required)
            pairedBluetoothDevices.isEmpty() -> getString(R.string.bluetooth_no_paired_devices)
            selectedLabels.isEmpty() -> getString(R.string.bluetooth_selected_none)
            else -> getString(R.string.bluetooth_selected_summary, selectedLabels.joinToString("、"))
        }
    }

    private fun syncTrackRecording() {
        TrackRecordingController.sync(this, stopUnavailable = true)
        renderStatus()
    }

    private fun renderStatus() {
        val settings = TrackRecordingSettingsStore.load(this)
        tvTrackStatus.text = when {
            !settings.enabled -> getString(R.string.track_status_off)
            !settings.hasTrigger -> getString(R.string.track_status_no_trigger)
            settings.bluetoothAddresses.isEmpty() -> getString(R.string.track_status_no_bluetooth)
            TrackRecordingService.isRunning() -> getString(R.string.track_status_running)
            else -> getString(R.string.track_status_waiting)
        }
    }
}
