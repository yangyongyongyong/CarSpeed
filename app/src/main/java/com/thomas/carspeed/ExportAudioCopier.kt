package com.thomas.carspeed

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 原速导出时把录像里的声音原样搬进成片，不重新编码。
 * 时间轴和画面一致：成片时间 = 源时间 - 导出起点。
 */
internal class ExportAudioCopier private constructor(
    private val videos: List<File>,
    private val offsetsMs: List<Long>,
    private val durationsMs: List<Long>,
    private val sourceStartUs: Long,
    private val sourceEndUs: Long,
    private val format: MediaFormat
) : AutoCloseable {
    private var trackIndex = -1
    private var segment = -1
    private var extractor: MediaExtractor? = null
    private var finished = false
    private var lastOutputUs = -1L
    private var pendingValid = false
    private val buffer = ByteBuffer.allocateDirect(maxInputSize(format))
    private val info = MediaCodec.BufferInfo()

    fun addTrack(muxer: MediaMuxer) {
        trackIndex = runCatching { muxer.addTrack(format) }.getOrDefault(-1)
        if (trackIndex < 0) finished = true
    }

    /** 写到成片时间 [outputUs] 为止，画面每推一帧就跟一次，让音画在文件里交错存放。 */
    fun writeUntil(muxer: MediaMuxer, outputUs: Long) {
        if (finished || trackIndex < 0) return
        try {
            while (!finished) {
                if (!pendingValid && !readNext()) {
                    finished = true
                    return
                }
                if (info.presentationTimeUs > outputUs) return
                pendingValid = false
                if (info.presentationTimeUs <= lastOutputUs) continue
                buffer.position(0)
                buffer.limit(info.size)
                muxer.writeSampleData(trackIndex, buffer, info)
                lastOutputUs = info.presentationTimeUs
            }
        } catch (_: Exception) {
            // 声音读坏了就到此为止，画面照常导出。
            finished = true
        }
    }

    /** 读出下一条落在导出区间里的音频样本，放进 [info]/[buffer]。 */
    private fun readNext(): Boolean {
        while (true) {
            val active = extractor ?: openNextSegment() ?: return false
            val sampleUs = active.sampleTime
            if (sampleUs < 0L) {
                closeExtractor()
                continue
            }
            val offsetUs = offsetsMs.getOrElse(segment) { 0L } * 1_000L
            val segmentEndUs = offsetUs + durationsMs.getOrElse(segment) { 0L } * 1_000L
            val globalUs = offsetUs + sampleUs
            if (globalUs >= sourceEndUs) {
                finished = true
                closeExtractor()
                return false
            }
            if (globalUs >= segmentEndUs) {
                // 声音比画面长出来的尾巴交给下一段，避免两段在接缝处重叠。
                closeExtractor()
                continue
            }
            if (globalUs < sourceStartUs) {
                active.advance()
                continue
            }
            buffer.clear()
            val size = active.readSampleData(buffer, 0)
            val sampleFlags = active.sampleFlags
            active.advance()
            if (size <= 0) continue
            val flags = if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, globalUs - sourceStartUs, flags)
            pendingValid = true
            return true
        }
    }

    private fun openNextSegment(): MediaExtractor? {
        while (segment + 1 < videos.size) {
            segment += 1
            val offsetUs = offsetsMs.getOrElse(segment) { 0L } * 1_000L
            val endUs = offsetUs + durationsMs.getOrElse(segment) { 0L } * 1_000L
            if (endUs <= sourceStartUs) continue
            if (offsetUs >= sourceEndUs) return null
            val opened = openAudio(videos[segment]) ?: continue
            if (!compatible(opened.second)) {
                runCatching { opened.first.release() }
                continue
            }
            val localStartUs = (sourceStartUs - offsetUs).coerceAtLeast(0L)
            if (localStartUs > 0L) opened.first.seekTo(localStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            extractor = opened.first
            return opened.first
        }
        return null
    }

    private fun compatible(other: MediaFormat): Boolean {
        return other.getString(MediaFormat.KEY_MIME) == format.getString(MediaFormat.KEY_MIME) &&
            other.intOr(MediaFormat.KEY_SAMPLE_RATE) == format.intOr(MediaFormat.KEY_SAMPLE_RATE) &&
            other.intOr(MediaFormat.KEY_CHANNEL_COUNT) == format.intOr(MediaFormat.KEY_CHANNEL_COUNT) &&
            maxInputSize(other) <= buffer.capacity()
    }

    private fun closeExtractor() {
        extractor?.let { runCatching { it.release() } }
        extractor = null
    }

    override fun close() {
        finished = true
        closeExtractor()
    }

    companion object {
        /** 导出区间里有能用的声音才返回；没有声音轨时整段成片保持无声。 */
        fun open(
            videos: List<File>,
            offsetsMs: List<Long>,
            durationsMs: List<Long>,
            sourceStartMs: Long,
            sourceEndMs: Long
        ): ExportAudioCopier? {
            videos.forEachIndexed { index, video ->
                val offsetMs = offsetsMs.getOrElse(index) { 0L }
                val endMs = offsetMs + durationsMs.getOrElse(index) { 0L }
                if (endMs <= sourceStartMs || offsetMs >= sourceEndMs) return@forEachIndexed
                val opened = openAudio(video) ?: return@forEachIndexed
                runCatching { opened.first.release() }
                val copier = ExportAudioCopier(
                    videos = videos,
                    offsetsMs = offsetsMs,
                    durationsMs = durationsMs,
                    sourceStartUs = sourceStartMs * 1_000L,
                    sourceEndUs = sourceEndMs * 1_000L,
                    format = opened.second
                )
                // 先读出第一个样本再加音轨：空音轨会让部分机型 muxer.stop() 失败，整条导出跟着失败。
                val primed = runCatching { copier.readNext() }.getOrDefault(false)
                if (!primed) {
                    copier.close()
                    return null
                }
                return copier
            }
            return null
        }

        private fun openAudio(video: File): Pair<MediaExtractor, MediaFormat>? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(video.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (track == null) {
                    extractor.release()
                    null
                } else {
                    extractor.selectTrack(track)
                    extractor to extractor.getTrackFormat(track)
                }
            } catch (_: Exception) {
                runCatching { extractor.release() }
                null
            }
        }

        private fun maxInputSize(format: MediaFormat): Int {
            val declared = format.intOr(MediaFormat.KEY_MAX_INPUT_SIZE)
            return if (declared > 0) declared.coerceAtLeast(MIN_BUFFER_BYTES) else DEFAULT_BUFFER_BYTES
        }

        private fun MediaFormat.intOr(key: String): Int {
            return if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(-1) else -1
        }

        private const val MIN_BUFFER_BYTES = 16 * 1024
        private const val DEFAULT_BUFFER_BYTES = 256 * 1024
    }
}
