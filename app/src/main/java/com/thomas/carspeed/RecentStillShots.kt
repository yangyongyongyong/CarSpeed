package com.thomas.carspeed

/**
 * 录像过程中间隔留几张 JPEG，语音拍照按“开口时间之后最近的一张”来取。
 * 不在口令确认时再解正在写的视频，避免解码卡住以后照片落不下来。
 */
internal class RecentStillShots(
    private val keepMs: Long = KEEP_MS
) {
    private val shots = ArrayDeque<Shot>()

    @Synchronized
    fun add(jpeg: ByteArray, elapsedMs: Long) {
        if (jpeg.isEmpty()) return
        shots.addLast(Shot(jpeg, elapsedMs))
        trim(elapsedMs)
    }

    @Synchronized
    fun atOrAfter(targetElapsedMs: Long): ByteArray? {
        val index = indexAtOrAfter(shots.map { it.elapsedMs }, targetElapsedMs)
        if (index < 0) return null
        return shots[index].jpeg.copyOf()
    }

    @Synchronized
    fun clear() {
        shots.clear()
    }

    private fun trim(newestElapsedMs: Long) {
        val horizon = newestElapsedMs - keepMs
        while (shots.size > 1 && shots[1].elapsedMs <= horizon) shots.removeFirst()
        while (shots.size > MAX_SHOTS) shots.removeFirst()
    }

    companion object {
        const val KEEP_MS = 6_000L
        private const val MAX_SHOTS = 16

        fun indexAtOrAfter(elapsedMs: List<Long>, targetElapsedMs: Long): Int {
            return elapsedMs.indexOfFirst { it >= targetElapsedMs }
        }
    }
}

private data class Shot(val jpeg: ByteArray, val elapsedMs: Long)
