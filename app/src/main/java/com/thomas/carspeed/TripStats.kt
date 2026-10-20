package com.thomas.carspeed

import java.time.Instant
import java.time.ZoneId
import kotlin.math.min

internal data class TripStatsBucket(
    val distanceMeters: Double = 0.0,
    val durationMs: Long = 0L
) {
    fun add(distanceMeters: Double, durationMs: Long): TripStatsBucket {
        return copy(
            distanceMeters = this.distanceMeters + distanceMeters.coerceAtLeast(0.0),
            durationMs = this.durationMs + durationMs.coerceAtLeast(0L)
        )
    }

    fun averageSpeedKmh(): Double {
        if (durationMs <= 0L || distanceMeters <= 0.0) return 0.0
        return distanceMeters / (durationMs / 1000.0) * 3.6
    }
}

internal data class CountedSpan(
    val startMs: Long,
    val endMs: Long
)

internal data class TripStatsSnapshot(
    val dayKey: String = "",
    val day: TripStatsBucket = TripStatsBucket(),
    val monthKey: String = "",
    val month: TripStatsBucket = TripStatsBucket(),
    val cumulative: TripStatsBucket = TripStatsBucket(),
    val recentSpans: List<CountedSpan> = emptyList()
)

internal object TripStats {
    const val MAX_RECENT_SPANS = 40

    fun apply(
        snapshot: TripStatsSnapshot,
        startMs: Long,
        endMs: Long,
        distanceMeters: Double,
        durationMs: Long,
        zone: ZoneId
    ): TripStatsSnapshot {
        val distance = distanceMeters.coerceAtLeast(0.0)
        val duration = durationMs.coerceAtLeast(0L)
        if (distance <= 0.0 && duration <= 0L) return snapshot
        val start = minOf(startMs, endMs)
        val end = maxOf(startMs, endMs)
        if (overlapsCountedTrip(snapshot.recentSpans, start, end)) return snapshot
        val stamp = if (end > 0L) end else start
        val day = dayKey(stamp, zone)
        val month = monthKey(stamp, zone)
        val spans = (snapshot.recentSpans + CountedSpan(start, end)).takeLast(MAX_RECENT_SPANS)
        return snapshot.copy(
            dayKey = day,
            day = (if (snapshot.dayKey == day) snapshot.day else TripStatsBucket()).add(distance, duration),
            monthKey = month,
            month = (if (snapshot.monthKey == month) snapshot.month else TripStatsBucket()).add(distance, duration),
            cumulative = snapshot.cumulative.add(distance, duration),
            recentSpans = spans
        )
    }

    fun alignForReading(snapshot: TripStatsSnapshot, nowMs: Long, zone: ZoneId): TripStatsSnapshot {
        val day = dayKey(nowMs, zone)
        val month = monthKey(nowMs, zone)
        return snapshot.copy(
            dayKey = day,
            day = if (snapshot.dayKey == day) snapshot.day else TripStatsBucket(),
            monthKey = month,
            month = if (snapshot.monthKey == month) snapshot.month else TripStatsBucket()
        )
    }

    fun dayKey(timeMs: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        return "%04d%02d%02d".format(date.year, date.monthValue, date.dayOfMonth)
    }

    fun monthKey(timeMs: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        return "%04d%02d".format(date.year, date.monthValue)
    }

    fun overlapsCountedTrip(spans: List<CountedSpan>, startMs: Long, endMs: Long): Boolean {
        val incoming = (endMs - startMs).coerceAtLeast(0L)
        return spans.any { span ->
            val overlap = overlapMs(span.startMs, span.endMs, startMs, endMs)
            if (overlap <= 0L) {
                startMs == endMs && startMs in span.startMs..span.endMs
            } else {
                val shorter = min(incoming, (span.endMs - span.startMs).coerceAtLeast(0L)).coerceAtLeast(1L)
                overlap * 2 >= shorter
            }
        }
    }

    private fun overlapMs(startA: Long, endA: Long, startB: Long, endB: Long): Long {
        return (minOf(endA, endB) - maxOf(startA, startB)).coerceAtLeast(0L)
    }
}
