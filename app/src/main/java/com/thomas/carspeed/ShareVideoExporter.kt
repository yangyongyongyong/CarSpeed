package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ShareExportOptions(
    val includeRouteMap: Boolean = true,
    val mapHeightPercent: Int = 28,
    val mapScalePercent: Int = 100,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = Long.MAX_VALUE,
    val playbackSpeed: Float = 1f,
    val subtitleFields: ShareSubtitleFields = ShareSubtitleFields(),
    val fps: Int = 15,
    val layoutMode: String = ReplayMapLayout.MODE_SPLIT,
    val floatSpec: ReplayFloatSpec? = null,
    val rotationDegrees: Int = 0,
    val mapCornerRadiusPx: Float = 24f,
    val mapAspectRatio: Float = 0f
)

data class ShareSubtitleFields(
    val showTime: Boolean = true,
    val showSpeed: Boolean = true,
    val showStationaryWait: Boolean = true,
    val showGps: Boolean = true,
    val showSatellites: Boolean = true,
    val showRadarSummary: Boolean = true,
    val showAddress: Boolean = true,
    val showFocus: Boolean = true,
    val showVideoMode: Boolean = true,
    val showRoadLayer: Boolean = true
) {
    /** 十项全关时底部白栏整条不显示，也不占画面高度。 */
    fun hasAnyField(): Boolean =
        showTime || showSpeed || showStationaryWait || showGps || showSatellites ||
            showRadarSummary || showAddress || showFocus || showVideoMode || showRoadLayer
}

/**
 * 设置里那十个开关到字幕字段的唯一映射。
 * 已经从 [DashcamSettings.load] 拿到设置的地方直接用它，避免为了十个布尔再读一次 SharedPreferences。
 */
fun WatermarkSettings.toSubtitleFields(): ShareSubtitleFields = ShareSubtitleFields(
    showTime = showTime,
    showSpeed = showSpeed,
    showStationaryWait = showStationaryWait,
    showGps = showGps,
    showSatellites = showSatellites,
    showRadarSummary = showRadarSummary,
    showAddress = showAddress,
    showFocus = showFocus,
    showVideoMode = showVideoMode,
    showRoadLayer = showRoadLayer
)

object ShareVideoExporter {
    private const val MIME_AVC = "video/avc"
    private const val I_FRAME_INTERVAL_SECONDS = 2
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L
    private const val END_OF_STREAM_ATTEMPTS = 500

    fun totalDurationMs(videos: List<File>): Long {
        return videos.sumOf { durationMs(it).coerceAtLeast(0L) }
    }

    internal fun resolveExportLayout(
        sourceWidth: Int,
        sourceHeight: Int,
        options: ShareExportOptions,
        hasTrack: Boolean
    ): ExportLayout {
        val quarter = ((options.rotationDegrees % 360) + 360) % 360
        val swapped = quarter == 90 || quarter == 270
        val videoWidth = even((if (swapped) sourceHeight else sourceWidth).coerceAtLeast(2))
        val videoHeight = even((if (swapped) sourceWidth else sourceHeight).coerceAtLeast(2))
        // 字幕开关全关时白底栏整条不画，成片就是纯画面，也不再占高度。
        val infoBarHeight = if (options.subtitleFields.hasAnyField()) {
            ExportSubtitleStyle.barHeightPx(videoWidth, videoHeight)
        } else {
            0
        }
        val stageWidth = if (swapped) videoWidth + infoBarHeight else videoWidth
        val stageHeight = if (swapped) videoHeight else videoHeight + infoBarHeight
        val showMap = options.includeRouteMap && hasTrack
        val spec = options.floatSpec
        if (showMap && options.layoutMode == ReplayMapLayout.MODE_FLOAT && spec != null) {
            return ExportLayout(
                width = stageWidth,
                height = stageHeight,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                infoBarHeight = infoBarHeight,
                mapHeight = 0,
                floatMapRect = floatMapFrame(videoWidth, videoHeight, spec, options.mapAspectRatio)
            )
        }
        val mapHeight = if (showMap) {
            even((videoHeight * options.mapHeightPercent.coerceIn(18, 70) / 100f).toInt()).coerceAtLeast(120)
        } else {
            0
        }
        return ExportLayout(
            width = stageWidth,
            height = stageHeight + mapHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            infoBarHeight = infoBarHeight,
            mapHeight = mapHeight,
            floatMapRect = null
        )
    }

    private fun floatMapFrame(
        videoWidth: Int,
        videoHeight: Int,
        spec: ReplayFloatSpec,
        mapAspectRatio: Float
    ): ReplayFloatFrame {
        val rawLeft = videoWidth * spec.leftFraction
        val rawTop = videoHeight * spec.topFraction
        val rawWidth = videoWidth * spec.widthFraction
        val rawHeight = videoHeight * spec.heightFraction
        val fittedHeight = if (mapAspectRatio > 0.05f) rawWidth / mapAspectRatio else rawHeight
        val centerX = rawLeft + rawWidth / 2f
        val centerY = rawTop + rawHeight / 2f
        var left = (centerX - rawWidth / 2f).roundToInt()
        var top = (centerY - fittedHeight / 2f).roundToInt()
        val width = rawWidth.roundToInt().coerceAtLeast(2)
        val height = fittedHeight.roundToInt().coerceAtLeast(2)
        if (left < 0) left = 0
        if (top < 0) top = 0
        if (left + width > videoWidth) left = (videoWidth - width).coerceAtLeast(0)
        if (top + height > videoHeight) top = (videoHeight - height).coerceAtLeast(0)
        return ReplayFloatFrame(left, top, width, height)
    }

    fun export(
        context: Context,
        videos: List<File>,
        options: ShareExportOptions,
        onProgress: (Int) -> Unit,
        mapFrameAt: ((Long) -> Bitmap?)? = null,
        shouldContinue: () -> Boolean = { true }
    ): File {
        require(videos.isNotEmpty()) { "没有可导出的视频" }
        val sources = videos.map { video ->
            if (!shouldContinue()) throw ExportCancelled()
            ReplayMediaSource.prepare(context, video)
        }
        val mediaFiles = sources.map { it.file }
        val durations = sources.mapIndexed { index, source ->
            source.durationMs.takeIf { it > 0L } ?: durationMs(mediaFiles[index]).coerceAtLeast(1L)
        }
        val timeline = buildTimeline(context, videos, durations)
        val sourceStartMs = options.validStartMs(timeline.totalDurationMs)
        val sourceEndMs = options.validEndMs(timeline.totalDurationMs).coerceAtLeast(sourceStartMs + 1_000L)
        val sourceDurationMs = (sourceEndMs - sourceStartMs).coerceAtLeast(1L)
        val playbackSpeed = options.playbackSpeed.coerceIn(0.25f, 16f)
        val outputDurationMs = ceil(sourceDurationMs / playbackSpeed.toDouble()).toLong().coerceAtLeast(1L)
        val retrievers = ArrayList<MediaMetadataRetriever>(videos.size)
        var audio: ExportAudioCopier? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var egl: ExportEglSurface? = null
        var muxer: MediaMuxer? = null
        var sequence: ExportFrameSequence? = null
        val output = VideoStorage.shareExportFile(context, videos.first().parentFile?.name ?: videos.first().name)
        try {
            mediaFiles.forEach { video ->
                val retriever = MediaMetadataRetriever()
                retrievers += retriever
                retriever.setDataSource(video.absolutePath)
            }
            val firstFrame = retrievers.first().getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retrievers.first().frameAtTime
                ?: throw IllegalStateException("无法读取视频帧")
            val hasTrack = timeline.track?.points?.isNotEmpty() == true
            val layout = resolveExportLayout(firstFrame.width, firstFrame.height, options, hasTrack)
            firstFrame.recycle()
            val format = MediaFormat.createVideoFormat(MIME_AVC, layout.width, layout.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(layout.width, layout.height, options.fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, options.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Int.MAX_VALUE)
            }
            var planar = false
            var flexible = false
            val surfaceEncoder = runCatching { startSurfaceEncoder(format) }.getOrNull()
            if (surfaceEncoder != null) {
                try {
                    val surface = surfaceEncoder.createInputSurface()
                    inputSurface = surface
                    surfaceEncoder.start()
                    egl = ExportEglSurface(surface, layout.width, layout.height)
                    encoder = surfaceEncoder
                } catch (error: Exception) {
                    surfaceEncoder.stopSafely()
                    inputSurface?.releaseSafely()
                    inputSurface = null
                    surfaceEncoder.releaseSafely()
                    egl = null
                }
            }
            if (encoder == null) {
                val codecSpec = selectAvcEncoder()
                planar = codecSpec.planar
                flexible = codecSpec.flexible
                val byteFormat = MediaFormat.createVideoFormat(MIME_AVC, layout.width, layout.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, codecSpec.colorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(layout.width, layout.height, options.fps))
                    setInteger(MediaFormat.KEY_FRAME_RATE, options.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                }
                encoder = MediaCodec.createByCodecName(codecSpec.name).apply {
                    configure(byteFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }
            val activeEncoder = encoder ?: throw IllegalStateException("无法创建视频编码器")
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val bufferInfo = MediaCodec.BufferInfo()
            // 倍速成片的声音会变调走样，只有原速时才带上原声。
            if (playbackSpeed == 1f) {
                audio = runCatching {
                    ExportAudioCopier.open(mediaFiles, timeline.offsetsMs, timeline.durationsMs, sourceStartMs, sourceEndMs)
                }.getOrNull()
            }
            val state = MuxerState(activeMuxer, audio = audio)
            val clock = ExportPresentationClock()
            sequence = ExportFrameSequence(mediaFiles, timeline.offsetsMs)
            val canvasBitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val yuv = if (egl == null) ByteArray(layout.width * layout.height * 3 / 2) else ByteArray(0)
            val argb = if (egl == null) IntArray(layout.width * layout.height) else IntArray(0)
            val totalFrames = max(1, ceil(outputDurationMs * options.fps / 1000.0).toInt())
            val frameDurationUs = 1_000_000L / options.fps.coerceAtLeast(1)
            val sourceStepMs = (1000f * playbackSpeed / options.fps.coerceAtLeast(1)).toLong()
            val seekOption = if (sourceStepMs >= 500L) {
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            } else {
                MediaMetadataRetriever.OPTION_CLOSEST
            }
            var fallbackFrame: Bitmap? = null
            var mapKey = Long.MIN_VALUE
            var mapBitmap: Bitmap? = null
            try {
                for (frameIndex in 0 until totalFrames) {
                    if (!shouldContinue()) throw ExportCancelled()
                    drainEncoder(activeEncoder, bufferInfo, state, endOfStream = false, clock = clock)
                    val outputElapsedMs = frameIndex * 1000L / options.fps.coerceAtLeast(1)
                    val sourceElapsedMs = min(sourceEndMs - 1, sourceStartMs + (outputElapsedMs * playbackSpeed).toLong()).coerceAtLeast(0L)
                    val decoded = sequence?.frameAt(sourceElapsedMs)
                    val frame = if (decoded != null) {
                        fallbackFrame?.recycle()
                        fallbackFrame = null
                        decoded
                    } else {
                        val fresh = frameAt(
                            timeline = timeline,
                            retrievers = retrievers,
                            elapsedMs = sourceElapsedMs,
                            targetWidth = layout.videoWidth,
                            targetHeight = layout.videoHeight,
                            option = seekOption
                        )
                        if (fresh !== fallbackFrame) fallbackFrame?.recycle()
                        fallbackFrame = fresh
                        fresh
                    } ?: continue
                    if (options.includeRouteMap && hasTrack && mapFrameAt != null) {
                        val key = ExportMapSampling.key(timeline.track?.points.orEmpty(), sourceElapsedMs)
                        if (key != mapKey) {
                            val fresh = mapFrameAt(sourceElapsedMs)
                            if (fresh != null) {
                                if (fresh !== mapBitmap) mapBitmap?.recycle()
                                mapBitmap = fresh
                            }
                            mapKey = key
                        }
                    }
                    ShareFramePainter.drawFrame(
                        canvas = canvas,
                        videoFrame = frame,
                        videoWidth = layout.videoWidth,
                        videoHeight = layout.videoHeight,
                        infoBarHeight = layout.infoBarHeight,
                        mapHeight = layout.mapHeight,
                        elapsedMs = sourceElapsedMs,
                        subtitle = subtitleAt(timeline, sourceElapsedMs, options.subtitleFields),
                        track = timeline.track,
                        options = options,
                        mapBitmap = mapBitmap,
                        floatMapRect = layout.floatMapRect
                    )
                    val presentationUs = frameIndex * frameDurationUs
                    val writer = egl
                    var submitted = false
                    var attempt = 0
                    while (attempt < 6 && !submitted) {
                        attempt += 1
                        if (writer != null) {
                            writer.draw(canvasBitmap, presentationUs)
                            submitted = true
                        } else if (queueBitmapFrame(activeEncoder, canvasBitmap, argb, yuv, planar, flexible, presentationUs)) {
                            submitted = true
                        } else {
                            drainEncoder(activeEncoder, bufferInfo, state, endOfStream = false, clock = clock)
                        }
                    }
                    if (submitted) clock.note(presentationUs)
                    if (state.started) state.audio?.writeUntil(state.muxer, presentationUs)
                    if (frameIndex % options.fps == 0 || frameIndex == totalFrames - 1) {
                        onProgress(((frameIndex + 1) * 100 / totalFrames).coerceIn(0, 100))
                    }
                }
                if (egl != null) {
                    activeEncoder.signalEndOfInputStream()
                } else {
                    queueEndOfStream(activeEncoder, totalFrames * frameDurationUs, bufferInfo, state, clock)
                }
                drainEncoder(activeEncoder, bufferInfo, state, endOfStream = true, clock = clock)
                if (state.started) state.audio?.writeUntil(state.muxer, outputDurationMs * 1_000L)
                if (!state.started) throw IllegalStateException("没有生成任何画面")
                // 在这里收尾，stop 失败（文件不完整）要当成导出失败，不能把坏文件交出去。
                activeMuxer.stop()
            } finally {
                fallbackFrame?.recycle()
                mapBitmap?.recycle()
                canvasBitmap.recycle()
            }
            return output
        } catch (cancelled: ExportCancelled) {
            output.delete()
            throw cancelled
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            sequence?.close()
            audio?.close()
            retrievers.forEach { retriever -> retriever.releaseSafely() }
            encoder?.stopSafely()
            egl?.close()
            inputSurface?.releaseSafely()
            encoder?.releaseSafely()
            muxer?.stopSafely()
            muxer?.releaseSafely()
        }
    }

    private fun startSurfaceEncoder(format: MediaFormat): MediaCodec {
        val created = MediaCodec.createEncoderByType(MIME_AVC)
        return try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created
        } catch (error: Exception) {
            created.releaseSafely()
            val plain = MediaFormat.createVideoFormat(
                MIME_AVC,
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT)
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
                setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL))
            }
            MediaCodec.createEncoderByType(MIME_AVC).apply {
                configure(plain, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
    }

    private fun buildTimeline(context: Context, videos: List<File>, durations: List<Long>): ExportTimeline {
        val offsets = durations.runningFold(0L) { total, duration -> total + duration }.dropLast(1)
        return ExportTimeline(
            durationsMs = durations,
            offsetsMs = offsets,
            totalDurationMs = durations.sum().coerceAtLeast(1L),
            subtitleCues = SrtSubtitle.readCues(videos, offsets, durations),
            track = VideoTrackStorage.readMergedTrack(context, videos, offsets, durations)
        )
    }

    private fun frameAt(
        timeline: ExportTimeline,
        retrievers: List<MediaMetadataRetriever>,
        elapsedMs: Long,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        option: Int = MediaMetadataRetriever.OPTION_CLOSEST
    ): Bitmap? {
        val segment = timeline.segmentAt(elapsedMs)
        val localMs = (elapsedMs - timeline.offsetsMs.getOrElse(segment) { 0L }).coerceAtLeast(0L)
        val timeUs = localMs * 1_000L
        val retriever = retrievers[segment]
        if (targetWidth > 0 && targetHeight > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, option, targetWidth, targetHeight)?.let { return it }
        }
        return retriever.getFrameAtTime(timeUs, option)
            ?: retriever.frameAtTime
    }

    private fun subtitleAt(timeline: ExportTimeline, elapsedMs: Long, fields: ShareSubtitleFields): String? {
        val cue = SrtSubtitle.cueAt(timeline.subtitleCues, elapsedMs) ?: return null
        val filtered = ShareSubtitleFormatter.overlayText(cue.text, fields)
        return filtered.takeIf { it.isNotBlank() }
    }

    private fun durationMs(video: File): Long {
        return MediaMetadataRetriever().useSafely { retriever ->
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } ?: 0L
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun ShareExportOptions.validStartMs(totalDurationMs: Long): Long {
        return sourceStartMs.coerceIn(0L, (totalDurationMs - 1).coerceAtLeast(0L))
    }

    private fun ShareExportOptions.validEndMs(totalDurationMs: Long): Long {
        return sourceEndMs.coerceIn(validStartMs(totalDurationMs) + 1L, totalDurationMs.coerceAtLeast(1L))
    }

    private fun videoBitrate(width: Int, height: Int, fps: Int): Int {
        return (width * height * fps * 0.18f).toInt().coerceIn(2_000_000, 12_000_000)
    }

    private fun selectAvcEncoder(): CodecSpec {
        val candidates = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { codec ->
            if (!codec.isEncoder || codec.supportedTypes.none { it.equals(MIME_AVC, ignoreCase = true) }) return@forEach
            val colorFormats = codec.getCapabilitiesForType(MIME_AVC).colorFormats.toSet()
            val colorFormat = candidates.firstOrNull { it in colorFormats } ?: return@forEach
            return CodecSpec(
                name = codec.name,
                colorFormat = colorFormat,
                planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                flexible = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
        }
        throw IllegalStateException("设备没有支持 YUV420 输入的 H.264 编码器")
    }

    private fun queueBitmapFrame(
        encoder: MediaCodec,
        bitmap: Bitmap,
        argb: IntArray,
        yuv: ByteArray,
        planar: Boolean,
        flexible: Boolean,
        presentationTimeUs: Long
    ): Boolean {
        val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) return false
        if (flexible) {
            // Flexible 的实际排布由编码器决定（很多机型是 NV12），必须按平面的步长写，不能当成 I420。
            val image = runCatching { encoder.getInputImage(inputIndex) }.getOrNull()
            if (image != null) {
                bitmapToImage(bitmap, argb, image)
                encoder.queueInputBuffer(inputIndex, 0, bitmap.width * bitmap.height * 3 / 2, presentationTimeUs, 0)
                return true
            }
        }
        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return false
        bitmapToYuv420(bitmap, argb, yuv, planar)
        inputBuffer.clear()
        inputBuffer.put(yuv)
        encoder.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
        return true
    }

    private fun queueEndOfStream(
        encoder: MediaCodec,
        presentationTimeUs: Long,
        bufferInfo: MediaCodec.BufferInfo,
        state: MuxerState,
        clock: ExportPresentationClock
    ) {
        repeat(END_OF_STREAM_ATTEMPTS) {
            val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            // 输入缓冲全被占着时要先取走输出，编码器才会还回输入缓冲。
            drainEncoder(encoder, bufferInfo, state, endOfStream = false, clock = clock)
        }
        throw IllegalStateException("编码器没有响应，导出中止")
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        state: MuxerState,
        endOfStream: Boolean,
        clock: ExportPresentationClock
    ) {
        var stalls = 0
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    stalls += 1
                    if (stalls > 500) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (state.started) throw IllegalStateException("编码格式重复变化")
                    state.trackIndex = state.muxer.addTrack(encoder.outputFormat)
                    state.audio?.addTrack(state.muxer)
                    state.muxer.start()
                    state.started = true
                    stalls = 0
                }
                outputIndex >= 0 -> {
                    stalls = 0
                    val config = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (!config && outputBuffer != null && bufferInfo.size > 0) {
                        if (!state.started) throw IllegalStateException("Muxer 尚未启动")
                        bufferInfo.presentationTimeUs = clock.take()
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        state.muxer.writeSampleData(state.trackIndex, outputBuffer, bufferInfo)
                    }
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (eos) return
                }
            }
        }
    }

    private fun bitmapToYuv420(bitmap: Bitmap, argb: IntArray, yuv: ByteArray, planar: Boolean) {
        val width = bitmap.width
        val height = bitmap.height
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        val uPlane = frameSize
        val vPlane = frameSize + frameSize / 4
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                yuv[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    if (planar) {
                        val uvOffset = (j / 2) * (width / 2) + (i / 2)
                        yuv[uPlane + uvOffset] = u.coerceIn(0, 255).toByte()
                        yuv[vPlane + uvOffset] = v.coerceIn(0, 255).toByte()
                    } else {
                        yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
                        yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    private fun bitmapToImage(bitmap: Bitmap, argb: IntArray, image: android.media.Image) {
        val width = min(bitmap.width, image.width)
        val height = min(bitmap.height, image.height)
        val sourceWidth = bitmap.width
        bitmap.getPixels(argb, 0, sourceWidth, 0, 0, sourceWidth, bitmap.height)
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPixel = planes[0].pixelStride
        val uBuffer = planes[1].buffer
        val uRow = planes[1].rowStride
        val uPixel = planes[1].pixelStride
        val vBuffer = planes[2].buffer
        val vRow = planes[2].rowStride
        val vPixel = planes[2].pixelStride
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * sourceWidth + i]
                val r = color shr 16 and 0xff
                val g = color shr 8 and 0xff
                val b = color and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(j * yRow + i * yPixel, y.coerceIn(0, 255).toByte())
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val row = j / 2
                    val column = i / 2
                    uBuffer.put(row * uRow + column * uPixel, u.coerceIn(0, 255).toByte())
                    vBuffer.put(row * vRow + column * vPixel, v.coerceIn(0, 255).toByte())
                }
            }
        }
    }

    private inline fun <T> MediaMetadataRetriever.useSafely(block: (MediaMetadataRetriever) -> T): T? {
        return try {
            block(this)
        } catch (_: Exception) {
            null
        } finally {
            releaseSafely()
        }
    }

    private fun MediaMetadataRetriever.releaseSafely() {
        try {
            release()
        } catch (_: Exception) {
            // 忽略系统解码器释放异常。
        }
    }

    private fun MediaCodec.stopSafely() {
        try {
            stop()
        } catch (_: Exception) {
            // 编码失败时可能尚未启动，忽略并继续释放资源。
        }
    }

    private fun MediaCodec.releaseSafely() {
        try {
            release()
        } catch (_: Exception) {
            // 忽略系统编码器释放异常。
        }
    }

    private fun Surface.releaseSafely() {
        try {
            release()
        } catch (_: Exception) {
            // 输入 Surface 可能已经随编码器释放。
        }
    }

    private fun MediaMuxer.stopSafely() {
        try {
            stop()
        } catch (_: Exception) {
            // 没写入样本时 stop 可能失败，外层已删除坏文件。
        }
    }

    private fun MediaMuxer.releaseSafely() {
        try {
            release()
        } catch (_: Exception) {
            // 忽略 muxer 释放异常。
        }
    }

    private data class CodecSpec(val name: String, val colorFormat: Int, val planar: Boolean, val flexible: Boolean)
    private data class MuxerState(
        val muxer: MediaMuxer,
        var started: Boolean = false,
        var trackIndex: Int = -1,
        val audio: ExportAudioCopier? = null
    )

    private data class ExportTimeline(
        val durationsMs: List<Long>,
        val offsetsMs: List<Long>,
        val totalDurationMs: Long,
        val subtitleCues: List<SrtCue>,
        val track: VideoTrack?
    ) {
        fun segmentAt(elapsedMs: Long): Int {
            return offsetsMs.indexOfLast { it <= elapsedMs }.coerceIn(0, durationsMs.lastIndex)
        }
    }
}

internal class ExportCancelled : Exception()

internal data class ExportLayout(
    val width: Int,
    val height: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    val infoBarHeight: Int,
    val mapHeight: Int,
    val floatMapRect: ReplayFloatFrame?
)

object ShareFramePainter {
    private val videoDst = RectF()
    private val mapRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val mapBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 24, 34)
        style = Paint.Style.FILL
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 128, 170)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 214, 151)
        style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    private val mapBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val mapClip = Path()
    private val mapShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val mapFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
    }

    internal fun drawFrame(
        canvas: Canvas,
        videoFrame: Bitmap,
        videoWidth: Int,
        videoHeight: Int,
        infoBarHeight: Int,
        mapHeight: Int,
        elapsedMs: Long,
        subtitle: String?,
        track: VideoTrack?,
        options: ShareExportOptions,
        mapBitmap: Bitmap? = null,
        floatMapRect: ReplayFloatFrame? = null
    ) {
        canvas.drawColor(Color.BLACK)
        drawVideoStage(
            canvas = canvas,
            bitmap = videoFrame,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            infoBarHeight = infoBarHeight,
            subtitle = subtitle,
            options = options
        )
        val overlay = floatMapRect
        if (options.includeRouteMap && overlay != null && overlay.width > 1 && overlay.height > 1) {
            val dst = RectF(
                overlay.left.toFloat(),
                overlay.top.toFloat(),
                (overlay.left + overlay.width).toFloat(),
                (overlay.top + overlay.height).toFloat()
            )
            drawMapInto(canvas, mapBitmap, dst, track, elapsedMs, options, options.mapCornerRadiusPx)
        } else if (mapHeight > 0 && options.includeRouteMap) {
            val mapTop = if (VideoReplayRotation.isQuarterTurn(options.rotationDegrees)) videoHeight else videoHeight + infoBarHeight
            mapRect.set(0f, mapTop.toFloat(), canvas.width.toFloat(), (mapTop + mapHeight).toFloat())
            drawMapInto(canvas, mapBitmap, mapRect, track, elapsedMs, options, 0f)
        }
    }

    /** 画面和白底参数栏是一个整体：先按未旋转的尺寸画好，再整体旋转到成片上。 */
    private fun drawVideoStage(
        canvas: Canvas,
        bitmap: Bitmap,
        videoWidth: Int,
        videoHeight: Int,
        infoBarHeight: Int,
        subtitle: String?,
        options: ShareExportOptions
    ) {
        val rotation = ((options.rotationDegrees % 360) + 360) % 360
        val quarter = rotation == 90 || rotation == 270
        val preWidth = (if (quarter) videoHeight else videoWidth).toFloat()
        val preHeight = (if (quarter) videoWidth else videoHeight).toFloat()
        val preStageHeight = preHeight + infoBarHeight
        val centerX = (if (quarter) videoWidth + infoBarHeight else videoWidth) / 2f
        val centerY = (if (quarter) videoHeight else videoHeight + infoBarHeight) / 2f
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(rotation.toFloat())
        canvas.translate(-preWidth / 2f, -preStageHeight / 2f)
        drawFitCenter(canvas, bitmap, RectF(0f, 0f, preWidth, preHeight))
        DashcamInfoBarPainter.draw(
            canvas,
            ShareSubtitleFormatter.infoBar(subtitle.orEmpty(), options.subtitleFields),
            preHeight,
            preWidth,
            infoBarHeight.toFloat()
        )
        canvas.restore()
    }

    private fun drawMapInto(
        canvas: Canvas,
        mapBitmap: Bitmap?,
        dst: RectF,
        track: VideoTrack?,
        elapsedMs: Long,
        options: ShareExportOptions,
        cornerRadius: Float
    ) {
        if (mapBitmap != null && !mapBitmap.isRecycled) {
            drawRoundedMap(canvas, mapBitmap, dst, cornerRadius)
        } else {
            drawRouteMap(canvas, dst, track, elapsedMs, options.mapScalePercent)
        }
    }

    private fun drawRoundedMap(canvas: Canvas, bitmap: Bitmap, dst: RectF, cornerRadius: Float) {
        val radius = cornerRadius.coerceAtLeast(0f)
        if (radius > 0f) {
            val shadow = RectF(dst)
            shadow.offset(0f, radius * 0.18f)
            canvas.drawRoundRect(shadow, radius, radius, mapShadowPaint)
        }
        canvas.save()
        if (radius > 0f) {
            mapClip.reset()
            mapClip.addRoundRect(dst, radius, radius, Path.Direction.CW)
            canvas.clipPath(mapClip)
        }
        drawMatchWidth(canvas, bitmap, dst)
        canvas.restore()
        if (radius > 0f) {
            mapFramePaint.strokeWidth = (radius * 0.12f).coerceIn(2f, 8f)
            canvas.drawRoundRect(dst, radius, radius, mapFramePaint)
        }
    }

    private fun drawMatchWidth(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        canvas.drawRect(dst, mapBgPaint)
        val scale = dst.width() / bitmap.width.coerceAtLeast(1)
        val drawnHeight = bitmap.height * scale
        val top = dst.centerY() - drawnHeight / 2f
        videoDst.set(dst.left, top, dst.right, top + drawnHeight)
        canvas.drawBitmap(bitmap, null, videoDst, mapBitmapPaint)
    }

    private fun drawFitCenter(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val scale = min(dst.width() / bitmap.width.coerceAtLeast(1), dst.height() / bitmap.height.coerceAtLeast(1))
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        videoDst.set(
            dst.centerX() - width / 2f,
            dst.centerY() - height / 2f,
            dst.centerX() + width / 2f,
            dst.centerY() + height / 2f
        )
        canvas.drawBitmap(bitmap, null, videoDst, null)
    }

    private fun drawRouteMap(canvas: Canvas, rect: RectF, track: VideoTrack?, elapsedMs: Long, scalePercent: Int) {
        canvas.drawRect(rect, mapBgPaint)
        val points = track?.points.orEmpty()
        textPaint.textSize = (rect.height() * 0.12f).coerceIn(18f, 30f)
        if (points.size < 2) {
            canvas.drawText("暂无 GPS 轨迹", rect.left + rect.width() * 0.08f, rect.centerY(), textPaint)
            return
        }
        val padding = rect.height() * 0.16f
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }
        val maxLon = points.maxOf { it.longitude }
        val latSpan = (maxLat - minLat).takeIf { it > 0.000001 } ?: 0.000001
        val lonSpan = (maxLon - minLon).takeIf { it > 0.000001 } ?: 0.000001
        val contentW = rect.width() - padding * 2f
        val contentH = rect.height() - padding * 2f
        val fitScale = min(contentW / lonSpan.toFloat(), contentH / latSpan.toFloat())
        val routeScale = fitScale * (scalePercent.coerceIn(60, 220) / 100f)
        val centerLon = (minLon + maxLon) / 2.0
        val centerLat = (minLat + maxLat) / 2.0

        fun x(point: VideoTrackPoint): Float = rect.centerX() + ((point.longitude - centerLon) * routeScale).toFloat()
        fun y(point: VideoTrackPoint): Float = rect.centerY() - ((point.latitude - centerLat) * routeScale).toFloat()

        routePaint.strokeWidth = (rect.height() * 0.035f).coerceIn(4f, 10f)
        playedPaint.strokeWidth = routePaint.strokeWidth * 0.72f
        markerStrokePaint.strokeWidth = routePaint.strokeWidth * 0.45f
        for (i in 1 until points.size) {
            canvas.drawLine(x(points[i - 1]), y(points[i - 1]), x(points[i]), y(points[i]), routePaint)
        }
        val playedIndex = VideoTrackSync.lastPlayedIndex(points, elapsedMs).coerceIn(1, points.lastIndex)
        for (i in 1..playedIndex) {
            canvas.drawLine(x(points[i - 1]), y(points[i - 1]), x(points[i]), y(points[i]), playedPaint)
        }
        val current = VideoTrackSync.pointAt(points, elapsedMs) ?: points.first()
        val cx = x(current)
        val cy = y(current)
        val radius = (rect.height() * 0.05f).coerceIn(7f, 14f)
        canvas.drawCircle(cx, cy, radius, markerPaint)
        canvas.drawCircle(cx, cy, radius, markerStrokePaint)
        val distance = TrackMetrics.distanceAt(points, elapsedMs)
        val label = String.format(Locale.CHINA, "%s  比例 %d%%", TrackMetrics.formatDistance(distance), scalePercent)
        canvas.drawText(label, rect.left + padding * 0.7f, rect.bottom - padding * 0.45f, textPaint)
    }
}

object ShareSubtitleFormatter {
    fun infoBar(text: String, fields: ShareSubtitleFields = ShareSubtitleFields()): DashcamInfoBarText {
        val values = text.lineSequence().flatMap { it.split('|').asSequence() }
            .map { it.trim() }.filter { it.isNotEmpty() && keepLine(it, fields) }
            .groupBy { categoryOf(it) }
        fun first(category: SubtitleCategory): String? = values[category]?.firstOrNull()
        val speed = first(SubtitleCategory.SPEED)?.let { SPEED_VALUE_REGEX.find(it)?.groupValues?.get(1) }
        val parameters = buildList {
            values[SubtitleCategory.VIDEO_MODE]?.forEach { mode ->
                add(mode.replace(RESOLUTION_SIZE_REGEX, "").replace("·", " ").trim().replace(SPACES_REGEX, "  "))
            }
            first(SubtitleCategory.FOCUS)?.let { focus ->
                add(if (focus.contains("MF")) "MF" else "AF")
            }
            values[SubtitleCategory.RADAR]?.let { addAll(it) }
            first(SubtitleCategory.STATIONARY_WAIT)?.let { wait ->
                // 停留是整趟的累计值，为 0 也要显示，否则同一段录像前后几秒会忽隐忽现。
                add(wait.replace("静止等待：", "停留 "))
            }
            first(SubtitleCategory.SATELLITES)?.let { add(it.replace("GPS卫星：", "卫星 ")) }
        }
        // 经纬度独占最后一行，和实时预览保持一致：它是成对读的一对坐标，不能被拆开，
        // 放末行也保证真到行数上限时被省略号收掉的是它，而不是把地址截掉。
        val gps = first(SubtitleCategory.GPS)
        val gpsPending = gps == "GPS --"
        val caption = buildList {
            first(SubtitleCategory.TIME)?.let { add(it.replace('-', '.')) }
            val address = first(SubtitleCategory.ADDRESS)
            // 地址和经纬度是两个独立开关，一个在不能把另一个顶掉。
            // 但两者都「还没定位到」时说的是同一件事，只写一遍：经纬度那行已经写了，这里就跳过。
            if (address != null && address != "位置解析中") {
                add(address)
            } else if (address != null && !gpsPending) {
                add("等待定位")
            }
            values[SubtitleCategory.ROAD_LAYER]?.let { addAll(it) }
            if (gps != null) {
                add("\n" + if (gpsPending) "等待定位" else gps)
            }
        }
        return DashcamInfoBarText(
            // 关掉速度就整块留空；没有速度数据时也不拿品牌名占位。
            title = speed ?: "",
            label = if (speed != null) "km/h" else "",
            parameters = parameters.joinToString("  "),
            caption = joinCaption(caption)
        )
    }

    /**
     * 拼 caption：硬换行前后不加分隔空格，否则「经纬度独占一行」会变成
     * 「上一行末尾两个空格 + 下一行行首两个空格」，右对齐时看着像没对齐。
     */
    private fun joinCaption(parts: List<String>): String {
        val result = StringBuilder()
        for (part in parts) {
            if (part.startsWith("\n")) {
                result.append(part.trimStart('\n').let { "\n$it" })
            } else {
                if (result.isNotEmpty()) result.append("  ")
                result.append(part)
            }
        }
        return result.toString()
    }

    private val SPEED_VALUE_REGEX = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*km/h", RegexOption.IGNORE_CASE)
    private val RESOLUTION_SIZE_REGEX = Regex("\\s*\\d{3,5}[x×]\\d{3,5}")
    private val SPACES_REGEX = Regex("\\s{2,}")

    fun filter(text: String, fields: ShareSubtitleFields): String {
        return text.lines()
            .flatMap { line -> line.split('|') }
            .map { it.trim() }
            .filter { segment -> segment.isNotBlank() && keepLine(segment, fields) }
            .joinToString("  |  ")
    }

    /** 回放页字幕是按行叠在画面上的。预览保留换行，只拿掉未勾选的字段。 */
    fun overlayText(text: String, fields: ShareSubtitleFields): String {
        return text.lines().mapNotNull { line ->
            val kept = line.split('|')
                .map { it.trim() }
                .filter { segment -> segment.isNotBlank() && keepLine(segment, fields) }
            if (kept.isEmpty()) null else kept.joinToString("  |  ")
        }.joinToString("\n")
    }

    fun canvasLines(text: String, fields: ShareSubtitleFields): List<String> {
        return overlayText(text, fields)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun keepLine(line: String, fields: ShareSubtitleFields): Boolean {
        val text = line.trim()
        if (text.isBlank()) return false
        return when (categoryOf(text)) {
            SubtitleCategory.TIME -> fields.showTime
            SubtitleCategory.SPEED -> fields.showSpeed
            SubtitleCategory.STATIONARY_WAIT -> fields.showStationaryWait
            SubtitleCategory.GPS -> fields.showGps
            SubtitleCategory.SATELLITES -> fields.showSatellites
            SubtitleCategory.RADAR -> fields.showRadarSummary
            SubtitleCategory.FOCUS -> fields.showFocus
            SubtitleCategory.VIDEO_MODE -> fields.showVideoMode
            SubtitleCategory.ROAD_LAYER -> fields.showRoadLayer
            SubtitleCategory.ADDRESS -> fields.showAddress
        }
    }

    private fun categoryOf(text: String): SubtitleCategory {
        return when {
            DATE_TIME_REGEX.containsMatchIn(text) -> SubtitleCategory.TIME
            text.startsWith("测速 ") -> SubtitleCategory.RADAR
            text.contains("km/h", ignoreCase = true) -> SubtitleCategory.SPEED
            text.startsWith("静止等待") -> SubtitleCategory.STATIONARY_WAIT
            text.startsWith("GPS卫星") -> SubtitleCategory.SATELLITES
            text == "GPS --" || COORDINATE_REGEX.containsMatchIn(text) -> SubtitleCategory.GPS
            text.startsWith("对焦") -> SubtitleCategory.FOCUS
            text == RoadLayerLabels.ON_BRIDGE ||
                text == RoadLayerLabels.UNDER_BRIDGE ||
                text == RoadLayerLabels.TUNNEL -> SubtitleCategory.ROAD_LAYER
            text.contains("fps", ignoreCase = true) || text.contains("HDR", ignoreCase = true) -> SubtitleCategory.VIDEO_MODE
            else -> SubtitleCategory.ADDRESS
        }
    }

    private enum class SubtitleCategory {
        TIME, SPEED, STATIONARY_WAIT, GPS, SATELLITES, RADAR, ADDRESS, FOCUS, VIDEO_MODE, ROAD_LAYER
    }

    private val DATE_TIME_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")
    private val COORDINATE_REGEX = Regex("\\d{1,3}\\.\\d{3,}°[NSEW]")
}
