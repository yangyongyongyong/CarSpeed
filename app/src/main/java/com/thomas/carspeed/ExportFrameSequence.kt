package com.thomas.carspeed

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.roundToInt

/**
 * 顺着文件往下解码。导出时每一帧都回头找关键帧，两分钟就会多花一分钟以上。
 * 返回的位图由这里复用，画完下一帧之前不要回收。
 */
internal class ExportFrameSequence(
    private val videos: List<File>,
    private val offsetsMs: List<Long>
) : AutoCloseable {
    private var segment = -1
    private var reader: SegmentReader? = null
    private var failed = false

    fun frameAt(elapsedMs: Long): Bitmap? {
        if (failed || videos.isEmpty()) return null
        val index = offsetsMs.indexOfLast { it <= elapsedMs }.coerceIn(0, videos.lastIndex)
        try {
            if (index != segment) {
                reader?.close()
                reader = SegmentReader(videos[index])
                segment = index
            }
            val localUs = (elapsedMs - offsetsMs.getOrElse(index) { 0L }).coerceAtLeast(0L) * 1_000L
            return reader?.frameAt(localUs)
        } catch (_: Exception) {
            failed = true
            reader?.close()
            reader = null
            return null
        }
    }

    override fun close() {
        reader?.close()
        reader = null
    }
}

internal object ExportDecode {
    fun shouldKeep(presentationUs: Long, targetUs: Long, endOfStream: Boolean): Boolean {
        return endOfStream || presentationUs >= targetUs
    }
}

internal class ExportPresentationClock {
    private val pending = ArrayDeque<Long>()
    private var last = -1L

    fun note(timeUs: Long) {
        pending.addLast(timeUs)
    }

    fun take(): Long {
        var time = if (pending.isEmpty()) last else pending.removeFirst()
        if (time <= last) time = last + 1
        last = time
        return time
    }
}

private class SegmentReader(file: File) : AutoCloseable {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val rotationDegrees: Int
    private val bufferInfo = MediaCodec.BufferInfo()
    private val matrix = Matrix()
    private val bounds = RectF()
    private var inputEos = false
    private var outputEos = false
    private var positioned = false
    private var cached: Bitmap? = null
    private var scratch: Bitmap? = null
    private var rotated: Bitmap? = null
    private var cachedPtsUs = Long.MIN_VALUE
    private var imageMisses = 0
    private var pixels: IntArray? = null
    private var yBytes: ByteArray? = null
    private var uBytes: ByteArray? = null
    private var vBytes: ByteArray? = null
    private val yuvPool = ExportYuvPool(
        ExportCoreSplit.workerCount(Runtime.getRuntime().availableProcessors(), 1080)
    )

    init {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalStateException("没有视频轨")
        val format = extractor.getTrackFormat(track)
        rotationDegrees = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.getInteger(MediaFormat.KEY_ROTATION)
        } else {
            0
        }
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("未知视频格式")
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    fun frameAt(targetUs: Long): Bitmap? {
        if (!positioned) {
            extractor.seekTo(targetUs.coerceAtLeast(0L), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            positioned = true
        }
        if (cached != null && targetUs <= cachedPtsUs) return cached
        var spins = 0
        var stalls = 0
        while (!outputEos && cachedPtsUs < targetUs && spins < 100_000 && stalls < 40) {
            spins += 1
            queueAvailableInput()
            when (val index = decoder.dequeueOutputBuffer(bufferInfo, 8_000L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> stalls += 1
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> stalls = 0
                else -> {
                    if (index < 0) continue
                    stalls = 0
                    val pts = bufferInfo.presentationTimeUs
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (ExportDecode.shouldKeep(pts, targetUs, eos) && bufferInfo.size > 0) {
                        val image = decoder.getOutputImage(index)
                        val raw = image?.use { yuvToBitmap(it) }
                        decoder.releaseOutputBuffer(index, false)
                        if (raw != null) {
                            imageMisses = 0
                            val oriented = rotate(raw)
                            val previous = cached
                            cached = oriented
                            cachedPtsUs = pts
                            if (previous != null && previous !== cached && previous !== scratch && previous !== rotated) {
                                previous.recycle()
                            }
                        } else {
                            imageMisses += 1
                            if (imageMisses >= 6) throw IllegalStateException("解码器没有输出画面")
                        }
                    } else {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    if (eos) outputEos = true
                }
            }
        }
        return cached
    }

    private fun queueAvailableInput() {
        while (!inputEos) {
            val index = decoder.dequeueInputBuffer(0L)
            if (index < 0) return
            val buffer = decoder.getInputBuffer(index)
            if (buffer == null) {
                decoder.queueInputBuffer(index, 0, 0, 0L, 0)
                continue
            }
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputEos = true
                return
            }
            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun rotate(bitmap: Bitmap): Bitmap {
        val turns = ((rotationDegrees % 360) + 360) % 360
        if (turns == 0) return bitmap
        matrix.reset()
        matrix.postRotate(turns.toFloat())
        bounds.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        val width = bounds.width().roundToInt().coerceAtLeast(1)
        val height = bounds.height().roundToInt().coerceAtLeast(1)
        val dest = rotated?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { rotated = it }
        Canvas(dest).drawBitmap(bitmap, matrix, null)
        return dest
    }

    private fun yuvToBitmap(image: Image): Bitmap? {
        if (image.planes.size < 3) return null
        val width = image.width
        val height = image.height
        if (width <= 1 || height <= 1) return null
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yPos = yBuffer.position()
        val uPos = uBuffer.position()
        val vPos = vBuffer.position()
        val yStride = yPlane.rowStride
        val uStride = uPlane.rowStride
        val vStride = vPlane.rowStride
        val yPixel = yPlane.pixelStride.coerceAtLeast(1)
        val uPixel = uPlane.pixelStride.coerceAtLeast(1)
        val vPixel = vPlane.pixelStride.coerceAtLeast(1)
        val yCopy = copyPlane(yBuffer, yBytes).also { yBytes = it }
        val uCopy = copyPlane(uBuffer, uBytes).also { uBytes = it }
        val vCopy = copyPlane(vBuffer, vBytes).also { vBytes = it }
        val yLast = yPos + (height - 1) * yStride + (width - 1) * yPixel
        val uLast = uPos + ((height - 1) shr 1) * uStride + ((width - 1) shr 1) * uPixel
        val vLast = vPos + ((height - 1) shr 1) * vStride + ((width - 1) shr 1) * vPixel
        if (yLast < 0 || uLast < 0 || vLast < 0 || yLast >= yCopy.size || uLast >= uCopy.size || vLast >= vCopy.size) {
            return null
        }
        val argb = pixels?.takeIf { it.size == width * height } ?: IntArray(width * height).also { pixels = it }
        yuvPool.convert(
            width = width,
            height = height,
            y = yCopy,
            u = uCopy,
            v = vCopy,
            yPos = yPos,
            uPos = uPos,
            vPos = vPos,
            yStride = yStride,
            uStride = uStride,
            vStride = vStride,
            yPixel = yPixel,
            uPixel = uPixel,
            vPixel = vPixel,
            argb = argb
        )
        val existing = scratch
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            existing.setPixels(argb, 0, width, 0, 0, width, height)
            return existing
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).also { scratch = it }
    }

    private fun copyPlane(buffer: java.nio.ByteBuffer, reuse: ByteArray?): ByteArray {
        val dup = buffer.duplicate()
        val limit = dup.limit().coerceAtLeast(0)
        val out = if (reuse != null && reuse.size == limit) reuse else ByteArray(limit)
        if (limit == 0) return out
        dup.position(0)
        dup.get(out, 0, limit)
        return out
    }

    override fun close() {
        listOfNotNull(cached, scratch, rotated).distinct().forEach { bitmap ->
            runCatching {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        cached = null
        scratch = null
        rotated = null
        yuvPool.close()
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }
}
