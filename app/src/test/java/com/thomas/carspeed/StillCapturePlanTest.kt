package com.thomas.carspeed

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCapturePlanTest {
    @Test
    fun jpeg_size_stays_within_the_video_and_keeps_a_smaller_fallback() {
        val sizes = listOf(
            CaptureSize(3840, 2160),
            CaptureSize(1920, 1080),
            CaptureSize(1280, 720),
            CaptureSize(4000, 3000)
        )

        val candidates = StillCapturePlan.jpegSizeCandidates(sizes, videoWidth = 3840, videoHeight = 2160)

        assertEquals(listOf(CaptureSize(3840, 2160), CaptureSize(1920, 1080)), candidates)
    }

    @Test
    fun jpeg_size_uses_the_largest_matching_frame_not_bigger_than_video() {
        val sizes = listOf(
            CaptureSize(1920, 1080),
            CaptureSize(1280, 720),
            CaptureSize(640, 480)
        )

        assertEquals(
            CaptureSize(1280, 720),
            StillCapturePlan.chooseJpegSize(sizes, videoWidth = 1280, videoHeight = 720)
        )
    }

    @Test
    fun empty_jpeg_sizes_have_no_candidate() {
        assertTrue(StillCapturePlan.jpegSizeCandidates(emptyList(), 1920, 1080).isEmpty())
    }

    @Test
    fun back_camera_jpeg_orientation_follows_the_display() {
        assertEquals(90, StillCapturePlan.jpegOrientationDegrees(90, Surface.ROTATION_0))
        assertEquals(0, StillCapturePlan.jpegOrientationDegrees(90, Surface.ROTATION_90))
        assertEquals(270, StillCapturePlan.jpegOrientationDegrees(90, Surface.ROTATION_180))
        assertEquals(180, StillCapturePlan.jpegOrientationDegrees(90, Surface.ROTATION_270))
        assertEquals(270, StillCapturePlan.jpegOrientationDegrees(270, Surface.ROTATION_0))
    }
}
