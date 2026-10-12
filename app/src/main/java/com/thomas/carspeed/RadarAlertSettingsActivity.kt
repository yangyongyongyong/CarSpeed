package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
    private var updating = false

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

        toolbar.setNavigationOnClickListener { finish() }
        setupControls()
        loadSettings()
    }

    override fun onResume() {
        super.onResume()
        if (RadarAlertSettingsStore.load(this).enabled) RadarAlertService.sync(this)
        renderStatus()
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
        updating = false
        updateRadarThresholdLabel(settings.thresholdsSeconds)
        updateRadarLeadLabel(settings.leadSeconds)
        updateRadarSpeechRateLabel(settings.speechRatePercent)
        renderStatus()
    }

    private fun showRadarThresholdDialog() {
        val current = RadarAlertSettingsStore.formatThresholds(RadarAlertSettingsStore.load(this).thresholdsSeconds)
        val input = android.widget.EditText(this).apply {
            setText(current)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.radar_thresholds_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.radar_thresholds_title)
            .setView(input)
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
