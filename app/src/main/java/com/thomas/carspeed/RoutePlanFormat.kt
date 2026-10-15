package com.thomas.carspeed

import java.util.Locale

internal data class RouteCameraSnapshot(
    val type: Int,
    val speedKmh: Int,
    val latitude: Double,
    val longitude: Double
)

/**
 * 路线方案上的测速雷达怎么汇总。
 * 驾车规划结果里没有车道数，这里只处理电子眼、时间和里程。
 */
internal object RoutePlanFormat {
    fun radars(cameras: List<RouteCameraSnapshot>): List<VideoTrackRadar> {
        return RouteRadarMarks.uniqueByLocation(
            cameras.mapNotNull { camera ->
                RouteRadarMarks.fromRouteCamera(
                    routeCameraType = camera.type,
                    speedKmh = camera.speedKmh,
                    latitude = camera.latitude,
                    longitude = camera.longitude,
                    elapsedMs = 0L,
                    roadLayer = ""
                )
            }
        )
    }

    fun summary(radars: List<VideoTrackRadar>): String {
        if (radars.isEmpty()) return "这条路线上没有测速雷达"
        val grouped = RouteRadarMarks.limitSummary(radars.map { it.speedLimit })
            ?.removePrefix("测速 ")
        val count = "测速雷达 ${radars.size} 个"
        return if (grouped.isNullOrBlank()) count else "$count · $grouped"
    }

    fun chip(
        label: String,
        index: Int,
        durationSeconds: Int,
        distanceMeters: Int,
        tollYuan: Int,
        radarCount: Int
    ): String {
        val title = label.trim().ifBlank { "路线 ${index + 1}" }
        val toll = if (tollYuan > 0) " · 收费${tollYuan}元" else ""
        return "$title\n${duration(durationSeconds)} · ${distance(distanceMeters)}$toll\n雷达 $radarCount"
    }

    fun duration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        if (safe < 60) return "1分钟以内"
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分钟"
        }
    }

    fun distance(meters: Int): String {
        val safe = meters.coerceAtLeast(0)
        if (safe < 1000) return "${safe}米"
        val km = safe / 1000.0
        return if (km >= 100.0) {
            "${km.toInt()}公里"
        } else {
            String.format(Locale.US, "%.1f公里", km)
        }
    }
}
