package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GpsTrackTimeLabelTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun same_day_does_not_repeat_the_date() {
        assertEquals(
            "1月1日10点~12点",
            GpsTrackTimeLabel.format(at(1, 1, 10, 0), at(1, 1, 12, 0), zone)
        )
    }

    @Test
    fun different_days_keep_both_dates() {
        assertEquals(
            "1月1日10点到1月2日12点",
            GpsTrackTimeLabel.format(at(1, 1, 10, 0), at(1, 2, 12, 0), zone)
        )
    }

    @Test
    fun minutes_are_kept_when_not_on_the_hour() {
        assertEquals(
            "1月1日10点30分~12点5分",
            GpsTrackTimeLabel.format(at(1, 1, 10, 30), at(1, 1, 12, 5), zone)
        )
    }

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance(zone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
