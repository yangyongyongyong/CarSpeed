package com.thomas.carspeed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaScannerConnection
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
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CameraRecordActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var previewCard: com.google.android.material.card.MaterialCardView
    private lateinit var previewView: PreviewView
    private lateinit var recordingTextureView: AspectRatioTextureView
    private lateinit var plateOverlayView: PlateOverlayView
    private lateinit var tvPreviewOverlay: TextView
    private lateinit var tvPreviewTimer: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSavedPath: TextView
    private lateinit var cbManualFocus: CheckBox
    private lateinit var tvFocusInlineLabel: TextView
    private lateinit var sbInlineFocus: SeekBar
    private lateinit var btnRecord: Button
    private lateinit var btnPhoto: Button
    private lateinit var btnPhotos: Button
    private lateinit var btnSettings: Button
    private lateinit var btnHistory: Button
    private lateinit var btnStatus: Button
    private lateinit var btnHud: Button
    private lateinit var btnRadar: Button
    private lateinit var btnTrack: Button
    private lateinit var btnRadarQuery: Button

    private var idlePreviewProvider: ProcessCameraProvider? = null
    private var idleCamera: Camera? = null
    private var idlePreviewUseCase: Preview? = null
    private var idleImageCapture: ImageCapture? = null
    private var idlePreviewRequested = false
    private var autoStartRequested = false
    private var autoStartConsumed = false
    private var startAfterNotificationPermission = false
    private var recordingStartRequestedAtMs = 0L
    private var lastRecordingUiState = false
    private var voicePermissionRequested = false
    private var pendingPhotoRequest = false
    private var targetRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null
    private var recordingPreviewSurface: Surface? = null
    private var updatingFocusControls = false
    private var resumed = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val photoExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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
            refreshVoiceRecognition()
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
        setContentView(R.layout.activity_camera_record)
        targetRotation = CameraRotation.displayRotation(this)
        autoStartRequested = intent.getBooleanExtra(EXTRA_AUTO_START_RECORDING, false) ||
            (intent?.action == Intent.ACTION_MAIN && DashcamSettings.load(this).autoStartRecording)

        toolbar = findViewById(R.id.toolbar)
        previewCard = findViewById(R.id.previewCard)
        previewView = findViewById(R.id.previewView)
        recordingTextureView = findViewById(R.id.recordingTextureView)
        plateOverlayView = findViewById(R.id.plateOverlayView)
        tvPreviewOverlay = findViewById(R.id.tvPreviewOverlay)
        tvPreviewTimer = findViewById(R.id.tvPreviewTimer)
        tvStatus = findViewById(R.id.tvCameraStatus)
        tvSavedPath = findViewById(R.id.tvSavedPath)
        cbManualFocus = findViewById(R.id.cbManualFocus)
        tvFocusInlineLabel = findViewById(R.id.tvFocusInlineLabel)
        sbInlineFocus = findViewById(R.id.sbInlineFocus)
        btnRecord = findViewById(R.id.btnRecordVideo)
        btnPhoto = findViewById(R.id.btnTakePhoto)
        btnPhotos = findViewById(R.id.btnOpenPhotos)
        btnSettings = findViewById(R.id.btnOpenSettings)
        btnHistory = findViewById(R.id.btnOpenHistory)
        btnStatus = findViewById(R.id.btnOpenStatus)
        btnHud = findViewById(R.id.btnOpenHud)
        btnRadar = findViewById(R.id.btnOpenRadar)
        btnTrack = findViewById(R.id.btnOpenTrack)
        btnRadarQuery = findViewById(R.id.btnOpenRadarQuery)

        toolbar.setNavigationOnClickListener { finish() }
        previewView.setOnClickListener { startActivity(Intent(this, CameraFullscreenActivity::class.java)) }
        btnRecord.setOnClickListener { toggleRecording() }
        btnPhoto.setOnClickListener { takePhotoFromUi() }
        btnPhotos.setOnClickListener { startActivity(Intent(this, PhotoGalleryActivity::class.java)) }
        btnSettings.setOnClickListener { startActivity(Intent(this, DashcamSettingsActivity::class.java)) }
        btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        btnStatus.setOnClickListener { startActivity(Intent(this, StatusActivity::class.java)) }
        btnHud.setOnClickListener { startActivity(Intent(this, SpeedHudActivity::class.java)) }
        btnRadar.setOnClickListener { startActivity(Intent(this, RadarAlertSettingsActivity::class.java)) }
        btnTrack.setOnClickListener { startActivity(Intent(this, TrackRecordingSettingsActivity::class.java)) }
        btnRadarQuery.setOnClickListener { startActivity(Intent(this, RadarQueryActivity::class.java)) }
        setupFocusControls()
        setupRecordingPreviewSurface()

        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
        ensureCameraPermission()
        updatePreviewOverlay()
        if (intent.getBooleanExtra(EXTRA_TAKE_PHOTO, false) || pendingExternalPhotoRequest) {
            pendingExternalPhotoRequest = false
            pendingPhotoRequest = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TAKE_PHOTO, false)) {
            pendingPhotoRequest = true
            takePhotoFromUi()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
        if (DashcamService.isRecording(this)) {
            stopIdlePreview()
        } else if (hasCameraPermission()) {
            scheduleIdlePreviewRestart()
        }
        syncUi()
        syncFocusControlsFromPrefs()
        RadarAlertService.sync(this)
        TrackRecordingController.sync(this, stopUnavailable = true)
        maybeAutoStartFromIntent()
        AutoTriggerController.sync(this)
        startOrientationListener()
        refreshVoiceRecognition()
        uiHandler.removeCallbacks(uiTicker)
        uiHandler.post(uiTicker)
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
        photoExecutor.shutdown()
        if (DashcamService.isRecording(this)) {
            idlePreviewRequested = false
            idlePreviewProvider = null
            idleCamera = null
            idlePreviewUseCase = null
        } else {
            stopIdlePreview()
        }
        clearRecordingPreviewSurface()
        DashcamService.setPreviewSurfaceProvider(null)
        if (instance === this) instance = null
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
        prepareRecordingPreviewForServiceStart()
        val requestAt = recordingStartRequestedAtMs
        recordingTextureView.postDelayed({
            if (recordingStartRequestedAtMs != requestAt) return@postDelayed
            updateRecordingPreviewSurfaceIfReady()
            DashcamService.startRecording(this)
            syncUi()
        }, RECORDING_PREVIEW_PREPARE_MS)
        syncUi()
    }

    private fun prepareRecordingPreviewForServiceStart() {
        stopIdlePreview()
        recordingTextureView.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        DashcamService.setPreviewSurfaceProvider(previewView.surfaceProvider)
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
            startActivity(Intent(this, StatusActivity::class.java))
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
                    val imageCapture = ImageCapture.Builder()
                        .setTargetRotation(targetRotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    provider.unbindAll()
                    idleCamera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    idlePreviewUseCase = preview
                    idleImageCapture = imageCapture
                    applyTargetRotation()
                    applyFocusToCurrentCamera()
                    tvStatus.setText(R.string.camera_ready)
                    if (pendingPhotoRequest) {
                        pendingPhotoRequest = false
                        previewView.postDelayed({ takePhotoFromUi() }, 300L)
                    }
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
        idleCamera = null
        idlePreviewUseCase = null
        idleImageCapture = null
    }

    private fun setupFocusControls() {
        sbInlineFocus.min = 0
        sbInlineFocus.max = 100
        cbManualFocus.isChecked = CameraFocus.isManualEnabled(this)
        sbInlineFocus.progress = CameraFocus.percent(this)
        updateFocusUi()

        cbManualFocus.setOnCheckedChangeListener { _, isChecked ->
            if (updatingFocusControls) return@setOnCheckedChangeListener
            CameraFocus.save(this, isChecked, sbInlineFocus.progress, commit = true)
            updateFocusUi()
            applyFocusToCurrentCamera()
        }
        sbInlineFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingFocusControls) return
                val fixed = progress.coerceIn(0, 100)
                CameraFocus.save(this@CameraRecordActivity, cbManualFocus.isChecked, fixed)
                updateFocusUi()
                if (fromUser) applyFocusToCurrentCamera()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                CameraFocus.save(this@CameraRecordActivity, cbManualFocus.isChecked, sbInlineFocus.progress, commit = true)
                applyFocusToCurrentCamera()
            }
        })
    }

    private fun updateFocusUi() {
        val manual = cbManualFocus.isChecked
        val supportsManual = CameraFocus.supportsManualFocus(idleCamera) || idleCamera == null || DashcamService.isRecording(this)
        cbManualFocus.isEnabled = supportsManual
        sbInlineFocus.isEnabled = manual && supportsManual
        tvFocusInlineLabel.text = when {
            !supportsManual -> getString(R.string.focus_unsupported_label)
            manual -> getString(R.string.focus_label, sbInlineFocus.progress, focusDistanceLabel(sbInlineFocus.progress))
            else -> getString(R.string.focus_auto_label)
        }
    }

    private fun syncFocusControlsFromPrefs() {
        updatingFocusControls = true
        cbManualFocus.isChecked = CameraFocus.isManualEnabled(this)
        sbInlineFocus.progress = CameraFocus.percent(this)
        updatingFocusControls = false
        updateFocusUi()
    }

    private fun applyFocusToCurrentCamera() {
        CameraFocus.apply(this, idleCamera)
        updateFocusUi()
        if (DashcamService.isRecording(this)) {
            DashcamService.refreshSettings(this)
        }
    }

    private fun focusDistanceLabel(percent: Int): String {
        return CameraFocus.distanceLabel(
            percent,
            CameraFocus.maxFocusDistanceDiopters(this),
            getString(R.string.focus_far_label)
        )
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
        idleImageCapture?.targetRotation = targetRotation
    }

    private fun syncUi() {
        val recording = DashcamService.isRecording(this)
        if (recording) recordingStartRequestedAtMs = 0L
        val startPending = isRecordingStartPending()
        val activeRecordingUiState = recording || startPending
        btnRecord.isEnabled = hasCameraPermission()
        btnRecord.setText(if (activeRecordingUiState) R.string.video_stop_recording else R.string.video_start_recording)
        btnPhoto.isEnabled = hasCameraPermission()
        tvStatus.setText(
            when {
                recording -> R.string.video_recording_background
                startPending -> R.string.video_recording_starting
                else -> R.string.camera_ready
            }
        )
        tvSavedPath.text = getString(R.string.video_background_hint)
        syncRecordingPreviewSurface(activeRecordingUiState)
        updatePreviewOverlay()
        plateOverlayView.setFrame(if (recording) PlateRecognitionMonitor.currentOverlayFrame() else null)
        if (lastRecordingUiState && !activeRecordingUiState && hasCameraPermission()) {
            scheduleIdlePreviewRestart()
            refreshVoiceRecognition()
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
                clearRecordingPreviewSurface()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    private fun syncRecordingPreviewSurface(recording: Boolean) {
        recordingTextureView.visibility = if (recording) View.VISIBLE else View.GONE
        previewView.visibility = if (recording) View.GONE else View.VISIBLE
        updatePreviewCardLayout(recording)
        if (recording) {
            updateRecordingPreviewSurfaceIfReady()
        } else {
            clearRecordingPreviewSurface()
        }
    }

    private fun updateRecordingPreviewSurfaceIfReady() {
        val mode = CameraCapabilities.selectedVideoMode(this)
        if (!recordingTextureView.isAvailable) return
        if (recordingPreviewSurface != null) {
            updateRecordingPreviewTransform()
            return
        }
        val texture = recordingTextureView.surfaceTexture ?: return
        val previewSize = CameraCapabilities.mainRecordingPreviewSize(this, mode)
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        updateRecordingPreviewTransform()
        recordingPreviewSurface?.release()
        recordingPreviewSurface = Surface(texture).also { surface ->
            DashcamService.setRecordingPreviewSurface(surface)
        }
    }

    private fun clearRecordingPreviewSurface() {
        DashcamService.setRecordingPreviewSurface(null)
        recordingPreviewSurface?.release()
        recordingPreviewSurface = null
    }

    private fun updateRecordingPreviewTransform() {
        val mode = CameraCapabilities.selectedVideoMode(this)
        val previewSize = CameraCapabilities.mainRecordingPreviewSize(this, mode)
        recordingTextureView.setAspectRatio(previewSize.height, previewSize.width)
        val viewWidth = recordingTextureView.width.toFloat()
        val viewHeight = recordingTextureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val rotationDegrees = rotationDegreesForTexture()
        val rotatesByRightAngle = rotationDegrees % 180f != 0f
        val bufferWidth = previewSize.width.toFloat()
        val bufferHeight = previewSize.height.toFloat()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val matrix = Matrix()
        if (rotatesByRightAngle) {
            val viewRect = RectF(0f, 0f, viewWidth, viewHeight)
            val bufferRect = RectF(0f, 0f, bufferHeight, bufferWidth)
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = maxOf(viewHeight / bufferHeight, viewWidth / bufferWidth)
            matrix.postScale(scale, scale, centerX, centerY)
        }
        matrix.postRotate(rotationDegrees, centerX, centerY)
        recordingTextureView.setTransform(matrix)
        val values = FloatArray(9)
        matrix.getValues(values)
        android.util.Log.i(
            "CarSpeedPreview",
            "transform target=$targetRotation display=${CameraRotation.displayRotation(this)} " +
                "view=${viewWidth.toInt()}x${viewHeight.toInt()} video=${mode.width}x${mode.height} " +
                "preview=${previewSize.width}x${previewSize.height} rotation=$rotationDegrees " +
                "matrix=${values.joinToString()}"
        )
    }

    private fun updatePreviewCardLayout(recording: Boolean) {
        val params = previewCard.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        if (!recording) {
            if (params.height != 0 || params.weight != 1f) {
                params.height = 0
                params.weight = 1f
                previewCard.layoutParams = params
            }
            return
        }
        val mode = CameraCapabilities.selectedVideoMode(this)
        val previewSize = CameraCapabilities.mainRecordingPreviewSize(this, mode)
        val cardWidth = previewCard.width.takeIf { it > 0 } ?: return
        val targetHeight = (cardWidth.toFloat() * previewSize.width / previewSize.height).roundToInt()
        if (params.height != targetHeight || params.weight != 0f) {
            params.height = targetHeight
            params.weight = 0f
            previewCard.layoutParams = params
        }
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

    private fun takePhotoFromUi() {
        if (!hasCameraPermission()) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (DashcamService.isRecording(this) || isRecordingStartPending()) {
            DashcamService.takePhoto(this)
            return
        }
        val capture = idleImageCapture
        if (capture == null) {
            pendingPhotoRequest = true
            scheduleIdlePreviewRestart()
            Toast.makeText(this, R.string.camera_opening, Toast.LENGTH_SHORT).show()
            return
        }
        val outputFile = VideoStorage.photoOutputFile(this)
        val tempFile = VideoStorage.tempPhotoFile(this)
        val mode = CameraCapabilities.selectedVideoMode(this)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        PhotoShutterSound.play(this)
        capture.takePicture(
            outputOptions,
            photoExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    PhotoWatermark.apply(this@CameraRecordActivity, tempFile, mode)
                    val published = VideoStorage.publishPhotoToGallery(this@CameraRecordActivity, tempFile, outputFile.name)
                    tempFile.delete()
                    MediaScannerConnection.scanFile(this@CameraRecordActivity, arrayOf(published.absolutePath), arrayOf("image/jpeg"), null)
                    runOnUiThread {
                        Toast.makeText(this@CameraRecordActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        Toast.makeText(this@CameraRecordActivity, R.string.photo_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun refreshVoiceRecognition() {
        val enabled = DashcamSettings.load(this).voicePhotoEnabled
        if (!hasAudioPermission()) {
            if (enabled && !voicePermissionRequested) {
                voicePermissionRequested = true
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return
        }
        DashcamService.refreshSettings(this)
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
        const val EXTRA_TAKE_PHOTO = "take_photo"
        private const val PREVIEW_REBIND_DELAY_MS = 700L
        private const val RECORDING_PREVIEW_PREPARE_MS = 150L
        private const val RECORDING_START_PENDING_TIMEOUT_MS = 8_000L
        @Volatile
        private var instance: CameraRecordActivity? = null

        @Volatile
        private var pendingExternalPhotoRequest = false

        fun prepareForServiceRecording() {
            val activity = instance ?: return
            if (!activity.resumed) return
            activity.runOnUiThread {
                activity.prepareRecordingPreviewForServiceStart()
            }
        }

        fun takePhoto(context: android.content.Context) {
            if (DashcamService.isRecording(context)) {
                DashcamService.takePhoto(context)
                return
            }
            val activity = instance
            if (activity != null) {
                activity.runOnUiThread { activity.takePhotoFromUi() }
                return
            }
            pendingExternalPhotoRequest = true
            context.startActivity(
                Intent(context, CameraRecordActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(EXTRA_TAKE_PHOTO, true)
                }
            )
        }
    }
}
