package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RoadLayerMonitorTest {
    @Test
    fun climb_ramp_then_flat_road_locks_elevated() {
        val monitor = RoadLayerMonitor()
        val g = VehiclePitchTracker.GRAVITY_MPS2
        monitor.onSpeed(0L, 30f)
        monitor.onSpeed(1_000L, 42f)

        var time = 0L
        while (time <= 500L) {
            step(monitor, time, 0f, 0f, g, 0f)
            time += 50L
        }
        time = 1_000L
        while (time <= 2_500L) {
            step(monitor, time, 0f, 1.2f, g, 0f)
            time += 50L
        }
        val flat = feedPitch(monitor, 2_500L, 34_000L, 0f)
        assertEquals(PitchPhase.FLAT, flat.phase)
        assertTrue(flat.usingDefault)

        val climbed = ramp(monitor, 34_000L, 36_000L, 0f, 3f)
        assertTrue(climbed.pitchDegrees!! > RoadLayerRules.SLOPE_ENTER_DEGREES)
        feedPitch(monitor, 36_000L, 45_000L, 3f)
        ramp(monitor, 45_000L, 47_000L, 3f, 0f)
        val decision = feedPitch(monitor, 47_000L, 63_000L, 0f)

        assertEquals(RoadLayer.ELEVATED, decision.detectedLayer)
        assertEquals(RoadLayer.ELEVATED, decision.alertLayer)
        assertFalse(decision.usingDefault)
    }

    private fun feedPitch(monitor: RoadLayerMonitor, startMs: Long, endMs: Long, degrees: Float): RoadLayerDecision {
        var time = startMs
        var decision = stepDegrees(monitor, time, degrees)
        while (time < endMs) {
            time = minOf(endMs, time + 50L)
            decision = stepDegrees(monitor, time, degrees)
        }
        return decision
    }

    private fun ramp(monitor: RoadLayerMonitor, startMs: Long, endMs: Long, fromDeg: Float, toDeg: Float): RoadLayerDecision {
        val duration = (endMs - startMs).coerceAtLeast(1L)
        var time = startMs
        var decision = stepDegrees(monitor, time, fromDeg, gyroX = 0f)
        while (time < endMs) {
            val next = minOf(endMs, time + 50L)
            val dt = (next - time) / 1_000f
            val nextDeg = fromDeg + (toDeg - fromDeg) * (next - startMs) / duration.toFloat()
            val gyroX = Math.toRadians((nextDeg - (fromDeg + (toDeg - fromDeg) * (time - startMs) / duration.toFloat())).toDouble()).toFloat() / dt
            decision = stepDegrees(monitor, next, nextDeg, gyroX)
            time = next
        }
        return decision
    }

    private fun stepDegrees(monitor: RoadLayerMonitor, timeMs: Long, degrees: Float, gyroX: Float = 0f): RoadLayerDecision {
        val radians = Math.toRadians(degrees.toDouble())
        val g = VehiclePitchTracker.GRAVITY_MPS2
        return step(monitor, timeMs, 0f, sin(radians).toFloat() * g, cos(radians).toFloat() * g, gyroX)
    }

    private fun step(
        monitor: RoadLayerMonitor,
        timeMs: Long,
        ax: Float,
        ay: Float,
        az: Float,
        gyroX: Float
    ): RoadLayerDecision {
        monitor.onAccelerometer(timeMs, ax, ay, az)
        return monitor.onGyroscope(timeMs, gyroX, 0f, 0f)!!
    }
}
