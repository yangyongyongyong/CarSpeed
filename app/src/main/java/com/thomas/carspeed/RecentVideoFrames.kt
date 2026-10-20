package com.thomas.carspeed

/**
 * 正在写入的 mp4 要等这一段结束才有 moov，不能按时间回读。
 * 最近几秒的编码帧留在内存里，语音抓拍按“目标时间之后最近的一帧”来取。
 */
internal class RecentVideoFrames(
    private val keepMs: Long = KEEP_MS
) {
    private val samples = ArrayDeque<VideoFrameSample>()
    private var codecConfig: ByteArray? = null
    private var codecConfig1: ByteArray? = null
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    @Synchronized
    fun setFormat(csd0: ByteArray, csd1: ByteArray?, width: Int, height: Int) {
        codecConfig = csd0
        codecConfig1 = csd1
        this.width = width
        this.height = height
    }

    @Synchronized
    fun clear() {
        samples.clear()
        codecConfig = null
        codecConfig1 = null
        width = 0
        height = 0
    }

    @Synchronized
    fun add(bytes: ByteArray, elapsedMs: Long, keyframe: Boolean) {
        samples.addLast(VideoFrameSample(bytes, elapsedMs, keyframe))
        trim(elapsedMs)
    }

    @Synchronized
    fun selectionAtOrAfter(targetElapsedMs: Long): VideoFrameSelection? {
        val config = codecConfig ?: return null
        if (samples.isEmpty() || width <= 0 || height <= 0) return null
        val elapsed = LongArray(samples.size) { samples[it].elapsedMs }
        val keyframes = BooleanArray(samples.size) { samples[it].keyframe }
        val window = VideoFramePick.window(elapsed, keyframes, targetElapsedMs) ?: return null
        val chosen = ArrayList<VideoFrameSample>(window.endIndex - window.startIndex + 1)
        for (index in window.startIndex..window.endIndex) {
            chosen.add(samples[index])
        }
        return VideoFrameSelection(
            codecConfig = config.copyOf(),
            codecConfig1 = codecConfig1?.copyOf(),
            width = width,
            height = height,
            samples = chosen,
            targetElapsedMs = targetElapsedMs
        )
    }

    private fun trim(newestElapsedMs: Long) {
        val horizon = newestElapsedMs - keepMs
        var keepFrom = 0
        for (index in samples.indices) {
            val sample = samples[index]
            if (sample.keyframe && sample.elapsedMs <= horizon) keepFrom = index
        }
        repeat(keepFrom) { samples.removeFirst() }
        while (samples.isNotEmpty() && !samples.first().keyframe) samples.removeFirst()
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
            while (samples.isNotEmpty() && !samples.first().keyframe) samples.removeFirst()
        }
    }

    companion object {
        const val KEEP_MS = 6_000L
        private const val MAX_SAMPLES = 240
    }
}

internal data class VideoFrameSample(
    val bytes: ByteArray,
    val elapsedMs: Long,
    val keyframe: Boolean
)

internal data class VideoFrameSelection(
    val codecConfig: ByteArray,
    val codecConfig1: ByteArray?,
    val width: Int,
    val height: Int,
    val samples: List<VideoFrameSample>,
    val targetElapsedMs: Long
)

internal data class FrameDecodeWindow(
    val startIndex: Int,
    val matchIndex: Int,
    val endIndex: Int
)

internal object VideoFramePick {
    /** 从目标时间之前最近的关键帧解到目标帧，并多留几帧让解码器把目标帧吐出来。 */
    fun window(
        elapsedMs: LongArray,
        keyframes: BooleanArray,
        targetElapsedMs: Long,
        extraFrames: Int = EXTRA_FRAMES
    ): FrameDecodeWindow? {
        if (elapsedMs.isEmpty() || elapsedMs.size != keyframes.size) return null
        val match = elapsedMs.indexOfFirst { it >= targetElapsedMs }
        if (match < 0) return null
        var start = match
        while (start > 0 && !keyframes[start]) start--
        if (!keyframes[start]) return null
        val end = minOf(elapsedMs.lastIndex, match + extraFrames.coerceAtLeast(0))
        return FrameDecodeWindow(start, match, end)
    }

    private const val EXTRA_FRAMES = 2
}
