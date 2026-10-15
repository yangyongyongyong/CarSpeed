package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadLayerResolverTest {
    @Test
    fun unknown_layer_uses_configured_default() {
        val elevated = RoadLayerResolver().update(sample(0L, 0f), RoadLayerConfig(unknownDefaultsToElevated = true))
        assertEquals(RoadLayer.UNKNOWN, elevated.detectedLayer)
        assertEquals(RoadLayer.ELEVATED, elevated.alertLayer)
        assertTrue(elevated.usingDefault)

        val ground = RoadLayerResolver().update(sample(0L, 0f), RoadLayerConfig(unknownDefaultsToElevated = false))
        assertEquals(RoadLayer.GROUND, ground.alertLayer)
        assertTrue(ground.usingDefault)
    }

    @Test
    fun stable_pitch_locks_flat_baseline() {
        val decision = feed(RoadLayerResolver(), 0L, RoadLayerRules.BASELINE_HOLD_MS, 0.2f)
        assertEquals(PitchPhase.FLAT, decision.phase)
        assertEquals(RoadLayer.UNKNOWN, decision.detectedLayer)
        assertTrue(decision.usingDefault)
    }

    @Test
    fun drifting_pitch_does_not_lock_baseline() {
        val resolver = RoadLayerResolver()
        var decision = resolver.update(sample(0L, 0f))
        var time = 1_000L
        while (time <= 45_000L) {
            decision = resolver.update(sample(time, time / 1_000f * 0.1f))
            time += 1_000L
        }
        assertEquals(PitchPhase.COLLECTING_BASELINE, decision.phase)
        assertTrue(decision.usingDefault)
    }

    @Test
    fun climb_then_relevel_locks_elevated_even_if_default_is_ground() {
        val decision = climb(RoadLayerResolver(), RoadLayerConfig(unknownDefaultsToElevated = false))
        assertEquals(RoadLayer.ELEVATED, decision.detectedLayer)
        assertEquals(RoadLayer.ELEVATED, decision.alertLayer)
        assertFalse(decision.usingDefault)
        assertEquals(PitchPhase.FLAT, decision.phase)
        assertEquals(RoadLayerLabels.ON_BRIDGE, RoadLayerLabels.subtitle(decision, amapOverlap = false))
    }

    @Test
    fun descent_then_relevel_locks_ground() {
        val decision = descend(RoadLayerResolver())
        assertEquals(RoadLayer.GROUND, decision.detectedLayer)
        assertEquals(RoadLayer.GROUND, decision.alertLayer)
        assertFalse(decision.usingDefault)
        assertEquals(RoadLayerLabels.UNDER_BRIDGE, RoadLayerLabels.subtitle(decision, amapOverlap = false))
    }

    @Test
    fun descent_into_weak_gps_locks_tunnel() {
        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f)
        feed(resolver, 30_000L, 38_000L, -2.5f, gpsWeak = true)
        val decision = feed(resolver, 38_000L, 53_000L, 0f, gpsWeak = true)
        assertEquals(RoadLayer.TUNNEL, decision.detectedLayer)
        assertEquals(RoadLayerLabels.TUNNEL, RoadLayerLabels.subtitle(decision, amapOverlap = false))
    }

    @Test
    fun weak_gps_on_lower_road_becomes_tunnel_until_fix_returns() {
        val resolver = RoadLayerResolver()
        val ground = descend(resolver)
        assertEquals(RoadLayer.GROUND, ground.detectedLayer)
        val tunnel = feed(resolver, 53_000L, 56_000L, 0f, gpsWeak = true)
        assertEquals(RoadLayer.TUNNEL, tunnel.detectedLayer)
        val back = feed(resolver, 56_000L, 71_000L, 0f, gpsWeak = false)
        assertEquals(RoadLayer.GROUND, back.detectedLayer)
        assertEquals(RoadLayerLabels.UNDER_BRIDGE, RoadLayerLabels.subtitle(back, amapOverlap = false))
    }

    @Test
    fun elevated_road_stays_elevated_when_gps_goes_weak() {
        val resolver = RoadLayerResolver()
        val elevated = climb(resolver)
        assertEquals(RoadLayer.ELEVATED, elevated.detectedLayer)
        val decision = feed(resolver, 53_000L, 70_000L, 0f, gpsWeak = true)
        assertEquals(RoadLayer.ELEVATED, decision.detectedLayer)
    }

    @Test
    fun subtitle_stays_hidden_until_overlap_is_known() {
        val unknown = RoadLayerResolver().update(sample(0L, 0f))
        assertNull(RoadLayerLabels.subtitle(unknown, amapOverlap = false))
        assertEquals(RoadLayerLabels.ON_BRIDGE, RoadLayerLabels.subtitle(unknown, amapOverlap = true))
        val groundDefault = RoadLayerResolver().update(sample(0L, 0f), RoadLayerConfig(unknownDefaultsToElevated = false))
        assertEquals(RoadLayerLabels.UNDER_BRIDGE, RoadLayerLabels.subtitle(groundDefault, amapOverlap = true))
    }

    @Test
    fun second_climb_stays_on_single_elevated_layer() {
        val resolver = RoadLayerResolver()
        climb(resolver)
        val decision = climb(resolver, startMs = 60_000L)
        assertEquals(RoadLayer.ELEVATED, decision.detectedLayer)
    }

    @Test
    fun descent_from_elevated_returns_to_ground() {
        val resolver = RoadLayerResolver()
        climb(resolver)
        val decision = descend(resolver, startMs = 60_000L)
        assertEquals(RoadLayer.GROUND, decision.detectedLayer)
        assertFalse(decision.usingDefault)
    }

    @Test
    fun short_tilt_does_not_change_layer() {
        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f)
        feed(resolver, 30_000L, 33_000L, 2.5f)
        val decision = feed(resolver, 33_000L, 40_000L, 0f)
        assertEquals(RoadLayer.UNKNOWN, decision.detectedLayer)
        assertEquals(PitchPhase.FLAT, decision.phase)
    }

    @Test
    fun slope_that_never_returns_does_not_switch() {
        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f)
        val decision = feed(resolver, 30_000L, 90_000L, 3f)
        assertEquals(RoadLayer.UNKNOWN, decision.detectedLayer)
        assertEquals(PitchPhase.ON_SLOPE, decision.phase)
        assertEquals(RoadLayer.ELEVATED, decision.alertLayer)
    }

    @Test
    fun sign_flip_restarts_slope_hold() {
        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f)
        feed(resolver, 30_000L, 34_000L, 2.5f)
        val stillFlat = resolver.update(sample(34_000L, -2.5f))
        assertEquals(PitchPhase.FLAT, stillFlat.phase)
        feed(resolver, 34_000L, 42_000L, -2.5f)
        val decision = feed(resolver, 42_000L, 57_000L, 0f)
        assertEquals(RoadLayer.GROUND, decision.detectedLayer)
    }

    @Test
    fun interrupted_relevel_waits_for_a_full_flat_hold() {
        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f)
        feed(resolver, 30_000L, 38_000L, 2.5f)
        val early = feed(resolver, 38_000L, 48_000L, 0f)
        assertEquals(RoadLayer.UNKNOWN, early.detectedLayer)
        assertEquals(PitchPhase.CONFIRMING_LEVEL, early.phase)
        resolver.update(sample(48_000L, 2.5f))
        val decision = feed(resolver, 49_000L, 64_000L, 0f)
        assertEquals(RoadLayer.ELEVATED, decision.detectedLayer)
    }

    @Test
    fun sample_gap_drops_unfinished_slope() {
        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f)
        resolver.update(sample(31_000L, 2.5f))
        val decision = resolver.update(sample(36_000L, 2.5f))
        assertEquals(PitchPhase.COLLECTING_BASELINE, decision.phase)
        assertEquals(RoadLayer.UNKNOWN, decision.detectedLayer)
        assertTrue(decision.usingDefault)
    }

    @Test
    fun pitch_recognition_off_keeps_the_default_layer_through_a_ramp() {
        val config = RoadLayerConfig(
            unknownDefaultsToElevated = false,
            pitchRecognitionEnabled = false
        )
        val climbed = climb(RoadLayerResolver(), config)
        assertEquals(RoadLayer.UNKNOWN, climbed.detectedLayer)
        assertEquals(RoadLayer.GROUND, climbed.alertLayer)
        assertTrue(climbed.usingDefault)

        val resolver = RoadLayerResolver()
        feed(resolver, 0L, RoadLayerRules.BASELINE_HOLD_MS, 0f, config)
        feed(resolver, 30_000L, 38_000L, -2.5f, config, gpsWeak = true)
        val descended = feed(resolver, 38_000L, 53_000L, 0f, config, gpsWeak = true)
        assertEquals(RoadLayer.UNKNOWN, descended.detectedLayer)
        assertEquals(RoadLayer.GROUND, descended.alertLayer)
    }

    @Test
    fun turning_pitch_recognition_off_drops_the_slope_layer() {
        val resolver = RoadLayerResolver()
        val detected = climb(resolver)
        assertEquals(RoadLayer.ELEVATED, detected.detectedLayer)
        val disabled = resolver.update(
            sample(80_000L, 0f),
            RoadLayerConfig(pitchRecognitionEnabled = false)
        )
        assertEquals(RoadLayer.UNKNOWN, disabled.detectedLayer)
        assertEquals(RoadLayer.ELEVATED, disabled.alertLayer)
        assertTrue(disabled.usingDefault)
        assertFalse(disabled.correctedByDivergence)
    }

    @Test
    fun divergence_correction_stays_when_pitch_recognition_is_off() {
        val resolver = RoadLayerResolver()
        resolver.update(sample(0L, 0f))
        val config = RoadLayerConfig(pitchRecognitionEnabled = false, unknownDefaultsToElevated = true)
        val corrected = resolver.applyCorrection(RoadLayer.GROUND, config)
        assertEquals(RoadLayer.GROUND, corrected.detectedLayer)
        assertTrue(corrected.correctedByDivergence)
        val still = resolver.update(sample(1_000L, 0f), config)
        assertEquals(RoadLayer.GROUND, still.detectedLayer)
        assertTrue(still.correctedByDivergence)
        assertFalse(still.usingDefault)
    }

    @Test
    fun divergence_correction_rewrites_the_assumed_layer_until_the_phone_moves() {
        val resolver = RoadLayerResolver()
        val before = resolver.update(sample(0L, 0f), RoadLayerConfig(unknownDefaultsToElevated = true))
        assertEquals(RoadLayer.UNKNOWN, before.detectedLayer)
        assertTrue(before.usingDefault)

        val corrected = resolver.applyCorrection(RoadLayer.GROUND)
        assertEquals(RoadLayer.GROUND, corrected.detectedLayer)
        assertEquals(RoadLayer.GROUND, corrected.alertLayer)
        assertFalse(corrected.usingDefault)
        assertTrue(corrected.correctedByDivergence)

        val moved = resolver.update(
            RoadAttitudeSample(timeMs = 1_000L, pitchDegrees = 0f, phoneMoved = true)
        )
        assertEquals(RoadLayer.UNKNOWN, moved.detectedLayer)
        assertFalse(moved.correctedByDivergence)
        assertTrue(moved.usingDefault)
    }

    @Test
    fun phone_moved_returns_to_default_layer() {
        val resolver = RoadLayerResolver()
        climb(resolver)
        val decision = resolver.update(
            RoadAttitudeSample(timeMs = 80_000L, pitchDegrees = 0f, phoneMoved = true),
            RoadLayerConfig(unknownDefaultsToElevated = false)
        )
        assertEquals(RoadLayer.UNKNOWN, decision.detectedLayer)
        assertEquals(RoadLayer.GROUND, decision.alertLayer)
        assertTrue(decision.usingDefault)
        assertEquals(PitchPhase.COLLECTING_BASELINE, decision.phase)
    }

    @Test
    fun small_pitch_noise_can_still_lock_baseline() {
        val resolver = RoadLayerResolver()
        var decision = resolver.update(sample(0L, 0f))
        var time = 1_000L
        while (time <= RoadLayerRules.BASELINE_HOLD_MS) {
            val pitch = if (time % 2_000L == 0L) 0.5f else 0f
            decision = resolver.update(sample(time, pitch))
            time += 1_000L
        }
        assertEquals(PitchPhase.FLAT, decision.phase)
    }

    @Test
    fun overlap_gate_suppresses_until_switch_wait_elapses() {
        assertEquals(
            OverlapAlertMode.NORMAL,
            RoadLayerAlertGate.mode(alertElevated = true, elevatedFlag = RoadLayerAlertGate.ELEVATED_NONE, pendingSwitchSinceMs = 0L, nowMs = 0L)
        )
        assertEquals(
            OverlapAlertMode.LAYER,
            RoadLayerAlertGate.mode(alertElevated = true, elevatedFlag = RoadLayerAlertGate.ELEVATED_ON, pendingSwitchSinceMs = 0L, nowMs = 0L)
        )
        assertEquals(
            OverlapAlertMode.SUPPRESS,
            RoadLayerAlertGate.mode(alertElevated = true, elevatedFlag = RoadLayerAlertGate.ELEVATED_UNDER, pendingSwitchSinceMs = 1_000L, nowMs = 2_000L)
        )
        assertEquals(
            OverlapAlertMode.LAYER,
            RoadLayerAlertGate.mode(
                alertElevated = true,
                elevatedFlag = RoadLayerAlertGate.ELEVATED_UNDER,
                pendingSwitchSinceMs = 1_000L,
                nowMs = 1_000L + RoadLayerAlertGate.SWITCH_WAIT_MS
            )
        )
        assertTrue(RoadLayerAlertGate.needsSwitch(alertElevated = true, elevatedFlag = RoadLayerAlertGate.ELEVATED_UNDER))
        assertTrue(RoadLayerAlertGate.needsSwitch(alertElevated = false, elevatedFlag = RoadLayerAlertGate.ELEVATED_ON))
        assertFalse(RoadLayerAlertGate.needsSwitch(alertElevated = true, elevatedFlag = RoadLayerAlertGate.ELEVATED_ON))
        assertFalse(RoadLayerAlertGate.needsSwitch(alertElevated = true, elevatedFlag = RoadLayerAlertGate.ELEVATED_NONE))
    }

    private fun climb(
        resolver: RoadLayerResolver,
        config: RoadLayerConfig = RoadLayerConfig(),
        startMs: Long = 0L
    ): RoadLayerDecision {
        feed(resolver, startMs, startMs + RoadLayerRules.BASELINE_HOLD_MS, 0f, config)
        feed(resolver, startMs + 30_000L, startMs + 38_000L, 2.5f, config)
        return feed(resolver, startMs + 38_000L, startMs + 53_000L, 0f, config)
    }

    private fun descend(
        resolver: RoadLayerResolver,
        config: RoadLayerConfig = RoadLayerConfig(),
        startMs: Long = 0L
    ): RoadLayerDecision {
        feed(resolver, startMs, startMs + RoadLayerRules.BASELINE_HOLD_MS, 0f, config)
        feed(resolver, startMs + 30_000L, startMs + 38_000L, -2.5f, config)
        return feed(resolver, startMs + 38_000L, startMs + 53_000L, 0f, config)
    }

    private fun feed(
        resolver: RoadLayerResolver,
        startMs: Long,
        endMs: Long,
        pitch: Float,
        config: RoadLayerConfig = RoadLayerConfig(),
        gpsWeak: Boolean = false
    ): RoadLayerDecision {
        var time = startMs
        var decision = resolver.update(sample(time, pitch, gpsWeak), config)
        while (time < endMs) {
            time = minOf(endMs, time + 1_000L)
            decision = resolver.update(sample(time, pitch, gpsWeak), config)
        }
        return decision
    }

    private fun sample(timeMs: Long, pitch: Float, gpsWeak: Boolean = false): RoadAttitudeSample {
        return RoadAttitudeSample(timeMs = timeMs, pitchDegrees = pitch, forwardKnown = true, gpsWeak = gpsWeak)
    }
}
