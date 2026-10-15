package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Test

class GnssSatelliteCountTest {
    @Test
    fun counts_only_satellites_used_in_the_fix() {
        assertEquals(2, GnssSatelliteCount.usedInFix(4) { index -> index == 0 || index == 3 })
    }

    @Test
    fun empty_or_unused_constellation_is_zero() {
        assertEquals(0, GnssSatelliteCount.usedInFix(0) { true })
        assertEquals(0, GnssSatelliteCount.usedInFix(5) { false })
    }

    @Test
    fun all_visible_satellites_can_be_used() {
        assertEquals(3, GnssSatelliteCount.usedInFix(3) { true })
    }

    @Test
    fun mean_cn0_uses_only_satellites_in_the_fix() {
        val mean = GnssSatelliteCount.meanCn0DbHz(
            satelliteCount = 3,
            usedInFix = { index -> index != 1 },
            cn0DbHz = { index -> if (index == 0) 20f else 40f }
        )
        assertEquals(30f, mean!!, 0.01f)
        assertEquals(null, GnssSatelliteCount.meanCn0DbHz(2, { false }, { 20f }))
    }
}
