package com.thomas.carspeed

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 雷达预警日志。坐标都是高德 GCJ-02，用来事后对播报时间和雷达点位置。
 * pos=cruise 是巡航回调原坐标，pos=route 是和查路线页同一套的电子眼，pos=road 是按剩余距离落到当前道路上的点。
 * radar 是我们真正用的点，radarRaw 是高德原本给的点，fixGap 是两者相差多少米——
 * 现场雷达位置和播报位置对不上时，先看 fixGap：偏得多的说明是被修正过的点，不是高德的原点。
 * 过滤：adb logcat -s RadarAlert
 */
internal object RadarAlertLog {
    const val TAG = "RadarAlert"

    fun line(event: Event): String {
        val calculated = distanceMeters(
            event.carLatitude,
            event.carLongitude,
            event.radarLatitude,
            event.radarLongitude
        )
        val fixGap = distanceMeters(
            event.radarLatitude,
            event.radarLongitude,
            event.reportedRadarLatitude,
            event.reportedRadarLongitude
        )
        val carPosition = isPosition(event.carLatitude, event.carLongitude)
        val radarPosition = isPosition(event.radarLatitude, event.radarLongitude)
        return buildString {
            append("t=").append(wallText(event.timeMs))
            append(" tRaw=").append(event.timeMs)
            append(" gpsTime=").append(if (event.gpsTimeMs > 0L) wallText(event.gpsTimeMs) else "-")
            append(" kind=").append(event.kind)
            append(" speech=").append(event.speech)
            append(" threshold=").append(event.thresholdSeconds?.toString() ?: "-")
            append(" lead=").append(event.leadSeconds)
            append(" rawEta=").append(event.rawEtaSeconds?.toString() ?: "-")
            append(" eta=").append(event.etaSeconds?.toString() ?: "-")
            append(" speed=").append(format1(event.speedKmh))
            append(" src=").append(event.speedSource)
            append(" naviAgeMs=").append(event.naviSpeedAgeMs)
            append(" gpsSpeed=").append(format1(event.gpsSpeedKmh))
            append(" distAmap=").append(event.amapDistanceMeters?.toString() ?: "-")
            append(" distCalc=").append(calculated?.toString() ?: "-")
            append(" car=").append(formatCoord(event.carLatitude, event.carLongitude))
            append(" bearing=").append(format1(event.carBearing))
            append(" roadBearing=").append(format1(event.roadBearing))
            append(" acc=").append(format1(event.carAccuracyMeters))
            append(" carAgeMs=").append(event.carAgeMs)
            append(" radar=").append(formatCoord(event.radarLatitude, event.radarLongitude))
            append(" radarRaw=").append(
                formatCoord(event.reportedRadarLatitude, event.reportedRadarLongitude)
            )
            append(" fixGap=").append(fixGap?.toString() ?: "-")
            append(" type=").append(event.cameraType)
            append(" limit=").append(event.speedLimit?.toString() ?: "-")
            append(" layer=").append(event.roadLayer)
            append(" default=").append(event.usingDefault)
            append(" corrected=").append(event.correctedByDivergence)
            append(" coord=GCJ02")
            append(" pos=").append(event.coordinateFix)
            append(" key=").append(event.cameraKey)
            append(" text=").append(event.text.ifBlank { "-" })
        }
    }

    /** 时间戳是毫秒，肉眼没法读；留档要能直接对上「我当时几点听到的」。 */
    fun wallText(timeMs: Long): String {
        if (timeMs <= 0L) return "-"
        return WALL_FORMAT.format(Date(timeMs))
    }
    /** 给 CSV 用：空值一律写空串而不是 "-"，否则 Excel 那一列整列变文本。 */
    fun formatDecimal(value: Float): String {
        if (value.isNaN() || value.isInfinite()) return ""
        return String.format(Locale.US, "%.6f", value)
    }

    /**
     * 坐标必须按 Double 写。
     * 转成 Float 再格式化会丢掉后两位（120.2010 变成 120.200996），
     * 差不多一米——留档本来就是拿来跟现场雷达对位置的，不能自己先引入误差。
     */
    fun formatCoordDecimal(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ""
        return String.format(Locale.US, "%.6f", value)
    }

    fun distanceMeters(
        latitude: Double,
        longitude: Double,
        otherLatitude: Double,
        otherLongitude: Double
    ): Int? {
        if (!isPosition(latitude, longitude) || !isPosition(otherLatitude, otherLongitude)) return null
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(otherLatitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(otherLongitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val meters = 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
        return meters.toInt()
    }

    private fun isPosition(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !latitude.isNaN() && !longitude.isNaN()
    }

    private fun formatCoord(latitude: Double, longitude: Double): String {
        if (!isPosition(latitude, longitude)) return "-"
        return String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
    }

    private fun format1(value: Float): String {
        if (value.isNaN()) return "-"
        return String.format(Locale.US, "%.1f", value)
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    private val WALL_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    internal data class Event(
        val kind: String,
        val speech: String,
        val text: String,
        val timeMs: Long,
        val gpsTimeMs: Long,
        val thresholdSeconds: Int?,
        val leadSeconds: Int,
        val rawEtaSeconds: Int?,
        val etaSeconds: Int?,
        val speedKmh: Float,
        val speedSource: String,
        val naviSpeedAgeMs: Long,
        val gpsSpeedKmh: Float,
        val amapDistanceMeters: Int?,
        val carLatitude: Double,
        val carLongitude: Double,
        val carBearing: Float,
        /** 高德给的「路的方向」。和车头方向差得多时，说明定位可能贴到了旁边那条路。 */
        val roadBearing: Float = Float.NaN,
        val carAccuracyMeters: Float,
        val carAgeMs: Long,
        val radarLatitude: Double,
        val radarLongitude: Double,
        /** 高德原本上报的电子眼坐标。和 radar 对不上时，说明我们自己修正过位置。 */
        val reportedRadarLatitude: Double = Double.NaN,
        val reportedRadarLongitude: Double = Double.NaN,
        val cameraType: Int,
        val speedLimit: Int?,
        val roadLayer: String,
        val usingDefault: Boolean,
        val correctedByDivergence: Boolean,
        val cameraKey: String,
        val coordinateFix: String = RadarBroadcastFix.SOURCE_CRUISE
    )
}
