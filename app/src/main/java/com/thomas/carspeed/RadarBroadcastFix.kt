package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 巡航播报用的雷达点，要和查路线页落在同一套地图坐标上。
 * 查路线页来自导航算路电子眼。巡航设施自己带的坐标经常偏到旁边，
 * 但剩余距离是沿当前道路量的。坐标和这段距离对得上才采用；对不上就从车的位置沿道路方向量出去。
 */
internal object RadarBroadcastFix {
    const val SOURCE_CRUISE = "cruise"
    const val SOURCE_ROUTE = "route"
    const val SOURCE_ROAD = "road"
    const val SOURCE_NONE = "none"

    private const val CROSS_TRACK_MAX_METERS = 150.0
    private const val ALONG_SLACK_METERS = 120.0
    private const val ALONG_RATIO = 0.45
    private const val BEHIND_TOLERANCE_METERS = 40.0
    private const val HOLD_MOVE_METERS = 40
    private const val HOLD_CLOSER_METERS = 80
    private const val CANDIDATE_MAX_AGE_MS = 3_000L

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val source: String
    )

    data class Held(
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Int,
        val source: String
    )

    /** 和查路线页同一套的电子眼，用来替换巡航设施坐标。 */
    data class Candidate(
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Int,
        val cameraType: Int,
        val speedLimit: Int?
    )

    fun bearing(roadBearing: Float, courseBearing: Float): Float {
        if (roadBearing in 0f..360f) return normalize(roadBearing)
        if (courseBearing in 0f..360f) return normalize(courseBearing)
        return Float.NaN
    }

    fun matchCandidate(
        candidates: List<Candidate>,
        ageMs: Long,
        cameraType: Int,
        speedLimit: Int?,
        distanceMeters: Int
    ): Candidate? {
        if (ageMs !in 0..CANDIDATE_MAX_AGE_MS) return null
        val limit = speedLimit?.takeIf { it > 0 }
        return candidates
            .filter { it.cameraType == cameraType && it.distanceMeters > 0 }
            .filter { limitsMatch(it.speedLimit, limit) }
            .filter { candidate ->
                val gap = abs(candidate.distanceMeters - distanceMeters)
                gap <= maxOf(80, (distanceMeters * 0.35).toInt())
            }
            .minByOrNull { abs(it.distanceMeters - distanceMeters) }
    }

    fun resolve(
        carLatitude: Double,
        carLongitude: Double,
        bearingDegrees: Float,
        distanceMeters: Int,
        reportedLatitude: Double,
        reportedLongitude: Double,
        reportedSource: String
    ): Fix {
        if (
            isPosition(reportedLatitude, reportedLongitude) &&
            agreesWithDistance(
                carLatitude,
                carLongitude,
                bearingDegrees,
                distanceMeters,
                reportedLatitude,
                reportedLongitude
            )
        ) {
            return Fix(reportedLatitude, reportedLongitude, reportedSource)
        }
        val projected = project(carLatitude, carLongitude, bearingDegrees, distanceMeters)
        if (projected != null) {
            return Fix(projected.first, projected.second, SOURCE_ROAD)
        }
        if (isPosition(reportedLatitude, reportedLongitude)) {
            return Fix(reportedLatitude, reportedLongitude, reportedSource)
        }
        return Fix(Double.NaN, Double.NaN, SOURCE_NONE)
    }

    /**
     * 同一部雷达先记住一个点。车开近之后，新的点离旧点超过一段、而且剩余距离明显更短，才改位置。
     * 避免每秒投影一次，回放上拉出一串点。
     */
    fun hold(previous: Held?, candidate: Fix, distanceMeters: Int): Held {
        if (!isPosition(candidate.latitude, candidate.longitude)) {
            return previous ?: Held(Double.NaN, Double.NaN, distanceMeters, SOURCE_NONE)
        }
        if (previous == null || !isPosition(previous.latitude, previous.longitude)) {
            return Held(candidate.latitude, candidate.longitude, distanceMeters, candidate.source)
        }
        val moved = RadarAlertLog.distanceMeters(
            previous.latitude,
            previous.longitude,
            candidate.latitude,
            candidate.longitude
        ) ?: 0
        val closer = distanceMeters + HOLD_CLOSER_METERS < previous.distanceMeters
        if (moved >= HOLD_MOVE_METERS && closer) {
            return Held(candidate.latitude, candidate.longitude, distanceMeters, candidate.source)
        }
        return previous
    }

    fun project(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Float,
        distanceMeters: Int
    ): Pair<Double, Double>? {
        if (!isPosition(latitude, longitude) || bearingDegrees.isNaN() || distanceMeters < 0) return null
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees.toDouble())
        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        val resultLat = Math.toDegrees(lat2)
        val resultLon = Math.toDegrees(lon2)
        if (!isPosition(resultLat, resultLon)) return null
        return resultLat to resultLon
    }

    fun isPosition(latitude: Double, longitude: Double): Boolean {
        return latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            latitude.isFinite() && longitude.isFinite() &&
            (abs(latitude) >= 0.01 || abs(longitude) >= 0.01)
    }

    private fun agreesWithDistance(
        carLatitude: Double,
        carLongitude: Double,
        bearingDegrees: Float,
        distanceMeters: Int,
        reportedLatitude: Double,
        reportedLongitude: Double
    ): Boolean {
        val straight = RadarAlertLog.distanceMeters(
            carLatitude,
            carLongitude,
            reportedLatitude,
            reportedLongitude
        ) ?: return false
        if (bearingDegrees.isNaN() || !isPosition(carLatitude, carLongitude)) {
            val slack = maxOf(ALONG_SLACK_METERS, distanceMeters * ALONG_RATIO)
            return abs(straight - distanceMeters) <= slack
        }
        val delta = angleDelta(
            bearingDegrees.toDouble(),
            bearingBetween(carLatitude, carLongitude, reportedLatitude, reportedLongitude)
        )
        val along = straight * cos(Math.toRadians(delta))
        val cross = abs(straight * sin(Math.toRadians(delta)))
        if (cross > CROSS_TRACK_MAX_METERS) return false
        if (along < -BEHIND_TOLERANCE_METERS) return false
        val slack = maxOf(ALONG_SLACK_METERS, distanceMeters * ALONG_RATIO)
        return abs(along - distanceMeters) <= slack
    }

    private fun limitsMatch(candidateLimit: Int?, facilityLimit: Int?): Boolean {
        val candidate = candidateLimit?.takeIf { it > 0 }
        val facility = facilityLimit?.takeIf { it > 0 }
        if (candidate == null || facility == null) return true
        return candidate == facility
    }

    private fun bearingBetween(
        latitude: Double,
        longitude: Double,
        otherLatitude: Double,
        otherLongitude: Double
    ): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(otherLatitude)
        val dLon = Math.toRadians(otherLongitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angleDelta(fromDegrees: Double, toDegrees: Double): Double {
        val raw = (toDegrees - fromDegrees + 540.0) % 360.0 - 180.0
        return raw
    }

    private fun normalize(bearing: Float): Float {
        if (bearing >= 360f) return 0f
        return bearing
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
