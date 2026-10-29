package com.thomas.carspeed

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale

class CameraFullscreenActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var recordingTextureView: TextureView
    private lateinit var plateOverlayView: PlateOverlayView
    private lateinit var tvTimer: TextView
    private lateinit var tvOverlay: DashcamInfoBar
    private lateinit var btnRecord: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnClose: Button

    private var idlePreviewProvider: ProcessCameraProvider? = null
    private var idlePreviewUseCase: Preview? = null
    private var idlePreviewRequested = false
    private var startAfterNotificationPermission = false
    private var recordingStartRequestedAtMs = 0L
    private var targetRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null
    private var recordingPreviewSurface: Surface? = null
    private var resumed = false
    private var lastRecordingUiState = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val focusCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            DashcamService.updatePreviewFocusTelemetry(
                result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                result.get(CaptureResult.CONTROL_AF_STATE)
            )
        }
    }

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
        instance = this
        setContentView(R.layout.activity_camera_fullscreen)
        targetRotation = CameraRotation.displayRotation(this)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        previewView = findViewById(R.id.fullscreenPreviewView)
        recordingTextureView = findViewById(R.id.fullscreenRecordingTextureView)
        plateOverlayView = findViewById(R.id.fullscreenPlateOverlayView)
        tvTimer = findViewById(R.id.tvFullscreenTimer)
        tvOverlay = findViewById(R.id.tvFullscreenOverlay)
        btnRecord = findViewById(R.id.btnFullscreenRecord)
        btnPhoto = findViewById(R.id.btnFullscreenPhoto)
        btnClose = findViewById(R.id.btnFullscreenClose)

        previewView.setOnClickListener { finish() }
        btnClose.setOnClickListener { finish() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnPhoto.setOnClickListener { DashcamService.takePhoto(this) }
        setupRecordingPreviewSurface()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (DashcamService.isRecording(this)) {
            stopIdlePreview()
        } else if (hasCameraPermission()) {
            scheduleIdlePreviewRestart()
        }
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
        startOrientationListener()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        uiHandler.removeCallbacks(uiTicker)
        stopOrientationListener()
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
            idlePreviewUseCase = null
        } else {
            stopIdlePreview()
        }
        clearRecordingPreviewSurface()
        if (instance === this) instance = null
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
        prepareRecordingPreviewForServiceStart()
        val requestAt = recordingStartRequestedAtMs
        recordingTextureView.postDelayed(
            {
                if (recordingStartRequestedAtMs != requestAt) return@postDelayed
                updateRecordingPreviewSurfaceIfReady()
                DashcamService.startRecording(this)
                syncUi()
            },
            RECORDING_PREVIEW_PREPARE_MS
        )
        syncUi()
    }

    private fun prepareRecordingPreviewForServiceStart() {
        stopIdlePreview()
        recordingTextureView.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        updateRecordingPreviewSurfaceIfReady()
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

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
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
                    val previewBuilder = Preview.Builder()
                        .setTargetRotation(targetRotation)
                    Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(focusCaptureCallback)
                    val preview = previewBuilder.build().also { preview ->
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    idlePreviewUseCase = preview
                    applyTargetRotation()
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
        idlePreviewUseCase = null
    }

    private fun startOrientationListener() {
        targetRotation = CameraRotation.displayRotation(this)
        applyTargetRotation()
    }

    private fun stopOrientationListener() {
        orientationListener?.disable()
        orientationListener = null
    }

    private fun applyTargetRotation() {
        idlePreviewUseCase?.targetRotation = targetRotation
    }

    private fun syncUi() {
        val recording = DashcamService.isRecording(this)
        if (recording) recordingStartRequestedAtMs = 0L
        val startPending = isRecordingStartPending()
        val activeRecordingUiState = recording || startPending
        btnRecord.setText(if (activeRecordingUiState) R.string.video_stop_recording else R.string.video_start_recording)
        btnPhoto.isEnabled = recording
        syncRecordingPreviewSurface(activeRecordingUiState)
        tvTimer.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) {
            tvTimer.text = getString(
                R.string.recording_elapsed_fmt,
                formatDuration(DashcamService.recordingElapsedSeconds(this) * 1_000L)
            )
        }
        tvOverlay.setInfo(
            DashcamOverlayText.infoBar(this, CameraCapabilities.selectedVideoMode(this)),
            DashcamSettings.subtitleFields(this).hasAnyField()
        )
        plateOverlayView.setFrame(if (recording) PlateRecognitionMonitor.currentOverlayFrame() else null)
        if (lastRecordingUiState && !activeRecordingUiState && hasCameraPermission()) {
            scheduleIdlePreviewRestart()
        }
        lastRecordingUiState = activeRecordingUiState
    }

    private fun setupRecordingPreviewSurface() {
        recordingTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                updateRecordingPreviewSurfaceIfReady()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateRecordingPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                clearRecordingPreviewSurface(surface)
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun syncRecordingPreviewSurface(recording: Boolean) {
        recordingTextureView.visibility = if (recording) View.VISIBLE else View.GONE
        previewView.visibility = if (recording) View.GONE else View.VISIBLE
        if (recording) {
            updateRecordingPreviewSurfaceIfReady()
        } else {
            clearRecordingPreviewSurface()
        }
    }

    private fun updateRecordingPreviewSurfaceIfReady() {
        if (!recordingTextureView.isAvailable) return
        if (recordingPreviewSurface != null) {
            updateRecordingPreviewTransform()
            return
        }
        val texture = recordingTextureView.surfaceTexture ?: return
        val mode = CameraCapabilities.selectedVideoMode(this)
        texture.setDefaultBufferSize(mode.width, mode.height)
        updateRecordingPreviewTransform()
        recordingPreviewSurface?.release()
        recordingPreviewSurface = Surface(texture).also { surface ->
            DashcamService.setRecordingPreviewSurface(surface)
        }
    }

    /** 先让录像器把预览从请求里摘掉，再晚一点释放画面，避免录像断帧。[texture] 不为空时由这里释放。 */
    private fun clearRecordingPreviewSurface(texture: SurfaceTexture? = null) {
        DashcamService.setRecordingPreviewSurface(null)
        val surface = recordingPreviewSurface
        recordingPreviewSurface = null
        if (surface == null && texture == null) return
        uiHandler.postDelayed({
            surface?.release()
            texture?.release()
        }, PREVIEW_SURFACE_RELEASE_DELAY_MS)
    }

    private fun updateRecordingPreviewTransform() {
        val viewWidth = recordingTextureView.width.toFloat()
        val viewHeight = recordingTextureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val mode = CameraCapabilities.selectedVideoMode(this)
        val bufferWidth = mode.width.toFloat()
        val bufferHeight = mode.height.toFloat()
        val rotationDegrees = rotationDegreesForTexture()
        val rotatesByRightAngle = rotationDegrees % 180f != 0f
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val matrix = Matrix()
        if (rotatesByRightAngle) {
            val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
            val bufferRect = RectF(0f, 0f, bufferHeight, bufferWidth)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = minOf(viewHeight / bufferHeight, viewWidth / bufferWidth)
            matrix.postScale(scale, scale, centerX, centerY)
        } else {
            val defaultScaleX = viewWidth / bufferWidth
            val defaultScaleY = viewHeight / bufferHeight
            val contentScale = minOf(defaultScaleX, defaultScaleY)
            matrix.setScale(contentScale / defaultScaleX, contentScale / defaultScaleY, centerX, centerY)
        }
        matrix.postRotate(rotationDegrees, centerX, centerY)
        recordingTextureView.setTransform(matrix)
    }

    private fun rotationDegreesForTexture(): Float {
        return CameraRotation.previewRotationDegrees(targetRotation)
    }

    private fun isRecordingStartPending(): Boolean {
        val requestedAt = recordingStartRequestedAtMs
        if (requestedAt <= 0L) return false
        val pending = SystemClock.elapsedRealtime() - requestedAt <= RECORDING_START_PENDING_TIMEOUT_MS
        if (!pending) recordingStartRequestedAtMs = 0L
        return pending
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
        private const val RECORDING_PREVIEW_PREPARE_MS = 150L
        private const val RECORDING_START_PENDING_TIMEOUT_MS = 8_000L
        private const val PREVIEW_SURFACE_RELEASE_DELAY_MS = 1_000L

        @Volatile
        private var instance: CameraFullscreenActivity? = null

        fun prepareForServiceRecording() {
            val activity = instance ?: return
            if (!activity.resumed) return
            activity.runOnUiThread {
                activity.prepareRecordingPreviewForServiceStart()
            }
        }
    }
}
