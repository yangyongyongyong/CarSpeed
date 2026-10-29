package com.thomas.carspeed

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 已规划的路线在巡航时怎么用。
 *
 * 用户在高德里导航、我们只知道终点和几条候选路线时，唯一能判断「他现在走哪条」的信息
 * 就是我们自己的 GPS。这里把这件事做成纯函数：给定几条折线和最近一小段轨迹，
 * 算出车最可能在哪条路上、离下一个雷达还有多远。
 *
 * 全部纯函数是为了能测：三条路线分岔、来回抖、GPS 飘走、刚出发还没走到分岔口，
 * 这些情况实车很难复现，但判断错了就会报成另一条路上的雷达。
 */
internal object RouteProgress {
    /** 轨迹离折线超过这个距离，就不认为车在这条路上。 */
    const val MAX_MATCH_METERS = 60.0

    /** 判定要用的最近轨迹点数量。太少会跟着单帧 GPS 漂移乱跳。 */
    const val MATCH_SAMPLE_POINTS = 6

    data class Fix(val latitude: Double, val longitude: Double)

    data class Progress(
        val routeId: Int,
        /** 已经走过的路线长度，米。 */
        val travelledMeters: Double,
        /** 轨迹到这条折线的平均偏离，米。越小越像。 */
        val deviationMeters: Double
    )

    /**
     * 把轨迹点投影到路线上，算出走到第几米、偏离多少。
     *
     * 用整条折线的最近投影做基准，再按投影点的顺序算累计里程——
     * 而不是逐段比较，避免 GPS 抖动时在相邻两段之间来回倒车。
     */
    fun project(points: List<Fix>, fix: Fix): Progress? {
        if (points.size < 2) return null
        var bestDistance = Double.MAX_VALUE
        var bestTravelled = 0.0
        var cumulative = 0.0
        for (index in 0 until points.size - 1) {
            val a = points[index]
            val b = points[index + 1]
            val segmentMeters = distance(a, b)
            val projected = projectOnSegment(a, b, fix)
            if (projected.distance < bestDistance) {
                bestDistance = projected.distance
                bestTravelled = cumulative + segmentMeters * projected.t
            }
            cumulative += segmentMeters
        }
        return Progress(
            routeId = -1,
            travelledMeters = bestTravelled,
            deviationMeters = bestDistance
        )
    }

    /**
     * 在几条候选路线里挑出车最可能走的那条。
     *
     * 只看最近几个轨迹点，取平均偏离最小的一条；偏离全部超出上限就返回 null，
     * 表示「现在不在任何一条规划路线上」——例如用户中途绕路了，别硬套。
     */
    fun bestRoute(routes: List<Pair<Int, List<Fix>>>, recentFixes: List<Fix>): Progress? {
        if (recentFixes.isEmpty()) return null
        // 轨迹太少时（刚出发）只取最新一个点，否则几个点可能横跨分岔口，两种路都像。
        val samples = recentFixes.takeLast(MATCH_SAMPLE_POINTS)
        val scored = routes.mapNotNull { (id, points) ->
            val projections = samples.mapNotNull { project(points, it) }
            if (projections.isEmpty()) return@mapNotNull null
            val average = projections.sumOf { it.deviationMeters } / projections.size
            if (average > MAX_MATCH_METERS) return@mapNotNull null
            // 里程用最新那个点的，不是平均——要知道车现在走到哪了。
            val current = projections.last()
            Progress(routeId = id, travelledMeters = current.travelledMeters, deviationMeters = average)
        }
        return scored.minByOrNull { it.deviationMeters }
    }

    /**
     * 「当前路线偏离」和「另一条更贴合」的分差要够大才切换，
     * 否则在两条路线重合的那一段会来回跳，播报跟着乱。
     */
    fun shouldSwitch(currentDeviationMeters: Double, candidateDeviationMeters: Double): Boolean {
        return candidateDeviationMeters + SWITCH_MARGIN_METERS < currentDeviationMeters
    }

    private const val SWITCH_MARGIN_METERS = 15.0

    /**
     * 沿路线算「从当前里程到某个点还有多远」。
     * 用来回答「下一个雷达还有多少米」，比直线距离准得多——路线是弯的。
     */
    fun afterTravelled(points: List<Fix>, fromMeters: Double): List<Double> {
        if (points.size < 2) return emptyList()
        val cumulative = cumulativeDistances(points)
        return cumulative.drop(1).map { (it - fromMeters).coerceAtLeast(0.0) }
    }

    fun cumulativeDistances(points: List<Fix>): List<Double> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<Double>(points.size)
        result += 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            total += distance(points[index - 1], points[index])
            result += total
        }
        return result
    }

    /** 已经开过头的一个雷达不应该再报，超过这个裕量就算过去。 */
    const val BEHIND_TOLERANCE_METERS = 30.0

    /**
     * 抓出当前路线里还开在前面的雷达，按远近排好。
     *
     * @param radars 路线上的雷达（带坐标）
     * @param travelled 车在当前路线上已经走了多少米
     * @param routePoints 路线折线，用来把雷达投影成里程
     */
    fun radarsAhead(
        radars: List<VideoTrackRadar>,
        routePoints: List<Fix>,
        travelled: Double
    ): List<VideoTrackRadar> {
        if (routePoints.size < 2) return radars
        return radars.mapNotNull { radar ->
            val hit = project(routePoints, Fix(radar.latitude, radar.longitude)) ?: return@mapNotNull null
            if (hit.deviationMeters > MAX_RADAR_OFFSET_METERS) return@mapNotNull null
            val ahead = hit.travelledMeters - travelled
            if (ahead < -BEHIND_TOLERANCE_METERS) null else radar to ahead
        }.sortedBy { it.second }.map { it.first }
    }

    /**
     * 路线上的雷达有时不在折线上（另一个车道、匝道旁）。
     * 允许一点偏移，太远的说明投影不可信，宁可不报也不要报错位置。
     */
    private const val MAX_RADAR_OFFSET_METERS = 120.0

    data class SegmentProjection(val t: Double, val distance: Double)

    private fun projectOnSegment(a: Fix, b: Fix, fix: Fix): SegmentProjection {
        // 小范围内按平面算足够：车速下的误差远小于 GPS 本身精度。
        val scale = cos(Math.toRadians(fix.latitude))
        val ax = (a.longitude - fix.longitude) * scale
        val ay = a.latitude - fix.latitude
        val bx = (b.longitude - fix.longitude) * scale
        val by = b.latitude - fix.latitude
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0) {
            return SegmentProjection(0.0, meters(ax, ay))
        }
        val t = ((-ax * dx) + (-ay * dy)) / lengthSquared
        val clampedT = t.coerceIn(0.0, 1.0)
        val px = ax + dx * clampedT
        val py = ay + dy * clampedT
        return SegmentProjection(clampedT, meters(px, py))
    }

    private fun meters(xDegrees: Double, yDegrees: Double): Double {
        val dx = xDegrees * DEGREE_METERS
        val dy = yDegrees * DEGREE_METERS
        return sqrt(dx * dx + dy * dy)
    }

    private const val DEGREE_METERS = 111_320.0

    fun distance(a: Fix, b: Fix): Double {
        return RadarAlertLog.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)?.toDouble()
            ?: haversine(a, b)
    }

    private fun haversine(a: Fix, b: Fix): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** 轨迹点够不够判断，太少时宁可先不动，等攒够了再切路线。 */
    fun hasEnoughTrack(recentFixes: List<Fix>): Boolean = recentFixes.size >= 2

    fun bearingDelta(from: Float, to: Float): Float {
        if (from.isNaN() || to.isNaN()) return Float.NaN
        return abs(((to - from + 540f) % 360f) - 180f)
    }
}
