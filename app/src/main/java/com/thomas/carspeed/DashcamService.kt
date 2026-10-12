package com.thomas.carspeed

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.PendingRecording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DashcamService : LifecycleService(), LocationListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val plateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var activeRecording: Recording? = null
    private var seamlessRecorder: SeamlessDashcamRecorder? = null
    private var activePlateSession: PlateRecognitionSession? = null
    private var activeVideoFile: File? = null
    private var activeSubtitleFile: File? = null
    private var activeVideoMode: VideoMode? = null
    private var activeHdrEnabled = false
    private var activeHdrRequested = false
    private var activePlateRecognitionRequested = false
    private var activePlateRecognitionBound = false
    private var activeSegmentDurationMs = DashcamSettings.DEFAULT_SEGMENT_MINUTES * 60_000L
    private var lastPlateFocusElapsedMs = 0L
    private var activeTripFolderName: String? = null
    private var activeTripStartWallMs = 0L
    private var activeVideoStartWallMs = 0L
    private var subtitleIndex = 0
    private var recordingStartElapsedMs = 0L
    private var tripRecordingStartElapsedMs = 0L
    private var requestedStop = false
    private var rollingRestart = false
    private var rollingRestartNeedsRebind = false
    private var lastLocation: Location? = null
    private var lastAddressLocation: Location? = null
    private var lastAddressText: String? = null
    private var lastGeocodeAtMs = 0L
    private var lastSpeedKmh = 0f
    private var lastFocusDistanceDiopters: Float? = null
    private var lastAutofocusState: String? = null
    private var lastVoicePhotoAtMs = 0L
    private var parkingStationarySinceElapsedMs: Long? = null
    private val activeTrackPoints = mutableListOf<VideoTrackPoint>()
    private val activeTripTrackPoints = mutableListOf<VideoTrackPoint>()
    private var lastTrackPointElapsedMs = 0L
    private var stoppingSelf = false
    private var targetRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null

    private val focusCaptureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            updateFocusTelemetry(result)
        }
    }

    private val subtitleTicker = object : Runnable {
        override fun run() {
            appendSubtitleTick()
            if (isRecordingActive()) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val segmentRollover = Runnable {
        if (activeRecording != null && !requestedStop && !rollingRestart) {
            rollingRestart = true
            rollingRestartNeedsRebind = false
            activeRecording?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        targetRotation = CameraRotation.displayRotation(this)
        startOrientationListener()
        startForegroundCompat()
        registerLocationUpdates()
    }

    override fun onDestroy() {
        stoppingSelf = true
        stopVoiceRecognition()
        stopOrientationListener()
        stopRecordingInternal()
        unregisterLocationUpdates()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        plateExecutor.shutdown()
        instance = null
        setRecordingState(this, false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat(includeCameraType = isRecordingActive())
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecordingInternal()
            ACTION_STOP_RECORDING -> {
                val hadRecording = isRecordingActive()
                stopRecordingInternal()
                if (!hadRecording && !shouldKeepAliveForVoice()) stopSelf()
            }
            ACTION_TAKE_PHOTO -> takePhotoInternal()
            ACTION_REFRESH_SETTINGS -> refreshSettings()
        }
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        if (location.hasSpeed()) {
            lastSpeedKmh = SpeedRules.toKmhClamped(location.speed)
            updateParkingStop(lastSpeedKmh)
        }
        updateTelemetrySnapshot()
        appendTrackPoint(location)
        maybeResolveAddress(location)
    }

    private fun startRecordingInternal() {
        if (isRecordingActive()) return
        requestedStop = false
        rollingRestart = false
        parkingStationarySinceElapsedMs = null
        val tripStartMs = System.currentTimeMillis()
        activeTripStartWallMs = tripStartMs
        activeTripFolderName = VideoStorage.tripFolderNameFor(tripStartMs)
        tripRecordingStartElapsedMs = SystemClock.elapsedRealtime()
        activeTripTrackPoints.clear()
        if (!DashcamNotificationGuard.canShowRecordingNotification(this)) {
            Toast.makeText(this, R.string.recording_notification_required, Toast.LENGTH_LONG).show()
            setRecordingState(this, false)
            stopSelf()
            return
        }
        if (!hasCameraPermission()) {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }
        startForegroundCompat(includeCameraType = true)

        startSeamlessVideoCapture()
    }

    private fun isRecordingActive(): Boolean = activeRecording != null || seamlessRecorder != null

    private fun startSeamlessVideoCapture() {
        val mode = CameraCapabilities.selectedVideoMode(this)
        val segmentDurationMs = DashcamSettings.segmentDurationMs(this)
        activeVideoMode = mode
        activeSegmentDurationMs = segmentDurationMs
        activeHdrRequested = DashcamHdrSettings.shouldEnableNow(this)
        activeHdrEnabled = false
        hdrActiveSnapshot = false
        activePlateRecognitionRequested = DashcamSettings.load(this).plateRecognitionEnabled
        activePlateRecognitionBound = false
        PlateRecognitionMonitor.onConfigured(
            enabled = activePlateRecognitionRequested,
            analyzerBound = false,
            error = if (activePlateRecognitionRequested) "无缝录像模式暂未绑定车牌分析流。" else null
        )

        val estimatedSegmentBytes = VideoStorage.estimateSegmentBytes(mode, segmentDurationMs)
        val limitGb = getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
        if (!VideoStorage.cleanupLoopStorage(this, limitGb, estimatedSegmentBytes)) {
            Toast.makeText(this, R.string.storage_too_low_for_recording, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val recordAudio = shouldRecordAudioInRecording()
        if (recordAudio) stopVoiceRecognition()
        val recorder = SeamlessDashcamRecorder(
            context = this,
            mode = mode,
            segmentDurationMs = segmentDurationMs,
            tripFolderName = activeTripFolderName,
            targetRotation = targetRotation,
            initialPreviewSurface = recordingPreviewSurface?.takeIf { it.isValid },
            enableAudio = recordAudio,
            callback = object : SeamlessDashcamRecorder.Callback {
                override fun onStarted() {
                    mainHandler.post { onSeamlessRecordingStarted() }
                }

                override fun onSegmentStarted(file: File, startWallMs: Long, startElapsedMs: Long) {
                    mainHandler.post { onSeamlessSegmentStarted(file, startWallMs, startElapsedMs) }
                }

                override fun onSegmentFinalized(file: File) {
                    mainHandler.post { onSeamlessSegmentFinalized(file) }
                }

                override fun onStopped() {
                    mainHandler.post { onSeamlessRecordingStopped() }
                }

                override fun onError(error: Throwable) {
                    mainHandler.post { onSeamlessRecordingError(error) }
                }

                override fun onCaptureResult(result: CaptureResult) {
                    mainHandler.post { updateFocusTelemetry(result) }
                }
            }
        )
        seamlessRecorder = recorder
        recorder.start()
    }

    private fun shouldRecordAudioInRecording(): Boolean {
        val settings = DashcamSettings.load(this)
        return settings.recordAudio && hasAudioPermission()
    }

    private fun onSeamlessRecordingStarted() {
        setRecordingState(this, true)
        startForegroundCompat(includeCameraType = true)
        refreshSettings()
    }

    private fun onSeamlessSegmentStarted(file: File, startWallMs: Long, startElapsedMs: Long) {
        activeVideoFile = file
        activeSubtitleFile = VideoStorage.subtitleFileFor(file)
        activeVideoStartWallMs = startWallMs
        recordingStartElapsedMs = startElapsedMs
        subtitleIndex = 0
        resetActiveTrack()
        lastPlateFocusElapsedMs = 0L
        val plateEnabled = DashcamSettings.load(this).plateRecognitionEnabled
        activePlateSession = if (plateEnabled) {
            PlateRecognitionSession(
                startWallMs = startWallMs,
                startElapsedMs = startElapsedMs,
                analyzerBound = false,
                initialError = "无缝录像模式暂未绑定车牌分析流。"
            )
        } else {
            null
        }
        PlateRecognitionMonitor.onActive(plateEnabled, false)
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.post(subtitleTicker)
        lastLocation?.let { appendTrackPoint(it, force = true) }
    }

    private fun onSeamlessSegmentFinalized(file: File) {
        val plateSession = activePlateSession
        scanFile(file, "video/mp4")
        activeSubtitleFile?.takeIf { it.exists() }?.let { scanFile(it, "application/x-subrip") }
        writeActiveTrack(file)
        plateSession?.finish(this, file)
        val limitGb = getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
        VideoStorage.cleanupLoopStorage(this, limitGb)
        activePlateSession = null
        activeSubtitleFile = null
        activeVideoFile = null
        activeVideoStartWallMs = 0L
        resetActiveTrack()
        mainHandler.removeCallbacks(subtitleTicker)
    }

    private fun onSeamlessRecordingStopped() {
        seamlessRecorder = null
        if (!rollingRestart) {
            activeHdrEnabled = false
            hdrActiveSnapshot = false
        }
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.removeCallbacks(segmentRollover)
        releaseCameraUseCases()
        writeTripDriveRecord()
        clearActiveTrip()
        setRecordingState(this, false)
        refreshSettings()
        startForegroundCompat(includeCameraType = false)
        if (!stoppingSelf && !shouldKeepAliveForVoice()) stopSelf()
    }

    private fun onSeamlessRecordingError(error: Throwable) {
        Toast.makeText(this, getString(R.string.camera_open_failed, error.message ?: error.javaClass.simpleName), Toast.LENGTH_SHORT).show()
    }

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        val requestedMode = CameraCapabilities.selectedVideoMode(this)
        val candidates = CameraCapabilities.fallbackCandidates(this, requestedMode)
        val hdrRequested = DashcamHdrSettings.shouldEnableNow(this)
        val plateRecognitionRequested = DashcamSettings.load(this).plateRecognitionEnabled
        val dynamicRanges = if (hdrRequested) {
            listOf(DynamicRange.HDR_UNSPECIFIED_10_BIT, DynamicRange.SDR)
        } else {
            listOf(DynamicRange.SDR)
        }
        var lastError: RuntimeException? = null
        for (dynamicRange in dynamicRanges) {
            for (mode in candidates) {
                try {
                    bindCameraUseCases(
                        mode,
                        dynamicRange,
                        enablePlateRecognition = plateRecognitionRequested,
                        enableImageCapture = true,
                        enablePreview = previewSurfaceProvider != null
                    )
                    activateBoundCamera(mode, dynamicRange, hdrRequested, plateRecognitionRequested)
                    return
                } catch (error: RuntimeException) {
                    lastError = error
                }
            }
        }
        if (plateRecognitionRequested) {
            for (dynamicRange in dynamicRanges) {
                for (mode in candidates) {
                    try {
                        bindCameraUseCases(
                            mode,
                            dynamicRange,
                            enablePlateRecognition = true,
                            enableImageCapture = false,
                            enablePreview = previewSurfaceProvider != null
                        )
                        activateBoundCamera(mode, dynamicRange, hdrRequested, plateRecognitionBound = true)
                        return
                    } catch (error: RuntimeException) {
                        lastError = error
                    }
                }
            }
            for (dynamicRange in dynamicRanges) {
                for (mode in candidates) {
                    try {
                        bindCameraUseCases(
                            mode,
                            dynamicRange,
                            enablePlateRecognition = false,
                            enableImageCapture = true,
                            enablePreview = previewSurfaceProvider != null
                        )
                        activateBoundCamera(
                            mode,
                            dynamicRange,
                            hdrRequested,
                            plateRecognitionBound = false,
                            plateError = "当前设备/规格无法同时绑定车牌分析流，已跳过车牌分析。"
                        )
                        return
                    } catch (error: RuntimeException) {
                        lastError = error
                    }
                }
            }
        }
        if (previewSurfaceProvider != null) {
            for (dynamicRange in dynamicRanges) {
                for (mode in candidates) {
                    try {
                        bindCameraUseCases(
                            mode,
                            dynamicRange,
                            enablePlateRecognition = false,
                            enableImageCapture = false,
                            enablePreview = true
                        )
                        activateBoundCamera(
                            mode,
                            dynamicRange,
                            hdrRequested,
                            plateRecognitionBound = false,
                            plateError = if (plateRecognitionRequested) "当前设备/规格无法同时绑定车牌分析流，已保留录像预览。" else null
                        )
                        return
                    } catch (error: RuntimeException) {
                        lastError = error
                    }
                }
            }
            for (dynamicRange in dynamicRanges) {
                for (mode in candidates) {
                    try {
                        bindCameraUseCases(
                            mode,
                            dynamicRange,
                            enablePlateRecognition = plateRecognitionRequested,
                            enableImageCapture = true,
                            enablePreview = false
                        )
                        activateBoundCamera(
                            mode,
                            dynamicRange,
                            hdrRequested,
                            plateRecognitionBound = plateRecognitionRequested,
                            plateError = if (plateRecognitionRequested) null else PlateRecognitionMonitor.currentState().lastError
                        )
                        return
                    } catch (error: RuntimeException) {
                        lastError = error
                    }
                }
            }
        }
        throw lastError ?: IllegalStateException("No camera video mode available")
    }

    private fun activateBoundCamera(
        mode: VideoMode,
        dynamicRange: DynamicRange,
        hdrRequested: Boolean,
        plateRecognitionBound: Boolean,
        plateError: String? = null
    ) {
        activeVideoMode = mode
        activeHdrRequested = hdrRequested
        activeHdrEnabled = dynamicRange != DynamicRange.SDR
        activePlateRecognitionRequested = DashcamSettings.load(this).plateRecognitionEnabled
        activePlateRecognitionBound = plateRecognitionBound
        hdrActiveSnapshot = activeHdrEnabled
        PlateRecognitionMonitor.onConfigured(
            enabled = DashcamSettings.load(this).plateRecognitionEnabled,
            analyzerBound = plateRecognitionBound,
            error = plateError
        )
    }

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases(
        selectedMode: VideoMode,
        dynamicRange: DynamicRange,
        enablePlateRecognition: Boolean,
        enableImageCapture: Boolean,
        enablePreview: Boolean
    ) {
        val provider = cameraProvider ?: return
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(
                CameraCapabilities.toCameraXQuality(selectedMode),
                Quality.FHD,
                Quality.HD,
                Quality.SD
            ).distinct(),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val videoBuilder = VideoCapture.Builder(recorder)
            .setDynamicRange(dynamicRange)
            .setTargetRotation(targetRotation)
        Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(selectedMode.fps, selectedMode.fps)
        )
        Camera2Interop.Extender(videoBuilder).setSessionCaptureCallback(focusCaptureCallback)
        videoCapture = videoBuilder.build()
        imageCapture = if (enableImageCapture) {
            ImageCapture.Builder()
                .setTargetRotation(targetRotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
        } else {
            null
        }
        imageAnalysis = null
        if (enablePlateRecognition) {
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(plateExecutor) { image ->
                        val session = activePlateSession
                        if (session == null) {
                            image.close()
                            return@setAnalyzer
                        }
                        val frame = session.analyze(image)
                        if (frame != null && frame.plates.isNotEmpty()) {
                            mainHandler.post { maybeFocusOnPlate(frame) }
                        }
                    }
                }
        }

        val useCases = mutableListOf<UseCase>()
        previewUseCase = if (enablePreview) {
            Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
                .also { preview ->
                    preview.setSurfaceProvider(previewSurfaceProvider)
                }
        } else {
            null
        }
        previewUseCase?.let { useCases += it }
        useCases += videoCapture ?: return
        imageCapture?.let { useCases += it }
        imageAnalysis?.let { useCases += it }

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray()
        )
        applyFocusSetting()
        applyTargetRotation()
    }

    private fun updatePreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        previewUseCase?.setSurfaceProvider(provider)
    }

    private fun updateRecordingPreviewSurface(surface: Surface?) {
        seamlessRecorder?.setPreviewSurface(surface)
    }

    private fun startOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = CameraRotation.rotationFromOrientationDegrees(orientation) ?: return
                if (rotation != targetRotation) {
                    targetRotation = rotation
                    applyTargetRotation()
                }
            }
        }.also { listener ->
            if (listener.canDetectOrientation()) listener.enable()
        }
    }

    private fun stopOrientationListener() {
        orientationListener?.disable()
        orientationListener = null
    }

    private fun applyTargetRotation() {
        previewUseCase?.targetRotation = targetRotation
        videoCapture?.targetRotation = targetRotation
        imageCapture?.targetRotation = targetRotation
        imageAnalysis?.targetRotation = targetRotation
    }

    private fun startVideoCapture() {
        val mode = activeVideoMode ?: CameraCapabilities.selectedVideoMode(this)
        val segmentDurationMs = DashcamSettings.segmentDurationMs(this)
        activeSegmentDurationMs = segmentDurationMs
        val estimatedSegmentBytes = VideoStorage.estimateSegmentBytes(mode, segmentDurationMs)
        val limitGb = getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
        if (!VideoStorage.cleanupLoopStorage(this, limitGb, estimatedSegmentBytes)) {
            Toast.makeText(this, R.string.storage_too_low_for_recording, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }

        val capture = videoCapture ?: return
        val startTimeMs = System.currentTimeMillis()
        val outputFile = VideoStorage.outputFile(this, startTimeMs, activeTripFolderName)
        activeVideoFile = outputFile
        activeSubtitleFile = VideoStorage.subtitleFileFor(outputFile)
        activeVideoStartWallMs = startTimeMs
        subtitleIndex = 0
        recordingStartElapsedMs = SystemClock.elapsedRealtime()
        resetActiveTrack()
        lastPlateFocusElapsedMs = 0L
        val plateEnabled = DashcamSettings.load(this).plateRecognitionEnabled
        activePlateSession = if (plateEnabled) {
            PlateRecognitionSession(
                startWallMs = startTimeMs,
                startElapsedMs = recordingStartElapsedMs,
                analyzerBound = activePlateRecognitionBound,
                initialError = if (activePlateRecognitionBound) null else PlateRecognitionMonitor.currentState().lastError
            )
        } else {
            null
        }
        PlateRecognitionMonitor.onActive(plateEnabled, activePlateRecognitionBound)

        activeRecording = preparePendingRecording(capture, outputFile, activeTripFolderName)
            .let { pending -> enableAudioIfAllowed(pending) }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onRecordingStarted()
                    is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
                }
            }
    }

    private fun preparePendingRecording(
        capture: VideoCapture<Recorder>,
        outputFile: File,
        tripFolderName: String?
    ): PendingRecording {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, outputFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, VideoStorage.videoRelativePath(tripFolderName))
            }
            val outputOptions = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values)
                .build()
            capture.output.prepareRecording(this, outputOptions)
        } else {
            capture.output.prepareRecording(this, FileOutputOptions.Builder(outputFile).build())
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableAudioIfAllowed(pending: PendingRecording): PendingRecording {
        if (!shouldRecordAudioInRecording()) return pending
        stopVoiceRecognition()
        return try {
            pending.withAudioEnabled()
        } catch (_: SecurityException) {
            pending
        }
    }

    private fun onRecordingStarted() {
        setRecordingState(this, true)
        startForegroundCompat(includeCameraType = true)
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.post(subtitleTicker)
        mainHandler.removeCallbacks(segmentRollover)
        mainHandler.postDelayed(segmentRollover, activeSegmentDurationMs)
        lastLocation?.let { appendTrackPoint(it, force = true) }
        refreshSettings()
    }

    private fun onRecordingFinalized(event: VideoRecordEvent.Finalize) {
        val file = activeVideoFile
        val plateSession = activePlateSession
        activeRecording = null
        activePlateSession = null
        activeVideoFile = null
        if (!rollingRestart) {
            activeHdrEnabled = false
            hdrActiveSnapshot = false
        }
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.removeCallbacks(segmentRollover)

        if (!event.hasError() && file != null) {
            scanFile(file, "video/mp4")
            activeSubtitleFile?.takeIf { it.exists() }?.let { scanFile(it, "application/x-subrip") }
            writeActiveTrack(file)
            plateSession?.finish(this, file)
            val limitGb = getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                .getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
            VideoStorage.cleanupLoopStorage(this, limitGb)
        } else {
            plateSession?.discard()
        }
        activeSubtitleFile = null
        activeVideoStartWallMs = 0L
        resetActiveTrack()

        if (rollingRestart && !requestedStop && !stoppingSelf) {
            val needsRebind = rollingRestartNeedsRebind
            rollingRestart = false
            rollingRestartNeedsRebind = false
            if (needsRebind) bindCameraUseCases()
            startVideoCapture()
            return
        }

        releaseCameraUseCases()
        writeTripDriveRecord()
        clearActiveTrip()
        setRecordingState(this, false)
        refreshSettings()
        startForegroundCompat(includeCameraType = false)
        if (!stoppingSelf && !shouldKeepAliveForVoice()) stopSelf()
    }

    private fun stopRecordingInternal() {
        requestedStop = true
        rollingRestart = false
        rollingRestartNeedsRebind = false
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(segmentRollover)
        seamlessRecorder?.stop()
        if (seamlessRecorder != null) {
            mainHandler.removeCallbacks(subtitleTicker)
            return
        }
        activeRecording?.stop()
        mainHandler.removeCallbacks(subtitleTicker)
        if (activeRecording == null) {
            releaseCameraUseCases()
            setRecordingState(this, false)
            if (!shouldKeepAliveForVoice()) stopVoiceRecognition()
            activePlateSession?.discard()
            activePlateSession = null
            resetActiveTrack()
            clearActiveTrip()
            startForegroundCompat(includeCameraType = false)
        }
    }

    private fun releaseCameraUseCases() {
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        previewUseCase = null
        videoCapture = null
        imageCapture = null
        imageAnalysis = null
        activePlateRecognitionBound = false
        PlateRecognitionMonitor.onActive(false, false)
    }

    private fun takePhotoInternal() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, R.string.photo_unavailable_while_plate_recognition, Toast.LENGTH_SHORT).show()
            return
        }
        val outputFile = VideoStorage.photoOutputFile(this)
        val tempFile = VideoStorage.tempPhotoFile(this)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()
        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    PhotoWatermark.apply(this@DashcamService, tempFile, activeVideoMode ?: CameraCapabilities.selectedVideoMode(this@DashcamService))
                    val published = VideoStorage.publishPhotoToGallery(this@DashcamService, tempFile, outputFile.name)
                    tempFile.delete()
                    scanFile(published, "image/jpeg")
                    mainHandler.post {
                        Toast.makeText(this@DashcamService, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainHandler.post {
                        Toast.makeText(this@DashcamService, R.string.photo_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun appendSubtitleTick() {
        val file = activeSubtitleFile ?: return
        val lines = buildWatermarkLines(forceTime = false)
        if (lines.isEmpty()) return
        val elapsedMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs
        val startMs = elapsedMs.coerceAtLeast(0L)
        val endMs = startMs + 1_000L
        subtitleIndex += 1
        file.appendText(
            buildString {
                appendLine(subtitleIndex)
                appendLine("${formatSrtTime(startMs)} --> ${formatSrtTime(endMs)}")
                appendLine(lines.joinToString("  |  "))
                appendLine()
            }
        )
    }

    private fun appendTrackPoint(location: Location, force: Boolean = false) {
        if (!isRecordingActive() || recordingStartElapsedMs <= 0L) return
        if (activeTrackPoints.size >= MAX_TRACK_POINTS_PER_SEGMENT) return
        val elapsedMs = (SystemClock.elapsedRealtime() - recordingStartElapsedMs).coerceAtLeast(0L)
        if (!force &&
            activeTrackPoints.isNotEmpty() &&
            elapsedMs - lastTrackPointElapsedMs < TRACK_POINT_INTERVAL_MS
        ) {
            return
        }

        val point = VideoTrackPoint(
            elapsedMs = elapsedMs,
            timeMs = location.time.takeIf { it > 0L } ?: (activeVideoStartWallMs + elapsedMs),
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = if (location.hasSpeed()) SpeedRules.toKmhClamped(location.speed) else lastSpeedKmh,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() }
        )
        activeTrackPoints += point
        val tripElapsedMs = (SystemClock.elapsedRealtime() - tripRecordingStartElapsedMs).coerceAtLeast(0L)
        activeTripTrackPoints += point.copy(elapsedMs = tripElapsedMs)
        lastTrackPointElapsedMs = elapsedMs
    }

    private fun writeActiveTrack(videoFile: File): File? {
        if (activeTrackPoints.isEmpty()) return null
        return try {
            val track = VideoTrack(
                videoFileName = videoFile.name,
                startTimeMs = activeVideoStartWallMs,
                points = activeTrackPoints.toList()
            )
            VideoTrackStorage.writeTrack(
                context = this,
                videoFile = videoFile,
                track = track
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeTripDriveRecord() {
        if (activeTripTrackPoints.isEmpty()) return
        val track = VideoTrack(
            videoFileName = activeTripFolderName.orEmpty(),
            startTimeMs = activeTripStartWallMs,
            points = activeTripTrackPoints.toList()
        )
        writeDriveRecord(track)
    }

    private fun writeDriveRecord(track: VideoTrack) {
        val points = track.points
        if (points.isEmpty()) return
        val summary = TrackMetrics.summary(points)
        val maxPoint = points.maxByOrNull { it.speedKmh }
        val startTimeMs = track.startTimeMs.takeIf { it > 0L } ?: points.first().timeMs
        val endTimeMs = points.last().timeMs.takeIf { it > 0L } ?: (startTimeMs + summary.durationMs)
        DriveRecordStore.addRecord(
            this,
            DriveRecord(
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                durationMs = summary.durationMs,
                maxSpeedKmh = summary.maxSpeedKmh,
                distanceMeters = summary.totalDistanceMeters,
                averageSpeedKmh = summary.averageSpeedKmh,
                maxSpeedLatitude = maxPoint?.latitude,
                maxSpeedLongitude = maxPoint?.longitude,
                maxSpeedAccuracyMeters = maxPoint?.accuracyMeters,
                averageAccuracyMeters = summary.averageAccuracyMeters,
                goodFixRatio = summary.goodFixRatio,
                source = DriveRecordStore.SOURCE_VIDEO,
                routePoints = points.map { point ->
                    DriveRoutePoint(
                        timeMs = point.timeMs,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        speedKmh = point.speedKmh,
                        accuracyMeters = point.accuracyMeters,
                        bearingDegrees = point.bearingDegrees
                    )
                }
            )
        )
    }

    private fun resetActiveTrack() {
        activeTrackPoints.clear()
        lastTrackPointElapsedMs = 0L
    }

    private fun clearActiveTrip() {
        activeTripFolderName = null
        activeTripStartWallMs = 0L
        tripRecordingStartElapsedMs = 0L
        activeTripTrackPoints.clear()
    }

    private fun buildWatermarkLines(forceTime: Boolean): List<String> {
        updateTelemetrySnapshot()
        return DashcamOverlayText.lines(this, activeVideoMode ?: CameraCapabilities.selectedVideoMode(this), forceTime)
    }

    private fun refreshSettings() {
        applyFocusSetting()
        val settings = DashcamSettings.load(this)
        lastLocation?.let { maybeResolveAddress(it) }
        if (activeRecording != null && DashcamHdrSettings.shouldEnableNow(this) != activeHdrRequested && !rollingRestart) {
            rollingRestart = true
            rollingRestartNeedsRebind = true
            activeRecording?.stop()
            return
        }
        if (activeRecording != null && settings.plateRecognitionEnabled != activePlateRecognitionRequested && !rollingRestart) {
            rollingRestart = true
            rollingRestartNeedsRebind = true
            activeRecording?.stop()
            return
        }
        if (settings.voicePhotoEnabled && hasAudioPermission() && !shouldReserveMicrophoneForRecording(settings)) {
            startVoiceRecognition()
        } else {
            stopVoiceRecognition()
        }
        startForegroundCompat(includeCameraType = isRecordingActive())
        if (!isRecordingActive() && !shouldKeepAliveForVoice()) {
            stopSelf()
        }
    }

    private fun updateParkingStop(speedKmh: Float) {
        if (!isRecordingActive() || requestedStop) {
            parkingStationarySinceElapsedMs = null
            return
        }
        val settings = DashcamSettings.load(this)
        if (!settings.parkingStopEnabled) {
            parkingStationarySinceElapsedMs = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        parkingStationarySinceElapsedMs = ParkingStopRules.nextStationaryStartMs(
            currentStartMs = parkingStationarySinceElapsedMs,
            speedKmh = speedKmh,
            nowMs = now
        )
        if (ParkingStopRules.shouldStopRecording(
                enabled = true,
                thresholdMinutes = settings.parkingStopMinutes,
                stationaryStartMs = parkingStationarySinceElapsedMs,
                nowMs = now
            )
        ) {
            Toast.makeText(this, R.string.parking_stop_recording_finished, Toast.LENGTH_SHORT).show()
            stopRecordingInternal()
        }
    }

    private fun applyFocusSetting() {
        CameraFocus.apply(this, camera)
    }

    private fun maybeFocusOnPlate(frame: PlateOverlayFrame) {
        if (!isRecordingActive() || requestedStop) return
        if (!DashcamSettings.load(this).plateRecognitionEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlateFocusElapsedMs < PLATE_FOCUS_INTERVAL_MS) return
        if (CameraFocus.focusOnPlate(this, camera, frame)) {
            lastPlateFocusElapsedMs = now
            PlateRecognitionMonitor.onSmartFocusTriggered(System.currentTimeMillis())
        }
    }

    private fun startVoiceRecognition() {
        VoicePhotoRecognizer.start(this, VOICE_OWNER) { command ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastVoicePhotoAtMs <= 3_000L) return@start
            lastVoicePhotoAtMs = now
            mainHandler.post {
                when (command) {
                    VoiceCommand.TAKE_PHOTO -> {
                        VoiceCommandFeedback.photoTaken(this)
                        if (isRecordingActive()) {
                            takePhotoInternal()
                        } else {
                            CameraRecordActivity.takePhoto(this)
                        }
                    }
                    VoiceCommand.START_RECORDING -> {
                        if (isRecordingActive()) {
                            VoiceCommandFeedback.alreadyRecording(this)
                        } else {
                            VoiceCommandFeedback.recordingStarted(this)
                            startRecordingInternal()
                        }
                    }
                    VoiceCommand.STOP_RECORDING -> {
                        if (isRecordingActive()) {
                            VoiceCommandFeedback.recordingStopped(this)
                            stopRecordingInternal()
                        } else {
                            VoiceCommandFeedback.notRecording(this)
                        }
                    }
                    VoiceCommand.RADAR_ALERT_ON -> enableRadarAlertFromVoice()
                    VoiceCommand.RADAR_ALERT_OFF -> disableRadarAlertFromVoice()
                }
            }
        }
    }

    private fun enableRadarAlertFromVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            VoiceCommandFeedback.radarAlertNeedsLocation(this)
            return
        }
        getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, true)
            .commit()
        RadarAlertService.sync(this)
        VoiceCommandFeedback.radarAlertStarted(this)
    }

    private fun disableRadarAlertFromVoice() {
        getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, false)
            .commit()
        RadarAlertService.stop(this)
        VoiceCommandFeedback.radarAlertStopped(this)
    }

    private fun stopVoiceRecognition() {
        VoicePhotoRecognizer.stop(VOICE_OWNER)
    }

    private fun shouldKeepAliveForVoice(): Boolean {
        return DashcamSettings.load(this).voicePhotoEnabled && hasAudioPermission()
    }

    private fun shouldReserveMicrophoneForRecording(settings: WatermarkSettings): Boolean {
        return isRecordingActive() && settings.recordAudio
    }

    private fun maybeResolveAddress(location: Location) {
        if (!DashcamSettings.load(this).showAddress) return
        if (!Geocoder.isPresent()) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastAddressLocation
        val movedEnough = previous == null || previous.distanceTo(location) > ADDRESS_REFRESH_DISTANCE_METERS
        if (!movedEnough && now - lastGeocodeAtMs < ADDRESS_REFRESH_INTERVAL_MS) return
        lastGeocodeAtMs = now
        lastAddressLocation = Location(location)
        cameraExecutor.execute {
            val text = reverseGeocode(location)
            if (text != null) {
                lastAddressText = text
                updateTelemetrySnapshot()
            }
        }
    }

    private fun updateTelemetrySnapshot() {
        val location = lastLocation
        telemetrySnapshot = DashcamTelemetry(
            speedKmh = lastSpeedKmh,
            latitude = location?.latitude,
            longitude = location?.longitude,
            address = lastAddressText,
            focusDistanceDiopters = lastFocusDistanceDiopters,
            autofocusState = lastAutofocusState
        )
    }

    private fun updateFocusTelemetry(result: CaptureResult) {
        lastFocusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        lastAutofocusState = autofocusStateLabel(result.get(CaptureResult.CONTROL_AF_STATE))
        updateTelemetrySnapshot()
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(location: Location): String? {
        return try {
            val address = Geocoder(this, Locale.CHINA)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?: return null
            formatChinaAddress(address)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun formatChinaAddress(address: Address): String {
        val country = when (address.countryCode?.uppercase(Locale.US)) {
            "CN" -> "中国大陆"
            else -> address.countryName ?: "位置"
        }
        val parts = listOfNotNull(
            country,
            address.adminArea,
            address.locality ?: address.subAdminArea,
            address.subLocality,
            address.thoroughfare,
            address.featureName
        ).filter { it.isNotBlank() }
        return parts.distinct().joinToString(" ")
    }

    private fun registerLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        } catch (_: RuntimeException) {
            // 定位不可用时只是不显示 GPS 水印，不影响录像。
        }
    }

    private fun unregisterLocationUpdates() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            manager.removeUpdates(this)
        } catch (_: RuntimeException) {
            // ignore teardown exception
        }
    }

    private fun scanFile(file: File, mimeType: String) {
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundCompat(includeCameraType: Boolean = false) {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType(includeCameraType))
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(includeCameraType: Boolean): Int {
        var type = 0
        if (includeCameraType) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val settings = DashcamSettings.load(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (settings.voicePhotoEnabled || settings.recordAudio) &&
            hasAudioPermission()
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun createNotificationChannel() {
        DashcamNotificationGuard.ensureRecordingChannel(this)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CameraRecordActivity::class.java),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DashcamService::class.java).apply { action = ACTION_STOP_RECORDING },
            flags
        )
        val photoIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DashcamService::class.java).apply { action = ACTION_TAKE_PHOTO },
            flags
        )
        val recording = isRecordingActive() || isRecording(this)
        val notificationTime = if (recording && activeVideoStartWallMs > 0L) activeVideoStartWallMs else System.currentTimeMillis()
        return NotificationCompat.Builder(this, DashcamNotificationGuard.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(if (recording) R.string.dashcam_notif_recording else R.string.dashcam_notif_ready))
            .setContentText(getString(if (recording) R.string.dashcam_notif_text_recording else R.string.dashcam_notif_text_ready))
            .setContentIntent(openIntent)
            .setWhen(notificationTime)
            .setShowWhen(recording)
            .setUsesChronometer(recording)
            .setChronometerCountDown(false)
            .addAction(android.R.drawable.ic_menu_camera, getString(R.string.dashcam_action_photo), photoIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(recording)
            .build()
    }

    private fun formatSrtTime(ms: Long): String {
        val totalMs = ms.coerceAtLeast(0L)
        val h = totalMs / 3_600_000L
        val m = (totalMs % 3_600_000L) / 60_000L
        val s = (totalMs % 60_000L) / 1_000L
        val milli = totalMs % 1_000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    companion object {
        const val ACTION_START_RECORDING = "com.thomas.carspeed.action.DASHCAM_START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.thomas.carspeed.action.DASHCAM_STOP_RECORDING"
        const val ACTION_TAKE_PHOTO = "com.thomas.carspeed.action.DASHCAM_TAKE_PHOTO"
        const val ACTION_REFRESH_SETTINGS = "com.thomas.carspeed.action.DASHCAM_REFRESH_SETTINGS"

        private const val NOTIFICATION_ID = 2001
        private const val KEY_RECORDING = "dashcam_recording"
        private const val KEY_RECORDING_START_MS = "dashcam_recording_start_ms"
        private const val ADDRESS_REFRESH_INTERVAL_MS = 60_000L
        private const val ADDRESS_REFRESH_DISTANCE_METERS = 200f
        private const val TRACK_POINT_INTERVAL_MS = 1_000L
        private const val MAX_TRACK_POINTS_PER_SEGMENT = 30_000
        private const val PLATE_FOCUS_INTERVAL_MS = 5_000L
        private const val VOICE_OWNER = "service"

        @Volatile
        var instance: DashcamService? = null
            private set

        @Volatile
        private var previewSurfaceProvider: Preview.SurfaceProvider? = null

        @Volatile
        private var recordingPreviewSurface: Surface? = null

        @Volatile
        private var telemetrySnapshot = DashcamTelemetry()

        fun currentTelemetry(): DashcamTelemetry = telemetrySnapshot

        fun updatePreviewFocusTelemetry(focusDistanceDiopters: Float?, autofocusState: Int?) {
            telemetrySnapshot = telemetrySnapshot.copy(
                focusDistanceDiopters = focusDistanceDiopters,
                autofocusState = autofocusStateLabel(autofocusState)
            )
        }

        fun autofocusStateLabel(state: Int?): String? {
            return when (state) {
                CaptureResult.CONTROL_AF_STATE_INACTIVE -> "AF空闲"
                CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "AF扫描"
                CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "AF已对焦"
                CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "AF主动扫描"
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "AF锁定"
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "AF未锁定"
                CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "AF未对焦"
                else -> null
            }
        }

        @Volatile
        private var hdrActiveSnapshot = false

        fun isHdrActive(): Boolean = hdrActiveSnapshot

        fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            instance?.updatePreviewSurfaceProvider(provider)
        }

        fun setRecordingPreviewSurface(surface: Surface?) {
            recordingPreviewSurface = surface
            instance?.updateRecordingPreviewSurface(surface)
        }

        fun startRecording(context: Context) {
            CameraRecordActivity.prepareForServiceRecording()
            CameraFullscreenActivity.prepareForServiceRecording()
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_START_RECORDING }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_STOP_RECORDING }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: RuntimeException) {
                context.startService(intent)
            }
        }

        fun takePhoto(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_TAKE_PHOTO }
            context.startService(intent)
        }

        fun refreshSettings(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_REFRESH_SETTINGS }
            ContextCompat.startForegroundService(context, intent)
        }

        fun isRecording(context: Context): Boolean {
            val active = instance?.isRecordingActive() == true
            val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            if (!active && instance == null && prefs.getBoolean(KEY_RECORDING, false)) {
                setRecordingState(context, false)
            }
            return active
        }

        fun recordingElapsedSeconds(context: Context): Long {
            if (!isRecording(context)) return 0L
            val start = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_RECORDING_START_MS, 0L)
            if (start <= 0L) return 0L
            return ((System.currentTimeMillis() - start) / 1_000L).coerceAtLeast(0L)
        }

        private fun setRecordingState(context: Context, recording: Boolean) {
            context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RECORDING, recording)
                .apply {
                    if (recording) {
                        putLong(KEY_RECORDING_START_MS, System.currentTimeMillis())
                    } else {
                        remove(KEY_RECORDING_START_MS)
                    }
                }
                .apply()
        }
    }
}
