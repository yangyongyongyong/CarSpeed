package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal data class MapScreenPoint(val x: Int, val y: Int)

internal data class MapFitPoint(val latitude: Double, val longitude: Double)

internal data class MapFitCamera(val latitude: Double, val longitude: Double, val zoom: Int)

/**
 * 全程镜头按地图控件自己的像素来算，不交给高德按整屏去框。
 * 边距必须小于较短边的一半，否则这次缩放会被丢掉。
 */
internal object MapCameraFit {
    private const val METERS_PER_DEGREE = 111_319.49
    private const val MERCATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO = 156_543.03392
    private const val POINT_ZOOM = 16

    fun paddingPx(mapWidth: Int, mapHeight: Int, preferredPx: Int): Int {
        if (mapWidth <= 2 || mapHeight <= 2) return 0
        val room = min(mapWidth, mapHeight) / 2 - 1
        if (room <= 0) return 0
        return preferredPx.coerceIn(0, room)
    }

    fun fit(
        points: List<MapFitPoint>,
        viewWidthPx: Int,
        viewHeightPx: Int,
        paddingPx: Int,
        minZoom: Int,
        maxZoom: Int
    ): MapFitCamera? {
        if (points.isEmpty() || viewWidthPx <= 2 || viewHeightPx <= 2) return null
        val padding = paddingPx(viewWidthPx, viewHeightPx, paddingPx)
        val innerWidth = (viewWidthPx - padding * 2).coerceAtLeast(1)
        val innerHeight = (viewHeightPx - padding * 2).coerceAtLeast(1)
        val minLatitude = points.minOf { it.latitude }
        val maxLatitude = points.maxOf { it.latitude }
        val minLongitude = points.minOf { it.longitude }
        val maxLongitude = points.maxOf { it.longitude }
        val latitude = (minLatitude + maxLatitude) / 2.0
        val longitude = (minLongitude + maxLongitude) / 2.0
        val latitudeSpan = maxLatitude - minLatitude
        val longitudeSpan = maxLongitude - minLongitude
        val latitudeFactor = abs(cos(Math.toRadians(latitude))).coerceAtLeast(0.2)
        val latitudeMeters = abs(latitudeSpan) * METERS_PER_DEGREE
        val longitudeMeters = abs(longitudeSpan) * METERS_PER_DEGREE * latitudeFactor
        if (latitudeMeters < 1.0 && longitudeMeters < 1.0) {
            return MapFitCamera(latitude, longitude, POINT_ZOOM.coerceIn(minZoom, maxZoom))
        }
        val metersPerPixel = maxOf(latitudeMeters / innerHeight, longitudeMeters / innerWidth)
        val metersAtZoomZero = MERCATOR_METERS_PER_PIXEL_AT_ZOOM_ZERO * latitudeFactor
        val zoom = floor(ln(metersAtZoomZero / metersPerPixel) / ln(2.0)).toInt()
        return MapFitCamera(latitude, longitude, zoom.coerceIn(minZoom, maxZoom))
    }

    /**
     * 算出来的级别如果仍然把路线裁到画面外，返回还要再缩小的级数。
     * 以画面中心为基准，看最远的点超出可画区域多少倍。
     */
    fun zoomLevelsTooClose(
        points: List<MapScreenPoint>,
        viewWidthPx: Int,
        viewHeightPx: Int,
        paddingPx: Int
    ): Int {
        if (points.isEmpty() || viewWidthPx <= 2 || viewHeightPx <= 2) return 0
        val distinct = points.any { it.x != points[0].x || it.y != points[0].y }
        if (!distinct) return 0
        val padding = paddingPx(viewWidthPx, viewHeightPx, paddingPx)
        val halfWidth = (viewWidthPx - padding * 2) / 2.0
        val halfHeight = (viewHeightPx - padding * 2) / 2.0
        if (halfWidth <= 1.0 || halfHeight <= 1.0) return 0
        val centerX = viewWidthPx / 2.0
        val centerY = viewHeightPx / 2.0
        var ratio = 1.0
        points.forEach { point ->
            ratio = max(ratio, abs(point.x - centerX) / halfWidth)
            ratio = max(ratio, abs(point.y - centerY) / halfHeight)
        }
        if (ratio <= 1.02) return 0
        return ceil(ln(ratio) / ln(2.0)).toInt().coerceAtLeast(1)
    }
}
