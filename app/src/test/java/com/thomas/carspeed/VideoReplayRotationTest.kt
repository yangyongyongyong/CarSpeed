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
    fun stage_keeps_the_video_aspect_and_adds_the_bar_on_top() {
        val flat = VideoReplayRotation.fittedStageSize(1080, 1800, 1920, 1080, barPx = 186, rotationDegrees = 0)
        assertEquals(1080, flat.first)
        assertEquals(1080 * 1080 / 1920 + 186, flat.second)

        val turned = VideoReplayRotation.fittedStageSize(1080, 1800, 1920, 1080, barPx = 186, rotationDegrees = 90)
        val videoHeight = turned.second - 186
        assertEquals(1920f / 1080f, turned.first.toFloat() / videoHeight.toFloat(), 0.02f)
        assertTrue(turned.first <= 1800)
        assertTrue(turned.second <= 1080)
    }

    @Test
    fun tiny_frame_never_loses_the_video_to_the_bar() {
        val stage = VideoReplayRotation.fittedStageSize(400, 300, 1920, 1080, barPx = 186, rotationDegrees = 0)
        assertTrue(stage.second > 186)
        assertTrue(stage.second <= 300)
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

    @Test
    fun subtitle_at_the_bottom_left_moves_to_the_top_left_after_a_clockwise_quarter_turn() {
        val contentWidth = 1920f
        val contentHeight = 1080f
        val flat = VideoReplayRotation.mapContentPoint(
            x = 0f,
            y = contentHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            outputWidth = contentWidth,
            outputHeight = contentHeight,
            rotationDegrees = 0
        )
        val turned = VideoReplayRotation.mapContentPoint(
            x = 0f,
            y = contentHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            outputWidth = contentHeight,
            outputHeight = contentWidth,
            rotationDegrees = 90
        )

        assertEquals(0f, flat.first, 0.5f)
        assertEquals(contentHeight, flat.second, 0.5f)
        assertEquals(0f, turned.first, 0.5f)
        assertEquals(0f, turned.second, 0.5f)
    }
}
