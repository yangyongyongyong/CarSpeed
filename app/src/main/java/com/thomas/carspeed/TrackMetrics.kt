package com.thomas.carspeed

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class TrackSummary(
    val durationMs: Long,
    val totalDistanceMeters: Float,
    val maxSpeedKmh: Float,
    val averageSpeedKmh: Float,
    val averageAccuracyMeters: Float?,
    val goodFixRatio: Float
)

object TrackMetrics {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val MIN_SEGMENT_DISTANCE_METERS = 0.5f
    private const val MAX_SEGMENT_ACCURACY_METERS = 100f
    private const val MAX_SEGMENT_SPEED_MPS = 80f
    private const val MIN_GOOD_ACCURACY_METERS = 30f
    /** 停车时 GPS 速度常在 0~1 km/h 抖动，低于这个值不计入里程。 */
    const val STATIONARY_SPEED_FLOOR_KMH = 1.5f

    fun summary(points: List<VideoTrackPoint>): TrackSummary {
        if (points.isEmpty()) {
            return TrackSummary(0L, 0f, 0f, 0f, null, 0f)
        }
        val durationMs = points.maxOf { it.elapsedMs }.coerceAtLeast(0L)
        val distance = totalDistance(points)
        val movingHours = durationMs / 3_600_000f
        val maxSpeed = points.maxOf { it.speedKmh.coerceAtLeast(0f) }
        val distanceAverage = if (movingHours > 0f && distance > 0f) distance / 1000f / movingHours else 0f
        val accuracies = points.mapNotNull { it.accuracyMeters }
        val averageAccuracy = if (accuracies.isNotEmpty()) accuracies.average().toFloat() else null
        val goodRatio = if (accuracies.isNotEmpty()) {
            accuracies.count { it <= MIN_GOOD_ACCURACY_METERS }.toFloat() / accuracies.size
        } else {
            0f
        }
        return TrackSummary(
            durationMs = durationMs,
            totalDistanceMeters = distance,
            maxSpeedKmh = maxSpeed,
            averageSpeedKmh = distanceAverage.coerceIn(0f, 9999f),
            averageAccuracyMeters = averageAccuracy,
            goodFixRatio = goodRatio.coerceIn(0f, 1f)
        )
    }

    fun totalDistance(points: List<VideoTrackPoint>): Float {
        return cumulativeDistances(points).lastOrNull() ?: 0f
    }

    fun distanceAt(points: List<VideoTrackPoint>, elapsedMs: Long): Float {
        if (points.size < 2) return 0f
        val sorted = points.sortedBy { it.elapsedMs }
        val target = elapsedMs.coerceAtLeast(0L)
        val cumulative = cumulativeDistances(sorted)
        if (target <= sorted.first().elapsedMs) return 0f
        if (target >= sorted.last().elapsedMs) return cumulative.lastOrNull() ?: 0f

        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1]
            val next = sorted[i]
            if (target <= next.elapsedMs) {
                val span = (next.elapsedMs - previous.elapsedMs).coerceAtLeast(1L)
                val ratio = ((target - previous.elapsedMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                val segment = (cumulative[i] - cumulative[i - 1]).coerceAtLeast(0f)
                return cumulative[i - 1] + segment * ratio
            }
        }
        return cumulative.lastOrNull() ?: 0f
    }

    fun cumulativeDistances(points: List<VideoTrackPoint>): List<Float> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.elapsedMs }
        val result = MutableList(sorted.size) { 0f }
        var total = 0f
        for (i in 1 until sorted.size) {
            total += acceptedDistance(sorted[i - 1], sorted[i])
            result[i] = total
        }
        return result
    }

    fun formatDistance(meters: Float): String {
        return if (meters >= 1000f) {
            String.format("%.2f km", meters / 1000f)
        } else {
            "${meters.roundToInt()} m"
        }
    }

    private fun acceptedDistance(from: VideoTrackPoint, to: VideoTrackPoint): Float {
        val dtSec = ((to.elapsedMs - from.elapsedMs) / 1000f).coerceAtLeast(0f)
        if (dtSec <= 0f) return 0f
        if ((from.accuracyMeters ?: 0f) > MAX_SEGMENT_ACCURACY_METERS) return 0f
        if ((to.accuracyMeters ?: 0f) > MAX_SEGMENT_ACCURACY_METERS) return 0f

        val speedDistance = speedIntegratedDistance(from, to, dtSec)
        if (speedDistance > 0f) return speedDistance
        // 两端都有速度但都低于抖动阈值：停车时的定位漂移，不计里程。
        if (from.speedKmh > 0f || to.speedKmh > 0f) return 0f

        val distance = haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)
        val jitterRadius = maxOf(from.accuracyMeters ?: 0f, to.accuracyMeters ?: 0f)
        if (distance < maxOf(MIN_SEGMENT_DISTANCE_METERS, jitterRadius)) return 0f
        val maxReasonableDistance = (MAX_SEGMENT_SPEED_MPS * dtSec).coerceAtLeast(50f)
        return if (distance <= maxReasonableDistance) distance else 0f
    }

    private fun speedIntegratedDistance(from: VideoTrackPoint, to: VideoTrackPoint, dtSec: Float): Float {
        val fromSpeedMps = (from.speedKmh.coerceIn(0f, MAX_SEGMENT_SPEED_MPS * 3.6f) / 3.6f)
        val toSpeedMps = (to.speedKmh.coerceIn(0f, MAX_SEGMENT_SPEED_MPS * 3.6f) / 3.6f)
        val averageSpeedMps = (fromSpeedMps + toSpeedMps) / 2f
        return if (averageSpeedMps * 3.6f >= STATIONARY_SPEED_FLOOR_KMH) averageSpeedMps * dtSec else 0f
    }

    private fun haversineMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val dLat = Math.toRadians(toLat - fromLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val a = sin(dLat / 2).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2.0)
        val c = 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
        return (EARTH_RADIUS_METERS * c).toFloat()
    }
}
