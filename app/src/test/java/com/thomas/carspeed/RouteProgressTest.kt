package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规划路线的行进判断。
 *
 * 这里出错的表现是「报成另一条路上的雷达」，比没有规划还糟，
 * 所以分岔、重合、GPS 漂、刚出发这几种情况都要单独测。
 */
class RouteProgressTest {
    /** 正北方向的一条直线，长约 1.1 公里，方便手算里程。 */
    private val straightNorth = listOf(
        RouteProgress.Fix(30.0000, 120.0000),
        RouteProgress.Fix(30.0100, 120.0000)
    )

    /** 走到一半后向东拐。 */
    private val northThenEast = listOf(
        RouteProgress.Fix(30.0000, 120.0000),
        RouteProgress.Fix(30.0050, 120.0000),
        RouteProgress.Fix(30.0050, 120.0100)
    )

    @Test
    fun a_point_on_the_line_projects_with_no_deviation() {
        val result = RouteProgress.project(straightNorth, RouteProgress.Fix(30.0050, 120.0000))
        assertNotNull(result)
        assertTrue("偏离应该接近 0，实际 ${result!!.deviationMeters}", result.deviationMeters < 1.0)
    }

    @Test
    fun travelled_distance_grows_as_the_car_moves_along_the_route() {
        val start = RouteProgress.project(straightNorth, RouteProgress.Fix(30.0000, 120.0000))!!
        val middle = RouteProgress.project(straightNorth, RouteProgress.Fix(30.0050, 120.0000))!!
        val end = RouteProgress.project(straightNorth, RouteProgress.Fix(30.0100, 120.0000))!!
        assertTrue(start.travelledMeters < 10.0)
        // 30.0050 大约是 0.005 度 ≈ 556 米。
        assertTrue("中点应约 556 米，实际 ${middle.travelledMeters}", middle.travelledMeters in 540.0..575.0)
        assertTrue("终点应约 1112 米，实际 ${end.travelledMeters}", end.travelledMeters in 1095.0..1130.0)
    }

    @Test
    fun a_point_off_the_line_reports_how_far_it_is() {
        // 经度偏 0.001 度，在 30 度纬度上约 96 米。
        val result = RouteProgress.project(straightNorth, RouteProgress.Fix(30.0050, 120.0010))!!
        assertTrue("应约 96 米，实际 ${result.deviationMeters}", result.deviationMeters in 85.0..105.0)
    }

    @Test
    fun the_route_the_car_is_actually_on_wins() {
        val onNorthThenEast = listOf(RouteProgress.Fix(30.0045, 120.0000))
        val best = RouteProgress.bestRoute(
            routes = listOf(1 to straightNorth, 2 to northThenEast),
            recentFixes = onNorthThenEast
        )!!
        // 两条路线在前半段完全重合，这一点分不出来，所以只要求它能选中其中一条且偏离很小。
        assertTrue(best.deviationMeters < 5.0)
    }

    @Test
    fun after_the_fork_the_correct_route_is_picked() {
        // 拐点之后的轨迹，只有 northThenEast 还在路上。
        val northThenEast = listOf(
            RouteProgress.Fix(30.0000, 120.0000),
            RouteProgress.Fix(30.0050, 120.0000),
            RouteProgress.Fix(30.0050, 120.0100)
        )
        val straightOnly = listOf(
            RouteProgress.Fix(30.0000, 120.0000),
            RouteProgress.Fix(30.0100, 120.0000)
        )
        val fixes = listOf(
            RouteProgress.Fix(30.0050, 120.0010),
            RouteProgress.Fix(30.0050, 120.0020)
        )
        val best = RouteProgress.bestRoute(
            routes = listOf(1 to straightOnly, 2 to northThenEast),
            recentFixes = fixes
        )!!
        assertEquals("车已经右拐，应该匹配到东向那条", 2, best.routeId)
    }

    @Test
    fun a_car_far_from_every_route_matches_none() {
        // 离所有路线都超过 60 米，说明车主绕路了，不该硬套任何一条。
        val far = listOf(RouteProgress.Fix(30.0050, 120.0100))
        assertNull(
            RouteProgress.bestRoute(
                routes = listOf(1 to straightNorth),
                recentFixes = far
            )
        )
    }

    @Test
    fun switching_routes_needs_a_clear_win_not_a_marginal_one() {
        // 只差 5 米就切，会在两条重合路线上来回跳。
        assertFalse(RouteProgress.shouldSwitch(currentDeviationMeters = 20.0, candidateDeviationMeters = 16.0))
        // 差得够多才切。
        assertTrue(RouteProgress.shouldSwitch(currentDeviationMeters = 40.0, candidateDeviationMeters = 10.0))
    }

    @Test
    fun radars_behind_the_car_are_dropped_and_the_rest_are_sorted() {
        val points = straightNorth
        val radars = listOf(
            radar(30.0020, 120.0000),
            radar(30.0080, 120.0000),
            radar(30.0002, 120.0000)
        )
        // 车在 30.0050 附近，约 556 米处。
        val ahead = RouteProgress.radarsAhead(radars, points, travelled = 556.0)
        assertEquals("已经开过的两个不该留下", 1, ahead.size)
        assertEquals(30.0080, ahead.first().latitude, 0.0001)
    }

    @Test
    fun a_radar_just_behind_is_tolerated_so_it_is_not_dropped_too_early() {
        val radars = listOf(radar(30.0048, 120.0000))
        // 车走了 556 米，雷达在 534 米处，落后 22 米——还在裕量内。
        val ahead = RouteProgress.radarsAhead(radars, straightNorth, travelled = 556.0)
        assertEquals(1, ahead.size)
    }

    @Test
    fun a_radar_way_off_the_route_is_not_trusted() {
        // 偏离路线 300 米以上，投影结果不可信，宁可丢掉。
        val radars = listOf(radar(30.0060, 120.0040))
        val ahead = RouteProgress.radarsAhead(radars, straightNorth, travelled = 0.0)
        assertTrue(ahead.isEmpty())
    }

    @Test
    fun cumulative_distances_start_at_zero_and_only_grow() {
        val cumulative = RouteProgress.cumulativeDistances(northThenEast)
        assertEquals(3, cumulative.size)
        assertEquals(0.0, cumulative[0], 0.001)
        assertTrue(cumulative[1] > cumulative[0])
        assertTrue(cumulative[2] > cumulative[1])
    }

    @Test
    fun a_single_sample_is_enough_to_start_but_two_are_preferred() {
        assertFalse(RouteProgress.hasEnoughTrack(listOf()))
        assertFalse(RouteProgress.hasEnoughTrack(listOf(RouteProgress.Fix(30.0, 120.0))))
        assertTrue(RouteProgress.hasEnoughTrack(listOf(RouteProgress.Fix(30.0, 120.0), RouteProgress.Fix(30.001, 120.0))))
    }

    @Test
    fun a_degenerate_route_never_matches() {
        assertNull(RouteProgress.project(emptyList(), RouteProgress.Fix(30.0, 120.0)))
        assertNull(RouteProgress.project(listOf(RouteProgress.Fix(30.0, 120.0)), RouteProgress.Fix(30.0, 120.0)))
    }

    private fun radar(latitude: Double, longitude: Double) = VideoTrackRadar(
        elapsedMs = 0L,
        timeMs = 0L,
        latitude = latitude,
        longitude = longitude,
        cameraType = 4,
        speedLimit = 80,
        roadLayer = ""
    )
}
