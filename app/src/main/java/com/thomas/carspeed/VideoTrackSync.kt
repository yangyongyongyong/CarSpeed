package com.thomas.carspeed

object VideoTrackSync {
    fun pointAt(points: List<VideoTrackPoint>, elapsedMs: Long): VideoTrackPoint? {
        if (points.isEmpty()) return null
        val target = elapsedMs.coerceAtLeast(0L)
        if (target <= points.first().elapsedMs) return points.first()
        if (target >= points.last().elapsedMs) return points.last()

        val nextIndex = points.indexOfFirst { it.elapsedMs >= target }
        if (nextIndex <= 0) return points.first()
        val previous = points[nextIndex - 1]
        val next = points[nextIndex]
        val span = (next.elapsedMs - previous.elapsedMs).coerceAtLeast(1L)
        val ratio = ((target - previous.elapsedMs).toDouble() / span.toDouble()).coerceIn(0.0, 1.0)

        return VideoTrackPoint(
            elapsedMs = target,
            timeMs = lerpLong(previous.timeMs, next.timeMs, ratio),
            latitude = lerp(previous.latitude, next.latitude, ratio),
            longitude = lerp(previous.longitude, next.longitude, ratio),
            speedKmh = lerp(previous.speedKmh.toDouble(), next.speedKmh.toDouble(), ratio).toFloat(),
            accuracyMeters = lerpNullable(previous.accuracyMeters, next.accuracyMeters, ratio),
            bearingDegrees = next.bearingDegrees ?: previous.bearingDegrees
        )
    }

    fun lastPlayedIndex(points: List<VideoTrackPoint>, elapsedMs: Long): Int {
        if (points.isEmpty()) return -1
        val target = elapsedMs.coerceAtLeast(0L)
        val index = points.indexOfLast { it.elapsedMs <= target }
        return index.coerceAtLeast(0)
    }

    private fun lerp(from: Double, to: Double, ratio: Double): Double {
        return from + (to - from) * ratio
    }

    private fun lerpLong(from: Long, to: Long, ratio: Double): Long {
        return (from + (to - from) * ratio).toLong()
    }

    private fun lerpNullable(from: Float?, to: Float?, ratio: Double): Float? {
        if (from == null || to == null) return from ?: to
        return lerp(from.toDouble(), to.toDouble(), ratio).toFloat()
    }
}
