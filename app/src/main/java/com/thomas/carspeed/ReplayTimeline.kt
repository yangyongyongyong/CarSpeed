package com.thomas.carspeed

import java.util.Locale

internal object ReplayTimeline {
    const val SEEK_MAX = 10_000

    fun progress(positionMs: Long, durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        return (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs * SEEK_MAX).toInt()
    }

    fun position(progress: Int, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (progress.coerceIn(0, SEEK_MAX).toDouble() / SEEK_MAX * durationMs).toLong()
    }

    fun formatDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        return if (seconds >= 3_600L) {
            String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
        } else {
            String.format(Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L)
        }
    }

    fun audioOffsetUs(videoStartUs: Long, videoDurationUs: Long, audioStartUs: Long): Long {
        if (videoDurationUs <= 0L || videoStartUs < 0L || audioStartUs < 0L) return 0L
        val offset = audioStartUs - videoStartUs
        return if (offset > videoDurationUs + 5_000_000L) offset else 0L
    }
}
