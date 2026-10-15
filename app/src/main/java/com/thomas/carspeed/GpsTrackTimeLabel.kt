package com.thomas.carspeed

import java.util.Calendar
import java.util.TimeZone

internal object GpsTrackTimeLabel {
    fun format(startMs: Long, endMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val start = calendar(startMs, zone)
        val end = calendar(endMs, zone)
        val sameDay = start.get(Calendar.YEAR) == end.get(Calendar.YEAR) &&
            start.get(Calendar.DAY_OF_YEAR) == end.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            "${dayClock(start)}~${clock(end)}"
        } else {
            "${dayClock(start)}到${dayClock(end)}"
        }
    }

    private fun dayClock(calendar: Calendar): String {
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return "${month}月${day}日${clock(calendar)}"
    }

    private fun clock(calendar: Calendar): String {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return if (minute == 0) "${hour}点" else "${hour}点${minute}分"
    }

    private fun calendar(timeMs: Long, zone: TimeZone): Calendar {
        return Calendar.getInstance(zone).apply { timeInMillis = timeMs }
    }
}
