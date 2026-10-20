package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFramePickTest {
    @Test
    fun picks_the_first_frame_at_or_after_the_target_and_starts_at_a_keyframe() {
        val elapsed = longArrayOf(1_000, 1_033, 1_066, 1_100, 1_133)
        val keys = booleanArrayOf(true, false, false, false, false)

        val between = VideoFramePick.window(elapsed, keys, 1_050)
        assertEquals(0, between!!.startIndex)
        assertEquals(2, between.matchIndex)
        assertEquals(1_066, elapsed[between.matchIndex])

        val exact = VideoFramePick.window(elapsed, keys, 1_100)
        assertEquals(3, exact!!.matchIndex)
        assertEquals(1_100, elapsed[exact.matchIndex])
    }

    @Test
    fun target_after_the_last_frame_has_no_later_picture() {
        val elapsed = longArrayOf(1_000, 1_033)
        val keys = booleanArrayOf(true, false)
        assertNull(VideoFramePick.window(elapsed, keys, 1_034))
    }

    @Test
    fun target_before_the_buffer_uses_the_earliest_later_frame() {
        val elapsed = longArrayOf(4_000, 4_033)
        val keys = booleanArrayOf(true, false)
        val window = VideoFramePick.window(elapsed, keys, 1_000)
        assertEquals(0, window!!.matchIndex)
    }

    @Test
    fun cannot_decode_without_a_keyframe() {
        val elapsed = longArrayOf(1_000, 1_033)
        val keys = booleanArrayOf(false, false)
        assertNull(VideoFramePick.window(elapsed, keys, 1_000))
        assertNull(VideoFramePick.window(longArrayOf(), booleanArrayOf(), 0))
    }

    @Test
    fun ring_keeps_a_keyframe_and_returns_the_frame_after_the_spoken_time() {
        val frames = RecentVideoFrames(keepMs = 6_000)
        frames.setFormat(byteArrayOf(1), null, 1920, 1080)
        for (time in 0L..3_000L step 100L) {
            frames.add(byteArrayOf(time.toInt().toByte()), time, keyframe = time % 1_000L == 0L)
        }

        val selection = frames.selectionAtOrAfter(1_050)
        assertEquals(1_000L, selection!!.samples.first().elapsedMs)
        assertEquals(true, selection.samples.first().keyframe)
        assertEquals(1_100L, selection.samples.first { it.elapsedMs >= 1_050L }.elapsedMs)
        assertEquals(1_050L, selection.targetElapsedMs)
    }

    @Test
    fun ring_drops_frames_older_than_the_window_but_still_picks_the_nearest_later_one() {
        val frames = RecentVideoFrames(keepMs = 6_000)
        frames.setFormat(byteArrayOf(1), null, 1280, 720)
        for (time in 0L..10_000L step 100L) {
            frames.add(byteArrayOf(1), time, keyframe = time % 1_000L == 0L)
        }

        val selection = frames.selectionAtOrAfter(1_000)
        val earliest = selection!!.samples.first().elapsedMs
        assertEquals(true, earliest >= 4_000L)
        assertEquals(true, selection.samples.first().keyframe)
        assertEquals(earliest, selection.samples.first { it.elapsedMs >= 1_000L }.elapsedMs)
        assertNull(frames.selectionAtOrAfter(10_001))
    }
}
