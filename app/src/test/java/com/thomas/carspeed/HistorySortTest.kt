package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySortTest {

    private val r1 = DriveRecord(
        startTimeMs = 1000,
        endTimeMs = 4000,
        durationMs = 3000,
        maxSpeedKmh = 80f,
        distanceMeters = 900f,
        averageSpeedKmh = 72f
    )
    private val r2 = DriveRecord(
        startTimeMs = 2000,
        endTimeMs = 5000,
        durationMs = 1500,
        maxSpeedKmh = 60f,
        distanceMeters = 300f,
        averageSpeedKmh = 36f
    )
    private val r3 = DriveRecord(
        startTimeMs = 3000,
        endTimeMs = 3500,
        durationMs = 500,
        maxSpeedKmh = 120f,
        distanceMeters = 1200f,
        averageSpeedKmh = 90f
    )

    @Test
    fun sort_by_start_time_desc() {
        val sorted = HistorySort.sort(listOf(r1, r2, r3), HistorySortField.START_TIME, ascending = false)
        assertEquals(listOf(r3, r2, r1), sorted)
    }

    @Test
    fun sort_by_end_time_asc() {
        val sorted = HistorySort.sort(listOf(r1, r2, r3), HistorySortField.END_TIME, ascending = true)
        assertEquals(listOf(r3, r1, r2), sorted)
    }

    @Test
    fun sort_by_duration_desc() {
        val sorted = HistorySort.sort(listOf(r1, r2, r3), HistorySortField.DURATION, ascending = false)
        assertEquals(listOf(r1, r2, r3), sorted)
    }

    @Test
    fun sort_by_max_speed_asc() {
        val sorted = HistorySort.sort(listOf(r1, r2, r3), HistorySortField.MAX_SPEED, ascending = true)
        assertEquals(listOf(r2, r1, r3), sorted)
    }

    @Test
    fun sort_by_distance_desc() {
        val sorted = HistorySort.sort(listOf(r1, r2, r3), HistorySortField.DISTANCE, ascending = false)
        assertEquals(listOf(r3, r1, r2), sorted)
    }

    @Test
    fun sort_by_average_speed_asc() {
        val sorted = HistorySort.sort(listOf(r1, r2, r3), HistorySortField.AVERAGE_SPEED, ascending = true)
        assertEquals(listOf(r2, r1, r3), sorted)
    }

    @Test
    fun average_speed_uses_moving_time_when_wait_exists() {
        val speed = DriveRecordStore.averageSpeedKmh(
            distanceMeters = 1_000f,
            durationMs = 100_000L,
            waitDurationMs = 50_000L
        )
        assertEquals(72f, speed)
    }
}
