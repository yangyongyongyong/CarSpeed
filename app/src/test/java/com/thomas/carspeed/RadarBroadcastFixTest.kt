package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarBroadcastFixTest {
    @Test
    fun keeps_a_point_that_already_sits_on_the_road() {
        val carLat = 30.274100
        val carLon = 120.155100
        val ahead = RadarBroadcastFix.project(carLat, carLon, 90f, 500)
        checkNotNull(ahead)
        val fix = RadarBroadcastFix.resolve(
            carLatitude = carLat,
            carLongitude = carLon,
            bearingDegrees = 90f,
            distanceMeters = 500,
            reportedLatitude = ahead.first,
            reportedLongitude = ahead.second,
            reportedSource = RadarBroadcastFix.SOURCE_CRUISE
        )
        assertEquals(RadarBroadcastFix.SOURCE_CRUISE, fix.source)
        assertEquals(ahead.first, fix.latitude, 0.000001)
        assertEquals(ahead.second, fix.longitude, 0.000001)
    }

    @Test
    fun moves_a_sideways_point_onto_the_road_at_the_reported_distance() {
        val carLat = 30.274100
        val carLon = 120.155100
        val ahead = checkNotNull(RadarBroadcastFix.project(carLat, carLon, 90f, 600))
        val sideways = checkNotNull(RadarBroadcastFix.project(ahead.first, ahead.second, 0f, 500))
        val fix = RadarBroadcastFix.resolve(
            carLatitude = carLat,
            carLongitude = carLon,
            bearingDegrees = 90f,
            distanceMeters = 600,
            reportedLatitude = sideways.first,
            reportedLongitude = sideways.second,
            reportedSource = RadarBroadcastFix.SOURCE_CRUISE
        )
        assertEquals(RadarBroadcastFix.SOURCE_ROAD, fix.source)
        val fromCar = RadarAlertLog.distanceMeters(carLat, carLon, fix.latitude, fix.longitude)
        val fromBadPoint = RadarAlertLog.distanceMeters(
            sideways.first,
            sideways.second,
            fix.latitude,
            fix.longitude
        )
        assertTrue(fromCar in 540..660)
        assertTrue(fromBadPoint != null && fromBadPoint > 300)
    }

    @Test
    fun does_not_keep_a_point_behind_the_car() {
        val carLat = 30.274100
        val carLon = 120.155100
        val behind = checkNotNull(RadarBroadcastFix.project(carLat, carLon, 270f, 500))
        val fix = RadarBroadcastFix.resolve(
            carLatitude = carLat,
            carLongitude = carLon,
            bearingDegrees = 90f,
            distanceMeters = 500,
            reportedLatitude = behind.first,
            reportedLongitude = behind.second,
            reportedSource = RadarBroadcastFix.SOURCE_CRUISE
        )
        assertEquals(RadarBroadcastFix.SOURCE_ROAD, fix.source)
        assertTrue(fix.longitude > carLon)
    }

    @Test
    fun keeps_a_point_on_a_gentle_bend() {
        val carLat = 30.274100
        val carLon = 120.155100
        val bend = checkNotNull(RadarBroadcastFix.project(carLat, carLon, 82f, 800))
        val fix = RadarBroadcastFix.resolve(
            carLatitude = carLat,
            carLongitude = carLon,
            bearingDegrees = 90f,
            distanceMeters = 900,
            reportedLatitude = bend.first,
            reportedLongitude = bend.second,
            reportedSource = RadarBroadcastFix.SOURCE_ROUTE
        )
        assertEquals(RadarBroadcastFix.SOURCE_ROUTE, fix.source)
        assertEquals(bend.first, fix.latitude, 0.000001)
    }

    @Test
    fun prefers_the_route_camera_when_distance_and_limit_match() {
        val page = RadarBroadcastFix.Candidate(
            latitude = 30.280000,
            longitude = 120.170000,
            distanceMeters = 480,
            cameraType = 4,
            speedLimit = 80
        )
        val otherLimit = page.copy(speedLimit = 100, latitude = 30.29)
        val matched = RadarBroadcastFix.matchCandidate(
            candidates = listOf(otherLimit, page),
            ageMs = 800L,
            cameraType = 4,
            speedLimit = 80,
            distanceMeters = 500
        )
        assertEquals(page, matched)
        assertNull(
            RadarBroadcastFix.matchCandidate(
                candidates = listOf(page),
                ageMs = 4_000L,
                cameraType = 4,
                speedLimit = 80,
                distanceMeters = 500
            )
        )
    }

    @Test
    fun holds_one_point_until_a_closer_look_moves_it() {
        val first = RadarBroadcastFix.Fix(30.274100, 120.160000, RadarBroadcastFix.SOURCE_ROAD)
        val held = RadarBroadcastFix.hold(null, first, 800)
        val nearby = RadarBroadcastFix.Fix(30.274120, 120.160050, RadarBroadcastFix.SOURCE_CRUISE)
        val still = RadarBroadcastFix.hold(held, nearby, 200)
        assertEquals(first.latitude, still.latitude, 0.000001)
        val closer = checkNotNull(RadarBroadcastFix.project(30.274100, 120.155100, 90f, 200))
        val updated = RadarBroadcastFix.hold(
            held,
            RadarBroadcastFix.Fix(closer.first, closer.second, RadarBroadcastFix.SOURCE_ROUTE),
            200
        )
        assertEquals(RadarBroadcastFix.SOURCE_ROUTE, updated.source)
        assertEquals(200, updated.distanceMeters)
    }
}
