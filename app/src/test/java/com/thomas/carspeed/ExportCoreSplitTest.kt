package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportCoreSplitTest {
    @Test
    fun worker_count_follows_cores_and_stays_small_on_a_short_frame() {
        assertEquals(1, ExportCoreSplit.workerCount(cores = 1, height = 1080))
        assertEquals(1, ExportCoreSplit.workerCount(cores = 8, height = 32))
        assertEquals(3, ExportCoreSplit.workerCount(cores = 4, height = 1080))
        assertEquals(6, ExportCoreSplit.workerCount(cores = 8, height = 1080))
    }

    @Test
    fun row_bands_cover_every_row_once() {
        val bands = ExportCoreSplit.rowBands(height = 10, workers = 3)
        assertEquals(listOf(0 until 4, 4 until 7, 7 until 10), bands)
        assertEquals(10, bands.sumOf { it.last - it.first + 1 })
        assertEquals(listOf(0 until 1080), ExportCoreSplit.rowBands(1080, 1))
    }

    @Test
    fun split_rows_match_a_single_pass() {
        val width = 4
        val height = 6
        val y = ByteArray(width * height) { 140.toByte() }
        val u = ByteArray(width * height / 4) { 128.toByte() }
        val v = ByteArray(width * height / 4) { 128.toByte() }
        val together = IntArray(width * height)
        val split = IntArray(width * height)
        ExportYuv.writeRows(0, height, width, y, u, v, 0, 0, 0, width, width / 2, width / 2, 1, 1, 1, together)
        ExportCoreSplit.rowBands(height, 3).forEach { band ->
            ExportYuv.writeRows(
                band.first, band.last + 1, width, y, u, v, 0, 0, 0,
                width, width / 2, width / 2, 1, 1, 1, split
            )
        }
        assertEquals(together.toList(), split.toList())
    }
}
