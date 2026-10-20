package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayMapLayoutTest {
    @Test
    fun unknown_layout_stays_on_the_split_default() {
        assertEquals(ReplayMapLayout.MODE_SPLIT, ReplayMapLayout.normalizeMode(null))
        assertEquals(ReplayMapLayout.MODE_SPLIT, ReplayMapLayout.normalizeMode("side"))
        assertEquals(ReplayMapLayout.MODE_FLOAT, ReplayMapLayout.normalizeMode(ReplayMapLayout.MODE_FLOAT))
    }

    @Test
    fun float_map_drops_track_text_and_offline_and_shrinks_the_actions() {
        val chrome = ReplayMapLayout.chrome(
            ReplayMapLayout.MODE_FLOAT,
            mapFullscreen = false,
            trackOnly = false
        )
        assertFalse(chrome.showTrackText)
        assertFalse(chrome.showSpeedCaption)
        assertFalse(chrome.showTrackSlider)
        assertFalse(chrome.showOfflineButton)
        assertFalse(chrome.showLayoutButton)
        assertFalse(chrome.showScaleControls)
        assertTrue(chrome.compactActions)
    }

    @Test
    fun split_map_keeps_the_full_action_row_and_track_text() {
        val chrome = ReplayMapLayout.chrome(
            ReplayMapLayout.MODE_SPLIT,
            mapFullscreen = false,
            trackOnly = false
        )
        assertTrue(chrome.showTrackText)
        assertTrue(chrome.showSpeedCaption)
        assertTrue(chrome.showTrackSlider)
        assertTrue(chrome.showOfflineButton)
        assertTrue(chrome.showLayoutButton)
        assertTrue(chrome.showScaleControls)
        assertFalse(chrome.compactActions)
    }

    @Test
    fun map_fullscreen_hides_the_track_text_without_using_the_float_buttons() {
        val chrome = ReplayMapLayout.chrome(
            ReplayMapLayout.MODE_FLOAT,
            mapFullscreen = true,
            trackOnly = false
        )
        assertFalse(chrome.showTrackText)
        assertTrue(chrome.showSpeedCaption)
        assertFalse(chrome.showTrackSlider)
        assertFalse(chrome.compactActions)
        assertFalse(chrome.showLayoutButton)
    }

    @Test
    fun track_alpha_stays_between_clear_and_solid() {
        assertEquals(0, ReplayMapLayout.trackAlphaPercent(-5))
        assertEquals(40, ReplayMapLayout.trackAlphaPercent(40))
        assertEquals(100, ReplayMapLayout.trackAlphaPercent(180))
    }

    @Test
    fun float_window_stays_inside_the_screen_and_keeps_a_minimum_size() {
        val frame = ReplayMapLayout.frame(
            spec = ReplayFloatSpec(-0.2f, 1.4f, 0.1f, 0.9f),
            parentWidth = 1000,
            parentHeight = 800,
            minWidth = 160,
            minHeight = 140
        )
        assertEquals(160, frame.width)
        assertTrue(frame.height in 140..800)
        assertTrue(frame.left >= 0)
        assertTrue(frame.top >= 0)
        assertTrue(frame.left + frame.width <= 1000)
        assertTrue(frame.top + frame.height <= 800)
    }

    @Test
    fun dragging_past_the_edge_stops_at_the_edge() {
        val start = ReplayFloatFrame(left = 20, top = 30, width = 200, height = 150)
        val moved = ReplayMapLayout.moved(start, dx = -100, dy = 900, parentWidth = 400, parentHeight = 300)
        assertEquals(0, moved.left)
        assertEquals(150, moved.top)
        assertEquals(200, moved.width)
    }

    @Test
    fun edges_resize_width_and_height_independently() {
        val start = ReplayFloatFrame(left = 100, top = 80, width = 200, height = 150)
        val wider = ReplayMapLayout.resizedEdges(
            frame = start,
            dx = 40,
            dy = 90,
            edges = setOf(FloatEdge.RIGHT),
            parentWidth = 500,
            parentHeight = 400,
            minWidth = 160,
            minHeight = 140
        )
        assertEquals(100, wider.left)
        assertEquals(240, wider.width)
        assertEquals(150, wider.height)
        val taller = ReplayMapLayout.resizedEdges(
            frame = start,
            dx = 40,
            dy = 30,
            edges = setOf(FloatEdge.BOTTOM),
            parentWidth = 500,
            parentHeight = 400,
            minWidth = 160,
            minHeight = 140
        )
        assertEquals(200, taller.width)
        assertEquals(180, taller.height)
        assertEquals(80, taller.top)
    }

    @Test
    fun left_and_top_edges_move_the_opposite_side_inward() {
        val start = ReplayFloatFrame(left = 100, top = 80, width = 200, height = 160)
        val resized = ReplayMapLayout.resizedEdges(
            frame = start,
            dx = -40,
            dy = -20,
            edges = setOf(FloatEdge.LEFT, FloatEdge.TOP),
            parentWidth = 500,
            parentHeight = 400,
            minWidth = 160,
            minHeight = 140
        )
        assertEquals(60, resized.left)
        assertEquals(240, resized.width)
        assertEquals(60, resized.top)
        assertEquals(180, resized.height)
    }

    @Test
    fun left_edge_keeps_the_minimum_width() {
        val resized = ReplayMapLayout.resizedEdges(
            frame = ReplayFloatFrame(left = 40, top = 20, width = 200, height = 160),
            dx = 80,
            dy = 0,
            edges = setOf(FloatEdge.LEFT),
            parentWidth = 400,
            parentHeight = 300,
            minWidth = 160,
            minHeight = 140
        )
        assertEquals(80, resized.left)
        assertEquals(160, resized.width)
        assertEquals(160, resized.height)
    }

    @Test
    fun corner_touch_is_both_edges_and_the_middle_is_not() {
        assertEquals(setOf(FloatEdge.LEFT, FloatEdge.TOP), ReplayMapLayout.edgesAt(4f, 6f, 300, 200, 24))
        assertEquals(setOf(FloatEdge.RIGHT), ReplayMapLayout.edgesAt(290f, 100f, 300, 200, 24))
        assertEquals(emptySet<FloatEdge>(), ReplayMapLayout.edgesAt(150f, 100f, 300, 200, 24))
    }

    @Test
    fun resizing_cannot_run_off_the_bottom_right() {
        val start = ReplayFloatFrame(left = 300, top = 200, width = 80, height = 80)
        val resized = ReplayMapLayout.resized(
            frame = start,
            dw = 500,
            dh = 500,
            parentWidth = 400,
            parentHeight = 300,
            minWidth = 160,
            minHeight = 140
        )
        assertEquals(100, resized.width)
        assertEquals(100, resized.height)
        assertEquals(300, resized.left)
        assertEquals(200, resized.top)
    }
}
