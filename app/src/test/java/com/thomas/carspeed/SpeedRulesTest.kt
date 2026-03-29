package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRulesTest {

    @Test
    fun low_then_zero_wait_then_high_speed_is_non_zero_and_wait_kept() {
        var state = SpeedRules.WaitState(
            tripStarted = false,
            tripStartTimeMs = 0L,
            waitAtZeroAccumMs = 0L,
            waitAtZeroStartMs = 0L
        )

        // 低速 > 10km/h（例如 5m/s=18km/h）触发开始驾驶
        var speedKmh = SpeedRules.toKmhClamped(5f)
        state = SpeedRules.updateTripAndWait(state, speedKmh, nowMs = 1_000L)
        assertTrue(state.tripStarted)

        // 速度=0 等待4秒
        speedKmh = SpeedRules.toKmhClamped(0f)
        state = SpeedRules.updateTripAndWait(state, speedKmh, nowMs = 2_000L)
        val waitAfter4s = SpeedRules.currentWaitDurationMs(state, nowMs = 6_000L)
        assertEquals(4_000L, waitAfter4s)

        // 给高速后当前速度应 > 0，且等待时长冻结
        speedKmh = SpeedRules.toKmhClamped(20f) // 72km/h
        state = SpeedRules.updateTripAndWait(state, speedKmh, nowMs = 7_000L)
        val waitFrozen = SpeedRules.currentWaitDurationMs(state, nowMs = 8_000L)
        assertEquals(5_000L, waitFrozen)
        assertTrue(speedKmh > 0f)
    }

    @Test
    fun poor_gps_results_zero_speed() {
        val speed = SpeedRules.selectSpeedMps(
            gpsSpeedMps = 0f,
            hasGpsSpeed = true,
            computedSpeedMps = Float.NaN
        )
        assertEquals(0f, speed)
    }

    @Test
    fun computed_speed_should_win_when_gps_is_zero() {
        val speed = SpeedRules.selectSpeedMps(
            gpsSpeedMps = 0f,
            hasGpsSpeed = true,
            computedSpeedMps = 25f
        )
        assertEquals(25f, speed)
    }

    @Test
    fun stabilize_speed_should_not_freeze_to_zero_immediately_after_short_drop() {
        var st = SpeedRules.SpeedFilterState()

        st = SpeedRules.stabilizeSpeedMps(st, rawSpeedMps = 30f, nowMs = 1_000L)
        assertTrue(st.currentSpeedMps > 0f)

        // 仅过 1 秒掉到 0，不应瞬间归零
        st = SpeedRules.stabilizeSpeedMps(st, rawSpeedMps = 0f, nowMs = 2_000L)
        assertTrue(st.currentSpeedMps > 0f)

        // 超过 grace 再给 0，应归零
        st = SpeedRules.stabilizeSpeedMps(st, rawSpeedMps = 0f, nowMs = 5_000L)
        assertEquals(0f, st.currentSpeedMps)
    }


    @Test
    fun stale_gap_should_force_zero() {
        val lastNs = 1_000_000_000L
        val nowNs = lastNs + 4_500_000_000L // 4500ms
        val shouldZero = SpeedRules.shouldForceZeroOnStale(
            lastReceiveRealtimeNs = lastNs,
            nowRealtimeNs = nowNs,
            staleTimeoutMs = 3_000L
        )
        assertTrue(shouldZero)
    }

    @Test
    fun reacquire_after_long_gap_should_enter_warmup() {
        val lastNs = 10_000_000_000L
        val nowNs = lastNs + 6_000_000_000L // 6000ms
        val shouldWarmup = SpeedRules.shouldEnterReacquireWarmup(
            lastReceiveRealtimeNs = lastNs,
            nowRealtimeNs = nowNs,
            reacquireGapMs = 5_000L
        )
        assertTrue(shouldWarmup)
    }


    @Test
    fun wait_clock_should_freeze_when_gps_lost() {
        val fixed = SpeedRules.resolveWaitClockNowMs(
            gpsSignalLost = true,
            lastGpsFixWallTimeMs = 10_000L,
            nowMs = 20_000L
        )
        assertEquals(10_000L, fixed)

        val normal = SpeedRules.resolveWaitClockNowMs(
            gpsSignalLost = false,
            lastGpsFixWallTimeMs = 10_000L,
            nowMs = 20_000L
        )
        assertEquals(20_000L, normal)
    }

    @Test
    fun max_speed_and_timestamp_update_when_new_peak() {
        val first = SpeedRules.updateMaxSpeed(
            oldMaxSpeedKmh = 10f,
            oldMaxSpeedTimeMs = 1000L,
            currentSpeedKmh = 30f,
            nowMs = 5000L
        )
        assertEquals(30f, first.maxSpeedKmh)
        assertEquals(5000L, first.maxSpeedTimeMs)

        val second = SpeedRules.updateMaxSpeed(
            oldMaxSpeedKmh = first.maxSpeedKmh,
            oldMaxSpeedTimeMs = first.maxSpeedTimeMs,
            currentSpeedKmh = 20f,
            nowMs = 9000L
        )
        assertEquals(30f, second.maxSpeedKmh)
        assertEquals(5000L, second.maxSpeedTimeMs)
    }

    @Test
    fun max_speed_is_capped_to_9999() {
        val result = SpeedRules.updateMaxSpeed(
            oldMaxSpeedKmh = 100f,
            oldMaxSpeedTimeMs = 1L,
            currentSpeedKmh = 20000f,
            nowMs = 2L
        )
        assertEquals(9999f, result.maxSpeedKmh)
    }

    @Test
    fun stationary_drift_with_poor_accuracy_should_lock_to_zero() {
        var state = SpeedRules.SpeedFilterState()

        val first = SpeedRules.processLocationSample(
            old = state,
            sample = sample(
                computedSpeedMps = 18f,
                accuracyMeters = 45f,
                distanceMeters = 9f,
                deltaTimeSec = 1f,
                linearAccelMps2 = 0.05f,
                nowMs = 1_000L
            )
        )
        state = first.nextFilterState

        val second = SpeedRules.processLocationSample(
            old = state,
            sample = sample(
                computedSpeedMps = 20f,
                accuracyMeters = 42f,
                distanceMeters = 10f,
                deltaTimeSec = 1f,
                linearAccelMps2 = 0.08f,
                nowMs = 2_000L
            )
        )

        assertEquals(0f, second.finalSpeedMps)
        assertTrue(second.isStationaryLocked)
        assertFalse(second.allowUpdateMaxSpeed)
    }

    @Test
    fun jump_point_while_stationary_should_be_rejected() {
        val locked = lockStationary()

        val result = SpeedRules.processLocationSample(
            old = locked,
            sample = sample(
                computedSpeedMps = 60f,
                accuracyMeters = 20f,
                distanceMeters = 45f,
                deltaTimeSec = 1f,
                linearAccelMps2 = 0.1f,
                nowMs = 3_000L
            )
        )

        assertEquals(0f, result.finalSpeedMps)
        assertTrue(result.isStationaryLocked)
        assertFalse(result.isSampleTrusted)
        assertFalse(result.allowUpdateMaxSpeed)
    }

    @Test
    fun real_motion_after_stationary_requires_consecutive_samples() {
        var state = lockStationary()

        val first = SpeedRules.processLocationSample(
            old = state,
            sample = sample(
                gpsSpeedMps = 3.5f,
                hasGpsSpeed = true,
                accuracyMeters = 6f,
                speedAccuracyMps = 0.6f,
                distanceMeters = 14f,
                deltaTimeSec = 1f,
                linearAccelMps2 = 1.1f,
                nowMs = 3_000L
            )
        )
        assertEquals(0f, first.finalSpeedMps)
        assertTrue(first.isStationaryLocked)

        state = first.nextFilterState
        val second = SpeedRules.processLocationSample(
            old = state,
            sample = sample(
                gpsSpeedMps = 4.2f,
                hasGpsSpeed = true,
                accuracyMeters = 5f,
                speedAccuracyMps = 0.4f,
                distanceMeters = 16f,
                deltaTimeSec = 1f,
                linearAccelMps2 = 1.3f,
                nowMs = 4_000L
            )
        )

        assertFalse(second.isStationaryLocked)
        assertTrue(second.finalSpeedMps > 0f)
        assertTrue(second.allowUpdateMaxSpeed)
    }

    @Test
    fun moving_state_should_not_drop_to_zero_immediately_on_bad_fix() {
        val moving = SpeedRules.SpeedFilterState(
            currentSpeedMps = 10f,
            lastNonZeroSpeedMps = 10f,
            lastNonZeroTimeMs = 1_000L,
            lastTrustedSpeedMps = 10f,
            lastTrustedTimeMs = 1_000L
        )

        val result = SpeedRules.processLocationSample(
            old = moving,
            sample = sample(
                gpsSpeedMps = 12f,
                hasGpsSpeed = true,
                accuracyMeters = 75f,
                speedAccuracyMps = 9f,
                distanceMeters = 50f,
                deltaTimeSec = 1f,
                linearAccelMps2 = 0.1f,
                nowMs = 2_000L
            )
        )

        assertFalse(result.isStationaryLocked)
        assertTrue(result.finalSpeedMps > 0f)
    }

    @Test
    fun tunnel_estimate_should_continue_after_gps_loss() {
        val state = SpeedRules.SpeedFilterState(
            currentSpeedMps = 16f,
            lastNonZeroSpeedMps = 16f,
            lastNonZeroTimeMs = 9_000L,
            lastTrustedSpeedMps = 16f,
            lastTrustedTimeMs = 9_000L,
            lastTrustedGpsSpeedMps = 16f,
            lastTrustedGpsTimeMs = 9_000L,
            lastEstimateTimeMs = 9_000L
        )

        val result = SpeedRules.estimateTunnelSpeed(
            old = state,
            nowMs = 10_000L,
            linearAccelMps2 = 0.03f,
            gpsSignalLost = true
        )

        assertTrue(result.isEstimated)
        assertTrue(result.finalSpeedMps > 0f)
        assertEquals(SpeedRules.EstimateStatus.ESTIMATE, result.estimateStatus)
        assertFalse(result.allowUpdateMaxSpeed)
    }

    @Test
    fun tunnel_estimate_should_decay_under_low_motion() {
        var state = SpeedRules.SpeedFilterState(
            currentSpeedMps = 12f,
            lastNonZeroSpeedMps = 12f,
            lastNonZeroTimeMs = 0L,
            lastTrustedSpeedMps = 12f,
            lastTrustedTimeMs = 0L,
            tunnelModeActive = true,
            estimatedSpeedMps = 12f,
            lastTrustedGpsSpeedMps = 12f,
            lastTrustedGpsTimeMs = 0L,
            lastEstimateTimeMs = 0L,
            estimateConfidence = 1f,
            estimateStatus = SpeedRules.EstimateStatus.ESTIMATE
        )

        repeat(8) { idx ->
            state = SpeedRules.estimateTunnelSpeed(
                old = state,
                nowMs = (idx + 1) * 1_000L,
                linearAccelMps2 = 0.01f,
                gpsSignalLost = true
            ).nextFilterState
        }

        assertTrue(state.currentSpeedMps < 12f)
        assertTrue(state.lowMotionSampleCount >= 4)
    }

    @Test
    fun tunnel_estimate_should_eventually_reach_decay_mode() {
        var state = SpeedRules.SpeedFilterState(
            currentSpeedMps = 10f,
            lastNonZeroSpeedMps = 10f,
            lastNonZeroTimeMs = 0L,
            lastTrustedSpeedMps = 10f,
            lastTrustedTimeMs = 0L,
            tunnelModeActive = true,
            estimatedSpeedMps = 10f,
            lastTrustedGpsSpeedMps = 10f,
            lastTrustedGpsTimeMs = 0L,
            lastEstimateTimeMs = 0L,
            estimateConfidence = 0.2f,
            lowMotionSampleCount = 3,
            estimateStatus = SpeedRules.EstimateStatus.ESTIMATE
        )

        val result = SpeedRules.estimateTunnelSpeed(
            old = state,
            nowMs = 10_000L,
            linearAccelMps2 = 0.0f,
            gpsSignalLost = true
        )

        assertEquals(SpeedRules.EstimateStatus.DECAY, result.estimateStatus)
        assertTrue(result.estimateConfidence < 0.2f)
    }

    @Test
    fun tunnel_estimate_should_not_start_without_recent_trusted_gps() {
        val state = SpeedRules.SpeedFilterState(
            currentSpeedMps = 8f,
            lastTrustedGpsSpeedMps = 8f,
            lastTrustedGpsTimeMs = 1_000L,
            lastEstimateTimeMs = 1_000L
        )

        val result = SpeedRules.estimateTunnelSpeed(
            old = state,
            nowMs = 8_000L,
            linearAccelMps2 = 0.0f,
            gpsSignalLost = true
        )

        assertFalse(result.isEstimated)
    }

    private fun lockStationary(): SpeedRules.SpeedFilterState {
        var state = SpeedRules.SpeedFilterState()
        repeat(2) { idx ->
            state = SpeedRules.processLocationSample(
                old = state,
                sample = sample(
                    computedSpeedMps = 15f,
                    accuracyMeters = 40f,
                    distanceMeters = 8f,
                    deltaTimeSec = 1f,
                    linearAccelMps2 = 0.05f,
                    nowMs = 1_000L + idx * 1_000L
                )
            ).nextFilterState
        }
        return state
    }

    private fun sample(
        gpsSpeedMps: Float = Float.NaN,
        hasGpsSpeed: Boolean = false,
        computedSpeedMps: Float = Float.NaN,
        accuracyMeters: Float = 10f,
        speedAccuracyMps: Float? = null,
        distanceMeters: Float? = null,
        deltaTimeSec: Float? = null,
        linearAccelMps2: Float? = null,
        inWarmup: Boolean = false,
        nowMs: Long = 0L
    ): SpeedRules.LocationSample {
        return SpeedRules.LocationSample(
            gpsSpeedMps = gpsSpeedMps,
            hasGpsSpeed = hasGpsSpeed,
            computedSpeedMps = computedSpeedMps,
            accuracyMeters = accuracyMeters,
            speedAccuracyMps = speedAccuracyMps,
            distanceMeters = distanceMeters,
            deltaTimeSec = deltaTimeSec,
            linearAccelMps2 = linearAccelMps2,
            inWarmup = inWarmup,
            nowMs = nowMs
        )
    }
}
