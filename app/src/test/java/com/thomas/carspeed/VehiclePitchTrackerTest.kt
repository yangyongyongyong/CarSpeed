package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePitchTrackerTest {
    @Test
    fun pitch_stays_unknown_until_forward_is_learned() {
        val tracker = VehiclePitchTracker()
        val estimate = tracker.update(level(timeMs = 0L))
        assertNull(estimate.pitchDegrees)
        assertFalse(estimate.forwardKnown)
    }

    @Test
    fun level_acceleration_learns_forward_and_reports_flat_pitch() {
        val tracker = learnForward()
        val estimate = tracker.update(level(timeMs = 2_000L))
        assertTrue(estimate.forwardKnown)
        assertEquals(0f, estimate.pitchDegrees!!, 0.2f)
        assertFalse(estimate.phoneMoved)
    }

    @Test
    fun nose_up_gyro_increases_pitch() {
        val tracker = learnForward()
        var estimate = tracker.update(level(timeMs = 2_000L))
        repeat(50) { step ->
            estimate = tracker.update(
                level(timeMs = 2_020L + step * 20L).copy(
                    gx = 0.1f,
                    speedBraking = true
                )
            )
        }
        assertEquals(Math.toDegrees(0.1).toFloat(), estimate.pitchDegrees!!, 0.6f)
    }

    @Test
    fun braking_does_not_fake_a_grade() {
        val tracker = learnForward()
        var estimate = tracker.update(level(timeMs = 2_000L))
        repeat(20) { step ->
            estimate = tracker.update(
                ImuSample(
                    timeMs = 2_100L + step * 50L,
                    ax = 0f,
                    ay = -4f,
                    az = VehiclePitchTracker.GRAVITY_MPS2,
                    speedBraking = true
                )
            )
        }
        assertEquals(0f, estimate.pitchDegrees!!, 0.3f)
        assertTrue(estimate.forwardKnown)
    }

    @Test
    fun braking_without_speed_flag_still_does_not_fake_a_grade() {
        val tracker = learnForward()
        var estimate = tracker.update(level(timeMs = 2_000L))
        repeat(40) { step ->
            estimate = tracker.update(
                ImuSample(
                    timeMs = 2_100L + step * 50L,
                    ax = 0f,
                    ay = -1.5f,
                    az = VehiclePitchTracker.GRAVITY_MPS2
                )
            )
        }
        assertEquals(0f, estimate.pitchDegrees!!, 0.3f)
    }

    @Test
    fun sudden_rotation_marks_phone_moved_and_forgets_forward() {
        val tracker = learnForward()
        val estimate = tracker.update(
            level(timeMs = 2_000L).copy(gx = 4f)
        )
        assertTrue(estimate.phoneMoved)
        assertFalse(estimate.forwardKnown)
        assertNull(estimate.pitchDegrees)
    }

    private fun learnForward(): VehiclePitchTracker {
        val tracker = VehiclePitchTracker()
        tracker.update(level(timeMs = 0L))
        repeat(6) { step ->
            tracker.update(
                ImuSample(
                    timeMs = 100L + step * 50L,
                    ax = 0f,
                    ay = 1.2f,
                    az = VehiclePitchTracker.GRAVITY_MPS2,
                    speedIncreasing = true
                )
            )
        }
        return tracker
    }

    private fun level(timeMs: Long): ImuSample {
        return ImuSample(
            timeMs = timeMs,
            ax = 0f,
            ay = 0f,
            az = VehiclePitchTracker.GRAVITY_MPS2
        )
    }
}
