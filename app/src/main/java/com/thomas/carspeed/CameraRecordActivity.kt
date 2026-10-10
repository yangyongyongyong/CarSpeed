package com.thomas.carspeed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale

class CameraRecordActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var previewView: PreviewView
    private lateinit var tvPreviewOverlay: TextView
    private lateinit var tvPreviewTimer: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSavedPath: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnSettings: Button
    private lateinit var btnHistory: Button
    private lateinit var btnStatus: Button
    private lateinit var btnHud: Button

    private var idlePreviewProvider: ProcessCameraProvider? = null
    private var idlePreviewRequested = false
    private var autoStartRequested = false
    private var autoStartConsumed = false
    private var startAfterNotificationPermission = false
    private var recordingStartRequestedAtMs = 0L
    private var lastRecordingUiState = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiTicker = object : Runnable {
        override fun run() {
            syncUi()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                syncUi()
                scheduleIdlePreviewRestart()
                maybeAutoStartFromIntent()
            } else {
                tvStatus.setText(R.string.camera_permission_denied)
                btnRecord.isEnabled = false
            }
        }

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                getSharedPreferences(MainActivity.PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(DashcamSettings.PREF_RECORD_AUDIO, false)
                    .putBoolean(DashcamSettings.PREF_VOICE_PHOTO, false)
                    .apply()
                Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_SHORT).show()
            }
            DashcamService.refreshSettings(this)
        }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && startAfterNotificationPermission) {
                startAfterNotificationPermission = false
                startRecordingFromUi()
            } else {
                startAfterNotificationPermission = false
                Toast.makeText(this, R.string.recording_notification_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_record)
        autoStartRequested = intent.getBooleanExtra(EXTRA_AUTO_START_RECORDING, false) ||
            (intent?.action == Intent.ACTION_MAIN && DashcamSettings.load(this).autoStartRecording)

        toolbar = findViewById(R.id.toolbar)
        previewView = findViewById(R.id.previewView)
        tvPreviewOverlay = findViewById(R.id.tvPreviewOverlay)
        tvPreviewTimer = findViewById(R.id.tvPreviewTimer)
        tvStatus = findViewById(R.id.tvCameraStatus)
        tvSavedPath = findViewById(R.id.tvSavedPath)
        btnRecord = findViewById(R.id.btnRecordVideo)
        btnPhoto = findViewById(R.id.btnTakePhoto)
        btnSettings = findViewById(R.id.btnOpenSettings)
        btnHistory = findViewById(R.id.btnOpenHistory)
        btnStatus = findViewById(R.id.btnOpenStatus)
        btnHud = findViewById(R.id.btnOpenHud)

        toolbar.setNavigationOnClickListener { finish() }
        previewView.setOnClickListener { startActivity(Intent(this, CameraFullscreenActivity::class.java)) }
        btnRecord.setOnClickListener { toggleRecording() }
        btnPhoto.setOnClickListener { DashcamService.takePhoto(this) }
        btnSettings.setOnClickListener { startActivity(Intent(this, DashcamSettingsActivity::class.java)) }
        btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        btnStatus.setOnClickListener { startActivity(Intent(this, StatusActivity::class.java)) }
        btnHud.setOnClickListener { startActivity(Intent(this, SpeedHudActivity::class.java)) }

        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
        ensureCameraPermission()
        updatePreviewOverlay()
    }

    override fun onResume() {
        super.onResume()
        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
        if (DashcamService.isRecording(this)) {
            stopIdlePreview()
        } else if (hasCameraPermission()) {
            scheduleIdlePreviewRestart()
        }
        syncUi()
        maybeAutoStartFromIntent()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTicker)
        if (!DashcamService.isRecording(this)) {
            stopIdlePreview()
        }
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(uiTicker)
        if (!DashcamService.isRecording(this)) {
            stopIdlePreview()
        }
        if (DashcamService.isRecording(this) && OverlayController.canStart(this) && !OverlayController.isRunning(this)) {
            OverlayController.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiTicker)
        if (DashcamService.isRecording(this)) {
            idlePreviewRequested = false
            idlePreviewProvider = null
        } else {
            stopIdlePreview()
        }
        DashcamService.setPreviewSurfaceProvider(null)
    }

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) {
            syncUi()
            scheduleIdlePreviewRestart()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleRecording() {
        if (!hasCameraPermission()) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        val settings = DashcamSettings.load(this)
        if (settings.voicePhotoEnabled && !hasAudioPermission()) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (DashcamService.isRecording(this) || isRecordingStartPending()) {
            recordingStartRequestedAtMs = 0L
            DashcamService.stopRecording(this)
        } else {
            startRecordingFromUi()
        }
        syncUi()
    }

    private fun maybeAutoStartFromIntent() {
        if (!autoStartRequested || autoStartConsumed) return
        if (!hasCameraPermission()) return
        autoStartConsumed = true
        if (!DashcamService.isRecording(this)) {
            startRecordingFromUi()
        }
        syncUi()
    }

    private fun startRecordingFromUi() {
        if (!ensureRecordingNotificationVisible()) return
        recordingStartRequestedAtMs = SystemClock.elapsedRealtime()
        stopIdlePreview()
        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
        DashcamService.startRecording(this)
        syncUi()
    }

    private fun ensureRecordingNotificationVisible(): Boolean {
        if (DashcamNotificationGuard.canShowRecordingNotification(this)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !DashcamNotificationGuard.hasRuntimeNotificationPermission(this)
        ) {
            startAfterNotificationPermission = true
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Toast.makeText(this, R.string.recording_notification_required, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, StatusActivity::class.java))
        }
        return false
    }

    private fun startIdlePreview() {
        if (!hasCameraPermission() || DashcamService.isRecording(this)) return
        idlePreviewRequested = true
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                if (!idlePreviewRequested || DashcamService.isRecording(this) || isFinishing || isDestroyed) return@addListener
                try {
                    val provider = providerFuture.get()
                    idlePreviewProvider = provider
                    val preview = Preview.Builder().build().also { preview ->
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    tvStatus.setText(R.string.camera_ready)
                } catch (e: RuntimeException) {
                    tvStatus.text = getString(R.string.camera_open_failed, e.message ?: "unknown")
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun scheduleIdlePreviewRestart() {
        stopIdlePreview()
        idlePreviewRequested = true
        previewView.post {
            uiHandler.postDelayed(
                {
                    if (idlePreviewRequested && !DashcamService.isRecording(this) && hasCameraPermission()) {
                        startIdlePreview()
                    }
                },
                PREVIEW_REBIND_DELAY_MS
            )
        }
    }

    private fun stopIdlePreview() {
        idlePreviewRequested = false
        idlePreviewProvider?.unbindAll()
        idlePreviewProvider = null
    }

    private fun syncUi() {
        val recording = DashcamService.isRecording(this)
        if (recording) recordingStartRequestedAtMs = 0L
        val startPending = isRecordingStartPending()
        val activeRecordingUiState = recording || startPending
        btnRecord.isEnabled = hasCameraPermission()
        btnRecord.setText(if (activeRecordingUiState) R.string.video_stop_recording else R.string.video_start_recording)
        btnPhoto.isEnabled = recording
        tvStatus.setText(
            when {
                recording -> R.string.video_recording_background
                startPending -> R.string.video_recording_starting
                else -> R.string.camera_ready
            }
        )
        tvSavedPath.text = getString(R.string.video_background_hint)
        updatePreviewOverlay()
        if (lastRecordingUiState && !activeRecordingUiState && hasCameraPermission()) {
            scheduleIdlePreviewRestart()
        }
        lastRecordingUiState = activeRecordingUiState
    }

    private fun isRecordingStartPending(): Boolean {
        val requestedAt = recordingStartRequestedAtMs
        if (requestedAt <= 0L) return false
        val pending = SystemClock.elapsedRealtime() - requestedAt <= RECORDING_START_PENDING_TIMEOUT_MS
        if (!pending) recordingStartRequestedAtMs = 0L
        return pending
    }

    private fun updatePreviewOverlay() {
        val recording = DashcamService.isRecording(this)
        val selectedMode = CameraCapabilities.selectedVideoMode(this)
        tvPreviewOverlay.text = DashcamOverlayText.text(this, selectedMode)
        if (recording) {
            tvPreviewTimer.visibility = android.view.View.VISIBLE
            tvPreviewTimer.text = getString(
                R.string.recording_elapsed_fmt,
                formatDuration(DashcamService.recordingElapsedSeconds(this) * 1_000L)
            )
        } else {
            tvPreviewTimer.visibility = android.view.View.GONE
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    companion object {
        const val EXTRA_AUTO_START_RECORDING = "auto_start_recording"
        private const val PREVIEW_REBIND_DELAY_MS = 700L
        private const val RECORDING_START_PENDING_TIMEOUT_MS = 8_000L
    }
}
