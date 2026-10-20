package com.thomas.carspeed

/**
 * 把识别器里的词时间（从这路音频开头算起的毫秒）对到 [android.os.SystemClock.elapsedRealtime]。
 * 录像外接的 PCM 用采集时刻锚定；识别器把时间轴清零时，改用这条结果到达的时刻重新对齐。
 */
internal class VoiceStreamClock {
    private var originElapsedMs: Long? = null
    private var highestStreamMs = Long.MIN_VALUE

    @Synchronized
    fun reset() {
        originElapsedMs = null
        highestStreamMs = Long.MIN_VALUE
    }

    /** 这段音频的最后一个采样是在 [chunkEndElapsedMs] 采集的。只在还没有原点时锚定。 */
    @Synchronized
    fun noteChunk(durationMs: Long, chunkEndElapsedMs: Long) {
        if (durationMs <= 0L || originElapsedMs != null) return
        originElapsedMs = chunkEndElapsedMs - durationMs
    }

    /**
     * 词的结束时间应落在“现在”附近。差太远说明原点跟当前识别器不是同一条时间轴，按这次结果重对齐。
     */
    @Synchronized
    fun alignIfNeeded(maxEndMs: Long, nowElapsedMs: Long) {
        val restarted = highestStreamMs != Long.MIN_VALUE && maxEndMs + RESTART_GAP_MS < highestStreamMs
        if (restarted || maxEndMs > highestStreamMs) highestStreamMs = maxEndMs
        val origin = originElapsedMs
        val plausible = origin != null && (origin + maxEndMs) in (nowElapsedMs - LATE_SLACK_MS)..(nowElapsedMs + EARLY_SLACK_MS)
        if (restarted || !plausible) {
            originElapsedMs = nowElapsedMs - maxEndMs
        }
    }

    @Synchronized
    fun elapsedFor(streamMs: Long): Long? = originElapsedMs?.plus(streamMs)

    companion object {
        private const val RESTART_GAP_MS = 500L
        private const val LATE_SLACK_MS = 8_000L
        private const val EARLY_SLACK_MS = 1_000L
    }
}
