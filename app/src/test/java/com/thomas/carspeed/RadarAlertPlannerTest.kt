package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarAlertPlannerTest {
    private val settings = RadarAlertSettings(
        enabled = true,
        thresholdsSeconds = listOf(20, 10, 5),
        leadSeconds = 2,
        speechRatePercent = 150
    )

    @Test
    fun nearest_camera_keeps_lock_even_when_second_camera_is_also_due() {
        val first = camera("first", distanceMeters = 70)
        val second = camera("second", distanceMeters = 320)

        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(first, second),
            currentLock = null,
            nowMs = 1_000L,
            speedKmh = 60f,
            settings = settings
        )

        assertEquals("first", selection.camera?.key)
        assertEquals("first", selection.lock?.key)
    }

    @Test
    fun second_camera_is_blocked_when_locked_camera_temporarily_disappears_before_pass_zone() {
        val lock = RadarCameraLock(
            key = "first",
            lastDistanceMeters = 180,
            closestDistanceMeters = 180,
            lastSeenAtMs = 1_000L,
            enteredPassZone = false
        )

        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera("second", distanceMeters = 320)),
            currentLock = lock,
            nowMs = 3_000L,
            speedKmh = 60f,
            settings = settings
        )

        assertNull(selection.camera)
        assertEquals("first", selection.lock?.key)
    }

    @Test
    fun second_camera_can_alert_after_first_camera_was_passed() {
        val lock = RadarCameraLock(
            key = "first",
            lastDistanceMeters = 20,
            closestDistanceMeters = 20,
            lastSeenAtMs = 1_000L,
            enteredPassZone = true
        )

        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera("second", distanceMeters = 320)),
            currentLock = lock,
            nowMs = 1_000L + RadarAlertPlanner.PASS_UNLOCK_MISSING_MS,
            speedKmh = 60f,
            settings = settings
        )

        assertEquals("second", selection.camera?.key)
        assertEquals("second", selection.lock?.key)
    }

    @Test
    fun late_final_alert_marks_larger_missed_thresholds_as_fired() {
        val due = RadarAlertPlanner.dueThresholds(
            etaSeconds = 5,
            settings = settings,
            fired = emptySet()
        )
        val spoken = RadarAlertPlanner.thresholdToSpeak(due, etaSeconds = 5, settings = settings)

        assertEquals(5, spoken)
        assertEquals(setOf(20, 10, 5), RadarAlertPlanner.thresholdsToMark(due, spoken))
    }

    @Test
    fun eta_uses_current_speed() {
        assertEquals(18, RadarAlertPlanner.etaSeconds(distanceMeters = 500, speedKmh = 100f))
        assertTrue(RadarAlertPlanner.etaSeconds(distanceMeters = 500, speedKmh = 0f) == null)
    }

    @Test
    fun far_unknown_limit_alert_requires_more_than_three_kilometers_and_no_limit() {
        assertTrue(
            RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(
                camera("unknown", distanceMeters = 3_200, speedLimit = null),
                fired = emptySet()
            )
        )
        assertNull(RadarAlertPlanner.thresholdToSpeak(emptyList(), etaSeconds = 120, settings = settings))
        assertEquals("前方3.2公里有测速雷达，暂无限速信息", RadarAlertPlanner.farUnknownLimitAlertText(3_200))
    }

    @Test
    fun far_unknown_limit_alert_is_not_repeated_or_used_with_known_limit() {
        assertTrue(
            !RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(
                camera("near", distanceMeters = 3_000, speedLimit = null),
                fired = emptySet()
            )
        )
        assertTrue(
            !RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(
                camera("known", distanceMeters = 3_200, speedLimit = 80),
                fired = emptySet()
            )
        )
        assertTrue(
            !RadarAlertPlanner.shouldSpeakFarUnknownLimitAlert(
                camera("fired", distanceMeters = 3_200, speedLimit = null),
                fired = setOf(RadarAlertPlanner.FAR_UNKNOWN_LIMIT_ALERT_MARKER)
            )
        )
    }

    @Test
    fun network_warning_requires_vehicle_moving() {
        val health = RadarDataHealthState(serviceStartedAtMs = 1_000L, lastDataAtMs = 1_000L)

        assertNull(
            RadarAlertPlanner.dataWarningType(
                networkAvailable = false,
                speedKmh = 0f,
                nowMs = 2_000L,
                health = health
            )
        )
        assertEquals(
            RadarDataWarningType.NETWORK_UNAVAILABLE,
            RadarAlertPlanner.dataWarningType(
                networkAvailable = false,
                speedKmh = 30f,
                nowMs = 2_000L,
                health = health
            )
        )
    }

    @Test
    fun stale_data_warning_waits_for_grace_period() {
        val health = RadarDataHealthState(serviceStartedAtMs = 1_000L, lastDataAtMs = 1_000L)

        assertNull(
            RadarAlertPlanner.dataWarningType(
                networkAvailable = true,
                speedKmh = 50f,
                nowMs = 1_000L + RadarAlertPlanner.DATA_STALE_WARNING_MS - 1,
                health = health
            )
        )
        assertEquals(
            RadarDataWarningType.DATA_STALE,
            RadarAlertPlanner.dataWarningType(
                networkAvailable = true,
                speedKmh = 50f,
                nowMs = 1_000L + RadarAlertPlanner.DATA_STALE_WARNING_MS,
                health = health
            )
        )
    }

    @Test
    fun data_warning_is_throttled() {
        val health = RadarDataHealthState(
            serviceStartedAtMs = 1_000L,
            lastDataAtMs = 1_000L,
            lastNetworkWarningAtMs = 2_000L
        )

        assertNull(
            RadarAlertPlanner.dataWarningType(
                networkAvailable = false,
                speedKmh = 50f,
                nowMs = 2_000L + RadarAlertPlanner.DATA_WARNING_REPEAT_MS - 1,
                health = health
            )
        )
        assertEquals(
            RadarDataWarningType.NETWORK_UNAVAILABLE,
            RadarAlertPlanner.dataWarningType(
                networkAvailable = false,
                speedKmh = 50f,
                nowMs = 2_000L + RadarAlertPlanner.DATA_WARNING_REPEAT_MS,
                health = health
            )
        )
    }

    private fun camera(key: String, distanceMeters: Int, speedLimit: Int? = 80): RadarCameraSnapshot {
        return RadarCameraSnapshot(
            key = key,
            distanceMeters = distanceMeters,
            cameraType = 4,
            speedLimit = speedLimit
        )
    }
}
