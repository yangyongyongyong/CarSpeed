package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReplayRotationTest {
    @Test
    fun landscape_video_grows_when_turned_on_a_portrait_frame() {
        val flat = VideoReplayRotation.fittedContentSize(1080, 1800, 1920, 1080, 0)
        val turned = VideoReplayRotation.fittedContentSize(1080, 1800, 1920, 1080, 90)
        val flatScreen = VideoReplayRotation.onScreenSize(flat.first, flat.second, 0)
        val turnedScreen = VideoReplayRotation.onScreenSize(turned.first, turned.second, 90)

        assertTrue(flatScreen.first <= 1080 && flatScreen.second <= 1800)
        assertTrue(turnedScreen.first <= 1080 && turnedScreen.second <= 1800)
        assertTrue(turnedScreen.first * turnedScreen.second > flatScreen.first * flatScreen.second)
    }

    @Test
    fun half_turns_keep_the_original_aspect() {
        val flat = VideoReplayRotation.fittedContentSize(1080, 700, 1920, 1080, 0)
        val half = VideoReplayRotation.fittedContentSize(1080, 700, 1920, 1080, 180)
        val right = VideoReplayRotation.fittedContentSize(1080, 1800, 1920, 1080, 90)
        val left = VideoReplayRotation.fittedContentSize(1080, 1800, 1920, 1080, -90)

        assertEquals(flat, half)
        assertEquals(right, left)
        assertFalse(VideoReplayRotation.isQuarterTurn(180))
        assertTrue(VideoReplayRotation.isQuarterTurn(-90))
    }

    @Test
    fun portrait_turn_takes_more_of_the_screen_than_the_wide_frame() {
        val flat = VideoReplayRotation.videoAreaFraction(1080, 2400, 1920, 1080, 0, 0.15f, 0.85f)
        val turned = VideoReplayRotation.videoAreaFraction(1080, 2400, 1920, 1080, 90, 0.15f, 0.85f)

        assertEquals(0.253f, flat, 0.01f)
        assertEquals(0.8f, turned, 0.01f)
        assertTrue(turned > flat)
    }
}
