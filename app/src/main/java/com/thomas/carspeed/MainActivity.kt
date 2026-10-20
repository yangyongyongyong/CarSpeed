package com.thomas.carspeed

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnCamera: Button
    private lateinit var btnHud: Button
    private lateinit var btnHistory: Button
    private lateinit var btnStatus: Button
    private lateinit var btnRadar: Button
    private lateinit var btnTrack: Button
    private lateinit var btnTripStats: Button
    private var lastAutoStartAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCamera = findViewById(R.id.btnCamera)
        btnHud = findViewById(R.id.btnHud)
        btnHistory = findViewById(R.id.btnHistory)
        btnStatus = findViewById(R.id.btnStatus)
        btnRadar = findViewById(R.id.btnRadar)
        btnTrack = findViewById(R.id.btnTrack)
        btnTripStats = findViewById(R.id.btnTripStats)

        btnCamera.setOnClickListener {
            startActivity(Intent(this, CameraRecordActivity::class.java))
        }

        btnHud.setOnClickListener {
            startActivity(Intent(this, SpeedHudActivity::class.java))
        }

        btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        btnStatus.setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }

        btnRadar.setOnClickListener {
            startActivity(Intent(this, RadarAlertSettingsActivity::class.java))
        }

        btnTrack.setOnClickListener {
            startActivity(Intent(this, TrackRecordingSettingsActivity::class.java))
        }

        btnTripStats.setOnClickListener {
            startActivity(Intent(this, TripStatsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        RadarAlertService.sync(this)
        TrackRecordingController.sync(this, stopUnavailable = true)
        maybeAutoStartRecording()
    }

    private fun maybeAutoStartRecording() {
        val settings = DashcamSettings.load(this)
        if (!settings.autoStartRecording) return
        if (DashcamService.isRecording(this)) return
        val now = System.currentTimeMillis()
        if (now - lastAutoStartAtMs < AUTO_START_THROTTLE_MS) return
        lastAutoStartAtMs = now
        startActivity(
            Intent(this, CameraRecordActivity::class.java).apply {
                putExtra(CameraRecordActivity.EXTRA_AUTO_START_RECORDING, true)
            }
        )
    }

    companion object {
        const val PREF_NAME = "overlay_prefs"
        const val PREF_BG_ALPHA_PERCENT = "overlay_bg_alpha_percent"
        const val PREF_Y_AXIS_MAX_KMH = "overlay_y_axis_max_kmh"
        const val PREF_SPEED_UNIT = "overlay_speed_unit"
        const val PREF_SPEED_WARNING_KMH = "overlay_speed_warning_kmh"
        private const val AUTO_START_THROTTLE_MS = 30_000L
    }
}
