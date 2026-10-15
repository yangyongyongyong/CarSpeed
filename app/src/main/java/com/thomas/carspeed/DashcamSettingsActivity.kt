package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
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
    private lateinit var spVideoResolution: Spinner
    private lateinit var spVideoFps: Spinner
    private lateinit var spHdrMode: Spinner
    private lateinit var tvHdrStartLabel: TextView
    private lateinit var sbHdrStartHour: SeekBar
    private lateinit var tvHdrEndLabel: TextView
    private lateinit var sbHdrEndHour: SeekBar
    private lateinit var tvLoopStorageLabel: TextView
    private lateinit var sbLoopStorage: SeekBar
    private lateinit var tvSegmentLabel: TextView
    private lateinit var sbSegmentMinutes: SeekBar
    private lateinit var cbParkingStop: CheckBox
    private lateinit var tvParkingStopLabel: TextView
    private lateinit var sbParkingStopMinutes: SeekBar
    private lateinit var cbRecordAudio: CheckBox
    private lateinit var cbCueSounds: CheckBox
    private lateinit var tvCueVolumeLabel: TextView
    private lateinit var sbCueVolume: SeekBar
    private lateinit var cbVoicePhoto: CheckBox
    private lateinit var cbAutoStartRecording: CheckBox
    private lateinit var cbAutoTriggerStartOnAmap: CheckBox
    private lateinit var btnAmapWatchGrant: com.google.android.material.button.MaterialButton
    private lateinit var cbAutoTriggerStartOnConnect: CheckBox
    private lateinit var cbAutoTriggerStartOnBluetoothSpeed: CheckBox
    private lateinit var cbAutoTriggerStopOnPowerDisconnected: CheckBox
    private lateinit var cbAutoTriggerStopOnBluetoothDisconnected: CheckBox
    private lateinit var cbAutoTriggerStopOnBluetoothStationary: CheckBox
    private lateinit var btnBluetoothDevices: Button
    private lateinit var tvBluetoothDeviceSummary: TextView
    private lateinit var cbWatermarkTime: CheckBox
    private lateinit var cbWatermarkSpeed: CheckBox
    private lateinit var cbWatermarkStationaryWait: CheckBox
    private lateinit var cbWatermarkGps: CheckBox
    private lateinit var cbWatermarkSatellites: CheckBox
    private lateinit var cbWatermarkRadar: CheckBox
    private lateinit var cbWatermarkAddress: CheckBox
    private lateinit var cbWatermarkRoadLayer: CheckBox
    private lateinit var cbWatermarkVideoMode: CheckBox
    private lateinit var cbWatermarkFocus: CheckBox
    private lateinit var cbPlateRecognition: CheckBox
    private lateinit var sectionVideoHeader: TextView
    private lateinit var sectionVideoContent: LinearLayout
    private lateinit var sectionTriggerHeader: TextView
    private lateinit var sectionTriggerContent: LinearLayout
    private lateinit var sectionPlateRecognitionHeader: TextView
    private lateinit var sectionPlateRecognitionContent: LinearLayout
    private lateinit var sectionWatermarkHeader: TextView
    private lateinit var sectionWatermarkContent: LinearLayout

    private var resolutionOptions: List<VideoResolution> = emptyList()
    private var fpsOptions: List<Int> = emptyList()
    private var pairedBluetoothDevices: List<PairedBluetoothDevice> = emptyList()
    private var selectedBluetoothAddresses: MutableSet<String> = mutableSetOf()
    private var updatingVideoSpinners = false
    private var updatingHdrControls = false

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
                bluetoothTriggerBoxes().forEach { it.isChecked = false }
                persistSettings()
            }
            setupBluetoothDevices()
            AutoTriggerController.sync(this, stopUnavailable = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam_settings)

        toolbar = findViewById(R.id.toolbar)
        spVideoResolution = findViewById(R.id.spVideoResolution)
        spVideoFps = findViewById(R.id.spVideoFps)
        spHdrMode = findViewById(R.id.spHdrMode)
        tvHdrStartLabel = findViewById(R.id.tvHdrStartLabel)
        sbHdrStartHour = findViewById(R.id.sbHdrStartHour)
        tvHdrEndLabel = findViewById(R.id.tvHdrEndLabel)
        sbHdrEndHour = findViewById(R.id.sbHdrEndHour)
        tvLoopStorageLabel = findViewById(R.id.tvLoopStorageLabel)
        sbLoopStorage = findViewById(R.id.sbLoopStorage)
        tvSegmentLabel = findViewById(R.id.tvSegmentLabel)
        sbSegmentMinutes = findViewById(R.id.sbSegmentMinutes)
        cbParkingStop = findViewById(R.id.cbParkingStop)
        tvParkingStopLabel = findViewById(R.id.tvParkingStopLabel)
        sbParkingStopMinutes = findViewById(R.id.sbParkingStopMinutes)
        cbRecordAudio = findViewById(R.id.cbRecordAudio)
        cbCueSounds = findViewById(R.id.cbCueSounds)
        tvCueVolumeLabel = findViewById(R.id.tvCueVolumeLabel)
        sbCueVolume = findViewById(R.id.sbCueVolume)
        cbVoicePhoto = findViewById(R.id.cbVoicePhoto)
        cbAutoStartRecording = findViewById(R.id.cbAutoStartRecording)
        cbAutoTriggerStartOnAmap = findViewById(R.id.cbAutoTriggerStartOnAmap)
        btnAmapWatchGrant = findViewById(R.id.btnAmapWatchGrant)
        cbAutoTriggerStartOnConnect = findViewById(R.id.cbAutoTriggerStartOnConnect)
        cbAutoTriggerStartOnBluetoothSpeed = findViewById(R.id.cbAutoTriggerStartOnBluetoothSpeed)
        cbAutoTriggerStopOnPowerDisconnected = findViewById(R.id.cbAutoTriggerStopOnPowerDisconnected)
        cbAutoTriggerStopOnBluetoothDisconnected = findViewById(R.id.cbAutoTriggerStopOnBluetoothDisconnected)
        cbAutoTriggerStopOnBluetoothStationary = findViewById(R.id.cbAutoTriggerStopOnBluetoothStationary)
        btnBluetoothDevices = findViewById(R.id.btnBluetoothDevices)
        tvBluetoothDeviceSummary = findViewById(R.id.tvBluetoothDeviceSummary)
        cbWatermarkTime = findViewById(R.id.cbWatermarkTime)
        cbWatermarkSpeed = findViewById(R.id.cbWatermarkSpeed)
        cbWatermarkStationaryWait = findViewById(R.id.cbWatermarkStationaryWait)
        cbWatermarkGps = findViewById(R.id.cbWatermarkGps)
        cbWatermarkSatellites = findViewById(R.id.cbWatermarkSatellites)
        cbWatermarkRadar = findViewById(R.id.cbWatermarkRadar)
        cbWatermarkAddress = findViewById(R.id.cbWatermarkAddress)
        cbWatermarkRoadLayer = findViewById(R.id.cbWatermarkRoadLayer)
        cbWatermarkVideoMode = findViewById(R.id.cbWatermarkVideoMode)
        cbWatermarkFocus = findViewById(R.id.cbWatermarkFocus)
        cbPlateRecognition = findViewById(R.id.cbPlateRecognition)
        sectionVideoHeader = findViewById(R.id.sectionVideoHeader)
        sectionVideoContent = findViewById(R.id.sectionVideoContent)
        sectionTriggerHeader = findViewById(R.id.sectionTriggerHeader)
        sectionTriggerContent = findViewById(R.id.sectionTriggerContent)
        sectionPlateRecognitionHeader = findViewById(R.id.sectionPlateRecognitionHeader)
        sectionPlateRecognitionContent = findViewById(R.id.sectionPlateRecognitionContent)
        sectionWatermarkHeader = findViewById(R.id.sectionWatermarkHeader)
        sectionWatermarkContent = findViewById(R.id.sectionWatermarkContent)

        toolbar.setNavigationOnClickListener { finish() }
        setupVideoModes()
        setupHdrControls()
        setupBluetoothDevices()
        btnBluetoothDevices.setOnClickListener { showBluetoothDevicePicker() }
        btnAmapWatchGrant.setOnClickListener { AmapWatchAccess.openNextGrant(this) }
        loadSettings()
        setupCheckBoxes()
        setupStorageAndZoomControls()
        setupCollapsibleSections()
    }

    private fun setupCheckBoxes() {
        listOf(
            cbRecordAudio,
            cbCueSounds,
            cbVoicePhoto,
            cbAutoStartRecording,
            cbAutoTriggerStartOnAmap,
            cbParkingStop,
            cbAutoTriggerStartOnConnect,
            cbAutoTriggerStartOnBluetoothSpeed,
            cbAutoTriggerStopOnPowerDisconnected,
            cbAutoTriggerStopOnBluetoothDisconnected,
            cbAutoTriggerStopOnBluetoothStationary,
            cbWatermarkTime,
            cbWatermarkSpeed,
            cbWatermarkStationaryWait,
            cbWatermarkGps,
            cbWatermarkSatellites,
            cbWatermarkRadar,
            cbWatermarkAddress,
            cbWatermarkRoadLayer,
            cbWatermarkVideoMode,
            cbWatermarkFocus,
            cbPlateRecognition
        ).forEach { box ->
            box.setOnCheckedChangeListener { _, _ ->
                if ((box == cbVoicePhoto || box == cbRecordAudio) && box.isChecked && !hasAudioPermission()) {
                    requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                if (box in bluetoothTriggerBoxes() && box.isChecked && !BluetoothDeviceList.hasPermission(this)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
                if (box == cbParkingStop) {
                    updateParkingStopControls()
                }
                if (box == cbCueSounds) {
                    updateCueVolumeControls()
                }
                if (box in bluetoothTriggerBoxes()) {
                    updateAutoTriggerControls()
                }
                if (box == cbAutoTriggerStartOnAmap) {
                    updateAmapWatchGrant()
                    if (box.isChecked) AmapWatchAccess.openNextGrant(this)
                }
                persistSettings()
                refreshDashcamIfRunning()
            }
        }
    }

    private fun setupVideoModes() {
        resolutionOptions = CameraCapabilities.resolutionOptions(this)
        val resolutionAdapter = ArrayAdapter(this, R.layout.spinner_item, resolutionOptions.map { resolutionLabel(it) })
        resolutionAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spVideoResolution.adapter = resolutionAdapter

        val selected = CameraCapabilities.selectedVideoMode(this)
        var selectedResolutionIndex = resolutionOptions.indexOfFirst { it.width == selected.width && it.height == selected.height }
        if (selectedResolutionIndex < 0) selectedResolutionIndex = 0
        spVideoResolution.setSelection(selectedResolutionIndex)
        updateFpsOptions(resolutionOptions.getOrNull(selectedResolutionIndex), selected.fps)

        spVideoResolution.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (updatingVideoSpinners) return
                val resolution = resolutionOptions.getOrNull(position) ?: return
                val currentFps = selectedFpsFromPrefs()
                updateFpsOptions(resolution, currentFps)
                persistVideoMode(resolution, fpsOptions.getOrNull(spVideoFps.selectedItemPosition) ?: currentFps)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        spVideoFps.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (updatingVideoSpinners) return
                val resolution = resolutionOptions.getOrNull(spVideoResolution.selectedItemPosition) ?: return
                val fps = fpsOptions.getOrNull(position) ?: return
                persistVideoMode(resolution, fps)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupHdrControls() {
        val items = listOf(
            getString(R.string.hdr_mode_off),
            getString(R.string.hdr_mode_always),
            getString(R.string.hdr_mode_schedule)
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spHdrMode.adapter = adapter
        syncHdrControlsFromPrefs()

        spHdrMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (updatingHdrControls) return
                saveHdrControls()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateHdrLabels()
                if (fromUser && !updatingHdrControls) saveHdrControls()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!updatingHdrControls) saveHdrControls()
            }
        }
        sbHdrStartHour.setOnSeekBarChangeListener(listener)
        sbHdrEndHour.setOnSeekBarChangeListener(listener)
    }

    private fun syncHdrControlsFromPrefs() {
        updatingHdrControls = true
        val settings = DashcamHdrSettings.load(this)
        spHdrMode.setSelection(settings.mode.coerceIn(DashcamSettings.HDR_MODE_OFF, DashcamSettings.HDR_MODE_SCHEDULE))
        sbHdrStartHour.progress = settings.startMinute / 60
        sbHdrEndHour.progress = settings.endMinute / 60
        updatingHdrControls = false
        updateHdrLabels()
    }

    private fun saveHdrControls() {
        DashcamHdrSettings.save(
            this,
            HdrSettings(
                mode = spHdrMode.selectedItemPosition.coerceIn(DashcamSettings.HDR_MODE_OFF, DashcamSettings.HDR_MODE_SCHEDULE),
                startMinute = sbHdrStartHour.progress.coerceIn(0, 23) * 60,
                endMinute = sbHdrEndHour.progress.coerceIn(0, 23) * 60
            )
        )
        updateHdrLabels()
        refreshDashcamIfRunning()
    }

    private fun updateHdrLabels() {
        val scheduleEnabled = spHdrMode.selectedItemPosition == DashcamSettings.HDR_MODE_SCHEDULE
        sbHdrStartHour.isEnabled = scheduleEnabled
        sbHdrEndHour.isEnabled = scheduleEnabled
        tvHdrStartLabel.isEnabled = scheduleEnabled
        tvHdrEndLabel.isEnabled = scheduleEnabled
        tvHdrStartLabel.text = getString(
            R.string.hdr_start_label,
            DashcamHdrSettings.formatMinute(sbHdrStartHour.progress.coerceIn(0, 23) * 60)
        )
        tvHdrEndLabel.text = getString(
            R.string.hdr_end_label,
            DashcamHdrSettings.formatMinute(sbHdrEndHour.progress.coerceIn(0, 23) * 60)
        )
    }

    private fun updateFpsOptions(resolution: VideoResolution?, preferredFps: Int) {
        val selectedResolution = resolution ?: return
        updatingVideoSpinners = true
        fpsOptions = CameraCapabilities.fpsOptions(this, selectedResolution.width, selectedResolution.height)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, fpsOptions.map { "${it}fps" })
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spVideoFps.adapter = adapter
        var selectedIndex = fpsOptions.indexOf(preferredFps)
        if (selectedIndex < 0) selectedIndex = fpsOptions.indexOf(30).takeIf { it >= 0 } ?: 0
        spVideoFps.setSelection(selectedIndex)
        updatingVideoSpinners = false
    }

    private fun resolutionLabel(resolution: VideoResolution): String {
        val prefix = when {
            resolution.width == 3840 && resolution.height == 2160 -> "4K"
            resolution.width == 1920 && resolution.height == 1080 -> "1080p"
            resolution.width == 1280 && resolution.height == 720 -> "720p"
            resolution.width == 720 && resolution.height == 480 -> "480p"
            is8k(resolution.width, resolution.height) -> "8K"
            else -> "录像"
        }
        return "$prefix：${resolution.width}x${resolution.height}"
    }

    private fun is8k(width: Int, height: Int): Boolean {
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return longEdge >= 7600 && shortEdge >= 4200
    }

    private fun selectedFpsFromPrefs(): Int {
        return getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .getInt(DashcamSettings.PREF_VIDEO_FPS, 30)
    }

    private fun persistVideoMode(resolution: VideoResolution, fps: Int) {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(DashcamSettings.PREF_VIDEO_QUALITY, resolution.qualityKey)
            .putInt(DashcamSettings.PREF_VIDEO_WIDTH, resolution.width)
            .putInt(DashcamSettings.PREF_VIDEO_HEIGHT, resolution.height)
            .putInt(DashcamSettings.PREF_VIDEO_FPS, fps)
            .apply()
    }

    private fun setupBluetoothDevices() {
        pairedBluetoothDevices = BluetoothDeviceList.pairedDevices(this)
        val saved = TriggerSettings.load(this)
        selectedBluetoothAddresses = saved.bluetoothAddresses
            .mapNotNull { TriggerSettings.normalizeAddress(it) }
            .toMutableSet()
        btnBluetoothDevices.isEnabled = BluetoothDeviceList.hasPermission(this) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        updateBluetoothSummary()
        updateAutoTriggerControls()
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

        val cueVolume = prefs.getInt(
            DashcamSettings.PREF_CUE_VOLUME_PERCENT,
            DashcamSettings.DEFAULT_CUE_VOLUME_PERCENT
        ).coerceIn(DashcamSettings.MIN_CUE_VOLUME_PERCENT, DashcamSettings.MAX_CUE_VOLUME_PERCENT)
        sbCueVolume.min = DashcamSettings.MIN_CUE_VOLUME_PERCENT
        sbCueVolume.max = DashcamSettings.MAX_CUE_VOLUME_PERCENT
        sbCueVolume.progress = cueVolume
        tvCueVolumeLabel.text = getString(R.string.cue_volume_label, cueVolume)
        updateCueVolumeControls()
        sbCueVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(
                    DashcamSettings.MIN_CUE_VOLUME_PERCENT,
                    DashcamSettings.MAX_CUE_VOLUME_PERCENT
                )
                tvCueVolumeLabel.text = getString(R.string.cue_volume_label, fixed)
                prefs.edit().putInt(DashcamSettings.PREF_CUE_VOLUME_PERCENT, fixed).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (cbCueSounds.isChecked && sbCueVolume.progress > 0) {
                    PhotoShutterSound.play(this@DashcamSettingsActivity)
                }
            }
        })

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
    }

    private fun loadSettings() {
        val settings = DashcamSettings.load(this)
        val triggerSettings = TriggerSettings.load(this)
        cbRecordAudio.isChecked = settings.recordAudio
        cbCueSounds.isChecked = settings.cueSoundsEnabled
        cbWatermarkTime.isChecked = settings.showTime
        cbWatermarkSpeed.isChecked = settings.showSpeed
        cbWatermarkStationaryWait.isChecked = settings.showStationaryWait
        cbWatermarkGps.isChecked = settings.showGps
        cbWatermarkSatellites.isChecked = settings.showSatellites
        cbWatermarkRadar.isChecked = settings.showRadarSummary
        cbWatermarkAddress.isChecked = settings.showAddress
        cbWatermarkRoadLayer.isChecked = settings.showRoadLayer
        cbWatermarkVideoMode.isChecked = settings.showVideoMode
        cbWatermarkFocus.isChecked = settings.showFocus
        cbPlateRecognition.isChecked = settings.plateRecognitionEnabled
        cbVoicePhoto.isChecked = settings.voicePhotoEnabled
        cbAutoStartRecording.isChecked = settings.autoStartRecording
        cbAutoTriggerStartOnAmap.isChecked = triggerSettings.startOnAmap
        updateAmapWatchGrant()
        cbParkingStop.isChecked = settings.parkingStopEnabled
        cbAutoTriggerStartOnConnect.isChecked = triggerSettings.startOnBluetoothConnected
        cbAutoTriggerStartOnBluetoothSpeed.isChecked = triggerSettings.startOnBluetoothConnectedAndSpeed
        cbAutoTriggerStopOnPowerDisconnected.isChecked = triggerSettings.stopOnPowerDisconnected
        cbAutoTriggerStopOnBluetoothDisconnected.isChecked = triggerSettings.stopOnBluetoothDisconnected
        cbAutoTriggerStopOnBluetoothStationary.isChecked = triggerSettings.stopOnBluetoothDisconnectedAndStationary
        updateAutoTriggerControls()
    }

    private fun updateCueVolumeControls() {
        val enabled = cbCueSounds.isChecked
        sbCueVolume.isEnabled = enabled
        tvCueVolumeLabel.isEnabled = enabled
    }

    private fun updateParkingStopControls() {
        sbParkingStopMinutes.isEnabled = cbParkingStop.isChecked
        tvParkingStopLabel.isEnabled = cbParkingStop.isChecked
    }

    private fun updateAmapWatchGrant() {
        val ready = AmapWatchAccess.missing(this) == null
        btnAmapWatchGrant.isEnabled = cbAutoTriggerStartOnAmap.isChecked && !ready
        btnAmapWatchGrant.setText(if (ready) R.string.amap_watch_ready else R.string.amap_watch_grant)
    }

    override fun onResume() {
        super.onResume()
        if (::btnAmapWatchGrant.isInitialized) updateAmapWatchGrant()
    }

    private fun updateAutoTriggerControls() {
        val bluetoothEnabled = bluetoothTriggerBoxes().any { it.isChecked }
        btnBluetoothDevices.isEnabled = bluetoothEnabled && (BluetoothDeviceList.hasPermission(this) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        tvBluetoothDeviceSummary.isEnabled = bluetoothEnabled
    }

    private fun bluetoothTriggerBoxes(): List<CheckBox> {
        return listOf(
            cbAutoTriggerStartOnConnect,
            cbAutoTriggerStartOnBluetoothSpeed,
            cbAutoTriggerStopOnBluetoothDisconnected,
            cbAutoTriggerStopOnBluetoothStationary
        )
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
            .putBoolean(DashcamSettings.PREF_WATERMARK_STATIONARY_WAIT, cbWatermarkStationaryWait.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_GPS, cbWatermarkGps.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_SATELLITES, cbWatermarkSatellites.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_RADAR, cbWatermarkRadar.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_ADDRESS, cbWatermarkAddress.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_ROAD_LAYER, cbWatermarkRoadLayer.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_VIDEO_MODE, cbWatermarkVideoMode.isChecked)
            .putBoolean(DashcamSettings.PREF_WATERMARK_FOCUS, cbWatermarkFocus.isChecked)
            .putBoolean(DashcamSettings.PREF_PLATE_RECOGNITION_ENABLED, cbPlateRecognition.isChecked)
            .putBoolean(DashcamSettings.PREF_RECORD_AUDIO, cbRecordAudio.isChecked)
            .putBoolean(DashcamSettings.PREF_CUE_SOUNDS, cbCueSounds.isChecked)
            .putBoolean(DashcamSettings.PREF_VOICE_PHOTO, cbVoicePhoto.isChecked)
            .putBoolean(DashcamSettings.PREF_AUTO_START_RECORDING, cbAutoStartRecording.isChecked)
            .putBoolean(DashcamSettings.PREF_PARKING_STOP_ENABLED, cbParkingStop.isChecked)
            .commit()
        TriggerSettings.save(
            this,
            AutoTriggerSettings(
                bluetoothAddresses = selectedBluetoothAddresses,
                bluetoothNamesByAddress = selectedBluetoothNames,
                startOnAppOpen = cbAutoStartRecording.isChecked,
                startOnAmap = cbAutoTriggerStartOnAmap.isChecked,
                startOnBluetoothConnected = cbAutoTriggerStartOnConnect.isChecked,
                startOnBluetoothConnectedAndSpeed = cbAutoTriggerStartOnBluetoothSpeed.isChecked,
                stopOnPowerDisconnected = cbAutoTriggerStopOnPowerDisconnected.isChecked,
                stopOnBluetoothDisconnected = cbAutoTriggerStopOnBluetoothDisconnected.isChecked,
                stopOnBluetoothDisconnectedAndStationary = cbAutoTriggerStopOnBluetoothStationary.isChecked
            )
        )
        AutoTriggerController.sync(this, stopUnavailable = true)
    }

    private fun setupCollapsibleSections() {
        setupSection(sectionVideoHeader, sectionVideoContent, PREF_SECTION_VIDEO, defaultExpanded = true)
        setupSection(sectionTriggerHeader, sectionTriggerContent, PREF_SECTION_TRIGGER, defaultExpanded = true)
        setupSection(sectionPlateRecognitionHeader, sectionPlateRecognitionContent, PREF_SECTION_PLATE_RECOGNITION, defaultExpanded = false)
        setupSection(sectionWatermarkHeader, sectionWatermarkContent, PREF_SECTION_WATERMARK, defaultExpanded = false)
    }

    private fun setupSection(header: TextView, content: LinearLayout, prefKey: String, defaultExpanded: Boolean) {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
        val title = header.text.toString().removePrefix("▼ ").removePrefix("▶ ")
        fun render(expanded: Boolean) {
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            header.text = getString(
                if (expanded) R.string.settings_section_collapse_fmt else R.string.settings_section_expand_fmt,
                title
            )
        }
        var expanded = prefs.getBoolean(prefKey, defaultExpanded)
        render(expanded)
        header.setOnClickListener {
            expanded = !expanded
            prefs.edit().putBoolean(prefKey, expanded).apply()
            render(expanded)
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshDashcamIfRunning() {
        if (DashcamService.instance != null) {
            DashcamService.refreshSettings(this)
        }
    }

    companion object {
        private const val PREF_SECTION_VIDEO = "dashcam_settings_section_video"
        private const val PREF_SECTION_TRIGGER = "dashcam_settings_section_trigger"
        private const val PREF_SECTION_PLATE_RECOGNITION = "dashcam_settings_section_plate_recognition"
        private const val PREF_SECTION_WATERMARK = "dashcam_settings_section_watermark"
    }
}
