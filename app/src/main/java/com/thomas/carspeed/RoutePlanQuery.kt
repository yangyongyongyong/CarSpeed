package com.thomas.carspeed

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.AimLessMode
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.navi.model.NaviPoi

internal enum class RoutePlanError {
    NO_ROUTE,
    FAILED
}

internal data class PlannedRoute(
    val id: Int,
    val label: String,
    val durationSeconds: Int,
    val distanceMeters: Int,
    val tollYuan: Int,
    val points: List<LatLng>,
    val radars: List<VideoTrackRadar>
)

/**
 * 按起点终点算出多条驾车路线，并带上每条路上的测速雷达。
 * 测速雷达正在巡航时先把巡航让开，算完立刻把巡航开回去，不用用户先去关掉。
 *
 * 车道数不在这里：AMapNaviLink 只有道路等级。AMapLaneInfo.laneCount 只在真实导航临近路口时出现，
 * 不能当成整段高速的车道数。货车路径规划里的 lane_num 是另一套付费接口，这套导航 SDK 没有。
 */
internal class RoutePlanQuery(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var requestId = 0
    private var navi: AMapNavi? = null
    private var listener: SimpleNaviListener? = null
    private var onSuccess: ((List<PlannedRoute>) -> Unit)? = null
    private var onError: ((RoutePlanError) -> Unit)? = null
    private var cruisePaused = false

    fun calculate(
        start: PlaceTip,
        end: PlaceTip,
        onSuccess: (List<PlannedRoute>) -> Unit,
        onError: (RoutePlanError) -> Unit
    ) {
        cancel()
        val id = ++requestId
        this.onSuccess = onSuccess
        this.onError = onError
        val navi = runCatching { AMapNavi.getInstance(appContext) }.getOrNull()
        if (navi == null) {
            deliverError(id, RoutePlanError.FAILED)
            return
        }
        this.navi = navi
        pauseCruiseIfNeeded(navi)
        val listener = object : SimpleNaviListener() {
            override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
                finishIfReady(id)
            }

            override fun onCalculateRouteSuccess(routeIds: IntArray?) {
                finishIfReady(id)
            }

            override fun onCalculateRouteFailure(errorInfo: Int) {
                deliverError(id, RoutePlanError.FAILED)
            }

            override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
                deliverError(id, RoutePlanError.FAILED)
            }
        }
        this.listener = listener
        navi.addAMapNaviListener(listener)
        val started = runCatching {
            navi.calculateDriveRoute(
                NaviPoi(start.name, LatLng(start.latitude, start.longitude), start.poiId),
                NaviPoi(end.name, LatLng(end.latitude, end.longitude), end.poiId),
                null,
                PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
            )
        }.getOrDefault(false)
        if (!started) {
            deliverError(id, RoutePlanError.FAILED)
            return
        }
        mainHandler.postDelayed({
            if (id != requestId) return@postDelayed
            val routes = runCatching { readRoutes() }.getOrDefault(emptyList())
            if (routes.isNotEmpty()) {
                detach()
                deliverSuccess(id, routes)
            } else {
                deliverError(id, RoutePlanError.NO_ROUTE)
            }
        }, TIMEOUT_MS)
    }

    fun cancel() {
        requestId++
        onSuccess = null
        onError = null
        detach()
    }

    private fun finishIfReady(id: Int) {
        if (id != requestId) return
        val routes = runCatching { readRoutes() }.getOrDefault(emptyList())
        if (routes.isEmpty()) return
        detach()
        deliverSuccess(id, routes)
    }

    private fun readRoutes(): List<PlannedRoute> {
        val paths = navi?.naviPaths?.takeIf { it.isNotEmpty() }
            ?: navi?.naviPath?.let { mapOf(0 to it) }
            ?: return emptyList()
        return paths.entries.sortedBy { it.key }.mapNotNull { (id, path) ->
            val points = coordinates(path)
            if (points.size < 2) null else PlannedRoute(
                id = id,
                label = path.labels.orEmpty(),
                durationSeconds = path.allTime,
                distanceMeters = path.allLength,
                tollYuan = path.tollCost,
                points = points,
                radars = RoutePlanFormat.radars(cameras(path))
            )
        }
    }

    private fun cameras(path: AMapNaviPath): List<RouteCameraSnapshot> {
        return path.allCameras.orEmpty().map { camera ->
            val speed = camera.cameraSpeed.takeIf { it > 0 }
                ?: camera.speed?.firstOrNull { it > 0 }
                ?: 0
            RouteCameraSnapshot(
                type = camera.cameraType,
                speedKmh = speed,
                latitude = camera.y,
                longitude = camera.x
            )
        }
    }

    private fun coordinates(path: AMapNaviPath): List<LatLng> {
        val direct = path.coordList.orEmpty().map { LatLng(it.latitude, it.longitude) }
        if (direct.size >= 2) return direct
        return path.steps.orEmpty().flatMap { step ->
            step.coords.orEmpty().map { LatLng(it.latitude, it.longitude) }
        }
    }

    private fun deliverSuccess(id: Int, routes: List<PlannedRoute>) {
        if (id != requestId) return
        requestId++
        val callback = onSuccess
        onSuccess = null
        onError = null
        if (callback == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) callback(routes)
        else mainHandler.post { callback(routes) }
    }

    private fun deliverError(id: Int, error: RoutePlanError) {
        if (id != requestId) return
        requestId++
        detach()
        val callback = onError
        onSuccess = null
        onError = null
        if (callback == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) callback(error)
        else mainHandler.post { callback(error) }
    }

    private fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        val listener = listener
        this.listener = null
        if (listener != null) {
            runCatching { navi?.removeAMapNaviListener(listener) }
        }
        resumeCruiseIfNeeded()
    }

    /** 算路和巡航抢同一个导航实例。算的时候先停巡航，结果拿走后再开回去。 */
    private fun pauseCruiseIfNeeded(navi: AMapNavi) {
        if (!RadarAlertController.currentState().active) return
        runCatching { navi.stopAimlessMode() }
        cruisePaused = true
    }

    private fun resumeCruiseIfNeeded() {
        if (!cruisePaused) return
        cruisePaused = false
        if (!RadarAlertController.currentState().active) return
        val activeNavi = navi ?: return
        runCatching {
            activeNavi.startGPS(1_000L)
            activeNavi.startAimlessMode(AimLessMode.CAMERA_DETECTED)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
