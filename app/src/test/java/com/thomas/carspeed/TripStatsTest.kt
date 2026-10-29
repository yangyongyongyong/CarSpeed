package com.thomas.carspeed

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun finished_trip_updates_today_month_and_cumulative() {
        val end = at(2026, 10, 16, 10, 0)
        val updated = TripStats.apply(
            snapshot = TripStatsSnapshot(),
            startMs = end - 20 * 60_000L,
            endMs = end,
            distanceMeters = 12_400.0,
            durationMs = 20 * 60_000L,
            zone = zone
        )
        assertEquals("20261016", updated.dayKey)
        assertEquals("202610", updated.monthKey)
        assertEquals(12_400.0, updated.day.distanceMeters, 0.01)
        assertEquals(12_400.0, updated.month.distanceMeters, 0.01)
        assertEquals(12_400.0, updated.cumulative.distanceMeters, 0.01)
        assertEquals(37.2, updated.day.averageSpeedKmh(), 0.01)
    }

    @Test
    fun later_trip_the_same_day_adds_to_existing_buckets() {
        val firstEnd = at(2026, 10, 16, 9, 0)
        val secondEnd = at(2026, 10, 16, 18, 0)
        val once = TripStats.apply(TripStatsSnapshot(), firstEnd - 600_000L, firstEnd, 10_000.0, 600_000L, zone)
        val twice = TripStats.apply(once, secondEnd - 600_000L, secondEnd, 5_000.0, 600_000L, zone)
        assertEquals(15_000.0, twice.day.distanceMeters, 0.01)
        assertEquals(15_000.0, twice.month.distanceMeters, 0.01)
        assertEquals(1_200_000L, twice.cumulative.durationMs)
    }

    @Test
    fun a_new_day_replaces_today_and_keeps_the_month() {
        val yesterday = at(2026, 10, 15, 21, 0)
        val today = at(2026, 10, 16, 8, 0)
        val stored = TripStats.apply(TripStatsSnapshot(), yesterday - 600_000L, yesterday, 8_000.0, 600_000L, zone)
        val updated = TripStats.apply(stored, today - 600_000L, today, 2_000.0, 600_000L, zone)
        assertEquals(2_000.0, updated.day.distanceMeters, 0.01)
        assertEquals(10_000.0, updated.month.distanceMeters, 0.01)
        assertEquals(10_000.0, updated.cumulative.distanceMeters, 0.01)
    }

    @Test
    fun a_new_month_replaces_today_and_month_but_keeps_cumulative() {
        val october = at(2026, 10, 31, 22, 0)
        val november = at(2026, 11, 1, 9, 0)
        val stored = TripStats.apply(TripStatsSnapshot(), october - 600_000L, october, 9_000.0, 600_000L, zone)
        val updated = TripStats.apply(stored, november - 600_000L, november, 1_000.0, 600_000L, zone)
        assertEquals(1_000.0, updated.day.distanceMeters, 0.01)
        assertEquals(1_000.0, updated.month.distanceMeters, 0.01)
        assertEquals(10_000.0, updated.cumulative.distanceMeters, 0.01)
    }

    @Test
    fun the_same_drive_recorded_twice_is_counted_once() {
        val end = at(2026, 10, 16, 10, 0)
        val start = end - 20 * 60_000L
        val once = TripStats.apply(TripStatsSnapshot(), start, end, 12_000.0, 20 * 60_000L, zone)
        val twice = TripStats.apply(once, start + 30_000L, end - 30_000L, 12_000.0, 19 * 60_000L, zone)
        assertEquals(12_000.0, twice.cumulative.distanceMeters, 0.01)
        assertEquals(1, twice.recentSpans.size)
    }

    @Test
    fun partially_overlapping_longer_trip_only_adds_uncovered_portion() {
        val start = at(2026, 10, 16, 9, 0)
        val clipEnd = start + 10 * 60_000L
        val fullEnd = start + 40 * 60_000L
        val afterClip = TripStats.apply(TripStatsSnapshot(), start, clipEnd, 5_000.0, 10 * 60_000L, zone)
        val afterFull = TripStats.apply(afterClip, start, fullEnd, 20_000.0, 40 * 60_000L, zone)

        assertEquals(20_000.0, afterFull.day.distanceMeters, 0.01)
        assertEquals(40 * 60_000L, afterFull.day.durationMs)
    }

    @Test
    fun reading_on_a_later_day_does_not_keep_yesterdays_mileage_as_today() {
        val yesterday = at(2026, 10, 15, 21, 0)
        val stored = TripStats.apply(TripStatsSnapshot(), yesterday - 600_000L, yesterday, 8_000.0, 600_000L, zone)
        val shown = TripStats.alignForReading(stored, at(2026, 10, 16, 8, 0), zone)
        assertEquals(0.0, shown.day.distanceMeters, 0.01)
        assertEquals(8_000.0, shown.month.distanceMeters, 0.01)
        assertEquals(8_000.0, shown.cumulative.distanceMeters, 0.01)
        assertEquals(8_000.0, stored.day.distanceMeters, 0.01)
    }

    @Test
    fun empty_trip_is_ignored() {
        val end = at(2026, 10, 16, 10, 0)
        val updated = TripStats.apply(TripStatsSnapshot(), end, end, 0.0, 0L, zone)
        assertTrue(updated.recentSpans.isEmpty())
        assertEquals(0.0, updated.cumulative.distanceMeters, 0.01)
    }

    @Test
    fun late_saved_older_trip_keeps_todays_buckets() {
        val today = at(2026, 11, 2, 9, 0)
        val lastMonth = at(2026, 10, 31, 20, 0)
        val stored = TripStats.apply(TripStatsSnapshot(), today - 600_000L, today, 3_000.0, 600_000L, zone)
        val updated = TripStats.apply(stored, lastMonth - 600_000L, lastMonth, 7_000.0, 600_000L, zone)
        assertEquals("20261102", updated.dayKey)
        assertEquals(3_000.0, updated.day.distanceMeters, 0.01)
        assertEquals("202611", updated.monthKey)
        assertEquals(3_000.0, updated.month.distanceMeters, 0.01)
        assertEquals(10_000.0, updated.cumulative.distanceMeters, 0.01)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    }
}
