package com.thomas.carspeed

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 雷达预警日志。坐标都是高德 GCJ-02，用来事后对播报时间和雷达点位置。
 * pos=cruise 是巡航回调原坐标，pos=route 是和查路线页同一套的电子眼，pos=road 是按剩余距离落到当前道路上的点。
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
        return buildString {
            append("kind=").append(event.kind)
            append(" speech=").append(event.speech)
            append(" t=").append(event.timeMs)
            append(" gpsTime=").append(if (event.gpsTimeMs > 0L) event.gpsTimeMs.toString() else "-")
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
            append(" acc=").append(format1(event.carAccuracyMeters))
            append(" carAgeMs=").append(event.carAgeMs)
            append(" radar=").append(formatCoord(event.radarLatitude, event.radarLongitude))
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
        val carAccuracyMeters: Float,
        val carAgeMs: Long,
        val radarLatitude: Double,
        val radarLongitude: Double,
        val cameraType: Int,
        val speedLimit: Int?,
        val roadLayer: String,
        val usingDefault: Boolean,
        val correctedByDivergence: Boolean,
        val cameraKey: String,
        val coordinateFix: String = RadarBroadcastFix.SOURCE_CRUISE
    )
}
