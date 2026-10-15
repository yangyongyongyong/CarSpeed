package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RadarAlertSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvRadarStatus: TextView
    private lateinit var cbRadarAlert: CheckBox
    private lateinit var tvRadarThresholdsLabel: TextView
    private lateinit var btnRadarThresholds: Button
    private lateinit var tvRadarLeadLabel: TextView
    private lateinit var sbRadarLeadSeconds: SeekBar
    private lateinit var tvRadarSpeechRateLabel: TextView
    private lateinit var sbRadarSpeechRate: SeekBar
    private lateinit var cbPitchRoadLayer: CheckBox
    private lateinit var cbSatelliteTunnel: CheckBox
    private lateinit var cbUnknownRoadElevated: CheckBox
    private lateinit var cbRoadDivergenceCorrection: CheckBox
    private lateinit var tvRoadLayerStatus: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var updating = false
    private val statusRefresh = object : Runnable {
        override fun run() {
            renderStatus()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                saveEnabled(true)
                RadarAlertService.sync(this)
            } else {
                cbRadarAlert.isChecked = false
                saveEnabled(false)
                Toast.makeText(this, R.string.radar_location_permission_required, Toast.LENGTH_SHORT).show()
            }
            renderStatus()
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            if (cbRadarAlert.isChecked) {
                saveEnabled(true)
                RadarAlertService.sync(this)
            }
            renderStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_radar_alert_settings)
        toolbar = findViewById(R.id.toolbar)
        tvRadarStatus = findViewById(R.id.tvRadarStatus)
        cbRadarAlert = findViewById(R.id.cbRadarAlert)
        tvRadarThresholdsLabel = findViewById(R.id.tvRadarThresholdsLabel)
        btnRadarThresholds = findViewById(R.id.btnRadarThresholds)
        tvRadarLeadLabel = findViewById(R.id.tvRadarLeadLabel)
        sbRadarLeadSeconds = findViewById(R.id.sbRadarLeadSeconds)
        tvRadarSpeechRateLabel = findViewById(R.id.tvRadarSpeechRateLabel)
        sbRadarSpeechRate = findViewById(R.id.sbRadarSpeechRate)
        cbPitchRoadLayer = findViewById(R.id.cbPitchRoadLayer)
        cbSatelliteTunnel = findViewById(R.id.cbSatelliteTunnel)
        cbUnknownRoadElevated = findViewById(R.id.cbUnknownRoadElevated)
        cbRoadDivergenceCorrection = findViewById(R.id.cbRoadDivergenceCorrection)
        tvRoadLayerStatus = findViewById(R.id.tvRoadLayerStatus)

        toolbar.setNavigationOnClickListener { finish() }
        setupControls()
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        if (RadarAlertSettingsStore.load(this).enabled) RadarAlertService.sync(this)
        uiHandler.removeCallbacks(statusRefresh)
        uiHandler.post(statusRefresh)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun setupControls() {
        cbRadarAlert.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (checked && !hasLocationPermission()) {
                requestLocationPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                return@setOnCheckedChangeListener
            }
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            saveEnabled(checked)
            RadarAlertService.sync(this)
            renderStatus()
        }

        btnRadarThresholds.setOnClickListener { showRadarThresholdDialog() }

        sbRadarLeadSeconds.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating) return
                val fixed = progress.coerceIn(RadarAlertSettingsStore.MIN_LEAD_SECONDS, RadarAlertSettingsStore.MAX_LEAD_SECONDS)
                updateRadarLeadLabel(fixed)
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(RadarAlertSettingsStore.PREF_RADAR_ALERT_LEAD_SECONDS, fixed)
                    .apply()
                RadarAlertService.sync(this@RadarAlertSettingsActivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val fixed = (seekBar?.progress ?: sbRadarLeadSeconds.progress)
                    .coerceIn(RadarAlertSettingsStore.MIN_LEAD_SECONDS, RadarAlertSettingsStore.MAX_LEAD_SECONDS)
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(RadarAlertSettingsStore.PREF_RADAR_ALERT_LEAD_SECONDS, fixed)
                    .commit()
                RadarAlertService.sync(this@RadarAlertSettingsActivity)
            }
        })

        sbRadarSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating) return
                val fixed = progress.coerceIn(
                    RadarAlertSettingsStore.MIN_SPEECH_RATE_PERCENT,
                    RadarAlertSettingsStore.MAX_SPEECH_RATE_PERCENT
                )
                updateRadarSpeechRateLabel(fixed)
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(RadarAlertSettingsStore.PREF_RADAR_ALERT_SPEECH_RATE_PERCENT, fixed)
                    .apply()
                RadarAlertService.sync(this@RadarAlertSettingsActivity)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val fixed = (seekBar?.progress ?: sbRadarSpeechRate.progress).coerceIn(
                    RadarAlertSettingsStore.MIN_SPEECH_RATE_PERCENT,
                    RadarAlertSettingsStore.MAX_SPEECH_RATE_PERCENT
                )
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putInt(RadarAlertSettingsStore.PREF_RADAR_ALERT_SPEECH_RATE_PERCENT, fixed)
                    .commit()
                RadarAlertService.sync(this@RadarAlertSettingsActivity)
            }
        })

        cbPitchRoadLayer.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(RadarAlertSettingsStore.PREF_RADAR_PITCH_ROAD_LAYER, checked)
                .commit()
        }

        cbSatelliteTunnel.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(RadarAlertSettingsStore.PREF_RADAR_SATELLITE_TUNNEL, checked)
                .commit()
        }

        cbUnknownRoadElevated.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(RadarAlertSettingsStore.PREF_RADAR_UNKNOWN_ROAD_LAYER_ELEVATED, checked)
                .commit()
            RadarAlertController.onUnknownRoadDefaultChanged(checked)
            renderStatus()
        }

        cbRoadDivergenceCorrection.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ROAD_DIVERGENCE_CORRECTION, checked)
                .commit()
        }
    }

    private fun loadSettings() {
        val settings = RadarAlertSettingsStore.load(this)
        updating = true
        cbRadarAlert.isChecked = settings.enabled
        sbRadarLeadSeconds.min = RadarAlertSettingsStore.MIN_LEAD_SECONDS
        sbRadarLeadSeconds.max = RadarAlertSettingsStore.MAX_LEAD_SECONDS
        sbRadarLeadSeconds.progress = settings.leadSeconds
        sbRadarSpeechRate.min = RadarAlertSettingsStore.MIN_SPEECH_RATE_PERCENT
        sbRadarSpeechRate.max = RadarAlertSettingsStore.MAX_SPEECH_RATE_PERCENT
        sbRadarSpeechRate.progress = settings.speechRatePercent
        cbPitchRoadLayer.isChecked = settings.pitchRoadLayerEnabled
        cbSatelliteTunnel.isChecked = settings.satelliteTunnelEnabled
        cbUnknownRoadElevated.isChecked = settings.unknownRoadLayerElevated
        cbRoadDivergenceCorrection.isChecked = settings.roadDivergenceCorrectionEnabled
        updating = false
        updateRadarThresholdLabel(settings.thresholdsSeconds)
        updateRadarLeadLabel(settings.leadSeconds)
        updateRadarSpeechRateLabel(settings.speechRatePercent)
        renderStatus()
    }

    private fun showRadarThresholdDialog() {
        val current = RadarAlertSettingsStore.formatThresholds(RadarAlertSettingsStore.load(this).thresholdsSeconds)
        val content = layoutInflater.cloneInContext(
            android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_CarSpeed_Dialog)
        ).inflate(R.layout.dialog_text_field, null)
        val field = content.findViewById<TextInputLayout>(R.id.tilDialogInput)
        val input = content.findViewById<TextInputEditText>(R.id.etDialogInput)
        field.hint = getString(R.string.radar_thresholds_hint)
        input.setText(current)
        input.selectAll()
        input.inputType = InputType.TYPE_CLASS_TEXT
        AlertDialog.Builder(this)
            .setTitle(R.string.radar_thresholds_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val thresholds = RadarAlertSettingsStore.parseThresholds(input.text.toString())
                RadarAlertSettingsStore.saveThresholds(this, thresholds)
                updateRadarThresholdLabel(thresholds)
                RadarAlertService.sync(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveEnabled(enabled: Boolean) {
        getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, enabled)
            .commit()
    }

    private fun renderStatus() {
        val settings = RadarAlertSettingsStore.load(this)
        val state = RadarAlertController.currentState()
        tvRadarStatus.text = when {
            !settings.enabled -> getString(R.string.radar_status_off)
            state.active -> getString(R.string.radar_status_on)
            !hasLocationPermission() -> getString(R.string.radar_status_need_location)
            else -> getString(R.string.radar_status_waiting)
        }
        tvRoadLayerStatus.text = roadLayerStatusText(settings, state)
    }

    private fun roadLayerStatusText(settings: RadarAlertSettings, state: RadarAlertState): String {
        if (settings.enabled && state.active && state.alertRoadInTunnel && !state.roadLayerUsingDefault) {
            return getString(R.string.radar_road_layer_tunnel)
        }
        if (settings.enabled && state.active && !state.attitudeSensorsAvailable) {
            return getString(R.string.radar_road_layer_no_gyro)
        }
        if (settings.enabled && state.active && state.roadLayerCorrectedByDivergence && !state.roadLayerUsingDefault) {
            return getString(
                if (state.alertRoadLayerElevated) {
                    R.string.radar_road_layer_divergence_elevated
                } else {
                    R.string.radar_road_layer_divergence_ground
                }
            )
        }
        val elevated = if (settings.enabled && state.active) {
            state.alertRoadLayerElevated
        } else {
            settings.unknownRoadLayerElevated
        }
        val usingDefault = !state.active || state.roadLayerUsingDefault
        return when {
            usingDefault && elevated -> getString(R.string.radar_road_layer_default_elevated)
            usingDefault -> getString(R.string.radar_road_layer_default_ground)
            elevated -> getString(R.string.radar_road_layer_elevated)
            else -> getString(R.string.radar_road_layer_ground)
        }
    }

    private fun updateRadarThresholdLabel(thresholdsSeconds: List<Int>) {
        tvRadarThresholdsLabel.text = getString(
            R.string.radar_thresholds_label,
            RadarAlertSettingsStore.thresholdsLabel(thresholdsSeconds)
        )
    }

    private fun updateRadarLeadLabel(seconds: Int) {
        tvRadarLeadLabel.text = getString(R.string.radar_lead_label, seconds)
    }

    private fun updateRadarSpeechRateLabel(percent: Int) {
        tvRadarSpeechRateLabel.text = getString(R.string.radar_speech_rate_label, percent)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
