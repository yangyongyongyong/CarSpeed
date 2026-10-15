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
        assertEquals("first", selection.passedCameraKey)
    }

    @Test
    fun passed_camera_is_not_reselected_when_distance_starts_increasing() {
        val lock = RadarCameraLock(
            key = "first",
            lastDistanceMeters = 20,
            closestDistanceMeters = 20,
            lastSeenAtMs = 1_000L,
            enteredPassZone = true
        )

        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera("first", distanceMeters = 70)),
            currentLock = lock,
            nowMs = 2_000L,
            speedKmh = 60f,
            settings = settings
        )

        assertNull(selection.camera)
        assertNull(selection.lock)
        assertEquals("first", selection.passedCameraKey)
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
    fun speed_camera_types_follow_amap_facility_codes() {
        assertTrue(RadarAlertPlanner.isSpeedCamera(RadarAlertPlanner.SPEED_CAMERA_TYPE))
        assertTrue(RadarAlertPlanner.isSpeedCamera(RadarAlertPlanner.INTERVAL_CAMERA_TYPE))
        assertTrue(RadarAlertPlanner.isSpeedCamera(RadarAlertPlanner.SPEEDING_VIOLATION_TYPE))
        assertTrue(!RadarAlertPlanner.isSpeedCamera(0))
        assertTrue(!RadarAlertPlanner.isSpeedCamera(5))
        assertTrue(!RadarAlertPlanner.isSpeedCamera(28))
    }

    @Test
    fun eta_prefers_fresh_navi_speed_from_the_same_fix_as_distance() {
        assertEquals(
            96f,
            RadarAlertPlanner.etaSpeedKmh(
                naviSpeedKmh = 96f,
                naviSpeedAtMs = 1_000L,
                vehicleSpeedKmh = 70f,
                nowMs = 2_000L
            )
        )
        assertEquals(
            70f,
            RadarAlertPlanner.etaSpeedKmh(
                naviSpeedKmh = 96f,
                naviSpeedAtMs = 1_000L,
                vehicleSpeedKmh = 70f,
                nowMs = 1_000L + RadarAlertPlanner.NAVI_SPEED_FRESH_MS + 1
            )
        )
        assertEquals(
            70f,
            RadarAlertPlanner.etaSpeedKmh(
                naviSpeedKmh = 0f,
                naviSpeedAtMs = 2_000L,
                vehicleSpeedKmh = 70f,
                nowMs = 2_000L
            )
        )
    }

    @Test
    fun spoken_distance_matches_the_seconds_in_the_same_sentence() {
        assertEquals(200, RadarAlertPlanner.spokenDistanceMeters(thresholdSeconds = 10, speedKmh = 72f))
        assertEquals(556, RadarAlertPlanner.spokenDistanceMeters(thresholdSeconds = 20, speedKmh = 100f))
        assertEquals(0, RadarAlertPlanner.spokenDistanceMeters(thresholdSeconds = 10, speedKmh = 0f))
    }

    @Test
    fun pass_zone_stays_next_to_the_camera() {
        assertEquals(30, RadarAlertPlanner.passZoneMeters(20f))
        assertEquals(56, RadarAlertPlanner.passZoneMeters(100f))
        assertEquals(60, RadarAlertPlanner.passZoneMeters(160f))
    }

    @Test
    fun distance_increase_far_from_the_camera_does_not_count_as_passed() {
        val lock = RadarCameraLock(
            key = "first",
            lastDistanceMeters = 150,
            closestDistanceMeters = 150,
            lastSeenAtMs = 1_000L,
            enteredPassZone = false
        )

        val selection = RadarAlertPlanner.selectActiveCamera(
            cameras = listOf(camera("first", distanceMeters = 210)),
            currentLock = lock,
            nowMs = 2_000L,
            speedKmh = 100f,
            settings = settings
        )

        assertEquals("first", selection.camera?.key)
        assertEquals(false, selection.lock?.enteredPassZone)
        assertNull(selection.passedCameraKey)
    }

    @Test
    fun sudden_distance_drop_does_not_skip_from_20_seconds_to_5_seconds() {
        val first = RadarAlertPlanner.reconcileEta(anchor = null, rawEtaSeconds = 20, nowMs = 0L)
        assertEquals(20, first.etaSeconds)

        val fourSecondsLater = RadarAlertPlanner.reconcileEta(first.anchor, rawEtaSeconds = 5, nowMs = 4_000L)
        assertEquals(14, fourSecondsLater.etaSeconds)
        assertEquals(first.anchor, fourSecondsLater.anchor)

        val due = RadarAlertPlanner.dueThresholds(
            etaSeconds = fourSecondsLater.etaSeconds,
            settings = settings,
            fired = setOf(20)
        )
        assertTrue(due.isEmpty())
        assertNull(RadarAlertPlanner.thresholdToSpeak(due, fourSecondsLater.etaSeconds, settings))
    }

    @Test
    fun countdown_reaches_the_5_second_alert_after_the_real_wait() {
        val first = RadarAlertPlanner.reconcileEta(anchor = null, rawEtaSeconds = 22, nowMs = 0L)
        val stillEarly = RadarAlertPlanner.reconcileEta(first.anchor, rawEtaSeconds = 4, nowMs = 10_000L)
        val earlyDue = RadarAlertPlanner.dueThresholds(stillEarly.etaSeconds, settings, fired = setOf(20))
        assertEquals(10, RadarAlertPlanner.thresholdToSpeak(earlyDue, stillEarly.etaSeconds, settings))

        val ready = RadarAlertPlanner.reconcileEta(first.anchor, rawEtaSeconds = 4, nowMs = 15_000L)
        val due = RadarAlertPlanner.dueThresholds(ready.etaSeconds, settings, fired = setOf(20, 10))
        assertEquals(5, RadarAlertPlanner.thresholdToSpeak(due, ready.etaSeconds, settings))
    }

    @Test
    fun plausible_eta_replaces_the_countdown_anchor() {
        val first = RadarAlertPlanner.reconcileEta(anchor = null, rawEtaSeconds = 22, nowMs = 0L)
        val next = RadarAlertPlanner.reconcileEta(first.anchor, rawEtaSeconds = 18, nowMs = 4_000L)
        assertEquals(18, next.etaSeconds)
        assertEquals(4_000L, next.anchor.atMs)
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

    @Test
    fun only_network_unavailable_data_warning_is_spoken() {
        assertTrue(RadarAlertPlanner.shouldSpeakDataWarning(RadarDataWarningType.NETWORK_UNAVAILABLE))
        assertTrue(!RadarAlertPlanner.shouldSpeakDataWarning(RadarDataWarningType.DATA_STALE))
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
