package com.thomas.carspeed

import com.amap.api.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedRouteStoreTest {
    private fun route(id: Int = 1, label: String = "推荐") = PlannedRoute(
        id = id,
        label = label,
        durationSeconds = 1800,
        distanceMeters = 25_000,
        tollYuan = 15,
        points = listOf(LatLng(30.0, 120.0), LatLng(30.01, 120.01), LatLng(30.02, 120.02)),
        radars = listOf(
            VideoTrackRadar(0L, 0L, 30.011, 120.011, cameraType = 4, speedLimit = 80, roadLayer = "ground"),
            VideoTrackRadar(0L, 0L, 30.015, 120.015, cameraType = 8, speedLimit = null, roadLayer = "elevated")
        )
    )

    private fun state(routes: List<PlannedRoute> = listOf(route())) = PlannedRouteStore.State(
        endName = "公司",
        endLatitude = 30.02,
        endLongitude = 120.02,
        routes = routes,
        selectedRouteId = routes.first().id,
        savedAtMs = 1_700_000_000_000L
    )

    @Test
    fun a_saved_plan_survives_the_round_trip() {
        val original = state(listOf(route(1), route(2, "备选")))
        val restored = PlannedRouteStore.decode(PlannedRouteStore.encode(original))!!
        assertEquals("公司", restored.endName)
        assertEquals(30.02, restored.endLatitude, 0.000001)
        assertEquals(2, restored.routes.size)
        assertEquals("备选", restored.routes[1].label)
        assertEquals(25_000, restored.routes[0].distanceMeters)
    }

    @Test
    fun the_route_geometry_and_radars_survive() {
        val restored = PlannedRouteStore.decode(PlannedRouteStore.encode(state()))!!
        val route = restored.routes.first()
        assertEquals(3, route.points.size)
        assertEquals(30.01, route.points[1].latitude, 0.000001)
        assertEquals(2, route.radars.size)
        assertEquals(80, route.radars[0].speedLimit)
        assertNull("无名限速要还回来是 null，不能变成 0", route.radars[1].speedLimit)
        assertEquals("elevated", route.radars[1].roadLayer)
    }

    @Test
    fun a_corrupt_payload_is_ignored_instead_of_crashing() {
        assertNull(PlannedRouteStore.decode("not json"))
        assertNull(PlannedRouteStore.decode(""))
        assertNull(PlannedRouteStore.decode("{}"))
    }

    @Test
    fun a_route_with_too_few_points_is_dropped() {
        val broken = route().copy(points = listOf(LatLng(30.0, 120.0)))
        assertNull(
            "只有一个点的路线没法用来算里程",
            PlannedRouteStore.decode(
                PlannedRouteStore.encode(
                    PlannedRouteStore.State("x", 30.0, 120.0, listOf(broken), 1, 1L)
                )
            )
        )
    }

    @Test
    fun a_place_name_with_separators_survives_the_round_trip() {
        // 地名里带竖线或换行的极端情况：不转义的话整条存档都会错位。
        val tricky = state().copy(endName = "公司|A栋\n3楼")
        val restored = PlannedRouteStore.decode(PlannedRouteStore.encode(tricky))!!
        assertEquals("公司|A栋\n3楼", restored.endName)
    }

    @Test
    fun an_unknown_selected_route_falls_back_to_the_first_one() {
        val plan = state(listOf(route(1), route(2, "备选"))).copy(selectedRouteId = 99)
        val restored = PlannedRouteStore.decode(PlannedRouteStore.encode(plan))!!
        assertEquals("选中的路线不存在时应该退回第一条", 1, restored.selectedRouteId)
    }
}
