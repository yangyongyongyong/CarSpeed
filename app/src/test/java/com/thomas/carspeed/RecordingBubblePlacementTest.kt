package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBubblePlacementTest {
    @Test
    fun default_position_sits_at_the_top_end() {
        assertEquals(
            880 to 96,
            RecordingBubblePlacement.defaultPosition(
                viewWidth = 180,
                viewHeight = 48,
                screenWidth = 1080,
                screenHeight = 2400,
                marginPx = 20,
                topInsetPx = 76
            )
        )
    }

    @Test
    fun clamp_keeps_a_dragged_bubble_on_screen() {
        assertEquals(
            0 to 0,
            RecordingBubblePlacement.clamp(-40, -10, 180, 48, 1080, 2400)
        )
        assertEquals(
            900 to 2352,
            RecordingBubblePlacement.clamp(2000, 3000, 180, 48, 1080, 2400)
        )
    }
}
