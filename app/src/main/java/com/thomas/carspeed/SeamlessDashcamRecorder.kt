package com.thomas.carspeed

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.view.Surface
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SeamlessDashcamRecorder(
    context: Context,
    private val mode: VideoMode,
    private val segmentDurationMs: Long,
    private val tripFolderName: String?,
    private val targetRotation: Int,
    private val callback: Callback
) {
    interface Callback {
        fun onStarted()
        fun onSegmentStarted(file: File, startWallMs: Long, startElapsedMs: Long)
        fun onSegmentFinalized(file: File)
        fun onStopped()
        fun onError(error: Throwable)
        fun onCaptureResult(result: CaptureResult)
    }

    private val appContext = context.applicationContext
    private val drainExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private val errorNotified = AtomicBoolean(false)
    @Volatile private var stopRequested = false

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var videoEncoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var videoFormat: MediaFormat? = null
    private var currentFile: File? = null
    private var currentSegmentStartElapsedMs = 0L
    private var currentSegmentFirstPtsUs: Long? = null
    private var currentSegmentHasSamples = false
    private var splitPending = false
    private var lastKeyFrameRequestElapsedMs = 0L
    private var orientationHintDegrees = 0

    fun start() {
        try {
            cameraThread = HandlerThread("CarSpeedSeamlessCamera").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            startVideoEncoder()
            drainExecutor.execute { drainVideoEncoder() }
            openCamera()
        } catch (error: Exception) {
            notifyError(error)
            stop()
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        stopRequested = true
        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { videoEncoder?.signalEndOfInputStream() }
    }

    private fun startVideoEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mode.width, mode.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate(mode))
            setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps.coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderInputSurface = encoder.createInputSurface()
        encoder.start()
        videoEncoder = encoder
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = backCameraId(manager) ?: throw IllegalStateException("没有可用后置摄像头")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        orientationHintDegrees = orientationHint(characteristics, targetRotation)
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, characteristics)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (!stopRequested) notifyError(IllegalStateException("摄像头连接已断开"))
                    stop()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    notifyError(IllegalStateException("摄像头错误：$error"))
                    stop()
                }
            },
            cameraHandler
        )
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(camera: CameraDevice, characteristics: CameraCharacteristics) {
        val surface = encoderInputSurface ?: return
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (stopRequested) return
                    captureSession = session
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.fps, mode.fps))
                            applyFocusOptions(characteristics)
                        }.build()
                        session.setRepeatingRequest(
                            request,
                            object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: CaptureRequest,
                                    result: TotalCaptureResult
                                ) {
                                    callback.onCaptureResult(result)
                                }
                            },
                            cameraHandler
                        )
                        callback.onStarted()
                    } catch (error: Exception) {
                        notifyError(error)
                        stop()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    notifyError(IllegalStateException("摄像头录像会话创建失败"))
                    stop()
                }
            },
            cameraHandler
        )
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
            closeCurrentSegment()
            releaseResources()
            callback.onStopped()
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
            if (splitPending && isKeyFrame && currentSegmentHasSamples) {
                closeCurrentSegment()
                openSegment(bufferInfo.presentationTimeUs)
            }
            if (muxer == null) openSegment(bufferInfo.presentationTimeUs)
            writeVideoSample(encodedData, bufferInfo)
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

    private fun openSegment(firstPtsUs: Long) {
        val format = videoFormat ?: throw IllegalStateException("编码器尚未输出视频格式")
        val startWallMs = System.currentTimeMillis()
        val file = VideoStorage.privateOutputFile(appContext, startWallMs, tripFolderName)
        file.parentFile?.mkdirs()
        val nextMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        nextMuxer.setOrientationHint(orientationHintDegrees)
        videoTrackIndex = nextMuxer.addTrack(format)
        nextMuxer.start()
        muxer = nextMuxer
        currentFile = file
        currentSegmentStartElapsedMs = SystemClock.elapsedRealtime()
        currentSegmentFirstPtsUs = firstPtsUs
        currentSegmentHasSamples = false
        splitPending = false
        callback.onSegmentStarted(file, startWallMs, currentSegmentStartElapsedMs)
    }

    private fun writeVideoSample(encodedData: java.nio.ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
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

    private fun closeCurrentSegment() {
        val file = currentFile
        val segmentHasSamples = currentSegmentHasSamples
        val activeMuxer = muxer
        muxer = null
        currentFile = null
        currentSegmentStartElapsedMs = 0L
        currentSegmentFirstPtsUs = null
        currentSegmentHasSamples = false
        splitPending = false
        if (activeMuxer != null) {
            runCatching { if (segmentHasSamples) activeMuxer.stop() }
            runCatching { activeMuxer.release() }
        }
        if (file != null) {
            if (segmentHasSamples) {
                callback.onSegmentFinalized(file)
            } else {
                file.delete()
            }
        }
    }

    private fun releaseResources() {
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { encoderInputSurface?.release() }
        encoderInputSurface = null
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        drainExecutor.shutdown()
    }

    private fun notifyError(error: Throwable) {
        if (errorNotified.compareAndSet(false, true)) callback.onError(error)
    }

    private fun backCameraId(manager: CameraManager): String? {
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull()
    }

    private fun orientationHint(characteristics: CameraCharacteristics, rotation: Int): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
    }

    private fun videoBitRate(mode: VideoMode): Int {
        val estimated = (mode.width.toLong() * mode.height.toLong() * mode.fps.coerceAtLeast(1) * 0.14).toInt()
        return estimated.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
    }

    companion object {
        private const val ENCODER_TIMEOUT_US = 10_000L
        private const val KEY_FRAME_REQUEST_INTERVAL_MS = 500L
        private const val MIN_VIDEO_BITRATE = 4_000_000
        private const val MAX_VIDEO_BITRATE = 80_000_000
    }
}
