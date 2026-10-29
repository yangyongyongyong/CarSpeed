package com.thomas.carspeed

internal object ExportFps {
    val SPEEDS = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 8f, 10f, 16f)
    val CHOICES = intArrayOf(15, 24, 30, 48, 60)

    fun defaultFor(playbackSpeed: Float): Int = if (playbackSpeed > 1f) 60 else 30
}

internal object ExportMapSampling {
    fun key(points: List<VideoTrackPoint>, elapsedMs: Long): Long {
        if (points.isEmpty()) return elapsedMs / 1_000L
        return VideoTrackSync.lastPlayedIndex(points, elapsedMs).toLong()
    }
}
