package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
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
    private val playedPolylines = mutableListOf<Polyline>()
    private var playedRunScales: List<Float> = emptyList()
    private var currentPulseCircle: Circle? = null
    private var currentCircle: Circle? = null
    private var maxSpeedCircle: Circle? = null
    private var photoMarks: List<RoutePhotoMark> = emptyList()
    private val photoMarkers = mutableListOf<Marker>()
    private var photoMarkerIcon: BitmapDescriptor? = null
    private var fitWholeRoute = false
    private var currentMapType = AMap.MAP_TYPE_NORMAL
    private var userCameraLocked = false
    private var routeCameraInitialized = false
    private var viewportScaleIndex = DEFAULT_VIEWPORT_SCALE_INDEX
    private var overlayControlsVisible = false
    private var activeAutoProvince: String? = null
    private var activeAutoProgress: Int? = null
    private val autoDownloadFailedNames = mutableSetOf<String>()
    private var downTouchX = 0f
    private var downTouchY = 0f
    var onMapTap: (() -> Unit)? = null
    var onScaleChanged: (() -> Unit)? = null
    var onPhotoClick: ((String) -> Unit)? = null

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

    internal fun setPhotoMarks(marks: List<RoutePhotoMark>) {
        photoMarks = marks
        renderPhotoMarks()
    }

    fun resetViewport() {
        showCurrentViewport()
    }

    fun showWholeRoute() {
        fitWholeRoute = true
        userCameraLocked = false
        routeCameraInitialized = false
        updateCamera()
        updateButtons()
    }

    fun showCurrentViewport() {
        fitWholeRoute = false
        userCameraLocked = false
        routeCameraInitialized = false
        updateCamera()
        updateButtons()
    }

    fun toggleMapType() {
        currentMapType = if (currentMapType == AMap.MAP_TYPE_NORMAL) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
        aMap.mapType = currentMapType
        updateButtons()
    }

    fun openOfflineMap() {
        context.startActivity(Intent(context, OfflineMapActivity::class.java))
    }

    fun setOverlayControlsVisible(visible: Boolean) {
        overlayControlsVisible = visible
        updateOverlayControlsVisibility()
    }

    fun mapTypeLabel(): String = if (currentMapType == AMap.MAP_TYPE_NORMAL) "道路" else "卫星"

    fun zoomInScale() {
        if (fitWholeRoute) {
            setViewportScaleIndex(VIEWPORT_SCALE_METERS.lastIndex)
        } else {
            setViewportScaleIndex(viewportScaleIndex - 1)
        }
    }

    fun zoomOutScale() {
        if (fitWholeRoute) return
        if (viewportScaleIndex >= VIEWPORT_SCALE_METERS.lastIndex) {
            fitWholeRoute = true
            userCameraLocked = false
            routeCameraInitialized = false
            updateCamera()
            updateButtons()
        } else {
            setViewportScaleIndex(viewportScaleIndex + 1)
        }
    }

    fun canZoomInScale(): Boolean = fitWholeRoute || viewportScaleIndex > 0

    fun canZoomOutScale(): Boolean = !fitWholeRoute

    fun mapScaleLabel(): String {
        return if (fitWholeRoute) "全程" else formatScaleMeters(VIEWPORT_SCALE_METERS[viewportScaleIndex])
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
        aMap.setOnMarkerClickListener { marker ->
            val path = marker.`object` as? String
            if (path.isNullOrBlank()) {
                false
            } else {
                onPhotoClick?.invoke(path)
                true
            }
        }
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
        viewportButton.setOnClickListener { showWholeRoute() }
        currentButton.text = "当前"
        currentButton.setOnClickListener { showCurrentViewport() }
        mapTypeButton.text = "道路"
        mapTypeButton.setOnClickListener { toggleMapType() }
        offlineButton.text = "离线"
        offlineButton.setOnClickListener { openOfflineMap() }

        listOf(viewportButton, currentButton, mapTypeButton, offlineButton).forEachIndexed { index, button ->
            styleOverlayButton(button)
            addView(
                button,
                LayoutParams(dp(76), dp(44)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(12 + index * 52), dp(12), 0)
                }
            )
        }
        updateOverlayControlsVisibility()
    }

    private fun renderRoute() {
        routeSegments.forEach { it.remove() }
        routeSegments.clear()
        clearPlayedRoute()
        currentPulseCircle?.remove()
        currentPulseCircle = null
        currentCircle?.remove()
        currentCircle = null
        maxSpeedCircle?.remove()
        maxSpeedCircle = null

        if (convertedPoints.size < 2) return
        for (i in 1 until convertedPoints.size) {
            val speedKmh = RouteTrackStyle.segmentSpeedKmh(points[i - 1].speedKmh, points[i].speedKmh)
            routeSegments += aMap.addPolyline(
                PolylineOptions()
                    .add(convertedPoints[i - 1], convertedPoints[i])
                    .width(routeWidthPx(RouteTrackStyle.widthScale(speedKmh)))
                    .color(ROUTE_COLOR)
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
        renderPhotoMarks()
    }

    private fun renderPhotoMarks() {
        photoMarkers.forEach { it.remove() }
        photoMarkers.clear()
        if (photoMarks.isEmpty()) return
        val icon = photoMarkerIcon()
        photoMarks.forEach { mark ->
            val marker = aMap.addMarker(
                MarkerOptions()
                    .position(toAmapLatLng(mark.latitude, mark.longitude))
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
                    .zIndex(55f)
            ) ?: return@forEach
            marker.setObject(mark.path)
            marker.isInfoWindowEnable = false
            photoMarkers += marker
        }
    }

    private fun photoMarkerIcon(): BitmapDescriptor {
        photoMarkerIcon?.let { return it }
        // 空心小圈，尽量少挡住轨迹。四周透明边距只扩大点击范围。
        val diameter = dp(10).coerceAtLeast(1)
        val inset = dp(5)
        val size = diameter + inset * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val strokeWidth = (1.5f * resources.displayMetrics.density).coerceAtLeast(1.5f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 145, 0)
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val radius = (diameter / 2f - strokeWidth / 2f).coerceAtLeast(strokeWidth / 2f)
        canvas.drawCircle(size / 2f, size / 2f, radius, stroke)
        val icon = BitmapDescriptorFactory.fromBitmap(bitmap)
        photoMarkerIcon = icon
        return icon
    }

    private fun renderPlaybackState() {
        if (points.isEmpty() || convertedPoints.isEmpty()) return
        val current = VideoTrackSync.pointAt(points, elapsedMs) ?: points.first()
        val currentLatLng = current.toAmapLatLng()
        val playedIndex = VideoTrackSync.lastPlayedIndex(points, elapsedMs).coerceIn(0, convertedPoints.lastIndex)
        renderPlayedRoute(playedIndex, currentLatLng)
        if (currentPulseCircle == null) {
            currentPulseCircle = aMap.addCircle(
                CircleOptions()
                    .center(currentLatLng)
                    .radius(currentPulseRadius())
                    .strokeWidth(currentPulseStrokeWidth())
                    .strokeColor(currentPulseStrokeColor())
                    .fillColor(currentPulseFillColor())
                    .zIndex(48f)
            )
        } else {
            currentPulseCircle?.let { circle ->
                circle.center = currentLatLng
                circle.radius = currentPulseRadius()
                circle.strokeWidth = currentPulseStrokeWidth()
                circle.strokeColor = currentPulseStrokeColor()
                circle.fillColor = currentPulseFillColor()
            }
        }
        if (currentCircle == null) {
            currentCircle = aMap.addCircle(
                CircleOptions()
                    .center(currentLatLng)
                    .radius(currentMarkerRadius())
                    .strokeWidth(currentMarkerStrokeWidth())
                    .strokeColor(currentMarkerStrokeColor())
                    .fillColor(currentMarkerColor())
                    .zIndex(60f)
            )
        } else {
            currentCircle?.let { circle ->
                circle.center = currentLatLng
                circle.radius = currentMarkerRadius()
                circle.strokeWidth = currentMarkerStrokeWidth()
                circle.strokeColor = currentMarkerStrokeColor()
                circle.fillColor = currentMarkerColor()
            }
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
            aMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    current,
                    chooseZoomForScreenWidth(
                        current.latitude,
                        width.toFloat(),
                        VIEWPORT_SCALE_METERS[viewportScaleIndex]
                    ).toFloat()
                )
            )
            routeCameraInitialized = true
        }
        notifyScaleChanged()
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
        mapTypeButton.text = mapTypeLabel()
        notifyScaleChanged()
    }

    private fun updateOverlayControlsVisibility() {
        val visibility = if (overlayControlsVisible) View.VISIBLE else View.GONE
        viewportButton.visibility = visibility
        currentButton.visibility = visibility
        mapTypeButton.visibility = visibility
        offlineButton.visibility = visibility
    }

    private fun styleOverlayButton(button: Button) {
        button.setTextColor(Color.WHITE)
        button.textSize = 12f
        button.typeface = android.graphics.Typeface.DEFAULT_BOLD
        button.background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.map_button_translucent_bg)
        button.minWidth = 0
        button.minHeight = 0
        button.minimumWidth = 0
        button.minimumHeight = 0
        button.includeFontPadding = false
        button.setPadding(0, 0, 0, 0)
        button.isAllCaps = false
    }

    private fun setViewportScaleIndex(index: Int) {
        val nextIndex = index.coerceIn(0, VIEWPORT_SCALE_METERS.lastIndex)
        if (nextIndex == viewportScaleIndex && !fitWholeRoute && !userCameraLocked) {
            notifyScaleChanged()
            return
        }
        viewportScaleIndex = nextIndex
        fitWholeRoute = false
        userCameraLocked = false
        routeCameraInitialized = false
        updateCamera()
        updateButtons()
    }

    private fun notifyScaleChanged() {
        onScaleChanged?.invoke()
    }

    private fun currentMarkerRadius(): Double {
        return when (currentMarkerPhase()) {
            0 -> 9.0
            1 -> 6.5
            else -> 8.0
        }
    }

    private fun currentMarkerStrokeWidth(): Float {
        return dp(if (currentMarkerPhase() == 1) 5 else 7).toFloat()
    }

    private fun currentMarkerStrokeColor(): Int {
        return when (currentMarkerPhase()) {
            0 -> Color.WHITE
            1 -> Color.rgb(255, 246, 143)
            else -> Color.rgb(255, 255, 255)
        }
    }

    private fun currentMarkerColor(): Int {
        return when (currentMarkerPhase()) {
            0 -> Color.rgb(0, 229, 255)
            1 -> Color.rgb(255, 193, 7)
            else -> Color.rgb(255, 64, 129)
        }
    }

    private fun currentPulseRadius(): Double {
        val fraction = currentPulseFraction()
        return 12.0 + 30.0 * fraction
    }

    private fun currentPulseStrokeWidth(): Float {
        return dp(5).toFloat()
    }

    private fun currentPulseStrokeColor(): Int {
        val alpha = (210 - 150 * currentPulseFraction()).toInt().coerceIn(50, 210)
        return when (currentMarkerPhase()) {
            0 -> Color.argb(alpha, 0, 229, 255)
            1 -> Color.argb(alpha, 255, 193, 7)
            else -> Color.argb(alpha, 255, 64, 129)
        }
    }

    private fun currentPulseFillColor(): Int {
        val alpha = (90 - 70 * currentPulseFraction()).toInt().coerceIn(12, 90)
        return when (currentMarkerPhase()) {
            0 -> Color.argb(alpha, 0, 229, 255)
            1 -> Color.argb(alpha, 255, 193, 7)
            else -> Color.argb(alpha, 255, 64, 129)
        }
    }

    private fun currentMarkerPhase(): Int {
        return ((SystemClock.uptimeMillis() / 300L) % 3L).toInt()
    }

    private fun currentPulseFraction(): Float {
        return (SystemClock.uptimeMillis() % 900L).toFloat() / 900f
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

    private fun VideoTrackPoint.toAmapLatLng(): LatLng = toAmapLatLng(latitude, longitude)

    private fun toAmapLatLng(latitude: Double, longitude: Double): LatLng {
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

    private data class PlayedRun(val scale: Float, val points: MutableList<LatLng>)

    private fun routeWidthPx(scale: Float): Float = dp(ROUTE_BASE_WIDTH_DP).toFloat() * scale

    private fun renderPlayedRoute(playedIndex: Int, currentLatLng: LatLng) {
        val runs = buildPlayedRuns(playedIndex, currentLatLng)
        val scales = runs.map { it.scale }
        if (scales == playedRunScales && playedPolylines.size == runs.size) {
            if (runs.isNotEmpty()) playedPolylines.last().points = runs.last().points
            return
        }
        clearPlayedRoute()
        runs.forEach { run ->
            val line = aMap.addPolyline(
                PolylineOptions()
                    .addAll(run.points)
                    .width(routeWidthPx(RouteTrackStyle.playedWidthScale(run.scale)))
                    .color(Color.WHITE)
                    .zIndex(30f)
            ) ?: return@forEach
            playedPolylines += line
            playedRunScales = playedRunScales + run.scale
        }
    }

    private fun buildPlayedRuns(playedIndex: Int, currentLatLng: LatLng): List<PlayedRun> {
        if (convertedPoints.isEmpty() || points.isEmpty()) return emptyList()
        val runs = mutableListOf<PlayedRun>()
        fun append(latLng: LatLng, scale: Float) {
            val last = runs.lastOrNull()
            if (last != null && last.scale == scale) {
                last.points += latLng
            } else {
                val seed = last?.points?.lastOrNull() ?: convertedPoints.first()
                runs += PlayedRun(scale, mutableListOf(seed, latLng))
            }
        }
        val lastFull = playedIndex.coerceIn(0, convertedPoints.lastIndex)
        for (i in 1..lastFull) {
            val speedKmh = RouteTrackStyle.segmentSpeedKmh(points[i - 1].speedKmh, points[i].speedKmh)
            append(convertedPoints[i], RouteTrackStyle.widthScale(speedKmh))
        }
        val ahead = (lastFull + 1).coerceAtMost(points.lastIndex)
        val partialScale = if (ahead > lastFull) {
            RouteTrackStyle.widthScale(
                RouteTrackStyle.segmentSpeedKmh(points[lastFull].speedKmh, points[ahead].speedKmh)
            )
        } else {
            RouteTrackStyle.widthScale(points[lastFull].speedKmh)
        }
        append(currentLatLng, partialScale)
        return runs
    }

    private fun clearPlayedRoute() {
        playedPolylines.forEach { it.remove() }
        playedPolylines.clear()
        playedRunScales = emptyList()
    }

    private fun compactAmapName(name: String): String = AmapOfflineMapStore.compactName(name)

    private fun formatTime(timeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timeMs))
    }

    private fun chooseZoomForScreenWidth(latitude: Double, width: Float, targetScreenWidthMeters: Double): Int {
        val targetMetersPerPixel = targetScreenWidthMeters / width.coerceAtLeast(1f)
        val latitudeFactor = cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
        val metersPerPixelAtZoomZero = WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL * latitudeFactor
        val zoom = (ln(metersPerPixelAtZoomZero / targetMetersPerPixel) / ln(2.0)).roundToInt()
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    private fun formatScaleMeters(meters: Double): String {
        return if (meters >= 1000.0) {
            val km = meters / 1000.0
            if (km % 1.0 == 0.0) {
                "${km.toInt()} km"
            } else {
                String.format(Locale.US, "%.1f km", km)
            }
        } else {
            "${meters.toInt()} m"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_ZOOM = 3
        private const val MAX_ZOOM = 21
        private val VIEWPORT_SCALE_METERS = doubleArrayOf(
            25.0,
            50.0,
            100.0,
            200.0,
            500.0,
            1000.0,
            2000.0,
            5000.0,
            10000.0,
            20000.0,
            50000.0,
            100000.0
        )
        private const val DEFAULT_VIEWPORT_SCALE_INDEX = 6
        private const val ROUTE_BASE_WIDTH_DP = 5
        private val ROUTE_COLOR = Color.rgb(79, 214, 151)
        private const val WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL = 156543.03392
    }
}
