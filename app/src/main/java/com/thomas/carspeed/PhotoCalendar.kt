package com.thomas.carspeed

import java.util.Calendar
import java.util.TimeZone

internal data class PhotoDayCell(
    val dayOfMonth: Int?,
    val count: Int,
    val dayStartMs: Long
)

internal data class CountSpan(
    val startMs: Long,
    val endMs: Long
)

internal data class DaySelection(
    val startMs: Long,
    val endMs: Long
) {
    val singleDay: Boolean
        get() = startMs == endMs
}

internal object PhotoCalendar {
    const val PAGE_SIZE = 5

    fun captureTimeMs(fileName: String, fallbackMs: Long): Long {
        return RoutePhotoMarks.timeFromFileName(fileName) ?: fallbackMs
    }

    fun startOfDay(timeMs: Long, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun endOfDay(timeMs: Long, zone: TimeZone): Long {
        return Calendar.getInstance(zone).apply {
            timeInMillis = startOfDay(timeMs, zone)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    fun selectDay(current: DaySelection?, dayStartMs: Long): DaySelection {
        if (current == null || !current.singleDay) return DaySelection(dayStartMs, dayStartMs)
        if (current.startMs == dayStartMs) return current
        return DaySelection(minOf(current.startMs, dayStartMs), maxOf(current.startMs, dayStartMs))
    }

    fun contains(selection: DaySelection, timeMs: Long, zone: TimeZone): Boolean {
        return timeMs in startOfDay(selection.startMs, zone)..endOfDay(selection.endMs, zone)
    }

    fun formatSelection(selection: DaySelection, zone: TimeZone): String {
        val start = formatDay(selection.startMs, zone)
        if (selection.singleDay) return start
        return "$start~${formatDay(selection.endMs, zone)}"
    }

    private fun formatDay(timeMs: Long, zone: TimeZone): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        return "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日"
    }

    fun yearMonth(timeMs: Long, zone: TimeZone): Pair<Int, Int> {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = timeMs }
        return calendar.get(Calendar.YEAR) to (calendar.get(Calendar.MONTH) + 1)
    }

    fun addMonth(year: Int, month: Int, delta: Int): Pair<Int, Int> {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, 1)
            add(Calendar.MONTH, delta)
        }
        return calendar.get(Calendar.YEAR) to (calendar.get(Calendar.MONTH) + 1)
    }

    fun monthGrid(year: Int, month: Int, timesMs: List<Long>, zone: TimeZone): List<PhotoDayCell> {
        return monthGridSpans(year, month, timesMs.map { CountSpan(it, it) }, zone)
    }

    fun monthGridSpans(year: Int, month: Int, spans: List<CountSpan>, zone: TimeZone): List<PhotoDayCell> {
        val first = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, 1)
        }
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val leadingBlanks = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val cells = mutableListOf<PhotoDayCell>()
        repeat(leadingBlanks) { cells.add(PhotoDayCell(dayOfMonth = null, count = 0, dayStartMs = 0L)) }
        for (day in 1..daysInMonth) {
            val start = Calendar.getInstance(zone).apply {
                clear()
                set(year, month - 1, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val end = endOfDay(start, zone)
            val count = spans.count { span ->
                val spanEnd = maxOf(span.startMs, span.endMs)
                spanEnd >= start && span.startMs <= end
            }
            cells.add(PhotoDayCell(dayOfMonth = day, count = count, dayStartMs = start))
        }
        return cells
    }

    fun pageCount(itemCount: Int): Int {
        return ((itemCount + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    }

    fun <T> pageOf(items: List<T>, pageIndex: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val index = pageIndex.coerceIn(0, pageCount(items.size) - 1)
        return items.drop(index * PAGE_SIZE).take(PAGE_SIZE)
    }
}
