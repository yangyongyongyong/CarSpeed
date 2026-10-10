package com.thomas.carspeed

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Range
import android.widget.Toast
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
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
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DashcamService : LifecycleService(), LocationListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null
    private var activeRecording: Recording? = null
    private var activeVideoFile: File? = null
    private var activeSubtitleFile: File? = null
    private var activeVideoMode: VideoMode? = null
    private var activeVideoStartWallMs = 0L
    private var activeSegmentDurationMs = DashcamSettings.DEFAULT_SEGMENT_MINUTES * 60_000L
    private var subtitleIndex = 0
    private var recordingStartElapsedMs = 0L
    private var requestedStop = false
    private var rollingRestart = false
    private var lastLocation: Location? = null
    private var lastAddressLocation: Location? = null
    private var lastAddressText: String? = null
    private var lastGeocodeAtMs = 0L
    private var lastSpeedKmh = 0f
    private var lastVoicePhotoAtMs = 0L
    private var parkingStationarySinceElapsedMs: Long? = null
    private val activeTrackPoints = mutableListOf<VideoTrackPoint>()
    private var lastTrackPointElapsedMs = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceListening = false
    private var stoppingSelf = false

    private val subtitleTicker = object : Runnable {
        override fun run() {
            appendSubtitleTick()
            if (activeRecording != null) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val segmentRollover = Runnable {
        if (activeRecording != null && !requestedStop) {
            rollingRestart = true
            activeRecording?.stop()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundCompat()
        registerLocationUpdates()
    }

    override fun onDestroy() {
        stoppingSelf = true
        stopVoiceRecognition()
        stopRecordingInternal()
        unregisterLocationUpdates()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        instance = null
        setRecordingState(this, false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat(includeCameraType = activeRecording != null)
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecordingInternal()
            ACTION_STOP_RECORDING -> {
                val hadRecording = activeRecording != null
                stopRecordingInternal()
                if (!hadRecording) stopSelf()
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
        if (activeRecording != null) return
        requestedStop = false
        rollingRestart = false
        parkingStationarySinceElapsedMs = null
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

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    bindCameraUseCases()
                    startVideoCapture()
                } catch (e: RuntimeException) {
                    Toast.makeText(this, getString(R.string.camera_open_failed, e.message ?: "unknown"), Toast.LENGTH_SHORT).show()
                    stopSelf()
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    @AndroidXOptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val selectedMode = CameraCapabilities.selectedVideoMode(this)
        activeVideoMode = selectedMode
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
        Camera2Interop.Extender(videoBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(selectedMode.fps, selectedMode.fps)
        )
        videoCapture = videoBuilder.build()
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val useCases = mutableListOf<UseCase>()
        previewSurfaceProvider?.let { surfaceProvider ->
            val preview = Preview.Builder().build().also { preview ->
                preview.setSurfaceProvider(surfaceProvider)
            }
            previewUseCase = preview
            useCases += preview
        }
        useCases += videoCapture ?: return
        useCases += imageCapture ?: return

        provider.unbindAll()
        camera = provider.bindToLifecycle(
            this,
            CameraSelector.DEFAULT_BACK_CAMERA,
            *useCases.toTypedArray()
        )
        applyZoomSetting()
    }

    private fun updatePreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
        if (provider != null) {
            previewUseCase?.setSurfaceProvider(provider)
        }
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
        val outputFile = VideoStorage.outputFile(this, startTimeMs)
        activeVideoFile = outputFile
        activeSubtitleFile = VideoStorage.subtitleFileFor(outputFile)
        activeVideoStartWallMs = startTimeMs
        subtitleIndex = 0
        recordingStartElapsedMs = SystemClock.elapsedRealtime()
        resetActiveTrack()

        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .let { pending -> enableAudioIfAllowed(pending) }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> onRecordingStarted()
                    is VideoRecordEvent.Finalize -> onRecordingFinalized(event)
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun enableAudioIfAllowed(pending: PendingRecording): PendingRecording {
        if (!DashcamSettings.load(this).recordAudio) return pending
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return pending
        }
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
        activeRecording = null
        activeVideoFile = null
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.removeCallbacks(segmentRollover)

        if (!event.hasError() && file != null) {
            scanFile(file, "video/mp4")
            activeSubtitleFile?.takeIf { it.exists() }?.let { scanFile(it, "application/x-subrip") }
            writeActiveTrack(file)?.let { scanFile(it, "application/json") }
            val limitGb = getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                .getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
            VideoStorage.cleanupLoopStorage(this, limitGb)
        }
        activeSubtitleFile = null
        activeVideoStartWallMs = 0L
        resetActiveTrack()

        if (rollingRestart && !requestedStop && !stoppingSelf) {
            rollingRestart = false
            bindCameraUseCases()
            startVideoCapture()
            return
        }

        setRecordingState(this, false)
        stopVoiceRecognition()
        startForegroundCompat(includeCameraType = false)
        if (!stoppingSelf) stopSelf()
    }

    private fun stopRecordingInternal() {
        requestedStop = true
        rollingRestart = false
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(segmentRollover)
        activeRecording?.stop()
        mainHandler.removeCallbacks(subtitleTicker)
        if (activeRecording == null) {
            setRecordingState(this, false)
            stopVoiceRecognition()
            resetActiveTrack()
        }
    }

    private fun takePhotoInternal() {
        val capture = imageCapture ?: return
        val outputFile = VideoStorage.photoOutputFile(this)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    applyPhotoWatermark(outputFile)
                    scanFile(outputFile, "image/jpeg")
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

    private fun applyPhotoWatermark(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val lines = buildWatermarkLines(forceTime = true)
        drawWatermark(canvas, lines)
        FileOutputStream(file).use { out ->
            mutable.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (mutable != bitmap) mutable.recycle()
        bitmap.recycle()
    }

    private fun drawWatermark(canvas: Canvas, lines: List<String>) {
        if (lines.isEmpty()) return
        val density = resources.displayMetrics.density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 18f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 1f * density, 1f * density, Color.BLACK)
        }
        val lineHeight = 24f * density
        val padding = 14f * density
        val width = lines.maxOf { textPaint.measureText(it) } + padding * 2
        val height = lineHeight * lines.size + padding * 2
        val left = padding
        val top = canvas.height - height - padding
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 0, 0)
        }
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 12f * density, 12f * density, bgPaint)
        lines.forEachIndexed { index, text ->
            canvas.drawText(text, left + padding, top + padding + lineHeight * (index + 1) - 6f * density, textPaint)
        }
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
        if (activeRecording == null || recordingStartElapsedMs <= 0L) return
        if (activeTrackPoints.size >= MAX_TRACK_POINTS_PER_SEGMENT) return
        val elapsedMs = (SystemClock.elapsedRealtime() - recordingStartElapsedMs).coerceAtLeast(0L)
        if (!force &&
            activeTrackPoints.isNotEmpty() &&
            elapsedMs - lastTrackPointElapsedMs < TRACK_POINT_INTERVAL_MS
        ) {
            return
        }

        activeTrackPoints += VideoTrackPoint(
            elapsedMs = elapsedMs,
            timeMs = location.time.takeIf { it > 0L } ?: (activeVideoStartWallMs + elapsedMs),
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = if (location.hasSpeed()) SpeedRules.toKmhClamped(location.speed) else lastSpeedKmh,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() }
        )
        lastTrackPointElapsedMs = elapsedMs
    }

    private fun writeActiveTrack(videoFile: File): File? {
        if (activeTrackPoints.isEmpty()) return null
        return try {
            VideoTrackStorage.writeTrack(
                videoFile = videoFile,
                track = VideoTrack(
                    videoFileName = videoFile.name,
                    startTimeMs = activeVideoStartWallMs,
                    points = activeTrackPoints.toList()
                )
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun resetActiveTrack() {
        activeTrackPoints.clear()
        lastTrackPointElapsedMs = 0L
    }

    private fun buildWatermarkLines(forceTime: Boolean): List<String> {
        updateTelemetrySnapshot()
        return DashcamOverlayText.lines(this, activeVideoMode ?: CameraCapabilities.selectedVideoMode(this), forceTime)
    }

    private fun refreshSettings() {
        applyZoomSetting()
        val settings = DashcamSettings.load(this)
        lastLocation?.let { maybeResolveAddress(it) }
        if (settings.voicePhotoEnabled && hasAudioPermission() && activeRecording != null) {
            startVoiceRecognition()
        } else {
            stopVoiceRecognition()
        }
        startForegroundCompat(includeCameraType = activeRecording != null)
    }

    private fun updateParkingStop(speedKmh: Float) {
        if (activeRecording == null || requestedStop) {
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

    private fun applyZoomSetting() {
        val activeCamera = camera ?: return
        val prefs = getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
        val percent = prefs.getInt(DashcamSettings.PREF_ZOOM_PERCENT, DashcamSettings.DEFAULT_ZOOM_PERCENT)
            .coerceIn(10, 100)
        val zoomState = activeCamera.cameraInfo.zoomState.value ?: return
        val minZoom = zoomState.minZoomRatio
        val maxZoom = zoomState.maxZoomRatio
        val t = (percent - 10) / 90f
        val zoomRatio = minZoom + (maxZoom - minZoom) * t
        activeCamera.cameraControl.setZoomRatio(zoomRatio.coerceIn(minZoom, maxZoom))
    }

    private fun startVoiceRecognition() {
        if (voiceListening || !SpeechRecognizer.isRecognitionAvailable(this)) return
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onPartialResults(partialResults: Bundle?) {
                    handleVoiceResults(partialResults)
                }

                override fun onResults(results: Bundle?) {
                    handleVoiceResults(results)
                    scheduleVoiceRestart()
                }

                override fun onError(error: Int) {
                    voiceListening = false
                    scheduleVoiceRestart()
                }
            })
        }
        voiceListening = true
        recognizer.startListening(voiceIntent())
    }

    private fun stopVoiceRecognition() {
        voiceListening = false
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun scheduleVoiceRestart() {
        voiceListening = false
        if (DashcamSettings.load(this).voicePhotoEnabled && activeRecording != null && hasAudioPermission()) {
            mainHandler.postDelayed({ startVoiceRecognition() }, 500L)
        }
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
            address = lastAddressText
        )
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

    private fun handleVoiceResults(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (matches.any { it.contains("我要拍照") || it.contains("拍照") || it.contains("照相") }) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVoicePhotoAtMs > 3_000L) {
                lastVoicePhotoAtMs = now
                takePhotoInternal()
            }
        }
    }

    private fun voiceIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && includeCameraType) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
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
        val recording = activeRecording != null || isRecording(this)
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

        @Volatile
        var instance: DashcamService? = null
            private set

        @Volatile
        private var previewSurfaceProvider: Preview.SurfaceProvider? = null

        @Volatile
        private var telemetrySnapshot = DashcamTelemetry()

        fun currentTelemetry(): DashcamTelemetry = telemetrySnapshot

        fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider?) {
            previewSurfaceProvider = provider
            instance?.updatePreviewSurfaceProvider(provider)
        }

        fun startRecording(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_START_RECORDING }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_STOP_RECORDING }
            context.startService(intent)
        }

        fun takePhoto(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_TAKE_PHOTO }
            context.startService(intent)
        }

        fun refreshSettings(context: Context) {
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_REFRESH_SETTINGS }
            context.startService(intent)
        }

        fun isRecording(context: Context): Boolean {
            val active = instance?.activeRecording != null
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
