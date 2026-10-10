package com.thomas.carspeed

object ParkingStopRules {
    const val STATIONARY_SPEED_KMH = 0.5f

    fun nextStationaryStartMs(currentStartMs: Long?, speedKmh: Float, nowMs: Long): Long? {
        val saneSpeed = if (speedKmh.isNaN()) Float.MAX_VALUE else speedKmh.coerceAtLeast(0f)
        return if (saneSpeed <= STATIONARY_SPEED_KMH) {
            currentStartMs ?: nowMs
        } else {
            null
        }
    }

    fun shouldStopRecording(
        enabled: Boolean,
        thresholdMinutes: Int,
        stationaryStartMs: Long?,
        nowMs: Long
    ): Boolean {
        if (!enabled || stationaryStartMs == null) return false
        val thresholdMs = thresholdMinutes
            .coerceIn(DashcamSettings.MIN_PARKING_STOP_MINUTES, DashcamSettings.MAX_PARKING_STOP_MINUTES) * 60_000L
        return nowMs - stationaryStartMs >= thresholdMs
    }
}
