package com.thomas.carspeed

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class RadarSighting(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val cameraType: Int,
    val speedLimit: Int?,
    val roadLayer: String,
    val usingDefault: Boolean,
    val correctedByDivergence: Boolean
)

internal data class TrackSample(
    val latitude: Double,
    val longitude: Double,
    val elapsedMs: Long
)

internal object RouteRadarMarks {
    const val LAYER_ELEVATED = "elevated"
    const val LAYER_GROUND = "ground"
    const val LAYER_TUNNEL = "tunnel"

    /** 旧录像没有当时的层级，是按轨迹事后向导航路线查到的。 */
    const val LAYER_LOOKED_UP = "looked-up"

    /** 导航算路电子眼类型。和巡航回调的 4 / 11 / 102 不是同一套编号。 */
    const val ROUTE_CAMERA_SPEED = 0
    const val ROUTE_CAMERA_INTERVAL_START = 8
    const val ROUTE_CAMERA_INTERVAL_END = 9
    const val ROUTE_CAMERA_FLOW = 10
    const val SIGHTING_KEEP_MS = 6 * 60 * 60 * 1000L
    const val SIGHTING_KEEP_MAX = 300

    fun layer(tunnel: Boolean, elevated: Boolean): String {
        return when {
            tunnel -> LAYER_TUNNEL
            elevated -> LAYER_ELEVATED
            else -> LAYER_GROUND
        }
    }

    fun locationKey(latitude: Double, longitude: Double): String {
        val longitudeKey = (longitude * 100_000).roundToInt()
        val latitudeKey = (latitude * 100_000).roundToInt()
        return "$longitudeKey:$latitudeKey"
    }

    fun remember(
        existing: List<RadarSighting>,
        incoming: RadarSighting,
        nowMs: Long
    ): List<RadarSighting> {
        val merged = upsert(existing, incoming)
        return merged
            .filter { nowMs - it.timeMs in 0..SIGHTING_KEEP_MS }
            .takeLast(SIGHTING_KEEP_MAX)
    }

    fun upsert(existing: List<RadarSighting>, incoming: RadarSighting): List<RadarSighting> {
        val key = locationKey(incoming.latitude, incoming.longitude)
        val index = existing.indexOfFirst { locationKey(it.latitude, it.longitude) == key }
        if (index < 0) return existing + incoming
        val current = existing[index]
        val speedLimit = current.speedLimit ?: incoming.speedLimit
        if (speedLimit == current.speedLimit) return existing
        return existing.toMutableList().also { sights ->
            sights[index] = current.copy(speedLimit = speedLimit)
        }
    }

    fun toTrackRadars(sightings: List<RadarSighting>, segmentStartMs: Long): List<VideoTrackRadar> {
        if (segmentStartMs <= 0L) return emptyList()
        return sightings.map { sighting ->
            VideoTrackRadar(
                elapsedMs = (sighting.timeMs - segmentStartMs).coerceAtLeast(0L),
                timeMs = sighting.timeMs,
                latitude = sighting.latitude,
                longitude = sighting.longitude,
                cameraType = sighting.cameraType,
                speedLimit = sighting.speedLimit,
                roadLayer = sighting.roadLayer,
                usingDefault = sighting.usingDefault,
                correctedByDivergence = sighting.correctedByDivergence
            )
        }
    }

    fun uniqueByLocation(radars: List<VideoTrackRadar>): List<VideoTrackRadar> {
        val kept = LinkedHashMap<String, VideoTrackRadar>()
        radars.forEach { radar ->
            val key = locationKey(radar.latitude, radar.longitude)
            val current = kept[key]
            if (current == null || (current.speedLimit == null && radar.speedLimit != null)) {
                kept[key] = radar
            }
        }
        return kept.values.toList()
    }

    /**
     * 同一限速合成一段。数字升序，没有限速的放在最后。
     * 例：测速 100kph×4 · 120kph×3
     */
    fun limitSummary(speedLimits: List<Int?>): String? {
        val known = speedLimits.mapNotNull { limit -> limit?.takeIf { it > 0 } }
            .groupingBy { it }
            .eachCount()
        val unknown = speedLimits.size - known.values.sum()
        if (known.isEmpty() && unknown <= 0) return null
        val parts = known.toSortedMap().map { (limit, count) -> "${limit}kph×$count" }.toMutableList()
        if (unknown > 0) parts += "无限速×$unknown"
        return "测速 " + parts.joinToString(" · ")
    }

    fun detail(
        cameraType: Int,
        speedLimit: Int?,
        roadLayer: String,
        usingDefault: Boolean,
        correctedByDivergence: Boolean
    ): String {
        val kind = when (cameraType) {
            RadarAlertPlanner.INTERVAL_CAMERA_TYPE -> "区间测速"
            RadarAlertPlanner.SPEEDING_VIOLATION_TYPE -> "超速违章"
            else -> "测速雷达"
        }
        val headline = when (roadLayer) {
            LAYER_ELEVATED -> "高架上的$kind"
            LAYER_GROUND -> "地面道路的$kind"
            LAYER_TUNNEL -> "隧道里的$kind"
            else -> kind
        }
        val lines = mutableListOf(headline)
        lines += speedLimit?.let { "限速 $it km/h" } ?: "暂无限速信息"
        if (usingDefault && roadLayer != LAYER_TUNNEL) {
            lines += "识别不出层级，当时按默认层级记下"
        }
        if (correctedByDivergence) {
            lines += "路线分开后，已按实际走的那条路修正"
        }
        if (roadLayer == LAYER_LOOKED_UP) {
            lines += "按这次行驶轨迹事后查到，没有当时的高架、地面或隧道"
        }
        return lines.joinToString("\n")
    }

    /**
     * 沿轨迹等距取样，给导航算路当起点、终点和途经点。
     * 点数已经不超过上限时原样返回。
     */
    fun sampleAlong(points: List<TrackSample>, maxCount: Int): List<TrackSample> {
        if (points.size <= 2 || maxCount < 2 || points.size <= maxCount) return points
        val distances = DoubleArray(points.size)
        for (index in 1 until points.size) {
            distances[index] = distances[index - 1] + meters(
                points[index - 1].latitude,
                points[index - 1].longitude,
                points[index].latitude,
                points[index].longitude
            )
        }
        val total = distances.last()
        if (total <= 1.0) return listOf(points.first(), points.last())
        val picked = mutableListOf<TrackSample>()
        var cursor = 0
        for (step in 0 until maxCount) {
            val target = total * step / (maxCount - 1).toDouble()
            while (cursor < distances.lastIndex && distances[cursor] < target) cursor++
            val point = points[cursor]
            if (picked.lastOrNull() != point) picked += point
        }
        if (picked.last() != points.last()) picked += points.last()
        return picked
    }

    /** 离轨迹超过 [maxMeters] 的电子眼不算这条路的，避免算路拐到旁边那条路。 */
    fun nearestWithin(
        latitude: Double,
        longitude: Double,
        points: List<TrackSample>,
        maxMeters: Double
    ): TrackSample? {
        var nearest: TrackSample? = null
        var nearestMeters = maxMeters
        points.forEach { point ->
            val meters = meters(latitude, longitude, point.latitude, point.longitude)
            if (meters <= nearestMeters) {
                nearest = point
                nearestMeters = meters
            }
        }
        return nearest
    }

    /**
     * 把导航路线上的电子眼收成回放标记。监控、闯红灯这类不是测速，直接丢掉。
     * 坐标已经是地图坐标。
     */
    fun fromRouteCamera(
        routeCameraType: Int,
        speedKmh: Int,
        latitude: Double,
        longitude: Double,
        elapsedMs: Long,
        roadLayer: String = LAYER_LOOKED_UP
    ): VideoTrackRadar? {
        val cameraType = when (routeCameraType) {
            ROUTE_CAMERA_SPEED, ROUTE_CAMERA_FLOW -> RadarAlertPlanner.SPEED_CAMERA_TYPE
            ROUTE_CAMERA_INTERVAL_START, ROUTE_CAMERA_INTERVAL_END -> RadarAlertPlanner.INTERVAL_CAMERA_TYPE
            else -> return null
        }
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (kotlin.math.abs(latitude) < 0.01 && kotlin.math.abs(longitude) < 0.01) return null
        return VideoTrackRadar(
            elapsedMs = elapsedMs,
            timeMs = 0L,
            latitude = latitude,
            longitude = longitude,
            cameraType = cameraType,
            speedLimit = speedKmh.takeIf { it > 0 },
            roadLayer = roadLayer,
            usingDefault = false,
            correctedByDivergence = false
        )
    }

    private fun meters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val earth = 6_371_000.0
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(toLon - fromLon)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earth * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
