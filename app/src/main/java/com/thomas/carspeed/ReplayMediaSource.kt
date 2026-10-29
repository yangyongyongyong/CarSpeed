package com.thomas.carspeed

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal object ReplayMediaSource {
    data class Source(val file: File, val durationMs: Long)

    @Synchronized
    fun prepare(context: Context, original: File): Source {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(original.absolutePath)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            if (video < 0) return Source(original, 0L)
            val format = formats[video]
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(video)
            val videoStartUs = extractor.sampleTime
            extractor.unselectTrack(video)
            val audio = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (audio < 0) return Source(original, durationUs / 1_000L)
            extractor.selectTrack(audio)
            val offsetUs = ReplayTimeline.audioOffsetUs(videoStartUs, durationUs, extractor.sampleTime)
            extractor.unselectTrack(audio)
            if (offsetUs == 0L) return Source(original, durationUs / 1_000L)

            // 只生成校正时间轴的回放副本，原录像及其字幕、轨迹文件保持不变。
            val dir = File(context.cacheDir, "replay-timeline").apply { mkdirs() }
            val signature = "${original.absolutePath}:${original.length()}:${original.lastModified()}"
            val key = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val output = File(dir, "$key.mp4")
            if (output.isFile) return Source(output, durationUs / 1_000L)
            val partial = File(dir, "$key.part")
            try {
                remux(original, extractor, formats, video, audio, videoStartUs, durationUs, offsetUs, partial)
                check(partial.renameTo(output)) { "无法保存校正后的回放文件" }
            } finally {
                partial.delete()
            }
            return Source(output, durationUs / 1_000L)
        } finally {
            extractor.release()
        }
    }

    private fun remux(
        original: File,
        extractor: MediaExtractor,
        formats: List<MediaFormat>,
        video: Int,
        audio: Int,
        videoStartUs: Long,
        durationUs: Long,
        audioOffsetUs: Long,
        output: File
    ) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(original.absolutePath)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                muxer.setOrientationHint(rotation)
            } finally {
                retriever.release()
            }
            val tracks = listOf(video, audio).associateWith { muxer.addTrack(formats[it]) }
            muxer.start()
            val info = MediaCodec.BufferInfo()
            val declaredBufferSize = formats.maxOf { format ->
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }
            var buffer = ByteBuffer.allocateDirect(maxOf(4 * 1024 * 1024, declaredBufferSize))
            for (track in listOf(video, audio)) {
                extractor.selectTrack(track)
                extractor.seekTo(if (track == audio) videoStartUs + audioOffsetUs else videoStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                while (extractor.sampleTime >= 0L) {
                    if (Thread.currentThread().isInterrupted) throw CancellationException()
                    val pts = extractor.sampleTime - videoStartUs - if (track == audio) audioOffsetUs else 0L
                    if (pts >= durationUs) break
                    if (pts >= 0L) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val size = extractor.sampleSize
                            check(size <= Int.MAX_VALUE) { "媒体样本过大" }
                            if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                        }
                        buffer.clear()
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) break
                        val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, read, pts, flags)
                        buffer.position(0)
                        buffer.limit(read)
                        muxer.writeSampleData(tracks.getValue(track), buffer, info)
                    }
                    extractor.advance()
                }
                extractor.unselectTrack(track)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }
}
