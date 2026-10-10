package com.thomas.carspeed

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

class StatusActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tvSummary: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvCameraStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var tvGpsStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnLocation: Button
    private lateinit var btnCamera: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnNotification: Button
    private lateinit var btnGps: Button
    private lateinit var btnBattery: Button

    private val requestLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            render()
        }

    private val requestNotificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            render()
        }

    private val requestCameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            render()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        toolbar = findViewById(R.id.toolbar)
        tvSummary = findViewById(R.id.tvSummary)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvCameraStatus = findViewById(R.id.tvCameraStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnLocation = findViewById(R.id.btnLocation)
        btnCamera = findViewById(R.id.btnCamera)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnNotification = findViewById(R.id.btnNotification)
        btnGps = findViewById(R.id.btnGps)
        btnBattery = findViewById(R.id.btnBattery)

        toolbar.setNavigationOnClickListener { finish() }

        btnLocation.setOnClickListener {
            requestLocationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        btnCamera.setOnClickListener {
            requestCameraLauncher.launch(Manifest.permission.CAMERA)
        }
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btnNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !DashcamNotificationGuard.hasRuntimeNotificationPermission(this)
            ) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        }
        btnGps.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        btnBattery.setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val locationOk = OverlayController.hasLocationPermission(this)
        val cameraOk = OverlayController.hasCameraPermission(this)
        val overlayOk = OverlayController.hasOverlayPermission(this)
        val notificationOk = DashcamNotificationGuard.canShowRecordingNotification(this)
        val gpsOk = isGpsEnabled()
        val batteryOk = isIgnoringBatteryOptimizations()

        val mustHaveOk = locationOk && cameraOk && overlayOk && notificationOk && gpsOk && batteryOk
        tvSummary.text = if (mustHaveOk) {
            getString(R.string.status_summary_ready)
        } else {
            getString(R.string.status_summary_need_fix)
        }

        tvLocationStatus.text = statusText(
            locationOk,
            getString(R.string.status_location_ok),
            getString(R.string.status_location_bad)
        )
        tvCameraStatus.text = statusText(
            cameraOk,
            getString(R.string.status_camera_ok),
            getString(R.string.status_camera_bad)
        )
        tvOverlayStatus.text = statusText(
            overlayOk,
            getString(R.string.status_overlay_ok),
            getString(R.string.status_overlay_bad)
        )
        tvNotificationStatus.text = statusText(
            notificationOk,
            getString(R.string.status_notification_ok),
            getString(R.string.status_notification_bad)
        )
        tvGpsStatus.text = statusText(
            gpsOk,
            getString(R.string.status_gps_ok),
            getString(R.string.status_gps_bad)
        )
        tvBatteryStatus.text = if (batteryOk) {
            getString(R.string.status_battery_ok)
        } else {
            getString(R.string.status_battery_warn)
        }

        btnLocation.isEnabled = !locationOk
        btnCamera.isEnabled = !cameraOk
        btnOverlay.isEnabled = !overlayOk
        btnNotification.isEnabled = !notificationOk
        btnGps.isEnabled = !gpsOk
        btnBattery.isEnabled = !batteryOk
    }

    private fun statusText(ok: Boolean, okText: String, badText: String): String {
        return if (ok) okText else badText
    }

    private fun isGpsEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationSettings() {
        if (!isIgnoringBatteryOptimizations()) {
            val requestIntent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            if (tryStartActivity(requestIntent)) return
        }
        if (tryStartActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        tryStartActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, DashcamNotificationGuard.CHANNEL_ID)
            if (tryStartActivity(channelIntent)) return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appNotificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (tryStartActivity(appNotificationIntent)) return
        }
        tryStartActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
