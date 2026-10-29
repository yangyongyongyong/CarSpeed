package com.thomas.carspeed

import com.amap.api.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 车在几条规划路线之间切换时的判断。
 *
 * 这是「知道目的地」这件事真正有用的地方：用户从 A 线改走 B 线，
 * 预警要跟着换到 B 线的雷达，而不是继续按 A 线报。
 */
class PlannedRouteTrackerTest {
    private fun radar(latitude: Double, longitude: Double, limit: Int = 80) = VideoTrackRadar(
        elapsedMs = 0L, timeMs = 0L,
        latitude = latitude, longitude = longitude,
        cameraType = 4, speedLimit = limit, roadLayer = ""
    )

    /** 两条路线共用一个起点，往北走一段后分岔：A 继续北，B 向东。 */
    private val common = listOf(LatLng(30.0000, 120.0000), LatLng(30.0050, 120.0000))
    private val routeA = common + listOf(LatLng(30.0150, 120.0000))
    private val routeB = common + listOf(LatLng(30.0050, 120.0150))

    private fun state(routes: List<PlannedRoute>): PlannedRouteStore.State =
        PlannedRouteStore.State(
            endName = "公司",
            endLatitude = 30.015,
            endLongitude = 120.015,
            routes = routes,
            selectedRouteId = routes.first().id,
            savedAtMs = 1L
        )

    private fun planned(id: Int, points: List<LatLng>, radars: List<VideoTrackRadar>) = PlannedRoute(
        id = id,
        label = "路线$id",
        durationSeconds = 1200,
        distanceMeters = 2000,
        tollYuan = 0,
        points = points,
        radars = radars
    )

    @Test
    fun before_any_fix_the_tracker_starts_on_the_route_the_user_picked() {
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(planned(1, routeA, emptyList()), planned(2, routeB, emptyList()))
            .let { it }).copy(selectedRouteId = 2))
        assertEquals(2, tracker.currentRouteId)
    }

    @Test
    fun a_car_that_turns_east_switches_from_route_a_to_route_b() {
        val a = planned(1, routeA, listOf(radar(30.0100, 120.0000)))
        val b = planned(2, routeB, listOf(radar(30.0050, 120.0100)))
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a, b)))
        assertEquals("一开始按用户选的 A 线", 1, tracker.currentRouteId)

        // 先沿共同段走，再拐向东。
        listOf(30.0010, 30.0020, 30.0040, 30.0050).forEach { lat ->
            tracker.onFix(RouteProgress.Fix(lat, 120.0000))
        }
        listOf(120.0030, 120.0060, 120.0090, 120.0110).forEach { lon ->
            tracker.onFix(RouteProgress.Fix(30.0050, lon))
        }
        assertEquals("拐向东之后应该换到 B 线", 2, tracker.currentRouteId)
    }

    @Test
    fun after_switching_the_next_radar_comes_from_the_new_route() {
        val a = planned(1, routeA, listOf(radar(30.0140, 120.0000)))
        val b = planned(2, routeB, listOf(radar(30.0050, 120.0120)))
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a, b)))
        listOf(30.0010, 30.0020, 30.0040, 30.0050).forEach { tracker.onFix(RouteProgress.Fix(it, 120.0000)) }
        listOf(120.0030, 120.0060, 120.0090).forEach { tracker.onFix(RouteProgress.Fix(30.0050, it)) }

        val next = tracker.nextRadarMeters()
        assertNotNull("换到 B 线后仍应有下一个雷达", next)
        assertEquals(2, tracker.currentRouteId)
        // B 线的雷达在东向约 120.0090→120.0120 之间，也就是几百米之内，不可能是 A 线那个 1 公里外的。
        assertTrue("下一个雷达应在 B 线上，实际 $next", next!! < 400)
    }

    @Test
    fun staying_on_the_same_road_never_switches() {
        val a = planned(1, routeA, listOf(radar(30.0100, 120.0000)))
        val b = planned(2, routeB, emptyList())
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a, b)))
        listOf(30.0010, 30.0030, 30.0050, 30.0070).forEach {
            tracker.onFix(RouteProgress.Fix(it, 120.0000))
        }
        assertEquals("一直往北走，不该换路线", 1, tracker.currentRouteId)
    }

    @Test
    fun a_car_that_leaves_every_route_keeps_the_last_judgement() {
        val a = planned(1, routeA, emptyList())
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a)))
        tracker.onFix(RouteProgress.Fix(30.0020, 120.0000))
        // 飘到离路线几公里外，所有候选都不匹配。
        tracker.onFix(RouteProgress.Fix(30.2000, 120.2000))
        assertEquals("判不出来时沿用上一次，不要清空", 1, tracker.currentRouteId)
    }

    @Test
    fun a_single_fix_is_not_enough_to_switch() {
        val a = planned(1, routeA, emptyList())
        val b = planned(2, routeB, emptyList())
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a, b)))
        // 只有一帧，即便它在 B 线上也不切。
        tracker.onFix(RouteProgress.Fix(30.0010, 120.0000))
        assertEquals(1, tracker.currentRouteId)
    }

    @Test
    fun without_a_bound_plan_the_tracker_reports_nothing() {
        val tracker = PlannedRouteTracker()
        tracker.bind(null)
        assertNull(tracker.currentRoute())
        assertNull(tracker.nextRadarMeters())
        assertNull(tracker.remainingMeters())
    }

    @Test
    fun the_remaining_distance_to_the_destination_shrinks_as_the_car_moves() {
        val a = planned(1, routeA, emptyList())
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a)))
        listOf(30.0010, 30.0030).forEach { tracker.onFix(RouteProgress.Fix(it, 120.0000)) }
        val early = tracker.remainingMeters()!!
        listOf(30.0080, 30.0120).forEach { tracker.onFix(RouteProgress.Fix(it, 120.0000)) }
        val later = tracker.remainingMeters()!!
        assertTrue("开了一段路，剩余里程应该变小：$early → $later", later < early)
    }

    @Test
    fun a_route_with_no_radars_reports_no_next_radar() {
        val a = planned(1, routeA, emptyList())
        val tracker = PlannedRouteTracker()
        tracker.bind(state(listOf(a)))
        tracker.onFix(RouteProgress.Fix(30.0010, 120.0000))
        assertNull(tracker.nextRadarMeters())
    }
}
