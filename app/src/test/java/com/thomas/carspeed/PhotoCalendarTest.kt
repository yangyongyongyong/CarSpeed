package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PhotoCalendarTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun capture_time_prefers_the_file_name() {
        val expected = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 15, 10, 15, 30)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, PhotoCalendar.captureTimeMs("carspeed-photo-20261015-101530.jpg", 1L))
        assertEquals(99L, PhotoCalendar.captureTimeMs("other.jpg", 99L))
    }

    @Test
    fun month_grid_counts_each_day_and_starts_on_monday() {
        val times = listOf(
            at(2026, 10, 15, 10, 0),
            at(2026, 10, 15, 18, 30),
            at(2026, 10, 1, 0, 5),
            at(2026, 9, 30, 23, 0)
        )

        val cells = PhotoCalendar.monthGrid(2026, 10, times, zone)
        val days = cells.filter { it.dayOfMonth != null }

        assertEquals(3, cells.indexOfFirst { it.dayOfMonth == 1 })
        assertEquals(31, days.size)
        assertEquals(1, days.first { it.dayOfMonth == 1 }.count)
        assertEquals(2, days.first { it.dayOfMonth == 15 }.count)
        assertEquals(0, days.first { it.dayOfMonth == 2 }.count)
        assertNull(cells.first().dayOfMonth)
    }

    @Test
    fun midnight_belongs_to_the_new_day() {
        val cells = PhotoCalendar.monthGrid(2026, 10, listOf(at(2026, 10, 16, 0, 0)), zone)
        val day15 = cells.first { it.dayOfMonth == 15 }
        val day16 = cells.first { it.dayOfMonth == 16 }
        assertEquals(0, day15.count)
        assertEquals(1, day16.count)
        assertEquals(day16.dayStartMs, PhotoCalendar.startOfDay(at(2026, 10, 16, 8, 0), zone))
    }

    @Test
    fun month_shift_crosses_the_year() {
        assertEquals(2025 to 12, PhotoCalendar.addMonth(2026, 1, -1))
        assertEquals(2027 to 1, PhotoCalendar.addMonth(2026, 12, 1))
    }

    @Test
    fun second_click_makes_an_inclusive_range_and_third_click_starts_over() {
        val day10 = at(2026, 10, 10, 0, 0)
        val day15 = at(2026, 10, 15, 0, 0)
        val day2 = at(2026, 10, 2, 0, 0)

        val single = PhotoCalendar.selectDay(null, day15)
        assertEquals(DaySelection(day15, day15), single)
        assertEquals(single, PhotoCalendar.selectDay(single, day15))

        val range = PhotoCalendar.selectDay(single, day10)
        assertEquals(DaySelection(day10, day15), range)
        assertEquals(true, PhotoCalendar.contains(range, at(2026, 10, 12, 8, 0), zone))
        assertEquals(false, PhotoCalendar.contains(range, at(2026, 10, 16, 0, 0), zone))
        assertEquals("10月10日~10月15日", PhotoCalendar.formatSelection(range, zone))
        assertEquals("10月15日", PhotoCalendar.formatSelection(single, zone))
        assertEquals(DaySelection(day2, day2), PhotoCalendar.selectDay(range, day2))
    }

    @Test
    fun a_span_counts_on_every_day_it_covers() {
        val cells = PhotoCalendar.monthGridSpans(
            2026,
            10,
            listOf(CountSpan(at(2026, 10, 15, 22, 0), at(2026, 10, 16, 1, 0))),
            zone
        )
        assertEquals(1, cells.first { it.dayOfMonth == 15 }.count)
        assertEquals(1, cells.first { it.dayOfMonth == 16 }.count)
        assertEquals(0, cells.first { it.dayOfMonth == 14 }.count)
    }

    @Test
    fun pages_match_the_video_page_size() {
        val items = (1..12).toList()
        assertEquals(5, PhotoCalendar.PAGE_SIZE)
        assertEquals(3, PhotoCalendar.pageCount(items.size))
        assertEquals(listOf(1, 2, 3, 4, 5), PhotoCalendar.pageOf(items, 0))
        assertEquals(listOf(11, 12), PhotoCalendar.pageOf(items, 9))
        assertEquals(1, PhotoCalendar.pageCount(0))
        assertEquals(emptyList<Int>(), PhotoCalendar.pageOf(emptyList<Int>(), 0))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long {
        return Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
