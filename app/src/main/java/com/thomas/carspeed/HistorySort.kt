package com.thomas.carspeed

enum class HistorySortField {
    START_TIME,
    END_TIME,
    DURATION,
    MAX_SPEED
}

object HistorySort {
    fun sort(
        records: List<DriveRecord>,
        field: HistorySortField,
        ascending: Boolean
    ): List<DriveRecord> {
        val comparator = when (field) {
            HistorySortField.START_TIME -> compareBy<DriveRecord> { it.startTimeMs }
            HistorySortField.END_TIME -> compareBy<DriveRecord> { it.endTimeMs }
            HistorySortField.DURATION -> compareBy<DriveRecord> { it.durationMs }
            HistorySortField.MAX_SPEED -> compareBy<DriveRecord> { it.maxSpeedKmh }
        }
        val sorted = records.sortedWith(comparator)
        return if (ascending) sorted else sorted.asReversed()
    }
}
