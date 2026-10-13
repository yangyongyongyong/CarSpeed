package com.thomas.carspeed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteTunnelTrackerTest {
    @Test
    fun drop_from_a_healthy_count_is_scarce() {
        val tracker = SatelliteTunnelTracker()
        tracker.update(0L, 12)
        tracker.update(1_000L, 0)
        assertTrue(tracker.scarce)
        tracker.update(2_000L, SatelliteTunnelTracker.SCARCE_COUNT)
        assertTrue(tracker.scarce)
    }

    @Test
    fun signal_loss_after_a_healthy_count_is_scarce() {
        val tracker = SatelliteTunnelTracker()
        tracker.update(0L, 8)
        tracker.update(2_000L, null)
        assertTrue(tracker.scarce)
    }

    @Test
    fun never_healthy_or_slow_decline_is_not_a_tunnel() {
        val cold = SatelliteTunnelTracker()
        cold.update(0L, 0)
        cold.update(1_000L, null)
        assertFalse(cold.scarce)

        val slow = SatelliteTunnelTracker()
        slow.update(0L, 10)
        slow.update(SatelliteTunnelTracker.RECENT_HEALTHY_MS + 1L, 1)
        assertFalse(slow.scarce)
    }

    @Test
    fun four_satellites_is_not_scarce_and_six_clears_it() {
        val tracker = SatelliteTunnelTracker()
        tracker.update(0L, 9)
        tracker.update(1_000L, 4)
        assertFalse(tracker.scarce)
        tracker.update(2_000L, 0)
        assertTrue(tracker.scarce)
        tracker.update(3_000L, SatelliteTunnelTracker.HEALTHY_COUNT)
        assertFalse(tracker.scarce)
    }

    @Test
    fun satellite_evidence_can_mark_gps_weak_without_a_previous_fix() {
        assertTrue(
            tunnelGpsWeak(
                hadGoodFix = false,
                accuracyWeak = false,
                fixStale = false,
                satelliteScarce = true,
                skyConfirmsGround = false,
                satelliteTunnelEnabled = true
            )
        )
    }

    @Test
    fun switch_off_ignores_satellites_but_keeps_accuracy_evidence() {
        assertFalse(
            tunnelGpsWeak(
                hadGoodFix = true,
                accuracyWeak = false,
                fixStale = false,
                satelliteScarce = true,
                skyConfirmsGround = false,
                satelliteTunnelEnabled = false
            )
        )
        assertTrue(
            tunnelGpsWeak(
                hadGoodFix = true,
                accuracyWeak = true,
                fixStale = false,
                satelliteScarce = false,
                skyConfirmsGround = false,
                satelliteTunnelEnabled = false
            )
        )
    }

    @Test
    fun stable_count_or_strength_means_ground_not_tunnel() {
        val countStable = SatelliteTunnelTracker()
        countStable.update(0L, 12, 30f)
        countStable.update(1_000L, 10, 18f)
        assertTrue(countStable.skyConfirmsGround)
        assertFalse(countStable.scarce)

        val strengthStable = SatelliteTunnelTracker()
        strengthStable.update(0L, 12, 32f)
        strengthStable.update(1_000L, 1, 30f)
        assertTrue(strengthStable.skyConfirmsGround)
        assertFalse(strengthStable.scarce)

        assertFalse(
            tunnelGpsWeak(
                hadGoodFix = true,
                accuracyWeak = true,
                fixStale = true,
                satelliteScarce = true,
                skyConfirmsGround = true,
                satelliteTunnelEnabled = true
            )
        )
    }
}
