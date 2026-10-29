package com.thomas.carspeed

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.ExifInterface
import android.media.ImageReader
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

class SeamlessDashcamRecorder(
    context: Context,
    private val mode: VideoMode,
    private val segmentDurationMs: Long,
    private val tripFolderName: String?,
    private val targetRotation: Int,
    initialPreviewSurface: Surface? = null,
    private val enableAudio: Boolean = false,
    enableHdr: Boolean = false,
    private val callback: Callback
) {
    interface Callback {
        fun onStarted()
        fun onSegmentStarted(file: File, startWallMs: Long, startElapsedMs: Long)
        fun onSegmentFinalized(file: File)
        fun onSegmentFailed(file: File, error: Throwable) = Unit
        fun onStopped()
        fun onError(error: Throwable)
        fun onCaptureResult(result: CaptureResult)
        fun onHdrStateChanged(enabled: Boolean) = Unit
    }

    fun interface RecordingPcmListener {
        fun onPcm16le(data: ByteArray, length: Int, sampleRate: Int)
    }

    /** 在相机线程回调，实现方应尽快把工作转到别的线程。 */
    fun interface StillFrameListener {
        fun onStillFrame(jpeg: ByteArray, elapsedMs: Long)
    }

    private data class SegmentStart(
        val file: File,
        val startWallMs: Long,
        val startElapsedMs: Long
    )

    private class ClosedSegment(val file: File, val error: Throwable?)

    private val appContext = context.applicationContext
    private val drainExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stillExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val muxerLock = Any()
    private val stopped = AtomicBoolean(false)
    private val errorNotified = AtomicBoolean(false)
    private val stoppedNotified = AtomicBoolean(false)
    @Volatile private var stopRequested = false
    @Volatile private var drainStarted = false
    @Volatile private var cameraOpenPending = false
    @Volatile private var resourcesReleased = false
    @Volatile private var hdrRequested = enableHdr

    @Volatile private var cameraThread: HandlerThread? = null
    @Volatile private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    @Volatile private var cameraCharacteristics: CameraCharacteristics? = null
    private var captureSession: CameraCaptureSession? = null
    @Volatile private var previewSurface: Surface? = initialPreviewSurface?.takeIf { it.isValid }
    private var videoEncoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var captureSessionPreviewSurface: Surface? = null
    private var previewTargeted = false
    private var appliedCaptureSettingsKey: String? = null
    private var captureSessionGeneration = 0
    @Volatile private var audioMuxingEnabled = false
    private var audioEncoder: MediaCodec? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile var recordingPcmListener: RecordingPcmListener? = null
    @Volatile var stillFrameListener: StillFrameListener? = null
    private var audioThread: Thread? = null
    private var audioBufferSize = 0
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    private var currentFile: File? = null
    private var currentSegmentStartElapsedMs = 0L
    private var currentSegmentFirstPtsUs: Long? = null
    private var currentSegmentHasSamples = false
    private var splitPending = false
    private var lastKeyFrameRequestElapsedMs = 0L
    @Volatile private var orientationHintDegrees = 0
    @Volatile private var hdrSceneModeEnabled = false
    private var startNotified = false
    private var stillReader: ImageReader? = null
    private var includeStillSurface = true
    private var stillCandidates: List<CaptureSize> = emptyList()
    private var stillCandidateIndex = 0
    private val pendingStills = ArrayDeque<PendingStill>()
    private val recentStills = RecentStillShots()
    private var nextStillId = 0L
    private var memoryStillFailures = 0

    /** 只有语音回看拍照需要在内存里留最近几张；关掉后不再缓存。 */
    @Volatile var memoryStillsEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) recentStills.clear()
        }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            callback.onCaptureResult(result)
        }
    }

    fun start() {
        try {
            cameraThread = HandlerThread("CarSpeedSeamlessCamera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = backCameraId(manager) ?: throw IllegalStateException("没有可用后置摄像头")
            val characteristics = manager.getCameraCharacteristics(cameraId)
            cameraCharacteristics = characteristics
            startAudioEncoderIfNeeded()
            startVideoEncoder()
            drainStarted = true
            drainExecutor.execute { drainVideoEncoder() }
            openCamera(manager, cameraId, characteristics)
        } catch (error: Exception) {
            notifyError(error)
            stop()
            if (!drainStarted) {
                stopAudioCaptureAndWait()
                releaseResources()
                notifyStopped()
            }
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        stopRequested = true
        cameraHandler?.post {
            captureSessionGeneration++
            failPendingStills(IllegalStateException("录像已停止"))
            closeCameraObjects()
        }
        runCatching { audioRecord?.stop() }
        runCatching { videoEncoder?.signalEndOfInputStream() }
    }

    /** 开口时间之后最近的一张已留存照片。还没有时返回空，调用方当场再拍。 */
    fun lookbackJpeg(targetElapsedMs: Long): ByteArray? = recentStills.atOrAfter(targetElapsedMs)

    /** 正在写入的分段文件，循环清理时要避开它。 */
    fun currentSegmentFile(): File? = synchronized(muxerLock) { currentFile }

    fun captureStill(file: File, onDone: (Result<File>) -> Unit) {
        val handler = cameraHandler
        if (handler == null || stopped.get()) {
            onDone(Result.failure(StillCaptureUnavailable()))
            return
        }
        val posted = handler.post {
            val session = captureSession
            val camera = cameraDevice
            val reader = stillReader
            val characteristics = cameraCharacteristics
            if (stopRequested || session == null || camera == null || reader == null || characteristics == null || !includeStillSurface) {
                onDone(Result.failure(StillCaptureUnavailable()))
                return@post
            }
            val id = enqueueStill(file, onDone, SystemClock.elapsedRealtime())
            try {
                session.capture(buildSnapshotRequest(camera, reader, characteristics, id), stillResultCallback, handler)
            } catch (error: Exception) {
                removePendingStill(id)
                onDone(Result.failure(error))
            }
        }
        if (!posted) onDone(Result.failure(StillCaptureUnavailable()))
    }

    /** 录像中改了 HDR 或手动对焦：直接换重复请求，不重建会话，也不断开分段。 */
    fun updateCaptureSettings(enableHdr: Boolean) {
        hdrRequested = enableHdr
        val handler = cameraHandler ?: return
        handler.post {
            if (stopRequested || stopped.get()) return@post
            val characteristics = cameraCharacteristics ?: return@post
            val hdr = hdrRequested && supportsHdrSceneMode(characteristics)
            val hdrChanged = hdr != hdrSceneModeEnabled
            hdrSceneModeEnabled = hdr
            if (captureSession == null || captureSettingsKey() == appliedCaptureSettingsKey) {
                if (hdrChanged) callback.onHdrStateChanged(hdr)
                return@post
            }
            try {
                applyRepeatingRequest()
            } catch (_: Exception) {
                // 保留上一套请求继续录。
            }
            if (hdrChanged) callback.onHdrStateChanged(hdr)
        }
    }

    fun setPreviewSurface(surface: Surface?) {
        val nextSurface = surface?.takeIf { it.isValid }
        if (previewSurface === nextSurface) return
        previewSurface = nextSurface
        val handler = cameraHandler ?: return
        handler.post {
            if (stopRequested || stopped.get()) return@post
            val camera = cameraDevice ?: return@post
            val characteristics = cameraCharacteristics ?: return@post
            val desiredPreview = previewSurface?.takeIf { it.isValid }
            if (desiredPreview == null) {
                if (!previewTargeted) return@post
                // 预览页面离开时只把预览从重复请求里摘掉，会话不重建，编码器就不会断帧。
                previewTargeted = false
                try {
                    applyRepeatingRequest()
                } catch (_: Exception) {
                    createCaptureSession(camera, characteristics)
                }
                return@post
            }
            if (desiredPreview === captureSessionPreviewSurface && captureSession != null) {
                if (!previewTargeted) {
                    previewTargeted = true
                    try {
                        applyRepeatingRequest()
                    } catch (_: Exception) {
                        createCaptureSession(camera, characteristics)
                    }
                }
                return@post
            }
            createCaptureSession(camera, characteristics)
        }
    }

    private fun startVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mode.width, mode.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate(mode))
            setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = encoder.createInputSurface()
        encoder.start()
        videoEncoder = encoder
    }

    fun isCapturingAudio(): Boolean = audioRecord != null

    @SuppressLint("MissingPermission")
    fun startListeningCaptureIfNeeded() {
        if (stopped.get() || audioRecord != null) return
        startAudioEncoderIfNeeded()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioEncoderIfNeeded() {
        if (audioRecord != null) return
        val listen = recordingPcmListener != null
        if (!enableAudio && !listen) return
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return
            audioBufferSize = (minBuffer * 2).coerceAtLeast(AUDIO_SAMPLE_RATE / 5 * AUDIO_BYTES_PER_SAMPLE)
            val encoder = if (enableAudio) {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                }
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { created ->
                    created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    created.start()
                }
            } else {
                null
            }
            audioEncoder = encoder
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioBufferSize
            ).also { recorder ->
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { recorder.release() }
                    audioRecord = null
                    if (encoder != null) {
                        runCatching { encoder.stop() }
                        runCatching { encoder.release() }
                    }
                    audioEncoder = null
                    return
                }
                recorder.startRecording()
            }
            if (enableAudio) audioMuxingEnabled = true
            audioThread = Thread({
                if (encoder != null) captureAndEncodeAudio() else capturePcmOnly()
            }, "CarSpeedSeamlessAudio").also { it.start() }
        } catch (_: Exception) {
            audioMuxingEnabled = false
            runCatching { audioRecord?.release() }
            audioRecord = null
            runCatching { audioEncoder?.stop() }
            runCatching { audioEncoder?.release() }
            audioEncoder = null
            audioThread = null
        }
    }

    private fun audioClockNowUs(): Long {
        // 编码 Surface 使用单调时钟，不能直接套用传感器的 BOOTTIME（含休眠时长）。
        return System.nanoTime() / 1_000L
    }

    private fun captureAndEncodeAudio() {
        val recorder = audioRecord ?: return
        val encoder = audioEncoder ?: return
        val readBuffer = ByteArray(audioBufferSize.coerceAtLeast(1))
        var samplesRead = 0L
        val audioStartPtsUs = audioClockNowUs()
        try {
            while (!stopRequested && !stopped.get()) {
                val inputIndex = encoder.dequeueInputBuffer(ENCODER_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val readSize = recorder.read(readBuffer, 0, minOf(inputBuffer.remaining(), readBuffer.size))
                        if (readSize > 0) {
                            dispatchPcm(readBuffer, readSize)
                            inputBuffer.put(readBuffer, 0, readSize)
                            val ptsUs = audioStartPtsUs + samplesRead * 1_000_000L / AUDIO_SAMPLE_RATE
                            samplesRead += readSize / AUDIO_BYTES_PER_SAMPLE
                            encoder.queueInputBuffer(inputIndex, 0, readSize, ptsUs, 0)
                        }
                    }
                }
                drainAudioEncoder(encoder, waitForOutput = false)
            }
            queueAudioEndOfStream(encoder)
            drainAudioEncoder(encoder, waitForOutput = true)
        } catch (_: Exception) {
            audioMuxingEnabled = false
            // 音频失败不应中断视频；后续分段会继续只写视频轨。
        }
    }

    private fun capturePcmOnly() {
        val recorder = audioRecord ?: return
        val readBuffer = ByteArray(audioBufferSize.coerceAtLeast(1))
        try {
            while (!stopRequested && !stopped.get()) {
                val readSize = recorder.read(readBuffer, 0, readBuffer.size)
                if (readSize > 0) dispatchPcm(readBuffer, readSize)
            }
        } catch (_: Exception) {
            // 只给语音识别供声时失败，不影响已经开始的无声视频。
        }
    }

    private fun dispatchPcm(data: ByteArray, length: Int) {
        val listener = recordingPcmListener ?: return
        try {
            listener.onPcm16le(data, length, AUDIO_SAMPLE_RATE)
        } catch (_: Exception) {
            // 识别跟不上时丢掉这块声音，录像编码继续。
        }
    }

    private fun queueAudioEndOfStream(encoder: MediaCodec) {
        val inputIndex = encoder.dequeueInputBuffer(ENCODER_TIMEOUT_US)
        if (inputIndex >= 0) {
            encoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                audioClockNowUs(),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
    }

    private fun drainAudioEncoder(encoder: MediaCodec, waitForOutput: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, if (waitForOutput) ENCODER_TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> audioFormat = encoder.outputFormat
                index >= 0 -> {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    val encodedData = encoder.getOutputBuffer(index)
                    if (bufferInfo.size > 0 && encodedData != null) {
                        writeAudioSample(encodedData, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(manager: CameraManager, cameraId: String, characteristics: CameraCharacteristics) {
        orientationHintDegrees = orientationHint(targetRotation)
        hdrSceneModeEnabled = hdrRequested && supportsHdrSceneMode(characteristics)
        callback.onHdrStateChanged(hdrSceneModeEnabled)
        cameraOpenPending = true
        try {
            openCameraDevice(manager, cameraId, characteristics)
        } catch (error: Exception) {
            cameraOpenPending = false
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraDevice(manager: CameraManager, cameraId: String, characteristics: CameraCharacteristics) {
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenPending = false
                    if (stopRequested) {
                        // 停止发生在相机打开之前：打开后立刻关掉，并替 releaseResources 结束相机线程。
                        camera.close()
                        if (resourcesReleased) Looper.myLooper()?.quitSafely()
                        return
                    }
                    cameraDevice = camera
                    prepareStillReader(characteristics)
                    createCaptureSession(camera, characteristics)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenPending = false
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (!stopRequested) notifyError(CameraLostException("摄像头连接已断开"))
                    stop()
                    if (resourcesReleased) Looper.myLooper()?.quitSafely()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenPending = false
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (!stopRequested) notifyError(CameraLostException("摄像头错误：$error"))
                    stop()
                    if (resourcesReleased) Looper.myLooper()?.quitSafely()
                }
            },
            cameraHandler
        )
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(camera: CameraDevice, characteristics: CameraCharacteristics) {
        val surface = encoderInputSurface ?: return
        val sessionId = ++captureSessionGeneration
        val sessionPreviewSurface = previewSurface?.takeIf { it.isValid }
        val stillSurface = if (includeStillSurface) stillReader?.surface else null
        val surfaces = buildList {
            add(surface)
            sessionPreviewSurface?.let { add(it) }
            stillSurface?.let { add(it) }
        }
        camera.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (stopRequested || sessionId != captureSessionGeneration) {
                        session.close()
                        return
                    }
                    val previousSession = captureSession
                    captureSession = session
                    captureSessionPreviewSurface = sessionPreviewSurface
                    previewTargeted = sessionPreviewSurface != null
                    try {
                        applyRepeatingRequest()
                        previousSession?.takeIf { it !== session }?.close()
                        if (!startNotified) {
                            startNotified = true
                            callback.onStarted()
                        }
                        val handler = cameraHandler
                        handler?.removeCallbacks(memoryStillTick)
                        handler?.postDelayed(memoryStillTick, MEMORY_STILL_INTERVAL_MS)
                    } catch (error: Exception) {
                        notifyError(error)
                        stop()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    if (sessionId != captureSessionGeneration || stopRequested) return
                    if (stillSurface != null && stillCandidateIndex + 1 < stillCandidates.size) {
                        stillCandidateIndex += 1
                        openStillReader(stillCandidates[stillCandidateIndex])
                        createCaptureSession(camera, characteristics)
                        return
                    }
                    if (stillSurface != null) {
                        includeStillSurface = false
                        closeStillReader()
                        createCaptureSession(camera, characteristics)
                        return
                    }
                    if (sessionPreviewSurface != null) {
                        previewSurface = null
                        createCaptureSession(camera, characteristics)
                    } else {
                        notifyError(IllegalStateException("摄像头录像会话创建失败"))
                        stop()
                    }
                }
            },
            cameraHandler
        )
    }

    /** 在相机线程调用。 */
    private fun applyRepeatingRequest() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val characteristics = cameraCharacteristics ?: return
        val surface = encoderInputSurface ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
            activePreviewTarget()?.let { addTarget(it) }
            applyHdrOptions()
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.fps, mode.fps))
            applyFocusOptions(characteristics)
        }.build()
        session.setRepeatingRequest(request, repeatingCallback, cameraHandler)
        appliedCaptureSettingsKey = captureSettingsKey()
    }

    private fun activePreviewTarget(): Surface? {
        if (!previewTargeted) return null
        return captureSessionPreviewSurface?.takeIf { it.isValid }
    }

    private fun captureSettingsKey(): String {
        return "$hdrSceneModeEnabled|${CameraFocus.isManualEnabled(appContext)}|${CameraFocus.percent(appContext)}"
    }

    private fun CaptureRequest.Builder.applyFocusOptions(characteristics: CameraCharacteristics) {
        if (CameraFocus.isManualEnabled(appContext)) {
            val maxDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                ?.coerceAtLeast(0f)
                ?: 0f
            if (maxDistance > 0.01f) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, CameraFocus.focusDistanceDiopters(appContext, maxDistance))
                return
            }
        }
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    }

    private fun CaptureRequest.Builder.applyHdrOptions() {
        if (hdrSceneModeEnabled) {
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE)
            set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_HDR)
        } else {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
    }

    private fun drainVideoEncoder() {
        val encoder = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = encoder.dequeueOutputBuffer(bufferInfo, ENCODER_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (stopRequested) requestEndOfStream()
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoFormat = encoder.outputFormat
                    }
                    index >= 0 -> {
                        handleEncodedVideoBuffer(encoder, index, bufferInfo)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (error: Exception) {
            if (!stopRequested) notifyError(error)
        } finally {
            stopAudioCaptureAndWait()
            closeCurrentSegment()
            releaseResources()
            notifyStopped()
        }
    }

    private fun stopAudioCaptureAndWait() {
        stopRequested = true
        runCatching { audioRecord?.stop() }
        if (Thread.currentThread() !== audioThread) {
            runCatching { audioThread?.join(AUDIO_THREAD_JOIN_TIMEOUT_MS) }
        }
    }

    private fun requestEndOfStream() {
        runCatching { videoEncoder?.signalEndOfInputStream() }
    }

    private fun handleEncodedVideoBuffer(
        encoder: MediaCodec,
        index: Int,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            bufferInfo.size = 0
        }
        val encodedData = encoder.getOutputBuffer(index)
        if (bufferInfo.size > 0 && encodedData != null) {
            maybeRequestSplitKeyFrame()
            val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            var closedSegment: ClosedSegment? = null
            var startedSegment: SegmentStart? = null
            synchronized(muxerLock) {
                if (splitPending && isKeyFrame && currentSegmentHasSamples) {
                    closedSegment = closeCurrentSegmentLocked()
                    startedSegment = openSegmentLocked(bufferInfo.presentationTimeUs)
                }
                if (muxer == null) {
                    if (!isKeyFrame || !canOpenSegmentLocked()) return@synchronized
                    startedSegment = openSegmentLocked(bufferInfo.presentationTimeUs)
                }
                writeVideoSampleLocked(encodedData, bufferInfo)
            }
            closedSegment?.let { dispatchClosedSegment(it) }
            startedSegment?.let { callback.onSegmentStarted(it.file, it.startWallMs, it.startElapsedMs) }
        }
        encoder.releaseOutputBuffer(index, false)
    }

    private fun maybeRequestSplitKeyFrame() {
        if (segmentDurationMs <= 0L || currentSegmentStartElapsedMs <= 0L || splitPending) return
        val now = SystemClock.elapsedRealtime()
        if (now - currentSegmentStartElapsedMs < segmentDurationMs) return
        splitPending = true
        if (now - lastKeyFrameRequestElapsedMs >= KEY_FRAME_REQUEST_INTERVAL_MS) {
            lastKeyFrameRequestElapsedMs = now
            runCatching {
                videoEncoder?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            }
        }
    }

    private fun canOpenSegmentLocked(): Boolean {
        return videoFormat != null && (!audioMuxingEnabled || audioFormat != null)
    }

    private fun openSegmentLocked(firstPtsUs: Long): SegmentStart {
        val format = videoFormat ?: throw IllegalStateException("编码器尚未输出视频格式")
        val activeAudioFormat = audioFormat
        val startWallMs = System.currentTimeMillis()
        val file = VideoStorage.privateOutputFile(appContext, startWallMs, tripFolderName)
        file.parentFile?.mkdirs()
        val nextMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        nextMuxer.setOrientationHint(orientationHintDegrees)
        videoTrackIndex = nextMuxer.addTrack(format)
        audioTrackIndex = if (audioMuxingEnabled && activeAudioFormat != null) {
            nextMuxer.addTrack(activeAudioFormat)
        } else {
            -1
        }
        nextMuxer.start()
        muxer = nextMuxer
        currentFile = file
        currentSegmentStartElapsedMs = SystemClock.elapsedRealtime()
        currentSegmentFirstPtsUs = firstPtsUs
        currentSegmentHasSamples = false
        splitPending = false
        return SegmentStart(file, startWallMs, currentSegmentStartElapsedMs)
    }

    private fun writeVideoSampleLocked(encodedData: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val activeMuxer = muxer ?: return
        val segmentFirstPts = currentSegmentFirstPtsUs ?: bufferInfo.presentationTimeUs.also { currentSegmentFirstPtsUs = it }
        val sampleInfo = MediaCodec.BufferInfo().apply {
            set(
                bufferInfo.offset,
                bufferInfo.size,
                (bufferInfo.presentationTimeUs - segmentFirstPts).coerceAtLeast(0L),
                bufferInfo.flags
            )
        }
        encodedData.position(bufferInfo.offset)
        encodedData.limit(bufferInfo.offset + bufferInfo.size)
        activeMuxer.writeSampleData(videoTrackIndex, encodedData, sampleInfo)
        currentSegmentHasSamples = true
    }

    private fun writeAudioSample(encodedData: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            val activeMuxer = muxer ?: return
            val audioTrack = audioTrackIndex.takeIf { it >= 0 } ?: return
            val segmentFirstPts = currentSegmentFirstPtsUs ?: return
            if (bufferInfo.presentationTimeUs < segmentFirstPts) return
            val sampleInfo = MediaCodec.BufferInfo().apply {
                set(
                    bufferInfo.offset,
                    bufferInfo.size,
                    bufferInfo.presentationTimeUs - segmentFirstPts,
                    bufferInfo.flags
                )
            }
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            activeMuxer.writeSampleData(audioTrack, encodedData, sampleInfo)
        }
    }

    private fun closeCurrentSegment() {
        val closed = synchronized(muxerLock) { closeCurrentSegmentLocked() }
        closed?.let { dispatchClosedSegment(it) }
    }

    private fun dispatchClosedSegment(closed: ClosedSegment) {
        val error = closed.error
        if (error == null) {
            callback.onSegmentFinalized(closed.file)
        } else {
            callback.onSegmentFailed(closed.file, error)
        }
    }

    private fun closeCurrentSegmentLocked(): ClosedSegment? {
        val file = currentFile
        val segmentHasSamples = currentSegmentHasSamples
        val activeMuxer = muxer
        muxer = null
        currentFile = null
        videoTrackIndex = -1
        audioTrackIndex = -1
        currentSegmentStartElapsedMs = 0L
        currentSegmentFirstPtsUs = null
        currentSegmentHasSamples = false
        splitPending = false
        var stopError: Throwable? = null
        if (activeMuxer != null) {
            if (segmentHasSamples) {
                try {
                    activeMuxer.stop()
                } catch (error: Exception) {
                    // stop 失败说明文件尾没写进去，不能当成正常分段交出去。
                    stopError = error
                }
            }
            runCatching { activeMuxer.release() }
        }
        if (file != null) {
            if (segmentHasSamples) {
                return ClosedSegment(file, stopError)
            } else {
                file.delete()
            }
        }
        return null
    }

    /** 在相机线程调用。 */
    private fun closeCameraObjects() {
        cameraHandler?.removeCallbacks(memoryStillTick)
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        captureSessionPreviewSurface = null
        previewTargeted = false
        runCatching { cameraDevice?.close() }
        cameraDevice = null
    }

    private fun releaseResources() {
        resourcesReleased = true
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        audioEncoder = null
        audioThread = null
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        val handler = cameraHandler
        val thread = cameraThread
        val cameraTeardown = Runnable {
            closeCameraObjects()
            closeStillReader()
            recentStills.clear()
            runCatching { encoderInputSurface?.release() }
            encoderInputSurface = null
        }
        // 相机对象只在相机线程上关；线程已不在时才就地收尾。
        if (handler == null || !handler.post(cameraTeardown)) cameraTeardown.run()
        if (handler != null && cameraOpenPending) {
            // 相机还没回调 onOpened：先留着线程，让回调能把它关掉。
            handler.postDelayed({ thread?.quitSafely() }, CAMERA_OPEN_GRACE_MS)
        } else {
            thread?.quitSafely()
        }
        cameraThread = null
        cameraHandler = null
        drainExecutor.shutdown()
        stillExecutor.shutdown()
    }

    private fun notifyError(error: Throwable) {
        if (errorNotified.compareAndSet(false, true)) callback.onError(error)
    }

    private fun notifyStopped() {
        if (stoppedNotified.compareAndSet(false, true)) callback.onStopped()
    }

    private fun prepareStillReader(characteristics: CameraCharacteristics) {
        if (stillReader != null) return
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(JPEG_IMAGE_FORMAT)
            ?.map { CaptureSize(it.width, it.height) }
            .orEmpty()
        stillCandidates = StillCapturePlan.jpegSizeCandidates(sizes, mode.width, mode.height)
        stillCandidateIndex = 0
        if (stillCandidates.isEmpty()) {
            includeStillSurface = false
            return
        }
        openStillReader(stillCandidates[stillCandidateIndex])
    }

    @Suppress("DEPRECATION")
    private fun openStillReader(size: CaptureSize) {
        closeStillReader()
        includeStillSurface = true
        stillReader = ImageReader.newInstance(size.width, size.height, JPEG_IMAGE_FORMAT, 2).also { reader ->
            reader.setOnImageAvailableListener({ onStillImage(it) }, cameraHandler)
        }
    }

    private fun closeStillReader() {
        runCatching { stillReader?.close() }
        stillReader = null
    }

    private fun onStillImage(reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val pending = pendingStills.pollFirst()
        if (pending == null) {
            image.close()
            return
        }
        val bytes: ByteArray? = try {
            image.planes.firstOrNull()?.buffer?.let { buffer ->
                buffer.rewind()
                ByteArray(buffer.remaining()).also { out -> buffer.get(out) }
            }
        } catch (error: Exception) {
            pending.callback?.invoke(Result.failure(error))
            return
        } finally {
            image.close()
        }
        if (bytes == null) {
            pending.callback?.invoke(Result.failure(IllegalStateException("照片数据为空")))
            return
        }
        val file = pending.file
        val callback = pending.callback
        if (file == null || callback == null) {
            rememberLookbackJpeg(bytes, pending.elapsedMs)
            return
        }
        // 写文件和旋转照片要解码整张图，放到单独线程，别卡住相机回调。
        val saveTask = Runnable {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { it.write(bytes) }
                bakeExifOrientation(file)
                callback(Result.success(file))
            } catch (error: Exception) {
                callback(Result.failure(error))
            }
        }
        try {
            stillExecutor.execute(saveTask)
        } catch (_: RejectedExecutionException) {
            saveTask.run()
        }
    }

    private fun failPendingStills(error: Throwable) {
        val callbacks = pendingStills.mapNotNull { it.callback }
        pendingStills.clear()
        callbacks.forEach { callback -> callback(Result.failure(error)) }
    }

    private val memoryStillTick: Runnable = Runnable {
        if (stopRequested || stopped.get()) return@Runnable
        captureMemoryStill()
        cameraHandler?.postDelayed(memoryStillTick, MEMORY_STILL_INTERVAL_MS)
    }

    private val stillResultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            val id = request.tag as? Long ?: return
            val pending = removePendingStill(id) ?: return
            if (pending.file == null) {
                memoryStillFailures++
            } else {
                pending.callback?.invoke(Result.failure(StillCaptureUnavailable()))
            }
        }
    }

    private fun captureMemoryStill() {
        // 语音回看和车牌识别都没开时不抓拍，省电也不抢录像带宽。
        if (!memoryStillsEnabled && stillFrameListener == null) return
        if (memoryStillFailures >= MAX_MEMORY_STILL_FAILURES) return
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val reader = stillReader ?: return
        val characteristics = cameraCharacteristics ?: return
        if (stopRequested || !includeStillSurface || pendingStills.isNotEmpty()) return
        val id = enqueueStill(file = null, callback = null, elapsedMs = SystemClock.elapsedRealtime())
        try {
            session.capture(buildSnapshotRequest(camera, reader, characteristics, id), stillResultCallback, cameraHandler)
        } catch (_: Exception) {
            removePendingStill(id)
            memoryStillFailures++
        }
    }

    private fun enqueueStill(
        file: File?,
        callback: ((Result<File>) -> Unit)?,
        elapsedMs: Long
    ): Long {
        val id = nextStillId++
        pendingStills.addLast(PendingStill(id, file, callback, elapsedMs))
        return id
    }

    private fun removePendingStill(id: Long): PendingStill? {
        val pending = pendingStills.firstOrNull { it.id == id } ?: return null
        pendingStills.remove(pending)
        return pending
    }

    private fun buildSnapshotRequest(
        camera: CameraDevice,
        reader: ImageReader,
        characteristics: CameraCharacteristics,
        tag: Long
    ): CaptureRequest {
        val encoderSurface = encoderInputSurface
        return camera.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT).apply {
            addTarget(reader.surface)
            encoderSurface?.let { addTarget(it) }
            activePreviewTarget()?.let { addTarget(it) }
            applyHdrOptions()
            applyFocusOptions(characteristics)
            set(
                CaptureRequest.JPEG_ORIENTATION,
                StillCapturePlan.jpegOrientationDegrees(
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    targetRotation
                )
            )
            set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            setTag(tag)
        }.build()
    }

    private fun rememberLookbackJpeg(bytes: ByteArray, elapsedMs: Long) {
        if (bytes.isEmpty()) return
        if (memoryStillsEnabled) recentStills.add(bytes, elapsedMs)
        val listener = stillFrameListener ?: return
        try {
            listener.onStillFrame(bytes, elapsedMs)
        } catch (_: Exception) {
            // 识别失败不影响录像。
        }
    }

    private class PendingStill(
        val id: Long,
        val file: File?,
        val callback: ((Result<File>) -> Unit)?,
        val elapsedMs: Long
    )

    private fun backCameraId(manager: CameraManager): String? {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun orientationHint(rotation: Int): Int {
        return CameraRotation.recordingOrientationHintDegrees(rotation)
    }

    private fun supportsHdrSceneMode(characteristics: CameraCharacteristics): Boolean {
        return characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
            ?.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR) == true
    }

    /** 相机被别的应用抢走或硬件出错，录像可以过一会儿重新打开相机接着录。 */
    class CameraLostException(message: String) : IllegalStateException(message)

    companion object {
        private const val ENCODER_TIMEOUT_US = 10_000L
        private const val I_FRAME_INTERVAL_SECONDS = 2
        private const val KEY_FRAME_REQUEST_INTERVAL_MS = 500L
        private const val AUDIO_THREAD_JOIN_TIMEOUT_MS = 1_500L
        private const val CAMERA_OPEN_GRACE_MS = 5_000L
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BYTES_PER_SAMPLE = 2
        private const val AUDIO_BIT_RATE = 64_000
        private const val BITRATE_PER_PIXEL_FPS = 0.10
        private const val MIN_VIDEO_BITRATE = 2_000_000
        private const val MAX_VIDEO_BITRATE = 80_000_000
        private const val JPEG_IMAGE_FORMAT = 256
        private const val JPEG_QUALITY: Byte = 92
        private const val MEMORY_STILL_INTERVAL_MS = 500L
        private const val MAX_MEMORY_STILL_FAILURES = 3

        internal fun videoBitRate(mode: VideoMode): Int {
            val estimated = (mode.width.toLong() * mode.height.toLong() * mode.fps.coerceAtLeast(1) * BITRATE_PER_PIXEL_FPS).toInt()
            return estimated.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
        }

        internal fun bakeExifOrientation(file: File) {
            val exif = ExifInterface(file.absolutePath)
            val degrees = when (
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> return
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            FileOutputStream(file).use { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY.toInt(), it) }
            if (rotated != bitmap) rotated.recycle()
            bitmap.recycle()
        }
    }
}
