package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapException
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.MapView
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapProvince
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class MapRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), OfflineMapManager.OfflineMapDownloadListener,
    OfflineMapManager.OfflineLoadedListener {
    private val mapView = MapView(context)
    private val aMap: AMap
    private val statsView = TextView(context)
    private val viewportButton = Button(context)
    private val currentButton = Button(context)
    private val mapTypeButton = Button(context)
    private val offlineButton = Button(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var offlineManager: OfflineMapManager? = null
    private var points: List<VideoTrackPoint> = emptyList()
    private var convertedPoints: List<LatLng> = emptyList()
    private var elapsedMs: Long = 0L
    private var summary: TrackSummary = TrackSummary(0L, 0f, 0f, 0f, null, 0f)
    private var routeKey: String? = null
    private val routeSegments = mutableListOf<Polyline>()
    private var playedPolyline: Polyline? = null
    private var currentCircle: Circle? = null
    private var maxSpeedCircle: Circle? = null
    private var fitWholeRoute = false
    private var currentMapType = AMap.MAP_TYPE_NORMAL
    private var userCameraLocked = false
    private var routeCameraInitialized = false
    private var activeAutoProvince: String? = null
    private var activeAutoProgress: Int? = null
    private val autoDownloadFailedNames = mutableSetOf<String>()
    private var downTouchX = 0f
    private var downTouchY = 0f
    var onMapTap: (() -> Unit)? = null

    init {
        mapView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mapView.onCreate(null as Bundle?)
        addView(mapView)
        aMap = mapView.map
        configureMap()
        buildControls()
        offlineManager = runCatching {
            OfflineMapManager(context.applicationContext, this, aMap).apply {
                setOnOfflineLoadedListener(this@MapRouteView)
            }
        }.getOrNull()
    }

    fun setRoute(points: List<VideoTrackPoint>, elapsedMs: Long) {
        this.elapsedMs = elapsedMs.coerceAtLeast(0L)
        val sorted = points.sortedBy { it.elapsedMs }
        val nextKey = sorted.routeKey()
        if (nextKey != routeKey) {
            this.points = sorted
            this.summary = TrackMetrics.summary(sorted)
            this.convertedPoints = sorted.map { it.toAmapLatLng() }
            routeKey = nextKey
            renderRoute()
            fitWholeRoute = false
            userCameraLocked = false
            routeCameraInitialized = false
            ensureNeededOfflineMap()
        }
        renderPlaybackState()
        updateCamera()
        updateStats()
    }

    fun resetViewport() {
        fitWholeRoute = false
        userCameraLocked = false
        routeCameraInitialized = false
        updateCamera()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mapView.onResume()
    }

    override fun onDetachedFromWindow() {
        offlineManager?.destroy()
        offlineManager = null
        mapView.onPause()
        mapView.onDestroy()
        super.onDetachedFromWindow()
    }

    override fun onDownload(status: Int, completeCode: Int, downName: String?) {
        post {
            val name = downName.orEmpty()
            if (name == activeAutoProvince) {
                if (status == OfflineMapStatus.SUCCESS) {
                    activeAutoProvince = null
                    activeAutoProgress = null
                    aMap.setLoadOfflineData(true)
                    aMap.reloadMap()
                    updateStats("高德离线包已就绪")
                } else if (status == OfflineMapStatus.ERROR || status == OfflineMapStatus.EXCEPTION_AMAP || status == OfflineMapStatus.EXCEPTION_NETWORK_LOADING) {
                    autoDownloadFailedNames += name
                    activeAutoProvince = null
                    activeAutoProgress = null
                    updateStats("${compactAmapName(name)}离线包自动下载失败 · 点离线重试")
                } else {
                    activeAutoProgress = completeCode.coerceIn(0, 100)
                    updateStats("自动下载${compactAmapName(name)}离线包 $completeCode%")
                }
            }
        }
    }

    override fun onCheckUpdate(hasNew: Boolean, name: String?) = Unit

    override fun onRemove(success: Boolean, name: String?, describe: String?) = Unit

    override fun onVerifyComplete() {
        post { ensureNeededOfflineMap() }
    }

    private fun configureMap() {
        aMap.mapType = currentMapType
        aMap.setLoadOfflineData(true)
        aMap.uiSettings.isZoomControlsEnabled = false
        aMap.uiSettings.isScaleControlsEnabled = false
        aMap.uiSettings.isCompassEnabled = false
        aMap.uiSettings.isMyLocationButtonEnabled = false
        aMap.uiSettings.isTiltGesturesEnabled = false
        aMap.uiSettings.isRotateGesturesEnabled = false
        aMap.showIndoorMap(true)
        aMap.showBuildings(true)
        aMap.setOnMapTouchListener { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTouchX = event.x
                    downTouchY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> lockCameraForUserGesture()
                MotionEvent.ACTION_MOVE -> {
                    val moved = max(abs(event.x - downTouchX), abs(event.y - downTouchY))
                    if (moved > touchSlop) lockCameraForUserGesture()
                }
            }
        }
        aMap.setOnMapClickListener { onMapTap?.invoke() }
    }

    private fun buildControls() {
        statsView.apply {
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.map_stats_light_bg)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        addView(
            statsView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(12), dp(12), dp(96), 0)
            }
        )

        viewportButton.text = "全程"
        viewportButton.setOnClickListener {
            fitWholeRoute = true
            userCameraLocked = false
            routeCameraInitialized = false
            updateCamera()
            updateButtons()
        }
        currentButton.text = "当前"
        currentButton.setOnClickListener {
            fitWholeRoute = false
            userCameraLocked = false
            routeCameraInitialized = false
            updateCamera()
            updateButtons()
        }
        mapTypeButton.text = "道路"
        mapTypeButton.setOnClickListener {
            currentMapType = if (currentMapType == AMap.MAP_TYPE_NORMAL) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
            aMap.mapType = currentMapType
            updateButtons()
        }
        offlineButton.text = "离线"
        offlineButton.setOnClickListener { context.startActivity(Intent(context, OfflineMapActivity::class.java)) }

        listOf(viewportButton, currentButton, mapTypeButton, offlineButton).forEachIndexed { index, button ->
            addView(
                button,
                LayoutParams(dp(76), dp(44)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(12 + index * 52), dp(12), 0)
                }
            )
        }
    }

    private fun renderRoute() {
        routeSegments.forEach { it.remove() }
        routeSegments.clear()
        playedPolyline?.remove()
        playedPolyline = null
        currentCircle?.remove()
        currentCircle = null
        maxSpeedCircle?.remove()
        maxSpeedCircle = null

        if (convertedPoints.size < 2) return
        for (i in 1 until convertedPoints.size) {
            routeSegments += aMap.addPolyline(
                PolylineOptions()
                    .add(convertedPoints[i - 1], convertedPoints[i])
                    .width(dp(5).toFloat())
                    .color(speedColor((points[i - 1].speedKmh + points[i].speedKmh) / 2f))
                    .zIndex(20f)
            )
        }
        val maxIndex = points.indices.maxByOrNull { points[it].speedKmh } ?: -1
        if (maxIndex >= 0 && points[maxIndex].speedKmh > 0f) {
            maxSpeedCircle = aMap.addCircle(
                CircleOptions()
                    .center(convertedPoints[maxIndex])
                    .radius(2.5)
                    .strokeWidth(dp(5).toFloat())
                    .strokeColor(Color.WHITE)
                    .fillColor(Color.rgb(255, 64, 129))
                    .zIndex(40f)
            )
        }
    }

    private fun renderPlaybackState() {
        if (points.isEmpty() || convertedPoints.isEmpty()) return
        val current = VideoTrackSync.pointAt(points, elapsedMs) ?: points.first()
        val currentLatLng = current.toAmapLatLng()
        val playedIndex = VideoTrackSync.lastPlayedIndex(points, elapsedMs).coerceIn(0, convertedPoints.lastIndex)
        val playedPoints = convertedPoints.take(playedIndex + 1) + currentLatLng

        if (playedPolyline == null) {
            playedPolyline = aMap.addPolyline(
                PolylineOptions()
                    .addAll(playedPoints)
                    .width(dp(3).toFloat())
                    .color(Color.WHITE)
                    .zIndex(30f)
            )
        } else {
            playedPolyline?.points = playedPoints
        }
        if (currentCircle == null) {
            currentCircle = aMap.addCircle(
                CircleOptions()
                    .center(currentLatLng)
                    .radius(2.5)
                    .strokeWidth(dp(5).toFloat())
                    .strokeColor(Color.WHITE)
                    .fillColor(Color.rgb(79, 214, 151))
                    .zIndex(50f)
            )
        } else {
            currentCircle?.center = currentLatLng
        }
    }

    private fun updateCamera() {
        if (convertedPoints.isEmpty() || width == 0 || height == 0) return
        if (fitWholeRoute && convertedPoints.size > 1) {
            val builder = LatLngBounds.builder()
            convertedPoints.forEach { builder.include(it) }
            runCatching { aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), dp(18))) }
            routeCameraInitialized = true
        } else if (userCameraLocked) {
            return
        } else if (routeCameraInitialized) {
            val current = VideoTrackSync.pointAt(points, elapsedMs)?.toAmapLatLng() ?: convertedPoints.first()
            aMap.moveCamera(CameraUpdateFactory.changeLatLng(current))
        } else {
            val current = VideoTrackSync.pointAt(points, elapsedMs)?.toAmapLatLng() ?: convertedPoints.first()
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(current, chooseZoomForScreenWidth(current.latitude, width.toFloat()).toFloat()))
            routeCameraInitialized = true
        }
    }

    private fun updateStats(extraLine: String? = null) {
        if (points.isEmpty()) {
            statsView.text = "高德官方地图"
            return
        }
        val current = VideoTrackSync.pointAt(points, elapsedMs)
        val currentDistance = TrackMetrics.distanceAt(points, elapsedMs)
        val mapLine = extraLine ?: mapStatusLine()
        statsView.text = listOfNotNull(
            current?.let { formatTime(it.timeMs) } ?: "时间 --",
            String.format(Locale.US, "%.1f km/h", current?.speedKmh ?: 0f),
            "里程 ${TrackMetrics.formatDistance(currentDistance)} / ${TrackMetrics.formatDistance(summary.totalDistanceMeters)}",
            mapLine.takeIf { it.isNotBlank() }
        ).joinToString("\n")
    }

    private fun mapStatusLine(): String {
        val downloadingProvince = activeAutoProvince
        if (downloadingProvince != null) {
            val province = offlineManager?.offlineMapProvinceList.orEmpty().firstOrNull { it.provinceName == downloadingProvince }
            if (province?.state == OfflineMapStatus.SUCCESS) {
                activeAutoProvince = null
                activeAutoProgress = null
                return ""
            }
            return if (downloadingProvince in autoDownloadFailedNames) {
                "${compactAmapName(downloadingProvince)}离线包自动下载失败 · 点离线重试"
            } else {
                val progress = activeAutoProgress?.let { " $it%" }.orEmpty()
                "自动下载${compactAmapName(activeAutoProvince.orEmpty())}离线包$progress"
            }
        }
        return ""
    }

    private fun updateButtons() {
        viewportButton.text = "全程"
        currentButton.text = "当前"
        mapTypeButton.text = if (currentMapType == AMap.MAP_TYPE_NORMAL) "道路" else "卫星"
    }

    private fun ensureNeededOfflineMap() {
        if (points.isEmpty()) return
        if (offlineManager == null) {
            updateStats("高德离线管理初始化失败")
            return
        }
        val region = AmapOfflineMapStore.regionForRoute(points) ?: return
        val province = findProvince(region) ?: return
        if (province.state == OfflineMapStatus.SUCCESS) {
            if (activeAutoProvince == province.provinceName) {
                activeAutoProvince = null
                activeAutoProgress = null
                updateStats()
            }
            return
        }
        if (province.provinceName in autoDownloadFailedNames) return
        if (activeAutoProvince == province.provinceName) return
        try {
            activeAutoProvince = province.provinceName
            activeAutoProgress = null
            if (province.state == OfflineMapStatus.NEW_VERSION) {
                offlineManager?.updateOfflineMapProvinceByName(province.provinceName)
            } else {
                offlineManager?.downloadByProvinceName(province.provinceName)
            }
            updateStats("自动下载${region.compactName}离线包")
        } catch (error: AMapException) {
            activeAutoProvince = null
            activeAutoProgress = null
            autoDownloadFailedNames += province.provinceName
            updateStats("${region.compactName}离线包自动下载失败 · 点离线重试")
        }
    }

    private fun lockCameraForUserGesture() {
        fitWholeRoute = false
        userCameraLocked = true
        updateButtons()
    }

    private fun findProvince(region: AmapOfflineRegion): OfflineMapProvince? {
        return offlineManager?.offlineMapProvinceList.orEmpty().firstOrNull { province ->
            val compact = compactAmapName(province.provinceName)
            compact == region.compactName || province.provinceName == region.provinceName || province.provinceName.contains(region.compactName)
        }
    }

    private fun VideoTrackPoint.toAmapLatLng(): LatLng {
        val raw = LatLng(latitude, longitude)
        return if (CoordinateConverter.isAMapDataAvailable(latitude, longitude)) {
            runCatching {
                CoordinateConverter(context)
                    .from(CoordinateConverter.CoordType.GPS)
                    .coord(raw)
                    .convert()
            }.getOrDefault(raw)
        } else {
            raw
        }
    }

    private fun List<VideoTrackPoint>.routeKey(): String {
        if (isEmpty()) return "empty"
        return "${size}:${first().timeMs}:${last().timeMs}"
    }

    private fun speedColor(speedKmh: Float): Int {
        return when {
            speedKmh < 30f -> Color.rgb(79, 214, 151)
            speedKmh < 80f -> Color.rgb(255, 205, 86)
            else -> Color.rgb(255, 91, 91)
        }
    }

    private fun compactAmapName(name: String): String = AmapOfflineMapStore.compactName(name)

    private fun formatTime(timeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timeMs))
    }

    private fun chooseZoomForScreenWidth(latitude: Double, width: Float): Int {
        val targetMetersPerPixel = TARGET_SCREEN_WIDTH_METERS / width.coerceAtLeast(1f)
        val latitudeFactor = cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
        val metersPerPixelAtZoomZero = WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL * latitudeFactor
        val zoom = (ln(metersPerPixelAtZoomZero / targetMetersPerPixel) / ln(2.0)).roundToInt()
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_ZOOM = 3
        private const val MAX_ZOOM = 21
        private const val TARGET_SCREEN_WIDTH_METERS = 100.0
        private const val WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL = 156543.03392
    }
}
