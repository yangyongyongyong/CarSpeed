package com.thomas.carspeed

import kotlin.math.cos
import kotlin.math.pow

/**
 * 把屏幕上的固定像素换算成地图圆的米制半径。
 * 高德 Circle 的 radius 是米，比例尺放大后如果半径不跟着缩小，当前位置的圈会铺满地图。
 */
internal object MapMarkerScale {
    const val EQUATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO = 156543.03392

    fun metersForPixels(pixels: Float, zoom: Float, latitudeDegrees: Double): Double {
        if (pixels <= 0f || zoom.isNaN()) return 0.0
        val latitudeFactor = cos(Math.toRadians(latitudeDegrees)).coerceAtLeast(0.2)
        val metersPerPixel = EQUATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO * latitudeFactor / 2.0.pow(zoom.toDouble())
        return pixels.toDouble() * metersPerPixel
    }
}
