package com.thomas.carspeed

import java.io.File

data class SrtCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object SrtSubtitle {
    fun readCues(videos: List<File>, offsetsMs: List<Long>, durationsMs: List<Long> = emptyList()): List<SrtCue> {
        return videos.flatMapIndexed { index, video ->
            val subtitle = VideoStorage.subtitleFileFor(video)
            if (!subtitle.exists()) return@flatMapIndexed emptyList()
            val offsetMs = offsetsMs.getOrElse(index) { 0L }
            val durationMs = durationsMs.getOrElse(index) { 0L }
            try {
                subtitle.readText(Charsets.UTF_8)
                    .replace("\r\n", "\n")
                    .split(Regex("\n{2,}"))
                    .mapNotNull { block -> parseBlock(block, offsetMs, durationMs) }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun cueAt(cues: List<SrtCue>, elapsedMs: Long, holdGapMs: Long = 0L): SrtCue? {
        if (cues.isEmpty()) return null
        val index = indexFor(cues)
        if (!index.sortedByStart) return linearCueAt(cues, elapsedMs, holdGapMs)
        val last = lastStartAtOrBefore(cues, elapsedMs)
        if (last < 0) return null
        // 按开始时间排好序时，能覆盖当前时刻的字幕只可能落在 [t - 最长时长, t] 这一段里。
        val activeFrom = firstStartAtOrAfter(cues, elapsedMs - index.maxDurationMs)
        for (i in activeFrom..last) {
            val cue = cues[i]
            if (elapsedMs < cue.endMs) return cue
        }
        if (holdGapMs <= 0L) return null
        val holdFrom = firstStartAtOrAfter(cues, elapsedMs - holdGapMs - index.maxDurationMs)
        for (i in last downTo holdFrom) {
            val cue = cues[i]
            if (elapsedMs >= cue.endMs && elapsedMs - cue.endMs <= holdGapMs) return cue
        }
        return null
    }

    internal fun linearCueAt(cues: List<SrtCue>, elapsedMs: Long, holdGapMs: Long): SrtCue? {
        val active = cues.firstOrNull { elapsedMs in it.startMs until it.endMs }
        if (active != null || holdGapMs <= 0L) return active
        return cues.asReversed().firstOrNull { cue ->
            elapsedMs >= cue.endMs && elapsedMs - cue.endMs <= holdGapMs
        }
    }

    private class CueIndex(val cues: List<SrtCue>, val sortedByStart: Boolean, val maxDurationMs: Long)

    private val recentIndexes = ArrayDeque<CueIndex>()

    private fun indexFor(cues: List<SrtCue>): CueIndex {
        synchronized(recentIndexes) {
            recentIndexes.firstOrNull { it.cues === cues }?.let { return it }
        }
        var sorted = true
        var maxDuration = 0L
        for (i in cues.indices) {
            val cue = cues[i]
            if (i > 0 && cue.startMs < cues[i - 1].startMs) sorted = false
            maxDuration = maxOf(maxDuration, cue.endMs - cue.startMs)
        }
        val built = CueIndex(cues, sorted, maxDuration)
        synchronized(recentIndexes) {
            recentIndexes.addFirst(built)
            while (recentIndexes.size > 4) recentIndexes.removeLast()
        }
        return built
    }

    private fun lastStartAtOrBefore(cues: List<SrtCue>, timeMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= timeMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun firstStartAtOrAfter(cues: List<SrtCue>, timeMs: Long): Int {
        var low = 0
        var high = cues.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs < timeMs) low = mid + 1 else high = mid
        }
        return low
    }

    private fun parseBlock(block: String, offsetMs: Long, durationMs: Long): SrtCue? {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.size < 3) return null
        val timeLine = lines.firstOrNull { it.contains("-->") } ?: return null
        val match = SRT_TIME_REGEX.find(timeLine) ?: return null
        val localStartMs = parseTime(match.groupValues, 1)
        val localEndMs = parseTime(match.groupValues, 5)
        if (durationMs > 0L && localStartMs > durationMs + SEGMENT_SIDE_CAR_TOLERANCE_MS) return null
        val startMs = offsetMs + localStartMs
        val endMs = offsetMs + if (durationMs > 0L) {
            minOf(localEndMs, durationMs + SEGMENT_SIDE_CAR_TOLERANCE_MS)
        } else {
            localEndMs
        }
        if (endMs <= startMs) return null
        val text = lines.dropWhile { !it.contains("-->") }
            .drop(1)
            .joinToString("\n")
            .replace("  |  ", "\n")
            .trim()
        if (text.isBlank()) return null
        return SrtCue(startMs, endMs, text)
    }

    private fun parseTime(values: List<String>, offset: Int): Long {
        val hours = values[offset].toLongOrNull() ?: 0L
        val minutes = values[offset + 1].toLongOrNull() ?: 0L
        val seconds = values[offset + 2].toLongOrNull() ?: 0L
        val millis = values[offset + 3].toLongOrNull() ?: 0L
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private val SRT_TIME_REGEX = Regex(
        "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s+-->\\s+(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})"
    )
    private const val SEGMENT_SIDE_CAR_TOLERANCE_MS = 500L
}
