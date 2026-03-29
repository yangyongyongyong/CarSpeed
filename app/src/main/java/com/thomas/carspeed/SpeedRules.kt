package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.max

object SpeedRules {
    private const val MAX_SPEED_KMH = 9999f
    private const val MAX_SPEED_MPS = 2777.8f
    private const val STATIONARY_NOISE_SPEED_MPS = 1.4f
    private const val MOVING_SPEED_MPS = 2.2f
    private const val LOW_LINEAR_ACCEL_MPS2 = 0.35f
    private const val EXIT_LINEAR_ACCEL_MPS2 = 0.65f
    private const val POOR_ACCURACY_METERS = 35f
    private const val VERY_POOR_ACCURACY_METERS = 60f
    private const val LARGE_SPEED_ACCURACY_MPS = 6f
    private const val MAX_IMPLAUSIBLE_ACCEL_MPS2 = 12f
    private const val ENTER_STATIONARY_SAMPLES = 2
    private const val EXIT_STATIONARY_SAMPLES = 2

    data class MaxSpeedResult(
        val maxSpeedKmh: Float,
        val maxSpeedTimeMs: Long
    )

    data class WaitState(
        val tripStarted: Boolean,
        val tripStartTimeMs: Long,
        val waitAtZeroAccumMs: Long,
        val waitAtZeroStartMs: Long
    )

    /**
     * 速度融合状态：避免模拟器 GPS 抖动/短时 0 值导致 UI“卡 0”。
     */
    data class SpeedFilterState(
        val currentSpeedMps: Float = 0f,
        val lastNonZeroSpeedMps: Float = 0f,
        val lastNonZeroTimeMs: Long = 0L,
        val stationarySampleCount: Int = 0,
        val movingSampleCount: Int = 0,
        val isStationaryLocked: Boolean = false,
        val lastTrustedSpeedMps: Float = 0f,
        val lastTrustedTimeMs: Long = 0L
    )

    data class LocationSample(
        val gpsSpeedMps: Float,
        val hasGpsSpeed: Boolean,
        val computedSpeedMps: Float,
        val accuracyMeters: Float,
        val speedAccuracyMps: Float?,
        val distanceMeters: Float?,
        val deltaTimeSec: Float?,
        val linearAccelMps2: Float?,
        val inWarmup: Boolean,
        val nowMs: Long
    )

    data class FilterResult(
        val finalSpeedMps: Float,
        val isStationaryLocked: Boolean,
        val isSampleTrusted: Boolean,
        val allowUpdateMaxSpeed: Boolean,
        val nextFilterState: SpeedFilterState
    )

    fun selectSpeedMps(
        gpsSpeedMps: Float,
        hasGpsSpeed: Boolean,
        computedSpeedMps: Float
    ): Float {
        val gpsValid = hasGpsSpeed && !gpsSpeedMps.isNaN() && gpsSpeedMps in 0f..MAX_SPEED_MPS
        val computedValid = !computedSpeedMps.isNaN() && computedSpeedMps in 0f..MAX_SPEED_MPS

        if (!gpsValid && !computedValid) return 0f
        if (gpsValid && computedValid) return max(gpsSpeedMps, computedSpeedMps)
        if (gpsValid) return gpsSpeedMps
        return computedSpeedMps
    }

    fun stabilizeSpeedMps(
        old: SpeedFilterState,
        rawSpeedMps: Float,
        nowMs: Long,
        zeroGraceMs: Long = 2_000L,
        decayFactorOnZero: Float = 0.85f
    ): SpeedFilterState {
        val saneRaw = if (rawSpeedMps.isNaN()) 0f else rawSpeedMps.coerceIn(0f, MAX_SPEED_MPS)
        return if (saneRaw > 0.05f) {
            SpeedFilterState(
                currentSpeedMps = saneRaw,
                lastNonZeroSpeedMps = saneRaw,
                lastNonZeroTimeMs = nowMs
            )
        } else {
            val dt = if (old.lastNonZeroTimeMs > 0L) nowMs - old.lastNonZeroTimeMs else Long.MAX_VALUE
            if (dt in 0..zeroGraceMs && old.currentSpeedMps > 0f) {
                SpeedFilterState(
                    currentSpeedMps = (old.currentSpeedMps * decayFactorOnZero).coerceAtLeast(0f),
                    lastNonZeroSpeedMps = old.lastNonZeroSpeedMps,
                    lastNonZeroTimeMs = old.lastNonZeroTimeMs
                )
            } else {
                SpeedFilterState(
                    currentSpeedMps = 0f,
                    lastNonZeroSpeedMps = old.lastNonZeroSpeedMps,
                    lastNonZeroTimeMs = old.lastNonZeroTimeMs
                )
            }
        }
    }

    fun processLocationSample(
        old: SpeedFilterState,
        sample: LocationSample
    ): FilterResult {
        val gpsSpeed = sanitizeSpeed(sample.gpsSpeedMps)
        val computedSpeed = sanitizeSpeed(sample.computedSpeedMps)
        val distanceMeters = sample.distanceMeters?.takeIf { !it.isNaN() } ?: Float.NaN
        val dtSec = sample.deltaTimeSec?.takeIf { !it.isNaN() && it > 0f } ?: Float.NaN
        val accuracy = sample.accuracyMeters.takeIf { !it.isNaN() && it > 0f } ?: VERY_POOR_ACCURACY_METERS
        val linearAccel = sample.linearAccelMps2?.let { abs(it) } ?: Float.NaN

        val gpsTrusted = isGpsSpeedTrusted(
            hasGpsSpeed = sample.hasGpsSpeed,
            gpsSpeedMps = gpsSpeed,
            accuracyMeters = accuracy,
            speedAccuracyMps = sample.speedAccuracyMps,
            distanceMeters = distanceMeters,
            dtSec = dtSec,
            linearAccelMps2 = linearAccel,
            old = old
        )
        val computedTrusted = isComputedSpeedTrusted(
            computedSpeedMps = computedSpeed,
            accuracyMeters = accuracy,
            distanceMeters = distanceMeters,
            dtSec = dtSec,
            linearAccelMps2 = linearAccel,
            old = old
        )

        val lowAccel = linearAccel.isNaN() || linearAccel <= LOW_LINEAR_ACCEL_MPS2
        val movingAccel = !linearAccel.isNaN() && linearAccel >= EXIT_LINEAR_ACCEL_MPS2
        val stationaryDistanceThreshold = max(6f, accuracy * 0.9f)
        val movingDistanceThreshold = max(12f, accuracy * 1.4f)

        val stationaryEvidence =
            (gpsTrusted && gpsSpeed <= STATIONARY_NOISE_SPEED_MPS || !gpsTrusted) &&
                (computedTrusted && computedSpeed <= STATIONARY_NOISE_SPEED_MPS || !computedTrusted) &&
                (distanceMeters.isNaN() || distanceMeters <= stationaryDistanceThreshold) &&
                lowAccel

        val movingEvidence =
            (gpsTrusted && gpsSpeed >= MOVING_SPEED_MPS) ||
                (computedTrusted && computedSpeed >= MOVING_SPEED_MPS &&
                    !distanceMeters.isNaN() &&
                    distanceMeters >= movingDistanceThreshold &&
                    !dtSec.isNaN())

        val stationaryCount = if (stationaryEvidence) old.stationarySampleCount + 1 else 0
        val movingCount = if (movingEvidence && (movingAccel || old.currentSpeedMps > 0.8f || sample.inWarmup)) {
            old.movingSampleCount + 1
        } else {
            0
        }

        val wasStationaryLocked = old.isStationaryLocked
        val isStationaryLocked = when {
            stationaryCount >= ENTER_STATIONARY_SAMPLES -> true
            wasStationaryLocked && movingCount < EXIT_STATIONARY_SAMPLES -> true
            else -> false
        }

        val chosenRawSpeed = when {
            sample.inWarmup -> {
                if (gpsTrusted && gpsSpeed <= MOVING_SPEED_MPS) gpsSpeed else 0f
            }
            isStationaryLocked -> 0f
            gpsTrusted -> gpsSpeed
            computedTrusted -> computedSpeed
            else -> 0f
        }

        val shouldHoldAtZero = old.currentSpeedMps <= 0.05f &&
            chosenRawSpeed >= MOVING_SPEED_MPS &&
            movingCount < EXIT_STATIONARY_SAMPLES

        val gatedRawSpeed = when {
            isStationaryLocked -> 0f
            shouldHoldAtZero -> 0f
            chosenRawSpeed <= STATIONARY_NOISE_SPEED_MPS && stationaryEvidence -> 0f
            else -> chosenRawSpeed
        }

        val stabilized = stabilizeSpeedMps(
            old = old,
            rawSpeedMps = gatedRawSpeed,
            nowMs = sample.nowMs
        ).copy(
            stationarySampleCount = stationaryCount,
            movingSampleCount = movingCount,
            isStationaryLocked = isStationaryLocked,
            lastTrustedSpeedMps = if (gpsTrusted || computedTrusted) chosenRawSpeed else old.lastTrustedSpeedMps,
            lastTrustedTimeMs = if (gpsTrusted || computedTrusted) sample.nowMs else old.lastTrustedTimeMs
        )

        val finalSpeed = if (isStationaryLocked) 0f else stabilized.currentSpeedMps
        val isSampleTrusted = gpsTrusted || computedTrusted
        val allowUpdateMaxSpeed = !isStationaryLocked &&
            isSampleTrusted &&
            movingCount >= 1 &&
            finalSpeed > STATIONARY_NOISE_SPEED_MPS

        return FilterResult(
            finalSpeedMps = finalSpeed,
            isStationaryLocked = isStationaryLocked,
            isSampleTrusted = isSampleTrusted,
            allowUpdateMaxSpeed = allowUpdateMaxSpeed,
            nextFilterState = stabilized.copy(currentSpeedMps = finalSpeed)
        )
    }

    fun gapMs(prevReceiveRealtimeNs: Long, nowReceiveRealtimeNs: Long): Long {
        if (prevReceiveRealtimeNs <= 0L || nowReceiveRealtimeNs <= prevReceiveRealtimeNs) return Long.MAX_VALUE
        return (nowReceiveRealtimeNs - prevReceiveRealtimeNs) / 1_000_000L
    }

    /**
     * 长时间没有定位更新（例如进隧道）时，UI 速度必须强制归零。
     */
    fun shouldForceZeroOnStale(
        lastReceiveRealtimeNs: Long,
        nowRealtimeNs: Long,
        staleTimeoutMs: Long
    ): Boolean {
        val g = gapMs(lastReceiveRealtimeNs, nowRealtimeNs)
        return g > staleTimeoutMs
    }

    /**
     * 重新获得定位后，先进入 warm-up，避免拿隧道前后两个点直接算出离谱速度。
     */
    fun shouldEnterReacquireWarmup(
        lastReceiveRealtimeNs: Long,
        nowRealtimeNs: Long,
        reacquireGapMs: Long
    ): Boolean {
        if (lastReceiveRealtimeNs <= 0L) return false
        val g = gapMs(lastReceiveRealtimeNs, nowRealtimeNs)
        return g > reacquireGapMs
    }


    fun resolveWaitClockNowMs(
        gpsSignalLost: Boolean,
        lastGpsFixWallTimeMs: Long,
        nowMs: Long
    ): Long {
        return if (gpsSignalLost && lastGpsFixWallTimeMs > 0L) {
            lastGpsFixWallTimeMs
        } else {
            nowMs
        }
    }

    fun toKmhClamped(speedMps: Float): Float {
        return (speedMps * 3.6f).coerceIn(0f, MAX_SPEED_KMH)
    }

    fun updateMaxSpeed(
        oldMaxSpeedKmh: Float,
        oldMaxSpeedTimeMs: Long,
        currentSpeedKmh: Float,
        nowMs: Long
    ): MaxSpeedResult {
        return if (currentSpeedKmh > oldMaxSpeedKmh) {
            MaxSpeedResult(currentSpeedKmh.coerceAtMost(MAX_SPEED_KMH), nowMs)
        } else {
            MaxSpeedResult(oldMaxSpeedKmh.coerceAtMost(MAX_SPEED_KMH), oldMaxSpeedTimeMs)
        }
    }

    fun updateTripAndWait(
        old: WaitState,
        speedKmh: Float,
        nowMs: Long
    ): WaitState {
        var tripStarted = old.tripStarted
        var tripStartTimeMs = old.tripStartTimeMs
        var waitAtZeroAccumMs = old.waitAtZeroAccumMs
        var waitAtZeroStartMs = old.waitAtZeroStartMs

        if (!tripStarted && speedKmh > 10f) {
            tripStarted = true
            tripStartTimeMs = nowMs
            waitAtZeroAccumMs = 0L
            waitAtZeroStartMs = 0L
        }

        if (tripStarted) {
            val isZeroSpeed = speedKmh < 0.5f
            if (isZeroSpeed) {
                if (waitAtZeroStartMs == 0L) waitAtZeroStartMs = nowMs
            } else if (waitAtZeroStartMs > 0L) {
                waitAtZeroAccumMs += (nowMs - waitAtZeroStartMs).coerceAtLeast(0L)
                waitAtZeroStartMs = 0L
            }
        }

        return WaitState(
            tripStarted = tripStarted,
            tripStartTimeMs = tripStartTimeMs,
            waitAtZeroAccumMs = waitAtZeroAccumMs,
            waitAtZeroStartMs = waitAtZeroStartMs
        )
    }

    fun currentWaitDurationMs(state: WaitState, nowMs: Long): Long {
        return if (state.tripStarted && state.waitAtZeroStartMs > 0L) {
            state.waitAtZeroAccumMs + (nowMs - state.waitAtZeroStartMs).coerceAtLeast(0L)
        } else {
            state.waitAtZeroAccumMs
        }
    }

    private fun sanitizeSpeed(value: Float): Float {
        if (value.isNaN()) return Float.NaN
        return value.coerceIn(0f, MAX_SPEED_MPS)
    }

    private fun isGpsSpeedTrusted(
        hasGpsSpeed: Boolean,
        gpsSpeedMps: Float,
        accuracyMeters: Float,
        speedAccuracyMps: Float?,
        distanceMeters: Float,
        dtSec: Float,
        linearAccelMps2: Float,
        old: SpeedFilterState
    ): Boolean {
        if (!hasGpsSpeed || gpsSpeedMps.isNaN() || gpsSpeedMps !in 0f..MAX_SPEED_MPS) return false

        val speedAccuracyPoor = speedAccuracyMps != null && speedAccuracyMps > LARGE_SPEED_ACCURACY_MPS
        if (accuracyMeters >= VERY_POOR_ACCURACY_METERS && gpsSpeedMps > STATIONARY_NOISE_SPEED_MPS) return false
        if (accuracyMeters >= POOR_ACCURACY_METERS && speedAccuracyPoor && gpsSpeedMps > MOVING_SPEED_MPS) return false

        if (!distanceMeters.isNaN() && !dtSec.isNaN() && dtSec > 0f) {
            val impliedAccel = abs(gpsSpeedMps - old.currentSpeedMps) / dtSec
            if (impliedAccel > MAX_IMPLAUSIBLE_ACCEL_MPS2 &&
                (linearAccelMps2.isNaN() || linearAccelMps2 < LOW_LINEAR_ACCEL_MPS2)
            ) {
                return false
            }
            if (distanceMeters <= max(6f, accuracyMeters) && gpsSpeedMps > MOVING_SPEED_MPS && speedAccuracyPoor) {
                return false
            }
        }

        return true
    }

    private fun isComputedSpeedTrusted(
        computedSpeedMps: Float,
        accuracyMeters: Float,
        distanceMeters: Float,
        dtSec: Float,
        linearAccelMps2: Float,
        old: SpeedFilterState
    ): Boolean {
        if (computedSpeedMps.isNaN() || computedSpeedMps !in 0f..MAX_SPEED_MPS) return false
        if (distanceMeters.isNaN() || dtSec.isNaN() || dtSec <= 0f) return false

        val stationaryDistanceThreshold = max(8f, accuracyMeters * 1.1f)
        if (distanceMeters <= stationaryDistanceThreshold && computedSpeedMps > STATIONARY_NOISE_SPEED_MPS) {
            return false
        }
        if (accuracyMeters >= VERY_POOR_ACCURACY_METERS && computedSpeedMps > STATIONARY_NOISE_SPEED_MPS) {
            return false
        }

        val impliedAccel = abs(computedSpeedMps - old.currentSpeedMps) / dtSec
        if (impliedAccel > MAX_IMPLAUSIBLE_ACCEL_MPS2 &&
            (linearAccelMps2.isNaN() || linearAccelMps2 < LOW_LINEAR_ACCEL_MPS2)
        ) {
            return false
        }

        return true
    }
}
