package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRadarMarksTest {
    @Test
    fun layer_names_match_elevated_ground_and_tunnel() {
        assertEquals(RouteRadarMarks.LAYER_TUNNEL, RouteRadarMarks.layer(tunnel = true, elevated = true))
        assertEquals(RouteRadarMarks.LAYER_ELEVATED, RouteRadarMarks.layer(tunnel = false, elevated = true))
        assertEquals(RouteRadarMarks.LAYER_GROUND, RouteRadarMarks.layer(tunnel = false, elevated = false))
    }

    @Test
    fun same_place_keeps_the_first_sighting_and_fills_in_a_missing_limit() {
        val first = sighting(speedLimit = null)
        val withLimit = first.copy(speedLimit = 80, timeMs = 2_000L)
        val again = withLimit.copy(speedLimit = 100, timeMs = 3_000L)

        val once = RouteRadarMarks.remember(emptyList(), first, nowMs = 1_000L)
        val filled = RouteRadarMarks.remember(once, withLimit, nowMs = 2_000L)
        val stable = RouteRadarMarks.remember(filled, again, nowMs = 3_000L)

        assertEquals(1, stable.size)
        assertEquals(80, stable.single().speedLimit)
        assertEquals(1_000L, stable.single().timeMs)
    }

    @Test
    fun detail_says_which_road_layer_the_radar_belongs_to() {
        assertEquals(
            "高架上的测速雷达\n限速 120 km/h\n识别不出层级，当时按默认层级记下",
            RouteRadarMarks.detail(
                cameraType = RadarAlertPlanner.SPEED_CAMERA_TYPE,
                speedLimit = 120,
                roadLayer = RouteRadarMarks.LAYER_ELEVATED,
                usingDefault = true,
                correctedByDivergence = false
            )
        )
        assertEquals(
            "地面道路的区间测速\n限速 80 km/h\n路线分开后，已按实际走的那条路修正",
            RouteRadarMarks.detail(
                cameraType = RadarAlertPlanner.INTERVAL_CAMERA_TYPE,
                speedLimit = 80,
                roadLayer = RouteRadarMarks.LAYER_GROUND,
                usingDefault = false,
                correctedByDivergence = true
            )
        )
        assertEquals(
            "隧道里的超速违章\n暂无限速信息",
            RouteRadarMarks.detail(
                cameraType = RadarAlertPlanner.SPEEDING_VIOLATION_TYPE,
                speedLimit = null,
                roadLayer = RouteRadarMarks.LAYER_TUNNEL,
                usingDefault = true,
                correctedByDivergence = false
            )
        )
    }

    @Test
    fun limit_summary_groups_counts_by_speed_and_leaves_unknown_last() {
        assertEquals(
            "测速 100kph×4 · 120kph×3",
            RouteRadarMarks.limitSummary(listOf(120, 100, 120, 100, 100, 120, 100))
        )
        assertEquals(
            "测速 80kph×1 · 无限速×2",
            RouteRadarMarks.limitSummary(listOf(null, 80, 0))
        )
        assertEquals(null, RouteRadarMarks.limitSummary(emptyList()))
    }

    @Test
    fun segment_radars_use_elapsed_time_from_the_segment_start() {
        val sighting = sighting(timeMs = 10_500L)
        val radars = RouteRadarMarks.toTrackRadars(listOf(sighting), segmentStartMs = 10_000L)
        assertEquals(500L, radars.single().elapsedMs)
        assertTrue(RouteRadarMarks.toTrackRadars(listOf(sighting), segmentStartMs = 0L).isEmpty())
    }

    @Test
    fun looked_up_route_camera_keeps_speed_and_interval_only() {
        val speed = RouteRadarMarks.fromRouteCamera(
            routeCameraType = RouteRadarMarks.ROUTE_CAMERA_SPEED,
            speedKmh = 120,
            latitude = 30.2,
            longitude = 120.1,
            elapsedMs = 4_000L
        )
        val interval = RouteRadarMarks.fromRouteCamera(
            routeCameraType = RouteRadarMarks.ROUTE_CAMERA_INTERVAL_START,
            speedKmh = 0,
            latitude = 30.3,
            longitude = 120.2,
            elapsedMs = 8_000L
        )
        val surveillance = RouteRadarMarks.fromRouteCamera(
            routeCameraType = 1,
            speedKmh = 80,
            latitude = 30.4,
            longitude = 120.3,
            elapsedMs = 1_000L
        )

        assertEquals(RadarAlertPlanner.SPEED_CAMERA_TYPE, speed?.cameraType)
        assertEquals(120, speed?.speedLimit)
        assertEquals(RouteRadarMarks.LAYER_LOOKED_UP, speed?.roadLayer)
        assertEquals(RadarAlertPlanner.INTERVAL_CAMERA_TYPE, interval?.cameraType)
        assertEquals(null, interval?.speedLimit)
        assertEquals(null, surveillance)
        assertTrue(
            RouteRadarMarks.detail(
                cameraType = RadarAlertPlanner.SPEED_CAMERA_TYPE,
                speedLimit = 120,
                roadLayer = RouteRadarMarks.LAYER_LOOKED_UP,
                usingDefault = false,
                correctedByDivergence = false
            ).contains("事后查到")
        )
    }

    @Test
    fun sample_along_keeps_the_ends_and_drops_cameras_far_from_the_track() {
        val points = listOf(
            TrackSample(30.0, 120.0, 0L),
            TrackSample(30.01, 120.0, 1_000L),
            TrackSample(30.02, 120.0, 2_000L)
        )
        val sampled = RouteRadarMarks.sampleAlong(points, maxCount = 2)
        assertEquals(listOf(points.first(), points.last()), sampled)

        val near = RouteRadarMarks.nearestWithin(30.01, 120.0001, points, maxMeters = 150.0)
        assertEquals(1_000L, near?.elapsedMs)
        assertEquals(null, RouteRadarMarks.nearestWithin(30.2, 120.0, points, maxMeters = 150.0))
    }

    private fun sighting(speedLimit: Int? = null, timeMs: Long = 1_000L): RadarSighting {
        return RadarSighting(
            timeMs = timeMs,
            latitude = 30.1,
            longitude = 120.2,
            cameraType = RadarAlertPlanner.SPEED_CAMERA_TYPE,
            speedLimit = speedLimit,
            roadLayer = RouteRadarMarks.LAYER_ELEVATED,
            usingDefault = false,
            correctedByDivergence = false
        )
    }
}
