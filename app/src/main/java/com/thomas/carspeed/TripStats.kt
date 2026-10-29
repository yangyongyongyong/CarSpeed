package com.thomas.carspeed

import java.time.Instant
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToLong

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
    private const val SAME_DRIVE_JITTER_MS = 60_000L

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
        val spanMs = (end - start).coerceAtLeast(0L)
        val coveredMs = coveredDurationMs(snapshot.recentSpans, start, end)
        val uncoveredMs = (spanMs - coveredMs).coerceAtLeast(0L)
        val effectiveDistance: Double
        val effectiveDuration: Long
        if (spanMs == 0L) {
            if (overlapsCountedTrip(snapshot.recentSpans, start, end)) return snapshot
            effectiveDistance = distance
            effectiveDuration = duration
        } else if (coveredMs > 0L) {
            if (uncoveredMs <= SAME_DRIVE_JITTER_MS) return snapshot
            val ratio = (uncoveredMs.toDouble() / spanMs.toDouble()).coerceIn(0.0, 1.0)
            effectiveDistance = distance * ratio
            effectiveDuration = (duration * ratio).roundToLong()
        } else {
            effectiveDistance = distance
            effectiveDuration = duration
        }
        if (effectiveDistance <= 0.0 && effectiveDuration <= 0L) return snapshot
        val stamp = if (end > 0L) end else start
        val day = dayKey(stamp, zone)
        val month = monthKey(stamp, zone)
        val spans = (snapshot.recentSpans + CountedSpan(start, end)).takeLast(MAX_RECENT_SPANS)
        val (dayKeyOut, dayOut) = mergeBucket(snapshot.dayKey, snapshot.day, day, effectiveDistance, effectiveDuration)
        val (monthKeyOut, monthOut) = mergeBucket(snapshot.monthKey, snapshot.month, month, effectiveDistance, effectiveDuration)
        return snapshot.copy(
            dayKey = dayKeyOut,
            day = dayOut,
            monthKey = monthKeyOut,
            month = monthOut,
            cumulative = snapshot.cumulative.add(effectiveDistance, effectiveDuration),
            recentSpans = spans
        )
    }

    // 补存的旧行程（日期早于当前桶）只计入累计，不能把今天/本月的桶清掉。
    private fun mergeBucket(
        storedKey: String,
        stored: TripStatsBucket,
        incomingKey: String,
        distance: Double,
        duration: Long
    ): Pair<String, TripStatsBucket> {
        return when {
            storedKey == incomingKey -> storedKey to stored.add(distance, duration)
            storedKey.isNotEmpty() && incomingKey < storedKey -> storedKey to stored
            else -> incomingKey to TripStatsBucket().add(distance, duration)
        }
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

    private fun coveredDurationMs(spans: List<CountedSpan>, startMs: Long, endMs: Long): Long {
        if (endMs <= startMs || spans.isEmpty()) return 0L
        val clipped = spans.mapNotNull { span ->
            val s = maxOf(startMs, minOf(span.startMs, span.endMs))
            val e = minOf(endMs, maxOf(span.startMs, span.endMs))
            if (e > s) CountedSpan(s, e) else null
        }.sortedBy { it.startMs }
        if (clipped.isEmpty()) return 0L
        var total = 0L
        var curStart = clipped.first().startMs
        var curEnd = clipped.first().endMs
        for (i in 1 until clipped.size) {
            val next = clipped[i]
            if (next.startMs <= curEnd) {
                curEnd = maxOf(curEnd, next.endMs)
            } else {
                total += curEnd - curStart
                curStart = next.startMs
                curEnd = next.endMs
            }
        }
        total += curEnd - curStart
        return total
    }
}
