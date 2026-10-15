package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class ShareExportOptions(
    val includeRouteMap: Boolean = true,
    val mapHeightPercent: Int = 28,
    val mapScalePercent: Int = 100,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = Long.MAX_VALUE,
    val playbackSpeed: Float = 1f,
    val subtitleFields: ShareSubtitleFields = ShareSubtitleFields(),
    val fps: Int = 15
)

data class ShareSubtitleFields(
    val showTime: Boolean = true,
    val showSpeed: Boolean = true,
    val showStationaryWait: Boolean = true,
    val showGps: Boolean = true,
    val showSatellites: Boolean = true,
    val showAddress: Boolean = true,
    val showFocus: Boolean = true,
    val showVideoMode: Boolean = true,
    val showRoadLayer: Boolean = true
)

object ShareVideoExporter {
    private const val MIME_AVC = "video/avc"
    private const val I_FRAME_INTERVAL_SECONDS = 2
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L

    fun totalDurationMs(videos: List<File>): Long {
        return videos.sumOf { durationMs(it).coerceAtLeast(0L) }
    }

    fun createPreview(context: Context, videos: List<File>, options: ShareExportOptions): Bitmap? {
        val timeline = buildTimeline(context, videos)
        val retrievers = videos.map { video -> MediaMetadataRetriever().apply { setDataSource(video.absolutePath) } }
        val previewElapsedMs = options.validStartMs(timeline.totalDurationMs)
            .let { start -> (start + 1_000L).coerceAtMost(options.validEndMs(timeline.totalDurationMs) - 1).coerceAtLeast(start) }
        val firstFrame = try {
            frameAt(timeline, retrievers, previewElapsedMs)
        } finally {
            retrievers.forEach { it.releaseSafely() }
        } ?: return null
        val outputSize = outputSize(firstFrame.width, firstFrame.height, options, timeline.track)
        val bitmap = Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        ShareFramePainter.drawFrame(
            canvas = canvas,
            videoFrame = firstFrame,
            videoWidth = outputSize.videoWidth,
            videoHeight = outputSize.videoHeight,
            mapHeight = outputSize.mapHeight,
            elapsedMs = previewElapsedMs,
            subtitle = subtitleAt(timeline, previewElapsedMs, options.subtitleFields),
            track = timeline.track,
            options = options
        )
        firstFrame.recycle()
        return bitmap
    }

    fun export(
        context: Context,
        videos: List<File>,
        options: ShareExportOptions,
        onProgress: (Int) -> Unit
    ): File {
        require(videos.isNotEmpty()) { "没有可导出的视频" }
        val timeline = buildTimeline(context, videos)
        val sourceStartMs = options.validStartMs(timeline.totalDurationMs)
        val sourceEndMs = options.validEndMs(timeline.totalDurationMs).coerceAtLeast(sourceStartMs + 1_000L)
        val sourceDurationMs = (sourceEndMs - sourceStartMs).coerceAtLeast(1L)
        val playbackSpeed = options.playbackSpeed.coerceIn(0.25f, 16f)
        val outputDurationMs = ceil(sourceDurationMs / playbackSpeed.toDouble()).toLong().coerceAtLeast(1L)
        val retrievers = videos.map { video ->
            MediaMetadataRetriever().apply { setDataSource(video.absolutePath) }
        }
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val output = VideoStorage.shareExportFile(context, videos.first().parentFile?.name ?: videos.first().name)
        try {
            val firstFrame = retrievers.first().getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retrievers.first().frameAtTime
                ?: throw IllegalStateException("无法读取视频帧")
            val outputSize = outputSize(firstFrame.width, firstFrame.height, options, timeline.track)
            firstFrame.recycle()
            val codecSpec = selectAvcEncoder()
            val format = MediaFormat.createVideoFormat(MIME_AVC, outputSize.width, outputSize.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, codecSpec.colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate(outputSize.width, outputSize.height, options.fps))
                setInteger(MediaFormat.KEY_FRAME_RATE, options.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            encoder = MediaCodec.createByCodecName(codecSpec.name).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            val state = MuxerState(muxer)
            val canvasBitmap = Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val yuv = ByteArray(outputSize.width * outputSize.height * 3 / 2)
            val argb = IntArray(outputSize.width * outputSize.height)
            val totalFrames = max(1, ceil(outputDurationMs * options.fps / 1000.0).toInt())
            val frameDurationUs = 1_000_000L / options.fps.coerceAtLeast(1)
            for (frameIndex in 0 until totalFrames) {
                drainEncoder(encoder, bufferInfo, state, endOfStream = false)
                val outputElapsedMs = frameIndex * 1000L / options.fps.coerceAtLeast(1)
                val sourceElapsedMs = min(sourceEndMs - 1, sourceStartMs + (outputElapsedMs * playbackSpeed).toLong()).coerceAtLeast(0L)
                val frame = frameAt(timeline, retrievers, sourceElapsedMs)
                    ?: continue
                ShareFramePainter.drawFrame(
                    canvas = canvas,
                    videoFrame = frame,
                    videoWidth = outputSize.videoWidth,
                    videoHeight = outputSize.videoHeight,
                    mapHeight = outputSize.mapHeight,
                    elapsedMs = sourceElapsedMs,
                    subtitle = subtitleAt(timeline, sourceElapsedMs, options.subtitleFields),
                    track = timeline.track,
                    options = options
                )
                frame.recycle()
                queueBitmapFrame(encoder, canvasBitmap, argb, yuv, codecSpec.planar, frameIndex * frameDurationUs)
                if (frameIndex % options.fps == 0 || frameIndex == totalFrames - 1) {
                    onProgress(((frameIndex + 1) * 100 / totalFrames).coerceIn(0, 100))
                }
            }
            queueEndOfStream(encoder, totalFrames * frameDurationUs)
            drainEncoder(encoder, bufferInfo, state, endOfStream = true)
            canvasBitmap.recycle()
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            retrievers.forEach { retriever -> retriever.releaseSafely() }
            encoder?.stopSafely()
            encoder?.releaseSafely()
            muxer?.stopSafely()
            muxer?.releaseSafely()
        }
    }

    private fun buildTimeline(context: Context, videos: List<File>): ExportTimeline {
        val durations = videos.map { video -> durationMs(video).coerceAtLeast(1L) }
        val offsets = durations.runningFold(0L) { total, duration -> total + duration }.dropLast(1)
        return ExportTimeline(
            durationsMs = durations,
            offsetsMs = offsets,
            totalDurationMs = durations.sum().coerceAtLeast(1L),
            subtitleCues = SrtSubtitle.readCues(videos, offsets),
            track = VideoTrackStorage.readMergedTrack(context, videos)
        )
    }

    private fun frameAt(timeline: ExportTimeline, retrievers: List<MediaMetadataRetriever>, elapsedMs: Long): Bitmap? {
        val segment = timeline.segmentAt(elapsedMs)
        val localMs = (elapsedMs - timeline.offsetsMs.getOrElse(segment) { 0L }).coerceAtLeast(0L)
        return retrievers[segment].getFrameAtTime(localMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retrievers[segment].frameAtTime
    }

    private fun subtitleAt(timeline: ExportTimeline, elapsedMs: Long, fields: ShareSubtitleFields): String? {
        val cue = SrtSubtitle.cueAt(timeline.subtitleCues, elapsedMs) ?: return null
        val filtered = ShareSubtitleFormatter.filter(cue.text, fields)
        return filtered.takeIf { it.isNotBlank() }
    }

    private fun durationMs(video: File): Long {
        return MediaMetadataRetriever().useSafely { retriever ->
            retriever.setDataSource(video.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } ?: 0L
    }

    private fun outputSize(sourceWidth: Int, sourceHeight: Int, options: ShareExportOptions, track: VideoTrack?): OutputSize {
        val videoWidth = even(sourceWidth.coerceAtLeast(2))
        val videoHeight = even(sourceHeight.coerceAtLeast(2))
        val mapHeight = if (options.includeRouteMap && track?.points?.isNotEmpty() == true) {
            even((videoHeight * options.mapHeightPercent.coerceIn(18, 45) / 100f).toInt()).coerceAtLeast(120)
        } else {
            0
        }
        return OutputSize(videoWidth, videoHeight + mapHeight, videoWidth, videoHeight, mapHeight)
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
                planar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                    colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
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
        presentationTimeUs: Long
    ) {
        val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) return
        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
        bitmapToYuv420(bitmap, argb, yuv, planar)
        inputBuffer.clear()
        inputBuffer.put(yuv)
        encoder.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
    }

    private fun queueEndOfStream(encoder: MediaCodec, presentationTimeUs: Long) {
        while (true) {
            val inputIndex = encoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        bufferInfo: MediaCodec.BufferInfo,
        state: MuxerState,
        endOfStream: Boolean
    ) {
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (state.started) throw IllegalStateException("编码格式重复变化")
                    state.trackIndex = state.muxer.addTrack(encoder.outputFormat)
                    state.muxer.start()
                    state.started = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        if (!state.started) throw IllegalStateException("Muxer 尚未启动")
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

    private data class CodecSpec(val name: String, val colorFormat: Int, val planar: Boolean)
    private data class OutputSize(val width: Int, val height: Int, val videoWidth: Int, val videoHeight: Int, val mapHeight: Int)
    private data class MuxerState(val muxer: MediaMuxer, var started: Boolean = false, var trackIndex: Int = -1)

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

object ShareFramePainter {
    private val videoDst = RectF()
    private val subtitleBg = RectF()
    private val mapRect = RectF()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
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

    fun drawFrame(
        canvas: Canvas,
        videoFrame: Bitmap,
        videoWidth: Int,
        videoHeight: Int,
        mapHeight: Int,
        elapsedMs: Long,
        subtitle: String?,
        track: VideoTrack?,
        options: ShareExportOptions
    ) {
        canvas.drawColor(Color.BLACK)
        drawFitCenter(canvas, videoFrame, RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat()))
        if (!subtitle.isNullOrBlank()) drawSubtitle(canvas, subtitle, videoWidth, videoHeight)
        if (mapHeight > 0 && options.includeRouteMap) {
            mapRect.set(0f, videoHeight.toFloat(), videoWidth.toFloat(), (videoHeight + mapHeight).toFloat())
            drawRouteMap(canvas, mapRect, track, elapsedMs, options.mapScalePercent)
        }
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

    private fun drawSubtitle(canvas: Canvas, subtitle: String, videoWidth: Int, videoHeight: Int) {
        val padding = videoWidth * 0.022f
        textPaint.textSize = (videoWidth * 0.036f).coerceIn(22f, 38f)
        val lines = subtitle.lines().filter { it.isNotBlank() }.takeLast(4)
        if (lines.isEmpty()) return
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent } * 1.15f
        val textWidth = lines.maxOf { textPaint.measureText(it) }.coerceAtMost(videoWidth - padding * 4)
        val boxHeight = lineHeight * lines.size + padding * 2
        subtitleBg.set(
            padding,
            videoHeight - boxHeight - padding,
            padding * 3 + textWidth,
            videoHeight - padding
        )
        canvas.drawRoundRect(subtitleBg, padding, padding, subtitleBgPaint)
        var baseline = subtitleBg.top + padding - textPaint.fontMetrics.ascent
        lines.forEach { line ->
            canvas.drawText(line.take(42), subtitleBg.left + padding, baseline, textPaint)
            baseline += lineHeight
        }
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
        val label = String.format(Locale.CHINA, "%.1f km/h  %s  比例 %d%%", current.speedKmh, TrackMetrics.formatDistance(distance), scalePercent)
        canvas.drawText(label, rect.left + padding * 0.7f, rect.bottom - padding * 0.45f, textPaint)
    }
}

object ShareSubtitleFormatter {
    fun filter(text: String, fields: ShareSubtitleFields): String {
        return text.lines()
            .flatMap { line -> line.split('|') }
            .map { it.trim() }
            .filter { segment -> segment.isNotBlank() && keepLine(segment, fields) }
            .joinToString("  |  ")
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
            SubtitleCategory.FOCUS -> fields.showFocus
            SubtitleCategory.VIDEO_MODE -> fields.showVideoMode
            SubtitleCategory.ROAD_LAYER -> fields.showRoadLayer
            SubtitleCategory.ADDRESS -> fields.showAddress
        }
    }

    private fun categoryOf(text: String): SubtitleCategory {
        return when {
            DATE_TIME_REGEX.containsMatchIn(text) -> SubtitleCategory.TIME
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
        TIME, SPEED, STATIONARY_WAIT, GPS, SATELLITES, ADDRESS, FOCUS, VIDEO_MODE, ROAD_LAYER
    }

    private val DATE_TIME_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")
    private val COORDINATE_REGEX = Regex("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}")
}
