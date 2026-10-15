package com.thomas.carspeed

import android.content.Context
import android.content.Intent
<<<<<<< HEAD
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
=======
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
>>>>>>> zipcopy/main
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
<<<<<<< HEAD
=======
import androidx.appcompat.content.res.AppCompatResources
>>>>>>> zipcopy/main
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapException
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
<<<<<<< HEAD
import com.amap.api.maps.MapView
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapProvince
import com.amap.api.maps.offlinemap.OfflineMapStatus
=======
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapProvince
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
>>>>>>> zipcopy/main
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
<<<<<<< HEAD
=======
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
>>>>>>> zipcopy/main
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
<<<<<<< HEAD
    private val mapView = MapView(context)
=======
    private class MaxSpeedTag(val speedKmh: Float)

    private val mapView = TextureMapView(context)
>>>>>>> zipcopy/main
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
<<<<<<< HEAD
    private var playedPolyline: Polyline? = null
    private var currentCircle: Circle? = null
    private var maxSpeedCircle: Circle? = null
=======
    private val playedPolylines = mutableListOf<Polyline>()
    private var playedRunScales: List<Float> = emptyList()
    private var currentPulseCircle: Circle? = null
    private var currentCircle: Circle? = null
    private var maxSpeedMarker: Marker? = null
    private var maxSpeedIcon: BitmapDescriptor? = null
    var onMaxSpeedClick: ((Float) -> Unit)? = null
    private var photoMarks: List<RoutePhotoMark> = emptyList()
    private val photoMarkers = mutableListOf<Marker>()
    private var photoMarkerIcon: BitmapDescriptor? = null
    private var radarMarks: List<VideoTrackRadar> = emptyList()
    private var radarLatLngs: List<LatLng> = emptyList()
    private val radarMarkers = mutableListOf<Marker>()
    private val radarMarkerIcons = mutableMapOf<Int, RadarMarkerBitmap>()
>>>>>>> zipcopy/main
    private var fitWholeRoute = false
    private var currentMapType = AMap.MAP_TYPE_NORMAL
    private var userCameraLocked = false
    private var routeCameraInitialized = false
<<<<<<< HEAD
=======
    private var viewportScaleIndex = DEFAULT_VIEWPORT_SCALE_INDEX
    private var contentRotationDegrees = 0
    private var overlayControlsVisible = false
>>>>>>> zipcopy/main
    private var activeAutoProvince: String? = null
    private var activeAutoProgress: Int? = null
    private val autoDownloadFailedNames = mutableSetOf<String>()
    private var downTouchX = 0f
    private var downTouchY = 0f
    var onMapTap: (() -> Unit)? = null
<<<<<<< HEAD

    init {
=======
    var onScaleChanged: (() -> Unit)? = null
    var onPhotoClick: ((String) -> Unit)? = null
    var onRadarClick: ((VideoTrackRadar) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
>>>>>>> zipcopy/main
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

<<<<<<< HEAD
    fun resetViewport() {
=======
    internal fun setPhotoMarks(marks: List<RoutePhotoMark>) {
        photoMarks = marks
        renderPhotoMarks()
    }

    fun setRadarMarks(marks: List<VideoTrackRadar>) {
        radarMarks = marks
        radarLatLngs = marks.map { LatLng(it.latitude, it.longitude) }
        renderRadarMarks()
        if (fitWholeRoute) updateCamera()
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
>>>>>>> zipcopy/main
        fitWholeRoute = false
        userCameraLocked = false
        routeCameraInitialized = false
        updateCamera()
<<<<<<< HEAD
=======
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

    fun setContentRotation(degrees: Int) {
        contentRotationDegrees = VideoReplayRotation.normalize(degrees)
        applyContentRotation()
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
>>>>>>> zipcopy/main
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
<<<<<<< HEAD
=======
        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) {
                resizeCurrentMarkers()
            }

            override fun onCameraChangeFinish(position: CameraPosition?) {
                resizeCurrentMarkers()
            }
        })
        aMap.setOnMarkerClickListener { marker ->
            val radar = marker.`object` as? VideoTrackRadar
            if (radar != null) {
                onRadarClick?.invoke(radar)
                return@setOnMarkerClickListener true
            }
            val maxSpeed = marker.`object` as? MaxSpeedTag
            if (maxSpeed != null) {
                onMaxSpeedClick?.invoke(maxSpeed.speedKmh)
                return@setOnMarkerClickListener true
            }
            val path = marker.`object` as? String
            if (path.isNullOrBlank()) {
                false
            } else {
                onPhotoClick?.invoke(path)
                true
            }
        }
>>>>>>> zipcopy/main
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
<<<<<<< HEAD
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
=======
        viewportButton.setOnClickListener { showWholeRoute() }
        currentButton.text = "当前"
        currentButton.setOnClickListener { showCurrentViewport() }
        mapTypeButton.text = "道路"
        mapTypeButton.setOnClickListener { toggleMapType() }
        offlineButton.text = "离线"
        offlineButton.setOnClickListener { openOfflineMap() }

        listOf(viewportButton, currentButton, mapTypeButton, offlineButton).forEachIndexed { index, button ->
            styleOverlayButton(button)
>>>>>>> zipcopy/main
            addView(
                button,
                LayoutParams(dp(76), dp(44)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(12 + index * 52), dp(12), 0)
                }
            )
        }
<<<<<<< HEAD
=======
        updateOverlayControlsVisibility()
>>>>>>> zipcopy/main
    }

    private fun renderRoute() {
        routeSegments.forEach { it.remove() }
        routeSegments.clear()
<<<<<<< HEAD
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
=======
        clearPlayedRoute()
        currentPulseCircle?.remove()
        currentPulseCircle = null
        currentCircle?.remove()
        currentCircle = null
        maxSpeedMarker?.remove()
        maxSpeedMarker = null

        if (convertedPoints.size < 2) return
        for (i in 1 until convertedPoints.size) {
            val speedKmh = RouteTrackStyle.segmentSpeedKmh(points[i - 1].speedKmh, points[i].speedKmh)
            routeSegments += aMap.addPolyline(
                PolylineOptions()
                    .add(convertedPoints[i - 1], convertedPoints[i])
                    .width(routeWidthPx(RouteTrackStyle.widthScale(speedKmh)))
                    .color(ROUTE_COLOR)
>>>>>>> zipcopy/main
                    .zIndex(20f)
            )
        }
        val maxIndex = points.indices.maxByOrNull { points[it].speedKmh } ?: -1
        if (maxIndex >= 0 && points[maxIndex].speedKmh > 0f) {
<<<<<<< HEAD
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

=======
            val marker = aMap.addMarker(
                MarkerOptions()
                    .position(convertedPoints[maxIndex])
                    .icon(maxSpeedMarkerIcon())
                    .anchor(0.5f, 0.5f)
                    .zIndex(42f)
            )
            marker?.setObject(MaxSpeedTag(points[maxIndex].speedKmh))
            marker?.isInfoWindowEnable = false
            maxSpeedMarker = marker
        }
        renderPhotoMarks()
        renderRadarMarks()
    }

    private fun maxSpeedMarkerIcon(): BitmapDescriptor {
        maxSpeedIcon?.let { return it }
        val diameter = dp(14).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 64, 129)
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (1.5f * resources.displayMetrics.density).coerceAtLeast(1.5f)
        }
        val radius = diameter / 2f - stroke.strokeWidth / 2f
        canvas.drawCircle(diameter / 2f, diameter / 2f, radius, fill)
        canvas.drawCircle(diameter / 2f, diameter / 2f, radius, stroke)
        val icon = BitmapDescriptorFactory.fromBitmap(bitmap)
        maxSpeedIcon = icon
        return icon
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

    private fun renderRadarMarks() {
        radarMarkers.forEach { it.remove() }
        radarMarkers.clear()
        if (radarMarks.isEmpty()) return
        radarMarks.forEach { mark ->
            val bitmap = radarMarkerBitmap(mark.speedLimit)
            // 高德巡航给的是地图坐标，轨迹点才需要从 GPS 再转一次。
            val marker = aMap.addMarker(
                MarkerOptions()
                    .position(LatLng(mark.latitude, mark.longitude))
                    .icon(bitmap.icon)
                    .anchor(0.5f, bitmap.anchorY)
                    .zIndex(58f)
            ) ?: return@forEach
            marker.`object` = mark
            marker.isInfoWindowEnable = false
            radarMarkers += marker
        }
    }

    private fun radarMarkerBitmap(speedLimit: Int?): RadarMarkerBitmap {
        val limit = speedLimit?.takeIf { it > 0 }
        val key = limit ?: RADAR_ICON_NO_LIMIT
        radarMarkerIcons[key]?.let { return it }
        val iconSize = dp(22).coerceAtLeast(1)
        val label = limit?.toString()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(11).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val textWidth = label?.let { textPaint.measureText(it) } ?: 0f
        val pillPadX = dp(4)
        val pillHeight = if (label == null) 0 else dp(14)
        val gap = if (label == null) 0 else dp(1)
        val width = max(iconSize, (textWidth + pillPadX * 2).roundToInt()).coerceAtLeast(1)
        val height = (iconSize + gap + pillHeight).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        AppCompatResources.getDrawable(context, R.drawable.ic_speed_radar)?.let { drawable ->
            val left = (width - iconSize) / 2
            drawable.setBounds(left, 0, left + iconSize, iconSize)
            drawable.draw(canvas)
        }
        if (label != null) {
            val pillTop = iconSize + gap
            val pill = android.graphics.RectF(
                (width - textWidth) / 2f - pillPadX,
                pillTop.toFloat(),
                (width + textWidth) / 2f + pillPadX,
                (pillTop + pillHeight).toFloat()
            )
            val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(21, 32, 51)
            }
            canvas.drawRoundRect(pill, dp(3).toFloat(), dp(3).toFloat(), pillPaint)
            val baseline = pill.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(label, width / 2f, baseline, textPaint)
        }
        val marker = RadarMarkerBitmap(
            icon = BitmapDescriptorFactory.fromBitmap(bitmap),
            anchorY = (iconSize / 2f) / height
        )
        radarMarkerIcons[key] = marker
        return marker
    }

    private data class RadarMarkerBitmap(
        val icon: BitmapDescriptor,
        val anchorY: Float
    )

>>>>>>> zipcopy/main
    private fun renderPlaybackState() {
        if (points.isEmpty() || convertedPoints.isEmpty()) return
        val current = VideoTrackSync.pointAt(points, elapsedMs) ?: points.first()
        val currentLatLng = current.toAmapLatLng()
        val playedIndex = VideoTrackSync.lastPlayedIndex(points, elapsedMs).coerceIn(0, convertedPoints.lastIndex)
<<<<<<< HEAD
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
=======
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
>>>>>>> zipcopy/main
        }
        if (currentCircle == null) {
            currentCircle = aMap.addCircle(
                CircleOptions()
                    .center(currentLatLng)
<<<<<<< HEAD
                    .radius(2.5)
                    .strokeWidth(dp(5).toFloat())
                    .strokeColor(Color.WHITE)
                    .fillColor(Color.rgb(79, 214, 151))
                    .zIndex(50f)
            )
        } else {
            currentCircle?.center = currentLatLng
=======
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
>>>>>>> zipcopy/main
        }
    }

    private fun updateCamera() {
        if (convertedPoints.isEmpty() || width == 0 || height == 0) return
        if (fitWholeRoute && convertedPoints.size > 1) {
            val builder = LatLngBounds.builder()
            convertedPoints.forEach { builder.include(it) }
<<<<<<< HEAD
=======
            radarLatLngs.forEach { builder.include(it) }
>>>>>>> zipcopy/main
            runCatching { aMap.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), dp(18))) }
            routeCameraInitialized = true
        } else if (userCameraLocked) {
            return
        } else if (routeCameraInitialized) {
            val current = VideoTrackSync.pointAt(points, elapsedMs)?.toAmapLatLng() ?: convertedPoints.first()
            aMap.moveCamera(CameraUpdateFactory.changeLatLng(current))
        } else {
            val current = VideoTrackSync.pointAt(points, elapsedMs)?.toAmapLatLng() ?: convertedPoints.first()
<<<<<<< HEAD
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(current, chooseZoomForScreenWidth(current.latitude, width.toFloat()).toFloat()))
            routeCameraInitialized = true
        }
=======
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyContentRotation()
    }

    private fun applyContentRotation() {
        if (width <= 0 || height <= 0) return
        val quarter = VideoReplayRotation.isQuarterTurn(contentRotationDegrees)
        val viewWidth = if (quarter) height else width
        val viewHeight = if (quarter) width else height
        val params = mapView.layoutParams
        if (params.width != viewWidth || params.height != viewHeight) {
            mapView.layoutParams = LayoutParams(viewWidth, viewHeight, Gravity.CENTER)
        }
        mapView.pivotX = viewWidth / 2f
        mapView.pivotY = viewHeight / 2f
        mapView.rotation = contentRotationDegrees.toFloat()
>>>>>>> zipcopy/main
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
<<<<<<< HEAD
        mapTypeButton.text = if (currentMapType == AMap.MAP_TYPE_NORMAL) "道路" else "卫星"
=======
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

    private fun currentMarkerRadius(): Double = screenRadiusMeters(currentDotDp())

    private fun currentMarkerStrokeWidth(): Float {
        return (2f / 3f) * resources.displayMetrics.density
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

    private fun currentPulseRadius(): Double = screenRadiusMeters(currentPulseDp())

    private fun currentPulseStrokeWidth(): Float {
        return (2f / 3f) * resources.displayMetrics.density
    }

    private fun resizeCurrentMarkers() {
        if (currentCircle == null && currentPulseCircle == null) return
        // 半径按当前缩放换算，避免比例尺变大后仍沿用上一次的米制半径。
        currentCircle?.radius = currentMarkerRadius()
        currentPulseCircle?.radius = currentPulseRadius()
    }

    private fun currentDotDp(): Float {
        return when (currentMarkerPhase()) {
            1 -> CURRENT_DOT_RADIUS_DP - (1f / 3f)
            else -> CURRENT_DOT_RADIUS_DP
        }
    }

    private fun currentPulseDp(): Float {
        return CURRENT_PULSE_MIN_DP + (CURRENT_PULSE_MAX_DP - CURRENT_PULSE_MIN_DP) * currentPulseFraction()
    }

    private fun screenRadiusMeters(radiusDp: Float, latitude: Double = currentLatitude()): Double {
        return MapMarkerScale.metersForPixels(radiusDp * resources.displayMetrics.density, currentZoom(), latitude)
    }

    private fun currentLatitude(): Double {
        return VideoTrackSync.pointAt(points, elapsedMs)?.latitude
            ?: points.firstOrNull()?.latitude
            ?: 30.0
    }

    private fun currentZoom(): Float {
        val zoom = aMap.cameraPosition?.zoom ?: 0f
        if (zoom > 1f) return zoom.coerceIn(MIN_ZOOM.toFloat(), MAX_ZOOM.toFloat())
        return chooseZoomForScreenWidth(
            currentLatitude(),
            width.toFloat().coerceAtLeast(1f),
            VIEWPORT_SCALE_METERS[viewportScaleIndex]
        ).toFloat()
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
>>>>>>> zipcopy/main
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

<<<<<<< HEAD
    private fun VideoTrackPoint.toAmapLatLng(): LatLng {
=======
    private fun VideoTrackPoint.toAmapLatLng(): LatLng = toAmapLatLng(latitude, longitude)

    private fun toAmapLatLng(latitude: Double, longitude: Double): LatLng {
>>>>>>> zipcopy/main
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

<<<<<<< HEAD
    private fun speedColor(speedKmh: Float): Int {
        return when {
            speedKmh < 30f -> Color.rgb(79, 214, 151)
            speedKmh < 80f -> Color.rgb(255, 205, 86)
            else -> Color.rgb(255, 91, 91)
        }
=======
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
>>>>>>> zipcopy/main
    }

    private fun compactAmapName(name: String): String = AmapOfflineMapStore.compactName(name)

    private fun formatTime(timeMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timeMs))
    }

<<<<<<< HEAD
    private fun chooseZoomForScreenWidth(latitude: Double, width: Float): Int {
        val targetMetersPerPixel = TARGET_SCREEN_WIDTH_METERS / width.coerceAtLeast(1f)
=======
    private fun chooseZoomForScreenWidth(latitude: Double, width: Float, targetScreenWidthMeters: Double): Int {
        val targetMetersPerPixel = targetScreenWidthMeters / width.coerceAtLeast(1f)
>>>>>>> zipcopy/main
        val latitudeFactor = cos(Math.toRadians(latitude)).coerceAtLeast(0.2)
        val metersPerPixelAtZoomZero = WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL * latitudeFactor
        val zoom = (ln(metersPerPixelAtZoomZero / targetMetersPerPixel) / ln(2.0)).roundToInt()
        return zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

<<<<<<< HEAD
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_ZOOM = 3
        private const val MAX_ZOOM = 21
        private const val TARGET_SCREEN_WIDTH_METERS = 100.0
=======
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
        private const val RADAR_ICON_NO_LIMIT = Int.MIN_VALUE
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
        private const val CURRENT_DOT_RADIUS_DP = 2f
        private const val CURRENT_PULSE_MIN_DP = 8f / 3f
        private const val CURRENT_PULSE_MAX_DP = 16f / 3f
        private const val ROUTE_BASE_WIDTH_DP = 5
        private val ROUTE_COLOR = Color.rgb(79, 214, 151)
>>>>>>> zipcopy/main
        private const val WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL = 156543.03392
    }
}
