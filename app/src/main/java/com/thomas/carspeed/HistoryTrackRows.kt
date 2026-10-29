package com.thomas.carspeed

/**
 * 有录像的行程自带轨迹。这里只留下没有被录像盖住的轨迹，给驾驶记录补上「仅轨迹」的行程。
 */
internal data class HistoryTrackWindow(
    val rangeStartMs: Long? = null,
    val rangeEndMs: Long? = null,
    val minDurationMs: Long? = null,
    val maxDurationMs: Long? = null,
    val minDistanceMeters: Float? = null,
    val maxDistanceMeters: Float? = null,
    val minPhotoCount: Int? = null,
    val favoriteOnly: Boolean = false
)

internal object HistoryTrackRows {
    const val VIDEO_OVERLAP_MS = 15_000L

    fun uncovered(records: List<DriveRecord>, videoSpans: List<Pair<Long, Long>>): List<DriveRecord> {
        return records
            .filter { it.source == DriveRecordStore.SOURCE_GPS_TRACK && it.routePoints.isNotEmpty() }
            .filter { !coveredByVideo(it.startTimeMs, it.endTimeMs, videoSpans) }
            .sortedByDescending { it.startTimeMs }
    }

    fun applyWindow(records: List<DriveRecord>, window: HistoryTrackWindow): List<DriveRecord> {
        if (window.favoriteOnly) return emptyList()
        if ((window.minPhotoCount ?: 0) > 0) return emptyList()
        return records.filter { matches(it, window) }
    }

    fun coveredByVideo(startMs: Long, endMs: Long, spans: List<Pair<Long, Long>>): Boolean {
        val start = minOf(startMs, endMs)
        val end = maxOf(startMs, endMs)
        return spans.any { (rawStart, rawEnd) ->
            val spanStart = minOf(rawStart, rawEnd)
            val spanEnd = maxOf(rawStart, rawEnd)
            minOf(end, spanEnd) - maxOf(start, spanStart) >= VIDEO_OVERLAP_MS
        }
    }

    private fun matches(record: DriveRecord, window: HistoryTrackWindow): Boolean {
        if (!rangeMatches(record.startTimeMs, record.endTimeMs, window.rangeStartMs, window.rangeEndMs)) return false
        if (window.minDurationMs != null && record.durationMs < window.minDurationMs) return false
        if (window.maxDurationMs != null && record.durationMs > window.maxDurationMs) return false
        if (window.minDistanceMeters != null && record.distanceMeters < window.minDistanceMeters) return false
        if (window.maxDistanceMeters != null && record.distanceMeters > window.maxDistanceMeters) return false
        return true
    }

    private fun rangeMatches(itemStartMs: Long, itemEndMs: Long, allowedStartMs: Long?, allowedEndMs: Long?): Boolean {
        if (allowedStartMs == null && allowedEndMs == null) return true
        val start = itemStartMs.takeIf { it > 0L } ?: itemEndMs
        val end = itemEndMs.takeIf { it > 0L } ?: start
        if (allowedStartMs != null && end < allowedStartMs) return false
        if (allowedEndMs != null && start > allowedEndMs) return false
        return true
    }
}
