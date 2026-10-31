package com.thomas.carspeed

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CaptureResult
import android.location.Address
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class DashcamService : LifecycleService(), LocationListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val plateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // 字幕、轨迹、清理这些文件读写都排在同一条后台线程上，既不卡主线程，也不会互相交错。
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var roadLayerStarted = false

    private var seamlessRecorder: SeamlessDashcamRecorder? = null
    @Volatile private var activePlateSession: PlateRecognitionSession? = null
    private val plateAnalysisBusy = AtomicBoolean(false)
    private var activeVideoFile: File? = null
    private var activeSubtitleFile: File? = null
    private val tripSubtitleFiles = mutableListOf<Pair<File, Long>>()
    private var activeVideoMode: VideoMode? = null
    private var activeHdrRequested = false
    private var activeSegmentDurationMs = DashcamSettings.DEFAULT_SEGMENT_MINUTES * 60_000L
    private var activeTripFolderName: String? = null
    private var activeTripStartWallMs = 0L
    private var activeVideoStartWallMs = 0L
    private var subtitleIndex = 0
    private var recordingStartElapsedMs = 0L
    private var tripRecordingStartElapsedMs = 0L
    private var requestedStop = false
    private var recordingCueOpen = false
    private var exitAfterStop = false
    private var recorderFailed = false
    private var reconnectPending = false
    private var reconnectAttempts = 0
    private var destroyed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var powerReceiver: PowerTriggerReceiver? = null
    private var lastLocation: Location? = null
    private var lastAddressLocation: Location? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var gpsUsedSatelliteCount: Int? = null
    private var lastAddressText: String? = null
    private var lastGeocodeAtMs = 0L
    private var lastSpeedKmh = 0f
    private var stationaryWaitState = SpeedRules.WaitState(
        tripStarted = false,
        tripStartTimeMs = 0L,
        waitAtZeroAccumMs = 0L,
        waitAtZeroStartMs = 0L
    )
    private var lastFocusDistanceDiopters: Float? = null
    private var lastAutofocusState: String? = null
    private val voiceCommandCooldown = VoiceCommandCooldown()
    private var parkingStationarySinceElapsedMs: Long? = null
    private val activeTrackPoints = mutableListOf<VideoTrackPoint>()
    private val activeTripTrackPoints = mutableListOf<VideoTrackPoint>()
    private var lastTrackPointElapsedMs = 0L
    private var stoppingSelf = false
    private var targetRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null

    private val subtitleTicker = object : Runnable {
        override fun run() {
            appendSubtitleTick()
            if (isRecordingActive()) {
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }

    private val reconnectRunnable = Runnable { reconnectRecording() }

    private val plateFrameListener = SeamlessDashcamRecorder.StillFrameListener { jpeg, elapsedMs ->
        val session = activePlateSession ?: return@StillFrameListener
        if (!plateAnalysisBusy.compareAndSet(false, true)) return@StillFrameListener
        val queued = runInBackground(plateExecutor) {
            try {
                session.analyzeJpeg(jpeg, elapsedMs)
            } finally {
                plateAnalysisBusy.set(false)
            }
        }
        if (!queued) plateAnalysisBusy.set(false)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        targetRotation = CameraRotation.displayRotation(this)
        startOrientationListener()
        registerLocationUpdates()
        roadLayerStarted = true
        RoadLayerEngine.start(this)
        runInBackground(cameraExecutor) {
            runCatching { VideoStorage.consolidateIntoAlbum(this) }
        }
    }

    override fun onDestroy() {
        hideRecordingBubble()
        stoppingSelf = true
        destroyed = true
        stopVoiceRecognition()
        stopOrientationListener()
        val recorderStillRunning = seamlessRecorder != null
        stopRecordingInternal()
        unregisterLocationUpdates()
        unregisterPowerReceiver()
        releaseRecordingWakeLock()
        if (roadLayerStarted) {
            roadLayerStarted = false
            RoadLayerEngine.stop()
        }
        // 录像器还在收尾时，最后一段的轨迹和清理要等它回调完再关线程池。
        if (!recorderStillRunning) shutdownExecutors()
        instance = null
        setRecordingState(this, false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val startingRecording = intent?.action == ACTION_START_RECORDING
        val foreground = startForegroundCompat(includeCameraType = isRecordingActive() || startingRecording)
        if (!foreground && !isRecordingActive()) {
            if (startingRecording) {
                Toast.makeText(this, BACKGROUND_START_BLOCKED_MESSAGE, Toast.LENGTH_LONG).show()
                if (shouldKeepAliveForVoice() && startForegroundCompat(includeCameraType = false)) {
                    return START_STICKY
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) {
            handleStickyRestart()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START_RECORDING -> startRecordingInternal()
            ACTION_STOP_RECORDING -> {
                val hadRecording = isRecordingActive()
                stopRecordingInternal()
                if (!hadRecording && !shouldKeepAliveForVoice()) stopSelf()
            }
            ACTION_EXIT -> {
                exitAfterStop = true
                stopVoiceRecognition()
                stopRecordingInternal()
                if (!isRecordingActive()) stopSelf()
            }
            ACTION_TAKE_PHOTO -> takePhotoInternal()
            ACTION_REFRESH_SETTINGS -> refreshSettings()
        }
        return if (exitAfterStop) START_NOT_STICKY else START_STICKY
    }

    /** 进程被系统杀掉后 START_STICKY 拉起时没有 intent，原来的录像已经断了。 */
    private fun handleStickyRestart() {
        val shouldResume = consumeResumeBudget(this)
        clearStaleRecordingFlag(this)
        if (shouldResume) {
            // 摄像头服务不能从后台直接开，借透明页取得前台后再接着录。
            runCatching { BackgroundRecordingStart.start(applicationContext) }
        }
        if (!shouldKeepAliveForVoice()) stopSelf()
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val now = SystemClock.elapsedRealtime()
        RoadLayerEngine.onGpsFix(
            now,
            location.accuracy.takeIf { location.hasAccuracy() },
            location.bearing.takeIf { location.hasBearing() }
        )
        RoadLayerEngine.consumeSubtitleCorrection()?.let { rewriteRecordedRoadLayer(it) }
        if (location.hasSpeed()) {
            lastSpeedKmh = SpeedRules.toKmhClamped(location.speed)
            updateParkingStop(lastSpeedKmh)
            updateStationaryWait(lastSpeedKmh)
            RoadLayerEngine.onSpeed(now, lastSpeedKmh)
        }
        updateTelemetrySnapshot()
        appendTrackPoint(location)
        maybeResolveAddress(location)
    }

    private fun startRecordingInternal() {
        if (isRecordingActive()) return
        requestedStop = false
        recorderFailed = false
        reconnectAttempts = 0
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
        if (!startForegroundCompat(includeCameraType = true)) {
            Toast.makeText(this, BACKGROUND_START_BLOCKED_MESSAGE, Toast.LENGTH_LONG).show()
            if (!shouldKeepAliveForVoice()) stopSelf()
            return
        }
        // 上次被杀时可能留下“正在录像”的旧计时，新的一趟从头算。
        setRecordingState(this, false)
        val tripStartMs = System.currentTimeMillis()
        activeTripStartWallMs = tripStartMs
        activeTripFolderName = VideoStorage.tripFolderNameFor(tripStartMs)
        tripRecordingStartElapsedMs = SystemClock.elapsedRealtime()
        activeTripTrackPoints.clear()
        resetStationaryWait()

        if (!startSeamlessVideoCapture()) {
            clearActiveTrip()
            stopSelf()
        }
    }

    private fun isRecordingActive(): Boolean = seamlessRecorder != null || reconnectPending

    private fun startSeamlessVideoCapture(): Boolean {
        val mode = CameraCapabilities.selectedVideoMode(this)
        val segmentDurationMs = DashcamSettings.segmentDurationMs(this)
        val settings = DashcamSettings.load(this)
        activeVideoMode = mode
        activeSegmentDurationMs = segmentDurationMs
        activeHdrRequested = DashcamHdrSettings.shouldEnableNow(this)
        hdrActiveSnapshot = false
        PlateRecognitionMonitor.onConfigured(
            enabled = settings.plateRecognitionEnabled,
            analyzerBound = settings.plateRecognitionEnabled
        )

        val estimatedSegmentBytes = VideoStorage.estimateSegmentBytes(mode, segmentDurationMs)
        if (!VideoStorage.cleanupLoopStorage(this, loopStorageLimitGb(), estimatedSegmentBytes)) {
            Toast.makeText(this, R.string.storage_too_low_for_recording, Toast.LENGTH_LONG).show()
            return false
        }

        val recordAudio = shouldRecordAudioInRecording()
        val shareVoice = VoiceCapture.mode(
            voiceEnabled = settings.voicePhotoEnabled,
            audioPermission = hasAudioPermission(),
            recording = true
        ) == VoiceCaptureMode.RECORDING_PCM
        // 录像只能占一个麦克风。开着语音时改把这一路 PCM 送给识别，不再把口令听停。
        if (shareVoice) {
            VoicePhotoRecognizer.beginExternalCapture(this, VOICE_OWNER, ::dispatchVoiceCommand)
        } else if (recordAudio) {
            stopVoiceRecognition()
        }
        val recorder = SeamlessDashcamRecorder(
            context = this,
            mode = mode,
            segmentDurationMs = segmentDurationMs,
            tripFolderName = activeTripFolderName,
            targetRotation = targetRotation,
            initialPreviewSurface = recordingPreviewSurface?.takeIf { it.isValid },
            enableAudio = recordAudio,
            enableHdr = activeHdrRequested,
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

                override fun onSegmentFailed(file: File, error: Throwable) {
                    mainHandler.post { onSeamlessSegmentFailed(file, error) }
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

                override fun onHdrStateChanged(enabled: Boolean) {
                    hdrActiveSnapshot = enabled
                }
            }
        )
        if (shareVoice) recorder.recordingPcmListener = recordingPcmListener
        recorder.memoryStillsEnabled = settings.voicePhotoEnabled
        if (settings.plateRecognitionEnabled) recorder.stillFrameListener = plateFrameListener
        seamlessRecorder = recorder
        recorder.start()
        return true
    }

    private fun shouldRecordAudioInRecording(): Boolean {
        val settings = DashcamSettings.load(this)
        return settings.recordAudio && hasAudioPermission()
    }

    private fun loopStorageLimitGb(): Int {
        return getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .getInt(DashcamSettings.PREF_LOOP_STORAGE_GB, DashcamSettings.DEFAULT_LOOP_STORAGE_GB)
    }

    private fun onSeamlessRecordingStarted() {
        if (destroyed) return
        playRecordingStartCue()
        setRecordingState(this, true)
        showRecordingBubble()
        acquireRecordingWakeLock()
        registerPowerReceiver()
        startForegroundCompat(includeCameraType = true)
        refreshSettings()
    }

    private fun onSeamlessSegmentStarted(file: File, startWallMs: Long, startElapsedMs: Long) {
        activeVideoFile = file
        activeSubtitleFile = VideoStorage.subtitleFileFor(file)
        rememberSubtitleFile(activeSubtitleFile, startElapsedMs)
        activeVideoStartWallMs = startWallMs
        recordingStartElapsedMs = startElapsedMs
        subtitleIndex = 0
        resetActiveTrack()
        val plateEnabled = DashcamSettings.load(this).plateRecognitionEnabled
        activePlateSession = if (plateEnabled) newPlateSession(startWallMs, startElapsedMs) else null
        PlateRecognitionMonitor.onActive(plateEnabled, plateEnabled)
        acquireRecordingWakeLock()
        // HDR 定时段可能在录像途中开始或结束，每段开头顺手对一下。
        val hdrNow = DashcamHdrSettings.shouldEnableNow(this)
        if (hdrNow != activeHdrRequested) {
            activeHdrRequested = hdrNow
            seamlessRecorder?.updateCaptureSettings(hdrNow)
        }
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.post(subtitleTicker)
        lastLocation?.let { appendTrackPoint(it, force = true) }
    }

    private fun newPlateSession(startWallMs: Long, startElapsedMs: Long): PlateRecognitionSession {
        return PlateRecognitionSession(
            startWallMs = startWallMs,
            startElapsedMs = startElapsedMs,
            analyzerBound = true
        )
    }

    private fun onSeamlessSegmentFinalized(file: File) {
        // 正常收完一段说明相机已稳定，重连次数从头算。
        reconnectAttempts = 0
        val isFirstSegment = activeVideoStartWallMs > 0L && activeVideoStartWallMs == activeTripStartWallMs
        val plateSession = activePlateSession
        activePlateSession = null
        val subtitleFile = activeSubtitleFile
        val track = buildActiveTrack(file)
        val recorder = seamlessRecorder
        val limitGb = loopStorageLimitGb()
        val nextSegmentBytes = VideoStorage.estimateSegmentBytes(
            activeVideoMode ?: CameraCapabilities.selectedVideoMode(this),
            activeSegmentDurationMs
        )
        runInBackground(ioExecutor) {
            scanFile(file, "video/mp4")
            subtitleFile?.takeIf { it.exists() }?.let { scanFile(it, "application/x-subrip") }
            if (isFirstSegment) {
                TripThumbnailGenerator.generate(file)
                    ?.let { thumbnail -> scanFile(thumbnail, "image/jpeg") }
            }
            if (track != null) {
                runCatching { VideoTrackStorage.writeTrack(context = this, videoFile = file, track = track) }
            }
            plateSession?.finish(this, file)
            val writing = recorder?.currentSegmentFile()
            val protectedFiles = listOfNotNull(writing, writing?.let { VideoStorage.subtitleFileFor(it) })
            val enoughSpace = runCatching {
                VideoStorage.cleanupLoopStorage(this, limitGb, nextSegmentBytes, protectedFiles)
            }.getOrDefault(true)
            if (!enoughSpace) {
                mainHandler.post {
                    if (destroyed || requestedStop || !isRecordingActive()) return@post
                    Toast.makeText(this, R.string.storage_too_low_for_recording, Toast.LENGTH_LONG).show()
                    stopRecordingInternal()
                }
            }
        }
        clearActiveSegment()
    }

    private fun onSeamlessSegmentFailed(file: File, error: Throwable) {
        Log.w(TAG, "segment not finalized: ${file.name}", error)
        activePlateSession?.discard()
        activePlateSession = null
        clearActiveSegment()
        if (!destroyed) Toast.makeText(this, SEGMENT_SAVE_FAILED_MESSAGE, Toast.LENGTH_LONG).show()
    }

    private fun clearActiveSegment() {
        activeSubtitleFile = null
        activeVideoFile = null
        activeVideoStartWallMs = 0L
        resetActiveTrack()
        mainHandler.removeCallbacks(subtitleTicker)
    }

    private fun onSeamlessRecordingStopped() {
        seamlessRecorder = null
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(subtitleTicker)
        if (shouldReconnect()) {
            recorderFailed = false
            reconnectAttempts += 1
            reconnectPending = true
            val delay = RECONNECT_DELAYS_MS[(reconnectAttempts - 1).coerceIn(0, RECONNECT_DELAYS_MS.lastIndex)]
            Log.i(TAG, "camera lost, reconnect #$reconnectAttempts in ${delay}ms")
            startForegroundCompat(includeCameraType = true)
            mainHandler.postDelayed(reconnectRunnable, delay)
            return
        }
        recorderFailed = false
        finishRecordingSession()
    }

    private fun shouldReconnect(): Boolean {
        return recorderFailed &&
            !requestedStop &&
            !stoppingSelf &&
            !exitAfterStop &&
            !destroyed &&
            activeTripFolderName != null &&
            reconnectAttempts < RECONNECT_DELAYS_MS.size &&
            hasCameraPermission()
    }

    private fun reconnectRecording() {
        reconnectPending = false
        if (requestedStop || stoppingSelf || exitAfterStop || destroyed) {
            finishRecordingSession()
            return
        }
        if (!startSeamlessVideoCapture()) finishRecordingSession()
    }

    private fun cancelReconnect() {
        if (!reconnectPending) return
        reconnectPending = false
        mainHandler.removeCallbacks(reconnectRunnable)
        finishRecordingSession()
    }

    /** 整趟录像结束：写驾驶记录、收起悬浮窗，按需退出服务。 */
    private fun finishRecordingSession() {
        playRecordingStopCue()
        hdrActiveSnapshot = false
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(subtitleTicker)
        mainHandler.removeCallbacks(reconnectRunnable)
        activePlateSession?.discard()
        activePlateSession = null
        PlateRecognitionMonitor.onActive(false, false)
        writeTripDriveRecord()
        clearActiveTrip()
        hideRecordingBubble()
        releaseRecordingWakeLock()
        unregisterPowerReceiver()
        setRecordingState(this, false)
        if (destroyed) {
            shutdownExecutors()
            return
        }
        if (exitAfterStop) {
            stopVoiceRecognition()
            stopSelf()
            return
        }
        refreshSettings()
        startForegroundCompat(includeCameraType = false)
        if (!stoppingSelf && !shouldKeepAliveForVoice()) stopSelf()
    }

    private fun onSeamlessRecordingError(error: Throwable) {
        Log.w(TAG, "recorder error", error)
        if (!requestedStop) recorderFailed = true
        if (destroyed) return
        Toast.makeText(this, getString(R.string.camera_open_failed, error.message ?: error.javaClass.simpleName), Toast.LENGTH_SHORT).show()
    }

    private fun updateRecordingPreviewSurface(surface: Surface?) {
        seamlessRecorder?.setPreviewSurface(surface)
    }

    private fun startOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = CameraRotation.rotationFromOrientationDegrees(orientation) ?: return
                if (rotation != targetRotation) targetRotation = rotation
            }
        }.also { listener ->
            if (listener.canDetectOrientation()) listener.enable()
        }
    }

    private fun stopOrientationListener() {
        orientationListener?.disable()
        orientationListener = null
    }

    private fun stopRecordingInternal() {
        requestedStop = true
        parkingStationarySinceElapsedMs = null
        mainHandler.removeCallbacks(subtitleTicker)
        val recorder = seamlessRecorder
        if (recorder != null) {
            recorder.stop()
            return
        }
        if (reconnectPending) {
            cancelReconnect()
            return
        }
        hideRecordingBubble()
        setRecordingState(this, false)
        if (!shouldKeepAliveForVoice()) stopVoiceRecognition()
        activePlateSession?.discard()
        activePlateSession = null
        resetActiveTrack()
        clearActiveTrip()
        if (!destroyed) startForegroundCompat(includeCameraType = false)
    }

    private fun showRecordingBubble() {
        RecordingBubble.show(this, ::tripElapsedMs)
    }

    private fun hideRecordingBubble() {
        RecordingBubble.hide(this)
    }

    private fun tripElapsedMs(): Long {
        if (tripRecordingStartElapsedMs <= 0L) return 0L
        return (SystemClock.elapsedRealtime() - tripRecordingStartElapsedMs).coerceAtLeast(0L)
    }

    private fun playRecordingStartCue() {
        if (recordingCueOpen) return
        recordingCueOpen = true
        PhotoShutterSound.playRecordingStart(this)
    }

    private fun playRecordingStopCue() {
        if (!recordingCueOpen) return
        recordingCueOpen = false
        PhotoShutterSound.playRecordingStop(this)
    }

    private fun takeVoicePhoto(spokenAtElapsedMs: Long?) {
        val recorder = seamlessRecorder
        val spokenAt = spokenAtElapsedMs
        val jpeg = if (recorder == null || spokenAt == null) {
            null
        } else {
            runCatching { recorder.lookbackJpeg(spokenAt) }.getOrNull()
        }
        if (recorder == null || spokenAt == null || jpeg == null) {
            if (spokenAt != null) Log.i(VOICE_PHOTO_LOG, "回看没有对应帧，改为当场拍照")
            takePhotoInternal()
            return
        }
        val ageMs = (SystemClock.elapsedRealtime() - spokenAt).coerceAtLeast(0L)
        val spokenAtWallMs = System.currentTimeMillis() - ageMs
        Log.i(VOICE_PHOTO_LOG, "抓拍回看 ${ageMs}ms")
        PhotoShutterSound.play(this)
        val outputFile = VideoStorage.photoOutputFile(this, tripFolderName = activeTripFolderName)
        val tempFile = VideoStorage.tempPhotoFile(this)
        val mode = activeVideoMode ?: CameraCapabilities.selectedVideoMode(this)
        val tripFolder = activeTripFolderName
        runInBackground(cameraExecutor) {
            try {
                tempFile.parentFile?.mkdirs()
                tempFile.writeBytes(jpeg)
                SeamlessDashcamRecorder.bakeExifOrientation(tempFile)
                publishSeamlessPhoto(tempFile, outputFile, mode, tripFolder, spokenAtWallMs)
            } catch (_: Exception) {
                tempFile.delete()
                mainHandler.post { if (!destroyed) takePhotoInternal() }
            }
        }
    }

    private fun takePhotoInternal() {
        val recorder = seamlessRecorder
        if (recorder == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        PhotoShutterSound.play(this)
        captureSeamlessPhoto(recorder)
    }

    private fun captureSeamlessPhoto(recorder: SeamlessDashcamRecorder) {
        val outputFile = VideoStorage.photoOutputFile(this, tripFolderName = activeTripFolderName)
        val tempFile = VideoStorage.tempPhotoFile(this)
        val mode = activeVideoMode ?: CameraCapabilities.selectedVideoMode(this)
        val tripFolder = activeTripFolderName
        recorder.captureStill(tempFile) { result ->
            val queued = runInBackground(cameraExecutor) {
                result.fold(
                    onSuccess = { publishSeamlessPhoto(tempFile, outputFile, mode, tripFolder) },
                    onFailure = { error ->
                        tempFile.delete()
                        val message = if (error is StillCaptureUnavailable) {
                            R.string.photo_unavailable_during_recording
                        } else {
                            R.string.photo_failed
                        }
                        mainHandler.post {
                            if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            if (!queued) tempFile.delete()
        }
    }

    private fun publishSeamlessPhoto(
        tempFile: File,
        outputFile: File,
        mode: VideoMode,
        tripFolderName: String?,
        captionAtMs: Long? = null
    ) {
        PhotoWatermark.apply(this, tempFile, mode, captionAtMs)
        val published = VideoStorage.publishPhotoToGallery(
            this,
            tempFile,
            outputFile.name,
            tripFolderName
        )
        tempFile.delete()
        scanFile(published, "image/jpeg")
        mainHandler.post {
            if (!destroyed) Toast.makeText(this, R.string.photo_saved, Toast.LENGTH_SHORT).show()
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
        val cue = buildString {
            appendLine(subtitleIndex)
            appendLine("${formatSrtTime(startMs)} --> ${formatSrtTime(endMs)}")
            appendLine(lines.joinToString("  |  "))
            appendLine()
        }
        runInBackground(ioExecutor) {
            try {
                file.appendText(cue)
            } catch (_: Exception) {
                // 存储一时写不进时丢掉这一秒字幕，录像继续。
            }
        }
    }

    private fun appendTrackPoint(location: Location, force: Boolean = false) {
        if (!isRecordingActive() || recordingStartElapsedMs <= 0L || activeVideoFile == null) return
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

    private fun buildActiveTrack(videoFile: File): VideoTrack? {
        if (activeTrackPoints.isEmpty()) return null
        return try {
            VideoTrack(
                videoFileName = videoFile.name,
                startTimeMs = activeVideoStartWallMs,
                points = activeTrackPoints.toList(),
                radars = RouteRadarMarks.toTrackRadars(
                    RadarAlertController.sightingsBetween(activeVideoStartWallMs, System.currentTimeMillis()),
                    activeVideoStartWallMs
                )
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
        val record = buildDriveRecord(track) ?: return
        DriveRecordStore.addRecordAsync(applicationContext, record)
    }

    private fun buildDriveRecord(track: VideoTrack): DriveRecord? {
        val points = track.points
        if (points.isEmpty()) return null
        val summary = TrackMetrics.summary(points)
        val maxPoint = points.maxByOrNull { it.speedKmh }
        val startTimeMs = track.startTimeMs.takeIf { it > 0L } ?: points.first().timeMs
        val endTimeMs = points.last().timeMs.takeIf { it > 0L } ?: (startTimeMs + summary.durationMs)
        return DriveRecord(
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            durationMs = summary.durationMs,
            maxSpeedKmh = summary.maxSpeedKmh,
            waitDurationMs = currentStationaryWaitMs(),
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
        tripSubtitleFiles.clear()
    }

    private fun rememberSubtitleFile(file: File?, startElapsedMs: Long) {
        if (file == null || startElapsedMs <= 0L) return
        if (tripSubtitleFiles.none { it.first == file }) {
            tripSubtitleFiles += file to startElapsedMs
        }
    }

    private fun rewriteRecordedRoadLayer(correction: DivergenceCorrection) {
        if (!DashcamSettings.load(this).showRoadLayer) return
        val label = when (correction.layer) {
            RoadLayer.ELEVATED -> RoadLayerLabels.ON_BRIDGE
            RoadLayer.GROUND -> RoadLayerLabels.UNDER_BRIDGE
            else -> return
        }
        val files = tripSubtitleFiles.toList()
        // 和每秒追加字幕排在同一条线程上，改写时不会和追加交错。
        runInBackground(ioExecutor) {
            files.forEach { (file, startElapsedMs) ->
                if (!file.exists()) return@forEach
                val fromElapsedMs = (correction.fromTimeMs - startElapsedMs).coerceAtLeast(0L)
                try {
                    val original = file.readText(Charsets.UTF_8)
                    val updated = SrtRoadLayerRewrite.rewrite(original, fromElapsedMs, label)
                    if (updated != original) file.writeText(updated, Charsets.UTF_8)
                } catch (_: Exception) {
                    // 字幕文件正在被系统占用时，下一秒的新字幕仍会使用修正后的层级。
                }
            }
        }
    }

    private fun buildWatermarkLines(forceTime: Boolean): List<String> {
        updateTelemetrySnapshot()
        return DashcamOverlayText.lines(this, activeVideoMode ?: CameraCapabilities.selectedVideoMode(this), forceTime)
    }

    private fun refreshSettings() {
        if (exitAfterStop) {
            if (!isRecordingActive()) {
                stopVoiceRecognition()
                stopSelf()
            }
            return
        }
        val settings = DashcamSettings.load(this)
        lastLocation?.let { maybeResolveAddress(it) }
        seamlessRecorder?.let { recorder ->
            // HDR 和手动对焦直接改录像请求，不用停下当前分段。
            activeHdrRequested = DashcamHdrSettings.shouldEnableNow(this)
            recorder.updateCaptureSettings(activeHdrRequested)
            recorder.memoryStillsEnabled = settings.voicePhotoEnabled
            updatePlateRecognition(recorder, settings.plateRecognitionEnabled)
        }
        when (VoiceCapture.mode(settings.voicePhotoEnabled, hasAudioPermission(), isRecordingActive())) {
            VoiceCaptureMode.OFF -> {
                seamlessRecorder?.recordingPcmListener = null
                VoicePhotoRecognizer.endExternalCapture(VOICE_OWNER)
                stopVoiceRecognition()
            }
            VoiceCaptureMode.RECORDING_PCM -> {
                val recorder = seamlessRecorder
                recorder?.recordingPcmListener = recordingPcmListener
                recorder?.startListeningCaptureIfNeeded()
                if (recorder?.isCapturingAudio() == true) {
                    VoicePhotoRecognizer.beginExternalCapture(this, VOICE_OWNER, ::dispatchVoiceCommand)
                } else {
                    recorder?.recordingPcmListener = null
                    VoicePhotoRecognizer.endExternalCapture(VOICE_OWNER)
                    startVoiceRecognition()
                }
            }
            VoiceCaptureMode.MICROPHONE -> {
                seamlessRecorder?.recordingPcmListener = null
                VoicePhotoRecognizer.endExternalCapture(VOICE_OWNER)
                startVoiceRecognition()
            }
        }
        startForegroundCompat(includeCameraType = isRecordingActive())
        if (!isRecordingActive() && !shouldKeepAliveForVoice()) {
            stopSelf()
        }
    }

    private fun updatePlateRecognition(recorder: SeamlessDashcamRecorder, enabled: Boolean) {
        val wasEnabled = recorder.stillFrameListener != null
        if (enabled == wasEnabled) return
        if (enabled) {
            if (activePlateSession == null && activeVideoStartWallMs > 0L) {
                activePlateSession = newPlateSession(activeVideoStartWallMs, recordingStartElapsedMs)
            }
            recorder.stillFrameListener = plateFrameListener
        } else {
            recorder.stillFrameListener = null
            activePlateSession?.discard()
            activePlateSession = null
        }
        PlateRecognitionMonitor.onActive(enabled, enabled)
    }

    private fun updateParkingStop(speedKmh: Float) {
        if (seamlessRecorder == null || requestedStop) {
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

    private val recordingPcmListener = SeamlessDashcamRecorder.RecordingPcmListener { data, length, sampleRate ->
        VoicePhotoRecognizer.feedExternalPcm(data, length, sampleRate)
    }

    private fun startVoiceRecognition() {
        VoicePhotoRecognizer.start(this, VOICE_OWNER, ::dispatchVoiceCommand)
    }

    private fun dispatchVoiceCommand(command: VoiceCommand, spokenAtElapsedMs: Long? = null) {
        val now = SystemClock.elapsedRealtime()
        if (!voiceCommandCooldown.tryAcquire(command, now)) return
        mainHandler.post {
            if (destroyed) return@post
            when (command) {
                VoiceCommand.TAKE_PHOTO -> {
                    VoiceCommandFeedback.photoTaken(this)
                    if (seamlessRecorder != null) {
                        // 口令要等说完再静音约 1 秒才确认。用这句话的开头时间，取当时或之后最近的录像帧。
                        takeVoicePhoto(spokenAtElapsedMs)
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

    private fun enableRadarAlertFromVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            VoiceCommandFeedback.radarAlertNeedsLocation(this)
            return
        }
        getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, true)
            .apply()
        RadarAlertService.sync(this)
        VoiceCommandFeedback.radarAlertStarted(this)
    }

    private fun disableRadarAlertFromVoice() {
        getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RadarAlertSettingsStore.PREF_RADAR_ALERT_ENABLED, false)
            .apply()
        RadarAlertService.stop(this)
        VoiceCommandFeedback.radarAlertStopped(this)
    }

    private fun stopVoiceRecognition() {
        VoicePhotoRecognizer.stop(VOICE_OWNER)
    }

    private fun shouldKeepAliveForVoice(): Boolean {
        return DashcamSettings.load(this).voicePhotoEnabled && hasAudioPermission()
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
        runInBackground(cameraExecutor) {
            val text = reverseGeocode(location)
            if (text != null) {
                mainHandler.post {
                    lastAddressText = text
                    updateTelemetrySnapshot()
                }
            }
        }
    }

    private fun updateStationaryWait(speedKmh: Float) {
        stationaryWaitState = SpeedRules.updateTripAndWait(
            stationaryWaitState,
            speedKmh,
            System.currentTimeMillis()
        )
    }

    private fun currentStationaryWaitMs(): Long {
        return SpeedRules.currentWaitDurationMs(stationaryWaitState, System.currentTimeMillis())
    }

    private fun resetStationaryWait() {
        stationaryWaitState = SpeedRules.WaitState(
            tripStarted = false,
            tripStartTimeMs = 0L,
            waitAtZeroAccumMs = 0L,
            waitAtZeroStartMs = 0L
        )
    }

    private fun updateTelemetrySnapshot() {
        val location = lastLocation
        telemetrySnapshot = DashcamTelemetry(
            speedKmh = lastSpeedKmh,
            latitude = location?.latitude,
            longitude = location?.longitude,
            satelliteCount = gpsUsedSatelliteCount,
            address = lastAddressText,
            focusDistanceDiopters = lastFocusDistanceDiopters,
            autofocusState = lastAutofocusState
        )
    }

    /** 这一次定位自带的原始速度；没带速度字段时返回 null，不拿上一次的旧值冒充。 */
    private fun lastLocationSpeedMps(): Float? {
        val location = lastLocation ?: return null
        return if (location.hasSpeed()) location.speed else null
    }

    private fun lastFixElapsedMs(): Long {
        val location = lastLocation ?: return 0L
        return location.elapsedRealtimeNanos / 1_000_000L
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
            formatPlace(address)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun formatPlace(address: Address): String? {
        val line = if (address.maxAddressLineIndex >= 0) address.getAddressLine(0) else null
        return PlaceSubtitle.format(
            adminArea = address.adminArea,
            subAdminArea = address.subAdminArea,
            locality = address.locality,
            subLocality = address.subLocality,
            thoroughfare = address.thoroughfare,
            featureName = address.featureName,
            premises = address.premises,
            addressLine = line
        )
    }

    private fun registerLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
        } catch (_: RuntimeException) {
            // 定位不可用时只是不显示 GPS 水印，不影响录像。
        }
        registerGnssStatusUpdates(manager)
    }

    private fun registerGnssStatusUpdates(manager: LocationManager) {
        if (gnssStatusCallback != null) return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                gpsUsedSatelliteCount = GnssSatelliteCount.usedInFix(status.satelliteCount, status::usedInFix)
                updateTelemetrySnapshot()
            }

            override fun onStopped() {
                gpsUsedSatelliteCount = null
                updateTelemetrySnapshot()
            }
        }
        try {
            val registered = manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            if (registered) gnssStatusCallback = callback
        } catch (_: SecurityException) {
            gpsUsedSatelliteCount = null
        } catch (_: RuntimeException) {
            gpsUsedSatelliteCount = null
        }
    }

    private fun unregisterLocationUpdates() {
        unregisterGnssStatusUpdates()
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            manager.removeUpdates(this)
        } catch (_: RuntimeException) {
            // ignore teardown exception
        }
    }

    private fun unregisterGnssStatusUpdates() {
        val callback = gnssStatusCallback ?: return
        gnssStatusCallback = null
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            manager.unregisterGnssStatusCallback(callback)
        } catch (_: RuntimeException) {
            // ignore teardown exception
        }
        gpsUsedSatelliteCount = null
    }

    private fun scanFile(file: File, mimeType: String) {
        val appContext = applicationContext
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, uri ->
            if (mimeType == "video/mp4") {
                runCatching { VideoStorage.publishIntoGalleryAlbum(appContext, file, uri) }
            }
        }
    }

    private fun acquireRecordingWakeLock() {
        // 激进省电的机型息屏后会冻结进程；只在录像期间持有，并带超时兜底。
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) return
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { created ->
                created.setReferenceCounted(false)
                wakeLock = created
            }
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseRecordingWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    /** 断电这条广播送不到清单里静态注册的接收器，只能在录像期间动态注册。 */
    private fun registerPowerReceiver() {
        if (powerReceiver != null) return
        val receiver = PowerTriggerReceiver()
        try {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            powerReceiver = receiver
        } catch (_: RuntimeException) {
            // 注册失败只是断电不自动停录。
        }
    }

    private fun unregisterPowerReceiver() {
        val receiver = powerReceiver ?: return
        powerReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun runInBackground(executor: ExecutorService, task: () -> Unit): Boolean {
        return try {
            executor.execute {
                // 线程池里漏出的异常会直接让进程崩溃，这里兜住只记日志。
                try {
                    task()
                } catch (error: Exception) {
                    Log.w(TAG, "background task failed", error)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun shutdownExecutors() {
        cameraExecutor.shutdown()
        plateExecutor.shutdown()
        ioExecutor.shutdown()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Android 14 起前台服务类型必须和已授予的权限对应，否则 startForeground 直接抛异常；
     * 从后台启动摄像头类型也会被拒。失败时退一步去掉摄像头类型再试，仍失败返回 false。
     */
    private fun startForegroundCompat(includeCameraType: Boolean = false): Boolean {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                startForeground(NOTIFICATION_ID, notification)
                true
            } catch (error: RuntimeException) {
                Log.w(TAG, "startForeground failed", error)
                false
            }
        }
        val fullType = foregroundServiceType(includeCameraType)
        val attempts = listOf(fullType, fullType and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA.inv()).distinct()
        attempts.forEachIndexed { index, type ->
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                // 退掉摄像头类型只够维持服务，不能用来开相机。
                return index == 0 || !includeCameraType
            } catch (error: RuntimeException) {
                Log.w(TAG, "startForeground type=$type failed", error)
            }
        }
        return false
    }

    private fun foregroundServiceType(includeCameraType: Boolean): Int {
        var type = 0
        if (includeCameraType && hasCameraPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission()) {
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
            .addAction(android.R.drawable.ic_lock_power_off, getString(R.string.notif_action_exit_background), AppBackgroundExit.pendingIntent(this))
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
        const val ACTION_EXIT = "com.thomas.carspeed.action.DASHCAM_EXIT"

        private const val TAG = "DashcamService"
        private const val NOTIFICATION_ID = 2001
        private const val KEY_RECORDING = "dashcam_recording"
        private const val KEY_RECORDING_START_MS = "dashcam_recording_start_ms"
        private const val KEY_RESUME_PENDING = "dashcam_resume_pending"
        private const val KEY_RESUME_WINDOW_START_MS = "dashcam_resume_window_start_ms"
        private const val KEY_RESUME_ATTEMPTS = "dashcam_resume_attempts"
        private const val RESUME_WINDOW_MS = 10 * 60_000L
        private const val MAX_RESUME_ATTEMPTS = 2
        private const val ADDRESS_REFRESH_INTERVAL_MS = 60_000L
        private const val ADDRESS_REFRESH_DISTANCE_METERS = 200f
        private const val TRACK_POINT_INTERVAL_MS = 1_000L
        private const val MAX_TRACK_POINTS_PER_SEGMENT = 30_000
        private const val VOICE_OWNER = "service"
        private const val VOICE_PHOTO_LOG = "VoicePhoto"
        private const val WAKE_LOCK_TAG = "CarSpeed:recording"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        private const val BACKGROUND_START_BLOCKED_MESSAGE = "系统不允许在后台打开摄像头，请回到应用里再开始录像"
        private const val SEGMENT_SAVE_FAILED_MESSAGE = "这一段录像没能正常保存，后面的录像不受影响"

        @Volatile
        var instance: DashcamService? = null
            private set

        @Volatile
        private var recordingPreviewSurface: Surface? = null

        @Volatile
        private var telemetrySnapshot = DashcamTelemetry()

        fun currentTelemetry(): DashcamTelemetry {
            val waitMs = instance?.currentStationaryWaitMs() ?: 0L
            return telemetrySnapshot.copy(stationaryWaitMs = waitMs)
        }

        /** 录像服务每秒都在收定位，蓝牙断开判断车速时优先用这一份。 */
        fun liveSpeedKmh(): Float? {
            val service = instance ?: return null
            return TriggerSpeed.kmhIfFresh(
                roomSpeedMps = service.lastLocationSpeedMps(),
                fixElapsedMs = service.lastFixElapsedMs(),
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        }

        fun radarLimitSummary(nowMs: Long = System.currentTimeMillis()): String? {
            val service = instance ?: return null
            val startMs = service.activeTripStartWallMs
            if (startMs <= 0L) return null
            val limits = RadarAlertController.sightingsBetween(startMs, nowMs).map { it.speedLimit }
            return RouteRadarMarks.limitSummary(limits)
        }

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

        fun setRecordingPreviewSurface(surface: Surface?) {
            recordingPreviewSurface = surface
            instance?.updateRecordingPreviewSurface(surface)
        }

        fun startRecording(context: Context) {
            CameraRecordActivity.prepareForServiceRecording()
            CameraFullscreenActivity.prepareForServiceRecording()
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_START_RECORDING }
            if (!startServiceSafely(context, intent)) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context.applicationContext, BACKGROUND_START_BLOCKED_MESSAGE, Toast.LENGTH_LONG).show()
                }
            }
        }

        fun stopRecording(context: Context) {
            if (instance == null) return
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_STOP_RECORDING }
            startServiceSafely(context, intent)
        }

        fun exit(context: Context) {
            if (instance == null) {
                context.stopService(Intent(context, DashcamService::class.java))
                return
            }
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_EXIT }
            startServiceSafely(context, intent)
        }

        fun takePhoto(context: Context) {
            if (instance == null) return
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_TAKE_PHOTO }
            runCatching { context.startService(intent) }
        }

        /** 服务没在跑时只有语音常驻需要它，不然不为了刷新设置把服务拉起来又停掉。 */
        fun refreshSettings(context: Context) {
            val service = instance
            if (service != null) {
                service.mainHandler.post {
                    if (instance === service && !service.destroyed) {
                        service.refreshSettings()
                    }
                }
                return
            }
            if (!needsVoiceKeepAlive(context)) return
            val intent = Intent(context, DashcamService::class.java).apply { action = ACTION_REFRESH_SETTINGS }
            startServiceSafely(context, intent)
        }

        private fun needsVoiceKeepAlive(context: Context): Boolean {
            return DashcamSettings.load(context).voicePhotoEnabled &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }

        /** 后台时 startForegroundService 会抛异常；服务已在跑就改走普通 startService。 */
        private fun startServiceSafely(context: Context, intent: Intent): Boolean {
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: IllegalStateException) {
                Log.w(TAG, "startForegroundService rejected", error)
                if (instance != null) runCatching { context.startService(intent) }.isSuccess else false
            } catch (error: SecurityException) {
                Log.w(TAG, "startForegroundService denied", error)
                false
            }
        }

        fun isRecording(context: Context): Boolean {
            val active = instance?.isRecordingActive() == true
            val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            if (!active && instance == null && prefs.getBoolean(KEY_RECORDING, false)) {
                clearStaleRecordingFlag(context)
            }
            return active
        }

        // 只清界面用的录像标记，续录标记留给 START_STICKY 重启时判断。
        private fun clearStaleRecordingFlag(context: Context) {
            context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RECORDING, false)
                .remove(KEY_RECORDING_START_MS)
                .apply()
        }

        /** 被杀前在录像，且最近没有反复续录（防止录像本身导致的崩溃循环）时返回 true。 */
        private fun consumeResumeBudget(context: Context): Boolean {
            val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_RESUME_PENDING, false)) return false
            val now = System.currentTimeMillis()
            val windowStart = prefs.getLong(KEY_RESUME_WINDOW_START_MS, 0L)
            val inWindow = now - windowStart in 0..RESUME_WINDOW_MS
            val attempts = if (inWindow) prefs.getInt(KEY_RESUME_ATTEMPTS, 0) else 0
            if (attempts >= MAX_RESUME_ATTEMPTS) {
                prefs.edit().remove(KEY_RESUME_PENDING).apply()
                return false
            }
            prefs.edit()
                .putLong(KEY_RESUME_WINDOW_START_MS, if (inWindow) windowStart else now)
                .putInt(KEY_RESUME_ATTEMPTS, attempts + 1)
                .apply()
            return true
        }

        fun recordingElapsedSeconds(context: Context): Long {
            if (!isRecording(context)) return 0L
            val start = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_RECORDING_START_MS, 0L)
            if (start <= 0L) return 0L
            return ((System.currentTimeMillis() - start) / 1_000L).coerceAtLeast(0L)
        }

        private fun setRecordingState(context: Context, recording: Boolean) {
            val prefs = context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE)
            // 断线重连会再次回调“开始”，计时要从这趟第一次开始算起。
            if (recording && prefs.getBoolean(KEY_RECORDING, false) && prefs.contains(KEY_RECORDING_START_MS)) return
            prefs.edit()
                .putBoolean(KEY_RECORDING, recording)
                .putBoolean(KEY_RESUME_PENDING, recording)
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
