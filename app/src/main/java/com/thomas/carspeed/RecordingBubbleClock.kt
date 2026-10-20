package com.thomas.carspeed

import java.util.Locale

internal object RecordingBubbleClock {
    fun format(elapsedMs: Long): String {
        val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
