package com.thomas.carspeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraFitTest {
    @Test
    fun large_map_keeps_the_preferred_padding() {
        assertEquals(48, MapCameraFit.paddingPx(800, 600, 48))
    }

    @Test
    fun small_float_window_shrinks_padding_so_the_route_still_fits() {
        val padding = MapCameraFit.paddingPx(mapWidth = 160, mapHeight = 140, preferredPx = 80)
        assertTrue(padding * 2 < 140)
        assertEquals(69, padding)
    }

    @Test
    fun unknown_size_does_not_add_padding() {
        assertEquals(0, MapCameraFit.paddingPx(0, 200, 18))
        assertEquals(0, MapCameraFit.paddingPx(2, 2, 18))
    }

    @Test
    fun whole_route_stays_inside_a_small_window_by_zooming_out() {
        val points = listOf(
            MapFitPoint(30.20, 120.10),
            MapFitPoint(30.30, 120.20)
        )
        val large = MapCameraFit.fit(points, 1000, 1000, 0, 3, 21)
        val small = MapCameraFit.fit(points, 200, 160, 0, 3, 21)
        assertEquals(30.25, large!!.latitude, 0.0001)
        assertEquals(120.15, large.longitude, 0.0001)
        assertTrue(small!!.zoom < large.zoom)
        assertTrue(small.zoom >= 3)
    }

    @Test
    fun center_follows_the_ends_of_the_route_not_where_most_points_sit() {
        val points = List(20) { MapFitPoint(30.20, 120.10) } + MapFitPoint(30.40, 120.30)
        val camera = MapCameraFit.fit(points, 400, 300, 0, 3, 21)
        assertEquals(30.30, camera!!.latitude, 0.0001)
        assertEquals(120.20, camera.longitude, 0.0001)
    }

    @Test
    fun points_outside_the_view_ask_for_another_zoom_out() {
        assertEquals(
            0,
            MapCameraFit.zoomLevelsTooClose(listOf(MapScreenPoint(20, 20), MapScreenPoint(180, 80)), 200, 100, 0)
        )
        assertEquals(
            1,
            MapCameraFit.zoomLevelsTooClose(listOf(MapScreenPoint(100, 50), MapScreenPoint(300, 50)), 200, 100, 0)
        )
        assertEquals(
            2,
            MapCameraFit.zoomLevelsTooClose(listOf(MapScreenPoint(100, 50), MapScreenPoint(500, 50)), 200, 100, 0)
        )
        assertEquals(0, MapCameraFit.zoomLevelsTooClose(emptyList(), 200, 100, 0))
        assertEquals(0, MapCameraFit.zoomLevelsTooClose(listOf(MapScreenPoint(0, 0), MapScreenPoint(0, 0)), 200, 100, 0))
    }

    @Test
    fun a_single_point_uses_a_street_zoom() {
        val camera = MapCameraFit.fit(listOf(MapFitPoint(30.27, 120.15)), 300, 200, 12, 3, 21)
        assertEquals(16, camera!!.zoom)
    }
}
