package com.thomas.carspeed

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt

class MapMarkerScaleTest {
    @Test
    fun pulse_stays_a_small_slice_of_a_200m_view() {
        val widthPx = 1080f
        val widthMeters = 200.0
        val latitude = 30.0
        val zoom = zoomForScreenWidth(latitude, widthPx, widthMeters)
        // 外圈大约 16dp，按 xxhdpi 估算。
        val radiusMeters = MapMarkerScale.metersForPixels(16f * 3f, zoom, latitude)
        val diameterFraction = radiusMeters * 2 / widthMeters
        assertTrue("diameter fraction was $diameterFraction", diameterFraction < 0.2)
    }

    @Test
    fun zooming_in_shrinks_the_meter_radius() {
        val latitude = 30.0
        val pixels = 48f
        val at200m = MapMarkerScale.metersForPixels(pixels, zoomForScreenWidth(latitude, 1080f, 200.0), latitude)
        val at25m = MapMarkerScale.metersForPixels(pixels, zoomForScreenWidth(latitude, 1080f, 25.0), latitude)
        assertTrue(at25m < at200m)
        assertTrue(at25m * 2 < 25.0)
    }

    private fun zoomForScreenWidth(latitude: Double, widthPx: Float, widthMeters: Double): Float {
        val metersPerPixel = widthMeters / widthPx
        val latitudeFactor = cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
        val metersPerPixelAtZoomZero = MapMarkerScale.EQUATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO * latitudeFactor
        return (ln(metersPerPixelAtZoomZero / metersPerPixel) / ln(2.0)).roundToInt().toFloat()
    }
}
