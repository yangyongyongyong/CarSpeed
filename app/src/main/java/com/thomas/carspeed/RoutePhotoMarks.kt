package com.thomas.carspeed

import java.text.SimpleDateFormat
import java.util.Locale

internal data class RoutePhotoCandidate(
    val timeMs: Long,
    val path: String
)

internal data class RoutePhotoMark(
    val path: String,
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double
)

internal object RoutePhotoMarks {
    const val MATCH_SLACK_MS = 5_000L
    private val FILE_TIME = Regex("""carspeed-photo-(\d{8}-\d{6})""")

    fun timeFromFileName(name: String): Long? {
        val stamp = FILE_TIME.find(name)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { isLenient = false }.parse(stamp)?.time
        }.getOrNull()
    }

    fun countBetween(photoTimesMs: List<Long>, startMs: Long, endMs: Long): Int {
        val start = startMs.takeIf { it > 0L } ?: endMs
        val end = endMs.takeIf { it > 0L } ?: startMs
        if (start <= 0L || end <= 0L) return 0
        val from = minOf(start, end) - MATCH_SLACK_MS
        val to = maxOf(start, end) + MATCH_SLACK_MS
        return photoTimesMs.count { it in from..to }
    }

    fun match(
        points: List<VideoTrackPoint>,
        photos: List<RoutePhotoCandidate>,
        trackStartMs: Long = 0L
    ): List<RoutePhotoMark> {
        val timed = points.mapNotNull { point ->
            val time = resolvedTime(point, trackStartMs)
            if (time <= 0L) null else TimedPoint(time, point.latitude, point.longitude)
        }.sortedBy { it.timeMs }
        if (timed.isEmpty()) return emptyList()
        val start = timed.first().timeMs - MATCH_SLACK_MS
        val end = timed.last().timeMs + MATCH_SLACK_MS
        return photos
            .filter { it.timeMs in start..end && it.path.isNotBlank() }
            .distinctBy { it.path }
            .sortedBy { it.timeMs }
            .map { photo ->
                val place = place(timed, photo.timeMs)
                RoutePhotoMark(photo.path, photo.timeMs, place.latitude, place.longitude)
            }
    }

    private fun resolvedTime(point: VideoTrackPoint, trackStartMs: Long): Long {
        if (point.timeMs > 0L) return point.timeMs
        if (trackStartMs > 0L) return trackStartMs + point.elapsedMs
        return 0L
    }

    private fun place(points: List<TimedPoint>, timeMs: Long): TimedPoint {
        if (timeMs <= points.first().timeMs) return points.first()
        if (timeMs >= points.last().timeMs) return points.last()
        val nextIndex = points.indexOfFirst { it.timeMs >= timeMs }.coerceAtLeast(1)
        val next = points[nextIndex]
        val previous = points[nextIndex - 1]
        val span = (next.timeMs - previous.timeMs).coerceAtLeast(1L)
        val ratio = ((timeMs - previous.timeMs).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)
        return TimedPoint(
            timeMs = timeMs,
            latitude = previous.latitude + (next.latitude - previous.latitude) * ratio,
            longitude = previous.longitude + (next.longitude - previous.longitude) * ratio
        )
    }

    private data class TimedPoint(
        val timeMs: Long,
        val latitude: Double,
        val longitude: Double
    )
}
