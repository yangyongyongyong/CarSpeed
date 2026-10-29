package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFpsTest {
    @Test
    fun speeds_above_one_default_to_sixty_frames() {
        assertEquals(30, ExportFps.defaultFor(1f))
        assertEquals(60, ExportFps.defaultFor(1.5f))
        assertEquals(60, ExportFps.defaultFor(10f))
        assertEquals(60, ExportFps.CHOICES.last())
    }

    @Test
    fun map_snapshot_is_reused_until_the_next_track_point() {
        val points = listOf(
            point(0L),
            point(1_000L),
            point(2_000L)
        )
        assertEquals(0L, ExportMapSampling.key(points, 500L))
        assertEquals(1L, ExportMapSampling.key(points, 1_000L))
        assertEquals(1L, ExportMapSampling.key(points, 1_500L))
        assertEquals(2L, ExportMapSampling.key(points, 2_000L))
    }

    private fun point(elapsedMs: Long): VideoTrackPoint {
        return VideoTrackPoint(elapsedMs, elapsedMs, 30.0, 120.0, 40f, null, null)
    }
}
