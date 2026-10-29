package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRotationAnchorTest {
    @Test
    fun quarter_turn_swaps_the_map_view_to_the_parent_cross_size() {
        assertEquals(400 to 800, MapRotationAnchor.targetSize(800, 400, 90))
        assertEquals(400 to 800, MapRotationAnchor.targetSize(800, 400, 270))
        assertEquals(800 to 400, MapRotationAnchor.targetSize(800, 400, 0))
    }

    @Test
    fun anchor_waits_until_the_measured_size_matches() {
        assertFalse(MapRotationAnchor.readyToAnchor(0, 0, 800, 400))
        assertFalse(MapRotationAnchor.readyToAnchor(800, 400, 400, 800))
        assertTrue(MapRotationAnchor.readyToAnchor(400, 800, 400, 800))
        assertFalse(MapRotationAnchor.readyToAnchor(3, 3, 2, 3))
    }
}
