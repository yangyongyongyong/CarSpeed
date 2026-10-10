package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
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
import java.util.Locale

class CameraFullscreenActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var tvTimer: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnClose: Button

    private var idlePreviewProvider: ProcessCameraProvider? = null
    private var idlePreviewRequested = false
    private var startAfterNotificationPermission = false
    private var recordingStartRequestedAtMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    private val uiTicker = object : Runnable {
        override fun run() {
            syncUi()
            uiHandler.postDelayed(this, 1_000L)
        }
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
        setContentView(R.layout.activity_camera_fullscreen)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        previewView = findViewById(R.id.fullscreenPreviewView)
        tvTimer = findViewById(R.id.tvFullscreenTimer)
        tvOverlay = findViewById(R.id.tvFullscreenOverlay)
        btnRecord = findViewById(R.id.btnFullscreenRecord)
        btnPhoto = findViewById(R.id.btnFullscreenPhoto)
        btnClose = findViewById(R.id.btnFullscreenClose)

        previewView.setOnClickListener { finish() }
        btnClose.setOnClickListener { finish() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnPhoto.setOnClickListener { DashcamService.takePhoto(this) }
    }

    override fun onResume() {
        super.onResume()
        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
        if (DashcamService.isRecording(this)) {
            stopIdlePreview()
        } else if (hasCameraPermission()) {
            scheduleIdlePreviewRestart()
        }
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
        uiHandler.removeCallbacks(uiTicker)
        if (!DashcamService.isRecording(this)) {
            stopIdlePreview()
        }
        super.onStop()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiTicker)
        if (DashcamService.isRecording(this)) {
            idlePreviewRequested = false
            idlePreviewProvider = null
        } else {
            stopIdlePreview()
        }
        super.onDestroy()
    }

    private fun toggleRecording() {
        if (!hasCameraPermission()) {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
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
            startActivity(android.content.Intent(this, StatusActivity::class.java))
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
                } catch (e: RuntimeException) {
                    Toast.makeText(this, getString(R.string.camera_open_failed, e.message ?: "unknown"), Toast.LENGTH_SHORT).show()
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
        btnRecord.setText(if (recording || startPending) R.string.video_stop_recording else R.string.video_start_recording)
        btnPhoto.isEnabled = recording
        tvTimer.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) {
            tvTimer.text = getString(
                R.string.recording_elapsed_fmt,
                formatDuration(DashcamService.recordingElapsedSeconds(this) * 1_000L)
            )
        }
        tvOverlay.text = buildOverlayText()
    }

    private fun isRecordingStartPending(): Boolean {
        val requestedAt = recordingStartRequestedAtMs
        if (requestedAt <= 0L) return false
        val pending = SystemClock.elapsedRealtime() - requestedAt <= RECORDING_START_PENDING_TIMEOUT_MS
        if (!pending) recordingStartRequestedAtMs = 0L
        return pending
    }

    private fun buildOverlayText(): String {
        val selectedMode = CameraCapabilities.selectedVideoMode(this)
        return DashcamOverlayText.text(this, selectedMode)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    companion object {
        private const val PREVIEW_REBIND_DELAY_MS = 700L
        private const val RECORDING_START_PENDING_TIMEOUT_MS = 8_000L
    }
}
