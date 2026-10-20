package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatWindowTouchTest {
    private val frame = ReplayFloatFrame(left = 100, top = 80, width = 220, height = 160)

    private fun touch() = FloatWindowTouch(edgePx = 24, touchSlop = 8, holdMs = 1_000L)

    @Test
    fun holding_the_middle_for_one_second_starts_dragging() {
        val touch = touch()
        assertFalse(touch.downBody(rawX = 400f, rawY = 500f, frame = frame, timeMs = 0L))
        assertNull(touch.move(400f, 500f, 999L, 800, 600, 160, 140))
        assertFalse(touch.consuming())
        val dragged = touch.move(430f, 540f, 1_000L, 800, 600, 160, 140)
        assertEquals(130, dragged?.left)
        assertEquals(120, dragged?.top)
        assertEquals(220, dragged?.width)
        assertEquals(160, dragged?.height)
        assertTrue(touch.takeDragEntry())
        assertFalse(touch.takeDragEntry())
    }

    @Test
    fun moving_before_one_second_does_not_drag_later() {
        val touch = touch()
        touch.downBody(400f, 500f, frame, 0L)
        assertNull(touch.move(420f, 500f, 200L, 800, 600, 160, 140))
        assertNull(touch.move(460f, 520f, 1_500L, 800, 600, 160, 140))
        assertFalse(touch.up(1_500L))
    }

    @Test
    fun edge_drag_changes_only_that_side() {
        val touch = touch()
        assertTrue(
            touch.down(
                localX = 4f,
                localY = 80f,
                rawX = 300f,
                rawY = 400f,
                viewWidth = 220,
                viewHeight = 160,
                frame = frame,
                timeMs = 0L
            )
        )
        val resized = touch.move(260f, 470f, 40L, 800, 600, 160, 140)
        assertEquals(60, resized?.left)
        assertEquals(260, resized?.width)
        assertEquals(80, resized?.top)
        assertEquals(160, resized?.height)
        assertTrue(touch.up(80L))
    }

    @Test
    fun releasing_after_one_second_without_moving_does_not_count_as_a_tap() {
        val touch = touch()
        touch.downBody(400f, 500f, frame, 0L)
        assertTrue(touch.up(1_000L))
    }
}
