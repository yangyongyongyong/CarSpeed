package com.thomas.carspeed

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class SpeedHudActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnToggle: Button
    private lateinit var btnStatus: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOpacityLabel: TextView
    private lateinit var sbOpacity: SeekBar
    private lateinit var tvYAxisLabel: TextView
    private lateinit var sbYAxisMax: SeekBar
    private lateinit var spSpeedUnit: Spinner
    private lateinit var tvWarningLabel: TextView
    private lateinit var sbSpeedWarning: SeekBar

    private var running = false

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllRequiredPermissions()) {
                startOverlayService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_speed_hud)

        toolbar = findViewById(R.id.toolbar)
        btnToggle = findViewById(R.id.btnToggle)
        btnStatus = findViewById(R.id.btnStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvOpacityLabel = findViewById(R.id.tvOpacityLabel)
        sbOpacity = findViewById(R.id.sbOpacity)
        tvYAxisLabel = findViewById(R.id.tvYAxisLabel)
        sbYAxisMax = findViewById(R.id.sbYAxisMax)
        spSpeedUnit = findViewById(R.id.spSpeedUnit)
        tvWarningLabel = findViewById(R.id.tvWarningLabel)
        sbSpeedWarning = findViewById(R.id.sbSpeedWarning)

        toolbar.setNavigationOnClickListener { finish() }

        initOverlaySettings()

        btnStatus.setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }

        btnToggle.setOnClickListener {
            if (running) {
                stopOverlayService()
            } else {
                ensurePermissionsAndStart()
            }
        }

        syncUi()
    }

    override fun onResume() {
        super.onResume()
        syncUi()
    }

    private fun initOverlaySettings() {
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)

        val alphaPercent = prefs.getInt(MainActivity.PREF_BG_ALPHA_PERCENT, 0)
        sbOpacity.progress = alphaPercent
        tvOpacityLabel.text = getString(R.string.opacity_label_fmt, alphaPercent)

        val yMax = prefs.getInt(MainActivity.PREF_Y_AXIS_MAX_KMH, 300).coerceIn(30, 2000)
        sbYAxisMax.progress = yMax
        tvYAxisLabel.text = getString(R.string.y_axis_label_fmt, yMax)

        val unit = SpeedDisplay.normalizeUnit(prefs.getString(MainActivity.PREF_SPEED_UNIT, SpeedDisplay.UNIT_KMH))
        val unitAdapter = ArrayAdapter(this, R.layout.spinner_item, listOf("km/h", "mph"))
        unitAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spSpeedUnit.adapter = unitAdapter
        spSpeedUnit.setSelection(SpeedDisplay.unitIndex(unit))

        val warningKmh = prefs.getInt(
            MainActivity.PREF_SPEED_WARNING_KMH,
            SpeedDisplay.DEFAULT_WARNING_KMH
        ).coerceIn(30, 240)
        sbSpeedWarning.min = 30
        sbSpeedWarning.max = 240
        sbSpeedWarning.progress = warningKmh
        tvWarningLabel.text = SpeedDisplay.warningLabel(warningKmh, unit)

        spSpeedUnit.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val selectedUnit = SpeedDisplay.unitFromIndex(position)
                prefs.edit().putString(MainActivity.PREF_SPEED_UNIT, selectedUnit).apply()
                tvWarningLabel.text = SpeedDisplay.warningLabel(
                    sbSpeedWarning.progress.coerceIn(30, 240),
                    selectedUnit
                )
                if (running) notifyOverlayStyleChanged()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOpacityLabel.text = getString(R.string.opacity_label_fmt, progress)
                prefs.edit().putInt(MainActivity.PREF_BG_ALPHA_PERCENT, progress).apply()
                if (running) notifyOverlayStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        sbYAxisMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(30, 2000)
                tvYAxisLabel.text = getString(R.string.y_axis_label_fmt, fixed)
                prefs.edit().putInt(MainActivity.PREF_Y_AXIS_MAX_KMH, fixed).apply()
                if (running) notifyOverlayStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        sbSpeedWarning.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(30, 240)
                val selectedUnit = SpeedDisplay.unitFromIndex(spSpeedUnit.selectedItemPosition)
                tvWarningLabel.text = SpeedDisplay.warningLabel(fixed, selectedUnit)
                prefs.edit().putInt(MainActivity.PREF_SPEED_WARNING_KMH, fixed).apply()
                if (running) notifyOverlayStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun notifyOverlayStyleChanged() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_STYLE
        }
        startService(intent)
    }

    private fun ensurePermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (!hasAllRequiredPermissions()) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            requestPermissionsLauncher.launch(perms.toTypedArray())
            return
        }

        startOverlayService()
    }

    private fun hasAllRequiredPermissions(): Boolean {
        return OverlayController.hasLocationPermission(this) && OverlayController.hasNotificationPermission(this)
    }

    private fun startOverlayService() {
        running = OverlayController.start(this)
        syncUi()
    }

    private fun stopOverlayService() {
        OverlayController.stop(this)
        running = false
        syncUi()
    }

    private fun syncUi() {
        running = OverlayController.isRunning(this)
        if (running) {
            tvStatus.setText(R.string.status_running)
            btnToggle.setText(R.string.stop_overlay)
        } else {
            tvStatus.setText(R.string.status_idle)
            btnToggle.setText(R.string.start_overlay)
        }
    }
}
