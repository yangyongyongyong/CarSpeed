package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentStillShotsTest {
    @Test
    fun picks_the_first_shot_at_or_after_the_spoken_time() {
        val shots = RecentStillShots(keepMs = 6_000)
        shots.add(byteArrayOf(1), 1_000)
        shots.add(byteArrayOf(2), 1_500)
        shots.add(byteArrayOf(3), 2_000)

        assertEquals(2, shots.atOrAfter(1_200)!!.single().toInt())
        assertEquals(2, shots.atOrAfter(1_500)!!.single().toInt())
        assertEquals(1, shots.atOrAfter(500)!!.single().toInt())
        assertNull(shots.atOrAfter(2_001))
    }

    @Test
    fun drops_shots_older_than_the_window_except_the_one_that_covers_the_edge() {
        val shots = RecentStillShots(keepMs = 6_000)
        shots.add(byteArrayOf(1), 0)
        shots.add(byteArrayOf(2), 1_000)
        shots.add(byteArrayOf(3), 8_000)

        assertEquals(2, shots.atOrAfter(500)!!.single().toInt())
        assertNull(shots.atOrAfter(8_001))
    }
}
