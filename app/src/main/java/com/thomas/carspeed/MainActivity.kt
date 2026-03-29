package com.thomas.carspeed

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var btnHistory: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOpacityLabel: TextView
    private lateinit var sbOpacity: SeekBar
    private lateinit var tvYAxisLabel: TextView
    private lateinit var sbYAxisMax: SeekBar

    private var running = false

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllRequiredPermissions()) {
                startOverlayService()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        btnHistory = findViewById(R.id.btnHistory)
        tvStatus = findViewById(R.id.tvStatus)
        tvOpacityLabel = findViewById(R.id.tvOpacityLabel)
        sbOpacity = findViewById(R.id.sbOpacity)
        tvYAxisLabel = findViewById(R.id.tvYAxisLabel)
        sbYAxisMax = findViewById(R.id.sbYAxisMax)

        initOverlaySettings()

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
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
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        val alphaPercent = prefs.getInt(PREF_BG_ALPHA_PERCENT, 0)
        sbOpacity.progress = alphaPercent
        tvOpacityLabel.text = getString(R.string.opacity_label_fmt, alphaPercent)

        val yMax = prefs.getInt(PREF_Y_AXIS_MAX_KMH, 300).coerceIn(30, 2000)
        sbYAxisMax.progress = yMax
        tvYAxisLabel.text = getString(R.string.y_axis_label_fmt, yMax)

        sbOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOpacityLabel.text = getString(R.string.opacity_label_fmt, progress)
                prefs.edit().putInt(PREF_BG_ALPHA_PERCENT, progress).apply()
                if (running) notifyOverlayStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        sbYAxisMax.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val fixed = progress.coerceIn(30, 2000)
                tvYAxisLabel.text = getString(R.string.y_axis_label_fmt, fixed)
                prefs.edit().putInt(PREF_Y_AXIS_MAX_KMH, fixed).apply()
                if (running) notifyOverlayStyleChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun notifyOverlayStyleChanged() {
        val intent = Intent(this@MainActivity, OverlayService::class.java).apply {
            action = OverlayService.ACTION_UPDATE_STYLE
        }
        startService(intent)
    }

    private fun ensurePermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
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
        val baseGranted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).all { p ->
            ContextCompat.checkSelfPermission(this, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return baseGranted && notifGranted
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        running = true
        syncUi()
    }

    private fun stopOverlayService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
        running = false
        syncUi()
    }

    private fun syncUi() {
        if (running) {
            tvStatus.setText(R.string.status_running)
            btnToggle.setText(R.string.stop_overlay)
        } else {
            tvStatus.setText(R.string.status_idle)
            btnToggle.setText(R.string.start_overlay)
        }
    }

    companion object {
        const val PREF_NAME = "overlay_prefs"
        const val PREF_BG_ALPHA_PERCENT = "overlay_bg_alpha_percent"
        const val PREF_Y_AXIS_MAX_KMH = "overlay_y_axis_max_kmh"
    }
}
