package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar

class DashcamSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var spVideoMode: Spinner
    private lateinit var tvLoopStorageLabel: TextView
    private lateinit var sbLoopStorage: SeekBar
    private lateinit var tvSegmentLabel: TextView
    private lateinit var sbSegmentMinutes: SeekBar
    private lateinit var cbParkingStop: CheckBox
    private lateinit var tvParkingStopLabel: TextView
    private lateinit var sbParkingStopMinutes: SeekBar
    private lateinit var tvZoomLabel: TextView
    private lateinit var sbZoom: SeekBar
    private lateinit var cbRecordAudio: CheckBox
    private lateinit var cbVoicePhoto: CheckBox
    private lateinit var cbAutoStartRecording: CheckBox
    private lateinit var cbAutoTrigger: CheckBox
    private lateinit var btnBluetoothDevices: Button
    private lateinit var tvBluetoothDeviceSummary: TextView
    private lateinit var cbWatermarkTime: CheckBox
    private lateinit var cbWatermarkSpeed: CheckBox
    private lateinit var cbWatermarkGps: CheckBox
    private lateinit var cbWatermarkAddress: CheckBox
    private lateinit var cbWatermarkVideoMode: CheckBox

    private var supportedModes: List<VideoMode> = emptyList()
    private var pairedBluetoothDevices: List<PairedBluetoothDevice> = emptyList()
    private var selectedBluetoothAddresses: MutableSet<String> = mutableSetOf()
    private var settingsLoaded = false

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                cbRecordAudio.isChecked = false
                cbVoicePhoto.isChecked = false
                persistSettings()
                Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
            }
            refreshDashcamIfRunning()
        }

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                cbAutoTrigger.isChecked = false
                persistSettings()
            }
            setupBluetoothDevices()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam_settings)

        toolbar = findViewById(R.id.toolbar)
        spVideoMode = findViewById(R.id.spVideoMode)
        tvLoopStorageLabel = findViewById(R.id.tvLoopStorageLabel)
        sbLoopStorage = findViewById(R.id.sbLoopStorage)
        tvSegmentLabel = findViewById(R.id.tvSegmentLabel)
        sbSegmentMinutes = findViewById(R.id.sbSegmentMinutes)
        cbParkingStop = findViewById(R.id.cbParkingStop)
        tvParkingStopLabel = findViewById(R.id.tvParkingStopLabel)
        sbParkingStopMinutes = findViewById(R.id.sbParkingStopMinutes)
        tvZoomLabel = findViewById(R.id.tvZoomLabel)
        sbZoom = findViewById(R.id.sbZoom)
        cbRecordAudio = findViewById(R.id.cbRecordAudio)
        cbVoicePhoto = findViewById(R.id.cbVoicePhoto)
        cbAutoStartRecording = findViewById(R.id.cbAutoStartRecording)
        cbAutoTrigger = findViewById(R.id.cbAutoTrigger)
        btnBluetoothDevices = findViewById(R.id.btnBluetoothDevices)
        tvBluetoothDeviceSummary = findViewById(R.id.tvBluetoothDeviceSummary)
        cbWatermarkTime = findViewById(R.id.cbWatermarkTime)
        cbWatermarkSpeed = findViewById(R.id.cbWatermarkSpeed)
        cbWatermarkGps = findViewById(R.id.cbWatermarkGps)
        cbWatermarkAddress = findViewById(R.id.cbWatermarkAddress)
        cbWatermarkVideoMode = findViewById(R.id.cbWatermarkVideoMode)

        toolbar.setNavigationOnClickListener { finish() }
        setupVideoModes()
        setupBluetoothDevices()
        btnBluetoothDevices.setOnClickListener { showBluetoothDevicePicker() }
        loadSettings()
        settingsLoaded = true
        setupCheckBoxes()
        setupStorageAndZoomControls()
    }

    private fun setupCheckBoxes() {
        listOf(
            cbRecordAudio,
            cbVoicePhoto,
            cbAutoStartRecording,
            cbParkingStop,
            cbAutoTrigger,
            cbWatermarkTime,
            cbWatermarkSpeed,
            cbWatermarkGps,
            cbWatermarkAddress,
            cbWatermarkVideoMode
        ).forEach { box ->
            box.setOnCheckedChangeListener { _, _ ->
                if ((box == cbVoicePhoto || box == cbRecordAudio) && box.isChecked && !hasAudioPermission()) {
                    requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (box == cbAutoTrigger && cbAutoTrigger.isChecked && !BluetoothDeviceList.hasPermission(this)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
                if (box == cbParkingStop) {
                    updateParkingStopControls()
                }
                persistSettings()
                refreshDashcamIfRunning()
            }
        }
    }

    private fun setupVideoModes() {
        supportedModes = CameraCapabilities.supportedVideoModes(this)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, supportedModes.map { it.label })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spVideoMode.adapter = adapter
        val selected = CameraCapabilities.selectedVideoMode(this)
        val selectedIndex = supportedModes.indexOfFirst {
            it.width == selected.width && it.height == selected.height && it.fps == selected.fps
        }.coerceAtLeast(0)
        spVideoMode.setSelection(selectedIndex)
        spVideoMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val mode = supportedModes.getOrNull(position) ?: return
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(DashcamSettings.PREF_VIDEO_QUALITY, mode.qualityKey)
                    .putInt(DashcamSettings.PREF_VIDEO_WIDTH, mode.width)
                    .putInt(DashcamSettings.PREF_VIDEO_HEIGHT, mode.height)
                    .putInt(DashcamSettings.PREF_VIDEO_FPS, mode.fps)
                    .apply()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupBluetoothDevices() {
        pairedBluetoothDevices = BluetoothDeviceList.pairedDevices(this)
        val saved = TriggerSettings.load(this)
        selectedBluetoothAddresses = saved.bluetoothAddresses
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
                if (isChecked) {
                    nextSelection += address
                } else {
                    nextSelection -= address
                }
            }
            .setPositiveButton(R.string.bluetooth_select_done) { _, _ ->
                selectedBluetoothAddresses = nextSelection
                persistSettings()
                updateBluetoothSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun setupStorageAndZoomControls() {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        val storageGb = prefs.getInt(
            DashcamSettings.PREF_LOOP_STORAGE_GB,
            DashcamSettings.DEFAULT_LOOP_STORAGE_GB
        ).coerceIn(DashcamSettings.MIN_LOOP_STORAGE_GB, DashcamSettings.MAX_LOOP_STORAGE_GB)
        sbLoopStorage.min = DashcamSettings.MIN_LOOP_STORAGE_GB
        sbLoopStorage.max = DashcamSettings.MAX_LOOP_STORAGE_GB
        sbLoopStorage.progress = storageGb
        tvLoopStorageLabel.text = getString(R.string.loop_storage_label, storageGb)

        val segmentMinutes = prefs.getInt(
            DashcamSettings.PREF_SEGMENT_MINUTES,
            DashcamSettings.DEFAULT_SEGMENT_MINUTES
        ).coerceIn(DashcamSettings.MIN_SEGMENT_MINUTES, DashcamSettings.MAX_SEGMENT_MINUTES)
        sbSegmentMinutes.min = DashcamSettings.MIN_SEGMENT_MINUTES
        sbSegmentMinutes.max = DashcamSettings.MAX_SEGMENT_MINUTES
        sbSegmentMinutes.progress = segmentMinutes
        tvSegmentLabel.text = getString(R.string.segment_duration_label, segmentMinutes)

        val parkingStopMinutes = prefs.getInt(
            DashcamSettings.PREF_PARKING_STOP_MINUTES,
            DashcamSettings.DEFAULT_PARKING_STOP_MINUTES
        ).coerceIn(DashcamSettings.MIN_PARKING_STOP_MINUTES, DashcamSettings.MAX_PARKING_STOP_MINUTES)
        sbParkingStopMinutes.min = DashcamSettings.MIN_PARKING_STOP_MINUTES
        sbParkingStopMinutes.max = DashcamSettings.MAX_PARKING_STOP_MINUTES
        sbParkingStopMinutes.progress = parkingStopMinutes
        tvParkingStopLabel.text = getString(R.string.parking_stop_label, parkingStopMinutes)
        updateParkingStopControls()

        val zoom = prefs.getInt(DashcamSettings.PREF_ZOOM_PERCENT, DashcamSettings.DEFAULT_ZOOM_PERCENT).coerceIn(10, 100)
        sbZoom.min = 10
        sbZoom.max = 100
        sbZoom.progress = zoom
        tvZoomLabel.text = getString(R.string.zoom_label, zoom)

        sbLoopStorage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(DashcamSettings.MIN_LOOP_STORAGE_GB, DashcamSettings.MAX_LOOP_STORAGE_GB)
                tvLoopStorageLabel.text = getString(R.string.loop_storage_label, fixed)
                prefs.edit().putInt(DashcamSettings.PREF_LOOP_STORAGE_GB, fixed).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        sbSegmentMinutes.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(DashcamSettings.MIN_SEGMENT_MINUTES, DashcamSettings.MAX_SEGMENT_MINUTES)
                tvSegmentLabel.text = getString(R.string.segment_duration_label, fixed)
                prefs.edit().putInt(DashcamSettings.PREF_SEGMENT_MINUTES, fixed).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        sbParkingStopMinutes.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(
                    DashcamSettings.MIN_PARKING_STOP_MINUTES,
                    DashcamSettings.MAX_PARKING_STOP_MINUTES
                )
                tvParkingStopLabel.text = getString(R.string.parking_stop_label, fixed)
                prefs.edit().putInt(DashcamSettings.PREF_PARKING_STOP_MINUTES, fixed).apply()
                refreshDashcamIfRunning()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(10, 100)
                tvZoomLabel.text = getString(R.string.zoom_label, fixed)
                prefs.edit().putInt(DashcamSettings.PREF_ZOOM_PERCENT, fixed).apply()
                refreshDashcamIfRunning()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun loadSettings() {
        val settings = DashcamSettings.load(this)
        cbRecordAudio.isChecked = settings.recordAudio
        cbWatermarkTime.isChecked = settings.showTime
        cbWatermarkSpeed.isChecked = settings.showSpeed
        cbWatermarkGps.isChecked = settings.showGps
        cbWatermarkAddress.isChecked = settings.showAddress
        cbWatermarkVideoMode.isChecked = settings.showVideoMode
        cbVoicePhoto.isChecked = settings.voicePhotoEnabled
        cbAutoStartRecording.isChecked = settings.autoStartRecording
        cbParkingStop.isChecked = settings.parkingStopEnabled
        cbAutoTrigger.isChecked = TriggerSettings.load(this).enabled
    }

    private fun updateParkingStopControls() {
        sbParkingStopMinutes.isEnabled = cbParkingStop.isChecked
        tvParkingStopLabel.isEnabled = cbParkingStop.isChecked
    }

    private fun persistSettings() {
        val previousNames = TriggerSettings.load(this).bluetoothNamesByAddress
        val selectedBluetoothNames = selectedBluetoothAddresses.associateWith { address ->
            pairedBluetoothDevices.firstOrNull { device ->
                TriggerSettings.normalizeAddress(device.address) == address
            }?.name ?: previousNames[address].orEmpty()
        }
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(DashcamSettings.PREF_WATERMARK_TIME, cbWatermarkTime.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_SPEED, cbWatermarkSpeed.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_GPS, cbWatermarkGps.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_ADDRESS, cbWatermarkAddress.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_VIDEO_MODE, cbWatermarkVideoMode.isChecked)
            .putBoolean(DashcamSettings.PREF_RECORD_AUDIO, cbRecordAudio.isChecked)
            .putBoolean(DashcamSettings.PREF_VOICE_PHOTO, cbVoicePhoto.isChecked)
            .putBoolean(DashcamSettings.PREF_AUTO_START_RECORDING, cbAutoStartRecording.isChecked)
            .putBoolean(DashcamSettings.PREF_PARKING_STOP_ENABLED, cbParkingStop.isChecked)
            .apply()
        TriggerSettings.save(
            this,
            AutoTriggerSettings(
                enabled = cbAutoTrigger.isChecked,
                bluetoothAddresses = selectedBluetoothAddresses,
                bluetoothNamesByAddress = selectedBluetoothNames
            )
        )
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshDashcamIfRunning() {
        if (DashcamService.instance != null) {
            DashcamService.refreshSettings(this)
        }
    }
}
