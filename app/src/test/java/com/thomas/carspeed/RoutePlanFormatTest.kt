package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutePlanFormatTest {
    @Test
    fun keeps_speed_cameras_and_groups_their_limits() {
        val radars = RoutePlanFormat.radars(
            listOf(
                RouteCameraSnapshot(RouteRadarMarks.ROUTE_CAMERA_SPEED, 120, 30.1, 120.1),
                RouteCameraSnapshot(RouteRadarMarks.ROUTE_CAMERA_SPEED, 100, 30.2, 120.2),
                RouteCameraSnapshot(RouteRadarMarks.ROUTE_CAMERA_SPEED, 120, 30.3, 120.3),
                RouteCameraSnapshot(1, 80, 30.4, 120.4),
                RouteCameraSnapshot(RouteRadarMarks.ROUTE_CAMERA_INTERVAL_START, 100, 30.5, 120.5)
            )
        )

        assertEquals(4, radars.size)
        assertEquals(
            "测速雷达 4 个 · 100kph×2 · 120kph×2",
            RoutePlanFormat.summary(radars)
        )
        assertFalse(
            RouteRadarMarks.detail(
                cameraType = radars.first().cameraType,
                speedLimit = radars.first().speedLimit,
                roadLayer = radars.first().roadLayer,
                usingDefault = false,
                correctedByDivergence = false
            ).contains("事后查到")
        )
    }

    @Test
    fun empty_route_says_there_is_no_radar() {
        assertEquals("这条路线上没有测速雷达", RoutePlanFormat.summary(emptyList()))
    }

    @Test
    fun chip_shows_time_distance_toll_and_radar_count() {
        assertEquals(
            "速度最快\n1小时20分 · 86.5公里 · 收费25元\n雷达 7",
            RoutePlanFormat.chip(
                label = "速度最快",
                index = 0,
                durationSeconds = 80 * 60,
                distanceMeters = 86_500,
                tollYuan = 25,
                radarCount = 7
            )
        )
        assertEquals(
            "路线 2\n12分钟 · 800米\n雷达 0",
            RoutePlanFormat.chip(
                label = "  ",
                index = 1,
                durationSeconds = 12 * 60,
                distanceMeters = 800,
                tollYuan = 0,
                radarCount = 0
            )
        )
        assertEquals("1分钟以内", RoutePlanFormat.duration(20))
        assertEquals("2小时", RoutePlanFormat.duration(2 * 3600))
        assertEquals("120公里", RoutePlanFormat.distance(120_400))
    }
}
