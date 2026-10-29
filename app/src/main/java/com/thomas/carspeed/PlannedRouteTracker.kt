package com.thomas.carspeed

/**
 * 跟着车走，判断「现在跑在规划路线的哪一条上、下一个雷达还有多远」。
 *
 * 高德导航在另一个 App 里，我们读不到它选了哪条路，只能靠自己这一小段 GPS 轨迹
 * 去跟几条候选路线比。比出来的结果决定雷达预警按哪条路线的点位播报——
 * 用户从 A 线切到 B 线，这里会跟着切过去，不需要手动操作。
 *
 * 做成纯内存对象、不碰 Context，方便直接用 JUnit 喂轨迹点验证。
 */
internal class PlannedRouteTracker {
    private var state: PlannedRouteStore.State? = null
    private val recentFixes = ArrayList<RouteProgress.Fix>()

    /** 当前判定在走哪条路线。null 表示还没判定出来。 */
    var currentRouteId: Int? = null
        private set

    var travelledMeters: Double = 0.0
        private set

    var deviationMeters: Double = Double.MAX_VALUE
        private set

    fun bind(state: PlannedRouteStore.State?) {
        this.state = state
        recentFixes.clear()
        currentRouteId = state?.selectedRouteId
            ?.takeIf { id -> state.routes.any { it.id == id } }
            ?: state?.routes?.firstOrNull()?.id
        travelledMeters = 0.0
        deviationMeters = Double.MAX_VALUE
    }

    fun currentState(): PlannedRouteStore.State? = state

    fun currentRoute(): PlannedRoute? = state?.routes?.firstOrNull { it.id == currentRouteId }

    /**
     * 喂一个定位点，返回当前应该按哪条路线报雷达。
     *
     * 刚开始只有一两个点时判不准（几条路线往往从同一点出发），这时沿用上次的判断，
     * 等轨迹攒够了再切——比拿单帧 GPS 乱跳强。
     */
    fun onFix(fix: RouteProgress.Fix): PlannedRoute? {
        val current = state ?: return null
        recentFixes += fix
        if (recentFixes.size > TRACK_HISTORY) recentFixes.removeAt(0)
        if (!RouteProgress.hasEnoughTrack(recentFixes)) return currentRoute()

        val candidates = current.routes.map { route ->
            route.id to route.points.map { RouteProgress.Fix(it.latitude, it.longitude) }
        }
        val best = RouteProgress.bestRoute(candidates, recentFixes) ?: return currentRoute()
        val active = currentRouteId
        if (active == null || best.routeId == active) {
            adopt(best)
            return currentRoute()
        }
        val activeRoute = current.routes.firstOrNull { it.id == active } ?: return currentRoute()
        val activeFix = activeRoute.points.map { RouteProgress.Fix(it.latitude, it.longitude) }
        val activeDeviation = RouteProgress.project(activeFix, fix)?.deviationMeters ?: Double.MAX_VALUE
        // 只有另一条明显更贴合才切，否则两条路线重合的那一段会来回跳。
        if (RouteProgress.shouldSwitch(activeDeviation, best.deviationMeters)) {
            currentRouteId = best.routeId
            adopt(best)
            return currentRoute()
        }
        if (activeDeviation <= RouteProgress.MAX_MATCH_METERS) {
            // 还贴在当前路线上，沿用当前路线的里程，别被别的路线带偏。
            travelledMeters = RouteProgress.project(activeFix, fix)?.travelledMeters ?: travelledMeters
        }
        return currentRoute()
    }

    private fun adopt(best: RouteProgress.Progress) {
        currentRouteId = best.routeId
        travelledMeters = best.travelledMeters
        deviationMeters = best.deviationMeters
    }

    /** 当前路线上还开在前面的雷达，由近到远。 */
    fun radarsAhead(): List<VideoTrackRadar> {
        val route = currentRoute() ?: return emptyList()
        val points = route.points.map { RouteProgress.Fix(it.latitude, it.longitude) }
        return RouteProgress.radarsAhead(route.radars, points, travelledMeters)
    }

    /**
     * 到下一个雷达的沿路距离（米）。没有雷达时返回 null。
     * 用沿路线里程算，不用直线距离——路线是弯的，直线会把距离说短。
     */
    fun nextRadarMeters(): Int? {
        val route = currentRoute() ?: return null
        val points = route.points.map { RouteProgress.Fix(it.latitude, it.longitude) }
        val ahead = RouteProgress.radarsAhead(route.radars, points, travelledMeters)
        val next = ahead.firstOrNull() ?: return null
        val projection = RouteProgress.project(points, RouteProgress.Fix(next.latitude, next.longitude))
            ?: return null
        return (projection.travelledMeters - travelledMeters).coerceAtLeast(0.0).toInt()
    }

    /** 离终点还有多远，用来判断这趟是不是快到了。 */
    fun remainingMeters(): Int? {
        val route = currentRoute() ?: return null
        val points = route.points.map { RouteProgress.Fix(it.latitude, it.longitude) }
        val total = RouteProgress.cumulativeDistances(points).lastOrNull() ?: return null
        return (total - travelledMeters).coerceAtLeast(0.0).toInt()
    }

    /** 轨迹里保留的点数：够判断又不需要太多，太多了拐弯后还在拿旧点算。 */
    private companion object {
        const val TRACK_HISTORY = 12
    }
}
