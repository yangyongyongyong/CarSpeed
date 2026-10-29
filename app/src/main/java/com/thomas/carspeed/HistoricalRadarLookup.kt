package com.thomas.carspeed

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviLatLng
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 旧录像的轨迹文件里没有雷达坐标。用这段 GPS 向导航算一条贴着轨迹的路线，
 * 取路线上的测速电子眼。巡航正在跑时不打断它。
 */
internal class HistoricalRadarLookup(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val converter = CoordinateConverter(appContext).from(CoordinateConverter.CoordType.GPS)
    private val finished = AtomicBoolean(false)
    private var navi: AMapNavi? = null
    private var listener: SimpleNaviListener? = null
    private val timeout = Runnable { finishEmpty() }
    private var onResult: ((List<VideoTrackRadar>) -> Unit)? = null

    fun load(points: List<VideoTrackPoint>, onResult: (List<VideoTrackRadar>) -> Unit) {
        this.onResult = onResult
        if (!PrivacyConsent.isAgreed(appContext) || RadarAlertController.currentState().active) {
            deliver(emptyList())
            return
        }
        val track = points.mapNotNull { point ->
            val gcj = toGcj(point.latitude, point.longitude) ?: return@mapNotNull null
            TrackSample(gcj.latitude, gcj.longitude, point.elapsedMs)
        }
        val samples = RouteRadarMarks.sampleAlong(track, MAX_ROUTE_POINTS)
        if (samples.size < 2) {
            deliver(emptyList())
            return
        }
        val navi = runCatching { AMapNavi.getInstance(appContext) }.getOrNull()
        if (navi == null) {
            deliver(emptyList())
            return
        }
        this.navi = navi
        val listener = object : SimpleNaviListener() {
            override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
                finish(track)
            }

            override fun onCalculateRouteSuccess(routeIds: IntArray?) {
                if (navi?.naviPath != null) finish(track)
            }

            override fun onCalculateRouteFailure(errorInfo: Int) {
                finishEmpty()
            }

            override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
                finishEmpty()
            }
        }
        this.listener = listener
        navi.addAMapNaviListener(listener)
        val started = runCatching {
            navi.calculateDriveRoute(
                listOf(NaviLatLng(samples.first().latitude, samples.first().longitude)),
                listOf(NaviLatLng(samples.last().latitude, samples.last().longitude)),
                samples.drop(1).dropLast(1).map { NaviLatLng(it.latitude, it.longitude) },
                PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT
            )
        }.getOrDefault(false)
        if (!started) {
            finishEmpty()
            return
        }
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
    }

    fun cancel() {
        if (!finished.compareAndSet(false, true)) return
        onResult = null
        detach()
    }

    private fun finish(track: List<TrackSample>) {
        if (!finished.compareAndSet(false, true)) return
        val radars = runCatching { camerasNear(track) }.getOrDefault(emptyList())
        detach()
        deliver(radars)
    }

    private fun finishEmpty() {
        if (!finished.compareAndSet(false, true)) return
        detach()
        deliver(emptyList())
    }

    private fun camerasNear(track: List<TrackSample>): List<VideoTrackRadar> {
        val cameras = navi?.naviPath?.allCameras ?: return emptyList()
        return RouteRadarMarks.uniqueByLocation(
            cameras.mapNotNull { camera ->
                // getX 是经度，getY 是纬度，已经是地图坐标。
                val nearest = RouteRadarMarks.nearestWithin(
                    camera.y,
                    camera.x,
                    track,
                    MAX_DISTANCE_METERS
                ) ?: return@mapNotNull null
                val speed = camera.cameraSpeed.takeIf { it > 0 }
                    ?: camera.speed?.firstOrNull { it > 0 }
                    ?: 0
                RouteRadarMarks.fromRouteCamera(
                    routeCameraType = camera.cameraType,
                    speedKmh = speed,
                    latitude = camera.y,
                    longitude = camera.x,
                    elapsedMs = nearest.elapsedMs
                )
            }
        )
    }

    private fun deliver(radars: List<VideoTrackRadar>) {
        val callback = onResult ?: return
        onResult = null
        if (Looper.myLooper() == Looper.getMainLooper()) callback(radars)
        else mainHandler.post { callback(radars) }
    }

    private fun detach() {
        mainHandler.removeCallbacks(timeout)
        val listener = listener ?: return
        this.listener = null
        runCatching { navi?.removeAMapNaviListener(listener) }
    }

    private fun toGcj(latitude: Double, longitude: Double): LatLng? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return runCatching { converter.coord(LatLng(latitude, longitude)).convert() }.getOrNull()
    }

    private companion object {
        const val MAX_ROUTE_POINTS = 16
        const val MAX_DISTANCE_METERS = 150.0
        const val TIMEOUT_MS = 20_000L
    }
}
