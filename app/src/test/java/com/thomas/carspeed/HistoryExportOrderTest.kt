package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HistoryExportOrderTest {
    @Test
    fun selected_trips_are_concatenated_in_time_order() {
        val later = trip(
            "later",
            "20261016-100000",
            listOf("carspeed-20261016-100500.mp4", "carspeed-20261016-100000.mp4")
        )
        val earlier = trip(
            "earlier",
            "行车 2026-10-16 09点34分22秒",
            listOf("carspeed-20261016-094000.mp4", "carspeed-20261016-093422.mp4")
        )
        val names = HistoryExportOrder.orderedSegments(listOf(later, earlier)).map { it.name }
        assertEquals(
            listOf(
                "carspeed-20261016-093422.mp4",
                "carspeed-20261016-094000.mp4",
                "carspeed-20261016-100000.mp4",
                "carspeed-20261016-100500.mp4"
            ),
            names
        )
    }

    @Test
    fun files_without_a_timestamp_fall_back_to_last_modified() {
        val root = File("build/tmp/history-export-order").apply { mkdirs() }
        val lateDir = File(root, "late").apply { mkdirs() }
        val earlyDir = File(root, "early").apply { mkdirs() }
        val late = File(lateDir, "b.mp4").apply {
            writeText("b")
            setLastModified(20_000L)
        }
        val early = File(earlyDir, "a.mp4").apply {
            writeText("a")
            setLastModified(10_000L)
        }
        val names = HistoryExportOrder.orderedSegments(
            listOf(
                VideoStorage.VideoTrip("late", "late", lateDir, listOf(late)),
                VideoStorage.VideoTrip("early", "early", earlyDir, listOf(early))
            )
        ).map { it.name }
        assertEquals(listOf("a.mp4", "b.mp4"), names)
    }

    @Test
    fun empty_selection_stays_empty() {
        assertEquals(emptyList<File>(), HistoryExportOrder.orderedSegments(emptyList()))
    }

    private fun trip(id: String, folder: String, files: List<String>): VideoStorage.VideoTrip {
        val directory = File("/history/$folder")
        return VideoStorage.VideoTrip(
            id = id,
            title = folder,
            directory = directory,
            segments = files.map { File(directory, it) }
        )
    }
}
