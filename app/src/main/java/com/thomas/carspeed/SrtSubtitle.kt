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
        val active = cues.firstOrNull { elapsedMs in it.startMs until it.endMs }
        if (active != null || holdGapMs <= 0L) return active
        return cues.asReversed().firstOrNull { cue ->
            elapsedMs >= cue.endMs && elapsedMs - cue.endMs <= holdGapMs
        }
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
