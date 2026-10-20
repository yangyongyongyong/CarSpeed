package com.thomas.carspeed

import java.io.File

/**
 * 多选导出时按时间把各段接起来。行程之间看开始时间，同一行程里的分段也按文件时间排。
 */
internal object HistoryExportOrder {
    private val STAMP = Regex("(\\d{8})-(\\d{6})")

    fun orderedSegments(trips: List<VideoStorage.VideoTrip>): List<File> {
        return trips
            .sortedWith(compareBy<VideoStorage.VideoTrip> { startMs(it) }.thenBy { it.id })
            .flatMap { trip ->
                trip.segments.sortedWith(
                    compareBy<File> { fileStartMs(it) ?: Long.MAX_VALUE }.thenBy { it.name }
                )
            }
    }

    fun startMs(trip: VideoStorage.VideoTrip): Long {
        VideoStorage.tripFolderStartTimeMs(trip.directory.name)?.let { return it }
        VideoStorage.tripFolderStartTimeMs(trip.title)?.let { return it }
        trip.segments.mapNotNull { fileStartMs(it) }.minOrNull()?.let { return it }
        return trip.segments.maxOfOrNull { it.lastModified() } ?: 0L
    }

    fun fileStartMs(file: File): Long? {
        val match = STAMP.find(file.name) ?: return null
        return VideoStorage.tripFolderStartTimeMs("${match.groupValues[1]}-${match.groupValues[2]}")
    }
}
