package com.thomas.carspeed

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import androidx.appcompat.content.res.AppCompatResources
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapException
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapProvince
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.Circle
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
    private class MaxSpeedTag(val speedKmh: Float)

    private val mapView = TextureMapView(context)
    private val aMap: AMap
    private val statsView = TextView(context)
    private val viewportButton = Button(context)
    private val currentButton = Button(context)
    private val mapTypeButton = Button(context)
    private val offlineButton = Button(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var cancelMapGesture = false
    private var offlineManager: OfflineMapManager? = null
    private var points: List<VideoTrackPoint> = emptyList()
    private var convertedPoints: List<LatLng> = emptyList()
    private var elapsedMs: Long = 0L
    private var captureReady: (() -> Unit)? = null
    private var summary: TrackSummary = TrackSummary(0L, 0f, 0f, 0f, null, 0f)
    private var routeKey: String? = null
    private var lastRouteInput: List<VideoTrackPoint>? = null
    private var lastRouteInputSize = -1
    private val routeSegments = mutableListOf<Polyline>()
    private val playedPolylines = mutableListOf<Polyline>()
    private var playedRunScales: List<Float> = emptyList()
    private var currentPulseCircle: Circle? = null
    private var currentCircle: Circle? = null
    private var maxSpeedMarker: Marker? = null
    private var maxSpeedMarkerSpeedKmh = 0f
    private var maxSpeedIcon: BitmapDescriptor? = null
    var onMaxSpeedClick: ((Float) -> Unit)? = null
    private var photoMarks: List<RoutePhotoMark> = emptyList()
    private val photoMarkers = mutableListOf<Marker>()
    private var photoMarkerIcon: BitmapDescriptor? = null
    private var radarMarks: List<VideoTrackRadar> = emptyList()
    private val radarMarkers = mutableListOf<Marker>()
    private val radarMarkerIcons = mutableMapOf<RadarIconKey, RadarMarkerBitmap>()
    private var fitWholeRoute = false
    private var rememberWholeRoute = false
    private var wholeRouteAwaitingLayout = false
    private var currentMapType = AMap.MAP_TYPE_NORMAL
    private var userCameraLocked = false
    private var gestureEndedAtMs = 0L
    /** 用户松手后 10 秒内不抢镜头；中途再触摸会重新计时。 */
    private val resumeCameraFollow = object : Runnable {
        override fun run() {
            if (!userCameraLocked || !isAttachedToWindow) return
            val waitMs = MapCameraFollowHold.remainingMs(gestureEndedAtMs, SystemClock.elapsedRealtime())
            if (waitMs > 0L) {
                postDelayed(this, waitMs)
                return
            }
            userCameraLocked = false
            routeCameraInitialized = true
            updateCamera()
            updateButtons()
        }
    }
    private var routeCameraInitialized = false
    private var cameraPrimed = false
    private var viewportScaleIndex = DEFAULT_VIEWPORT_SCALE_INDEX
    private var contentRotationDegrees = 0
    private var rotationLayoutListener: View.OnLayoutChangeListener? = null
    private var surfaceNudgeListener: View.OnLayoutChangeListener? = null
    private var firstSurfaceRefreshed = false
    private var surfaceRefreshTries = 0
    private var overlayControlsVisible = false
    private var activeAutoProvince: String? = null
    private var activeAutoProgress: Int? = null
    private val autoDownloadFailedNames = mutableSetOf<String>()
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var gestureMaxMove = 0f
    private var gestureStartedInWholeRoute = false
    var onMapTap: (() -> Unit)? = null
    var onScaleChanged: (() -> Unit)? = null
    var onCameraPreferenceChanged: (() -> Unit)? = null
    var onPhotoClick: ((String) -> Unit)? = null
    var onRadarClick: ((VideoTrackRadar) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
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
        val sameInput = points === lastRouteInput && points.size == lastRouteInputSize
        if (!sameInput) {
            lastRouteInput = points
            lastRouteInputSize = points.size
        }
        val sorted = if (sameInput) this.points else points.sortedBy { it.elapsedMs }
        val nextKey = if (sameInput) routeKey else sorted.routeKey()
        if (nextKey != routeKey) {
            // 实时记录时每秒来一个点，整条线重画在高德那边太贵，还会把镜头抢回去。
            // 只是尾巴上追加就只补新的一段，见 [appendToRoute]。
            if (appendToRoute(sorted)) {
                routeKey = nextKey
            } else {
                this.points = sorted
                this.summary = TrackMetrics.summary(sorted)
                this.convertedPoints = sorted.map { it.toAmapLatLng() }
                routeKey = nextKey
                renderRoute()
                if (sameInput) {
                    // 同一条实时轨迹只是第一个点迟到了（手机刚定位），接着跟随就行，
                    // 别把用户刚拖好的镜头拽回来。
                    routeCameraInitialized = false
                } else {
                    fitWholeRoute = rememberWholeRoute
                    userCameraLocked = false
                    routeCameraInitialized = false
                    cameraPrimed = false
                }
                ensureNeededOfflineMap()
            }
        }
        renderPlaybackState()
        updateCamera()
        updateStats()
    }

    /**
     * 刚点「仅轨迹」时还没有点，updateCamera 会直接返回，地图就停在默认的全国视野。
     * 拿最后一次已知位置先把镜头放到位，等第一个 GPS 点到了再交给 followCamera。
     */
    private fun primeCameraIfEmpty() {
        if (convertedPoints.isNotEmpty() || cameraPrimed) return
        val location = lastKnownLocation() ?: return
        cameraPrimed = true
        val target = toAmapLatLng(location.first, location.second)
        val zoom = if (width > 0) {
            chooseZoomForScreenWidth(
                target.latitude,
                width.toFloat(),
                VIEWPORT_SCALE_METERS[viewportScaleIndex]
            ).toFloat()
        } else {
            PRIME_ZOOM
        }
        anchorCameraCenter()
        runCatching {
            aMap.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(target).zoom(zoom).bearing(0f).tilt(0f).build()
                )
            )
        }
    }

    private fun lastKnownLocation(): Pair<Double, Double>? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.asSequence()
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .map { it.latitude to it.longitude }
            .firstOrNull { it.first != 0.0 || it.second != 0.0 }
    }

    /**
     * 实时轨迹只会在尾巴上加新点。确认老数据一个没动、后面纯追加，
     * 就只补最后那几段线：原来的线、用户的拖动缩放、10 秒不抢镜头都留着。
     * 返回 false 时调用方走整条重画。
     */
    private fun appendToRoute(next: List<VideoTrackPoint>): Boolean {
        val old = points
        if (old.isEmpty() || next.size <= old.size) return false
        if (next.size - old.size > ROUTE_APPEND_MAX_POINTS) return false
        val joined = old.last()
        if (next[0].timeMs != old[0].timeMs) return false
        if (next[old.lastIndex].timeMs != joined.timeMs) return false
        if (next[old.lastIndex].latitude != joined.latitude) return false
        if (next[old.lastIndex].longitude != joined.longitude) return false

        points = next
        summary = TrackMetrics.summary(next)
        convertedPoints = convertedPoints + next.subList(old.size, next.size).map { it.toAmapLatLng() }
        // 第 index 段连的是点 index-1 和点 index，old.lastIndex 那段之前已经画过了。
        for (index in old.size until next.size) {
            val speedKmh = RouteTrackStyle.segmentSpeedKmh(next[index - 1].speedKmh, next[index].speedKmh)
            routeSegments += aMap.addPolyline(
                PolylineOptions()
                    .add(convertedPoints[index - 1], convertedPoints[index])
                    .width(routeWidthPx(RouteTrackStyle.widthScale(speedKmh)))
                    .color(ROUTE_COLOR)
                    .zIndex(20f)
            )
        }
        val addedMaxIndex = (old.size until next.size).maxByOrNull { next[it].speedKmh } ?: -1
        if (addedMaxIndex >= 0 && next[addedMaxIndex].speedKmh > maxSpeedMarkerSpeedKmh) {
            maxSpeedMarker?.remove()
            maxSpeedMarker = null
            addMaxSpeedMarker(addedMaxIndex)
        }
        renderPhotoMarks()
        renderRadarMarks()
        // 跨省不用每个点都去查一遍，隔一段看一次就够了。
        if (next.size % OFFLINE_REGION_PROBE_STEP == 0) ensureNeededOfflineMap()
        return true
    }

    /**
     * 把地图拨到这一帧再截图。高德截的是地图本身，悬浮按钮不在里面。
     * 回调在主线程，位图是拷贝，调用方负责回收。
     */
    fun captureFrame(points: List<VideoTrackPoint>, elapsedMs: Long, onResult: (Bitmap?) -> Unit) {
        var readyFired = false
        fun ready() {
            if (readyFired) return
            readyFired = true
            captureReady = null
            captureSnapshot(onResult)
        }
        setRoute(points, elapsedMs)
        captureReady = { ready() }
        postDelayed({ ready() }, 60L)
    }

    private fun captureSnapshot(onResult: (Bitmap?) -> Unit) {
        val delivered = AtomicBoolean(false)
        var latest: Bitmap? = null
        fun keep(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.isRecycled) return
            val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
            val turned = turnCapturedBitmap(copy)
            val previous = latest
            latest = turned
            if (previous != null && previous !== turned) previous.recycle()
        }
        fun finish() {
            if (!delivered.compareAndSet(false, true)) return
            onResult(latest)
        }
        aMap.getMapScreenShot(object : AMap.OnMapScreenShotListener {
            override fun onMapScreenShot(bitmap: Bitmap?) {
                keep(bitmap)
            }

            override fun onMapScreenShot(bitmap: Bitmap?, status: Int) {
                keep(bitmap)
                if (status == 1) finish()
            }
        })
        postDelayed({ finish() }, 180L)
    }

    internal fun setPhotoMarks(marks: List<RoutePhotoMark>) {
        photoMarks = marks
        renderPhotoMarks()
    }

    fun setRadarMarks(marks: List<VideoTrackRadar>) {
        radarMarks = marks
        renderRadarMarks()
        if (fitWholeRoute) updateCamera()
    }

    fun resetViewport() {
        showCurrentViewport()
    }

    fun showWholeRoute() {
        rememberWholeRoute = true
        setWholeRouteMode(true)
        clearCameraFollowHold()
        routeCameraInitialized = false
        updateCamera()
        post { if (fitWholeRoute) updateCamera() }
        updateButtons()
        notifyCameraPreference()
    }

    fun revealWholeRoute() {
        if (fitWholeRoute && width > 0 && height > 0) updateCamera()
    }

    fun showCurrentViewport() {
        rememberWholeRoute = false
        setWholeRouteMode(false)
        clearCameraFollowHold()
        routeCameraInitialized = false
        updateCamera()
        updateButtons()
        notifyCameraPreference()
    }

    /** 用户显式点了「当前位置」或「全程」，就不用再等那 10 秒。 */
    private fun clearCameraFollowHold() {
        removeCallbacks(resumeCameraFollow)
        gestureEndedAtMs = 0L
        userCameraLocked = false
    }

    fun toggleMapType() {
        currentMapType = if (currentMapType == AMap.MAP_TYPE_NORMAL) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
        aMap.mapType = currentMapType
        updateButtons()
        notifyCameraPreference()
    }

    fun isSatelliteMap(): Boolean = currentMapType == AMap.MAP_TYPE_SATELLITE

    fun isWholeRoute(): Boolean = rememberWholeRoute

    fun scaleIndex(): Int = viewportScaleIndex

    fun restoreCamera(satellite: Boolean, wholeRoute: Boolean, scaleIndex: Int) {
        suppressCameraNotify = true
        try {
            rememberWholeRoute = wholeRoute
            val targetType = if (satellite) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
            if (currentMapType != targetType) {
                currentMapType = targetType
                aMap.mapType = currentMapType
            }
            if (wholeRoute) {
                showWholeRoute()
            } else {
                setViewportScaleIndex(scaleIndex)
            }
            updateButtons()
        } finally {
            suppressCameraNotify = false
        }
    }

    fun openOfflineMap() {
        context.startActivity(Intent(context, OfflineMapActivity::class.java))
    }

    fun setSpeedCaptionVisible(visible: Boolean) {
        statsView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private var consumeTouch: ((MotionEvent) -> Boolean)? = null

    /**
     * 返回 true 时地图不再跟着平移。拖动悬浮窗中途接管时，先给地图补一次取消，避免这次按住被当成点击。
     * 必须拦在 dispatchTouchEvent：地图子视图会吃掉触摸，外层 OnTouchListener 收不到。
     */
    fun setConsumeTouchListener(listener: ((MotionEvent) -> Boolean)?) {
        consumeTouch = listener
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelMapGesture = false
            // 手指一碰就把待执行的恢复取消，松手后再从头计 10 秒。
            removeCallbacks(resumeCameraFollow)
        }
        val consume = consumeTouch?.invoke(ev) == true
        val handled = if (!consume) {
            super.dispatchTouchEvent(ev)
        } else {
            if (!cancelMapGesture) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                cancelMapGesture = true
            }
            true
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // 松手后 10 秒内不抢镜头：用户可能在看某一段路的细节。
            // 到点由 resumeCameraFollow 用地图当前的缩放级别恢复居中。
            removeCallbacks(resumeCameraFollow)
            if (userCameraLocked) {
                gestureEndedAtMs = SystemClock.elapsedRealtime()
                postDelayed(resumeCameraFollow, MapCameraFollowHold.HOLD_MS + CAMERA_RESUME_AFTER_GESTURE_MS)
            }
        }
        return handled
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
            rememberWholeRoute = true
            setWholeRouteMode(true)
            clearCameraFollowHold()
            routeCameraInitialized = false
            updateCamera()
            updateButtons()
            notifyCameraPreference()
        } else {
            setViewportScaleIndex(viewportScaleIndex + 1)
        }
    }

    fun canZoomInScale(): Boolean = fitWholeRoute || viewportScaleIndex > 0

    fun canZoomOutScale(): Boolean = !fitWholeRoute

    fun mapScaleLabel(): String {
        return if (fitWholeRoute) "全程" else formatScaleMeters(VIEWPORT_SCALE_METERS[viewportScaleIndex])
    }

    // 未调用过 onResume 前先当作未恢复，避免 onAttachedToWindow 错过一次 resume 后地图一直停在暂停状态。
    private var hostResumed = false

    fun onHostResume() {
        if (hostResumed) return
        hostResumed = true
        mapView.onResume()
    }

    fun onHostPause() {
        if (!hostResumed) return
        hostResumed = false
        mapView.onPause()
    }

    fun onHostLowMemory() {
        mapView.onLowMemory()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hostResumed) mapView.onResume()
        mapView.post { updateCamera() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(resumeCameraFollow)
        gestureEndedAtMs = 0L
        userCameraLocked = false
        rotationLayoutListener?.let { mapView.removeOnLayoutChangeListener(it) }
        rotationLayoutListener = null
        surfaceNudgeListener?.let { mapView.removeOnLayoutChangeListener(it) }
        surfaceNudgeListener = null
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
                    gestureMaxMove = 0f
                    gestureStartedInWholeRoute = fitWholeRoute
                }
                MotionEvent.ACTION_POINTER_DOWN -> lockCameraForUserGesture()
                MotionEvent.ACTION_MOVE -> {
                    val moved = max(abs(event.x - downTouchX), abs(event.y - downTouchY))
                    gestureMaxMove = max(gestureMaxMove, moved)
                    if (gestureMaxMove > touchSlop) lockCameraForUserGesture()
                }
            }
        }
        aMap.setOnMapClickListener {
            // 点一下是去全屏，不是拖地图。手指稍微动一下也不要取消全程。
            if (gestureStartedInWholeRoute && gestureMaxMove <= touchSlop * 2) {
                setWholeRouteMode(true)
                clearCameraFollowHold()
            }
            onMapTap?.invoke()
        }
        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) {
                resizeCurrentMarkers()
            }

            override fun onCameraChangeFinish(position: CameraPosition?) {
                resizeCurrentMarkers()
                captureReady?.let { pending -> post { pending.invoke() } }
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
                LayoutParams(dp(56), dp(30)).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, dp(8 + index * 36), dp(8), 0)
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
        maxSpeedMarker?.remove()
        maxSpeedMarker = null
        maxSpeedMarkerSpeedKmh = 0f

        if (convertedPoints.size < 2) return
        // 必须一段一条线。收成一条长线时，高德在分区和悬浮里都不画。
        for (index in 1 until convertedPoints.size) {
            val speedKmh = RouteTrackStyle.segmentSpeedKmh(points[index - 1].speedKmh, points[index].speedKmh)
            routeSegments += aMap.addPolyline(
                PolylineOptions()
                    .add(convertedPoints[index - 1], convertedPoints[index])
                    .width(routeWidthPx(RouteTrackStyle.widthScale(speedKmh)))
                    .color(ROUTE_COLOR)
                    .zIndex(20f)
            )
        }
        val maxIndex = points.indices.maxByOrNull { points[it].speedKmh } ?: -1
        addMaxSpeedMarker(maxIndex)
        renderPhotoMarks()
        renderRadarMarks()
    }

    private fun addMaxSpeedMarker(index: Int) {
        if (index < 0 || index >= points.size || index >= convertedPoints.size) return
        if (points[index].speedKmh <= 0f) return
        val marker = aMap.addMarker(
            MarkerOptions()
                .position(convertedPoints[index])
                .icon(maxSpeedMarkerIcon())
                .anchor(0.5f, 0.5f)
                .zIndex(42f)
        ) ?: return
        marker.setObject(MaxSpeedTag(points[index].speedKmh))
        marker.isInfoWindowEnable = false
        maxSpeedMarker = marker
        maxSpeedMarkerSpeedKmh = points[index].speedKmh
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
            val bitmap = radarMarkerBitmap(mark.speedLimit, fitWholeRoute)
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

    private fun radarMarkerBitmap(speedLimit: Int?, overview: Boolean): RadarMarkerBitmap {
        val limit = speedLimit?.takeIf { it > 0 }
        val key = RadarIconKey(limit ?: RADAR_ICON_NO_LIMIT, overview)
        radarMarkerIcons[key]?.let { return it }
        // 全程时图标按屏幕尺寸会盖住路线，改成大约一半。
        val iconSize = dp(if (overview) 11 else 22).coerceAtLeast(1)
        val label = limit?.toString()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = dp(if (overview) 8 else 11).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val textWidth = label?.let { textPaint.measureText(it) } ?: 0f
        val pillPadX = dp(if (overview) 2 else 4)
        val pillHeight = if (label == null) 0 else dp(if (overview) 10 else 14)
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

    private data class RadarIconKey(val limit: Int, val overview: Boolean)

    private data class RadarMarkerBitmap(
        val icon: BitmapDescriptor,
        val anchorY: Float
    )

    private fun setWholeRouteMode(enabled: Boolean) {
        if (fitWholeRoute == enabled) return
        fitWholeRoute = enabled
        renderRadarMarks()
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
        if (width == 0 || height == 0) return
        if (convertedPoints.isEmpty()) {
            // 还没开始画线，但地图已经露出来了，先把镜头放到当前位置。
            primeCameraIfEmpty()
            return
        }
        if (fitWholeRoute && convertedPoints.size > 1) {
            // 只有视野框还没摆对才重新框全程。实时轨迹每秒一个新点，
            // 每次都重框的话镜头会一直往外拉，用户根本看不了一段路的细节。
            if (!mapViewMatchesVisibleSize() && !routeCameraInitialized) {
                if (!wholeRouteAwaitingLayout) {
                    wholeRouteAwaitingLayout = true
                    mapView.post {
                        wholeRouteAwaitingLayout = false
                        if (!fitWholeRoute) return@post
                        if (mapViewMatchesVisibleSize()) {
                            updateCamera()
                        } else if (mapView.width > 2 && mapView.height > 2) {
                            moveWholeRouteCamera()
                            routeCameraInitialized = true
                            notifyScaleChanged()
                        }
                    }
                }
                return
            }
            moveWholeRouteCamera()
            routeCameraInitialized = true
        } else if (userCameraLocked) {
            return
        } else {
            followCamera()
        }
        notifyScaleChanged()
    }

    private fun followCamera() {
        val current = VideoTrackSync.pointAt(points, elapsedMs)?.toAmapLatLng() ?: convertedPoints.first()
        val zoom = if (routeCameraInitialized) {
            aMap.cameraPosition?.zoom?.takeIf { it > 1f }
        } else {
            null
        } ?: chooseZoomForScreenWidth(
            current.latitude,
            width.toFloat().coerceAtLeast(1f),
            VIEWPORT_SCALE_METERS[viewportScaleIndex]
        ).toFloat()
        anchorCameraCenter()
        aMap.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(current)
                    .zoom(zoom)
                    .bearing(0f)
                    .tilt(0f)
                    .build()
            )
        )
        routeCameraInitialized = true
    }

    private fun anchorCameraCenter() {
        if (mapView.width > 2 && mapView.height > 2) {
            aMap.setPointToCenter(mapView.width / 2, mapView.height / 2)
        }
    }

    private fun turnCapturedBitmap(bitmap: Bitmap): Bitmap {
        val turns = VideoReplayRotation.normalize(contentRotationDegrees)
        if (turns == 0) return bitmap
        val matrix = Matrix().apply { postRotate(turns.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyContentRotation()
        if (oldw == 0 && oldh == 0 && w > 0 && h > 0) {
            // 播放页第一次进来时卡片高度是 0，高德按这个空尺寸建了绘制区。
            // 之后拉到真实大小也不会铺满，半边是白的；再改一次尺寸才会好。这里主动重建一次。
            refreshMapSurfaceAfterFirstLayout()
        }
        if (fitWholeRoute && w > 0 && h > 0 && (w != oldw || h != oldh)) {
            mapView.post { if (fitWholeRoute) updateCamera() }
        } else if (!fitWholeRoute && convertedPoints.isEmpty() && w > 0 && h > 0) {
            // 实时轨迹页刚露出来时还没有点，先按当前位置把镜头放好。
            mapView.post { if (convertedPoints.isEmpty()) primeCameraIfEmpty() }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val (targetWidth, targetHeight) = MapRotationAnchor.targetSize(width, height, contentRotationDegrees)
        if (MapRotationAnchor.readyToAnchor(mapView.width, mapView.height, targetWidth, targetHeight)) {
            mapView.pivotX = targetWidth / 2f
            mapView.pivotY = targetHeight / 2f
        }
    }

    private fun mapViewMatchesVisibleSize(): Boolean {
        val (expectWidth, expectHeight) = MapRotationAnchor.targetSize(width, height, contentRotationDegrees)
        return MapRotationAnchor.readyToAnchor(mapView.width, mapView.height, expectWidth, expectHeight)
    }

    private fun refreshMapSurfaceAfterFirstLayout() {
        if (firstSurfaceRefreshed || surfaceRefreshTries >= 8) return
        surfaceRefreshTries += 1
        mapView.post {
            if (!isAttachedToWindow || firstSurfaceRefreshed) return@post
            val (targetWidth, targetHeight) = MapRotationAnchor.targetSize(width, height, contentRotationDegrees)
            if (!MapRotationAnchor.readyToAnchor(mapView.width, mapView.height, targetWidth, targetHeight)) {
                refreshMapSurfaceAfterFirstLayout()
                return@post
            }
            firstSurfaceRefreshed = true
            // 宽高实际变过一次，高德才会按新尺寸重建绘制区。只改回原尺寸，中间那一帧必须先排完。
            val nudgedHeight = (targetHeight - 1).coerceAtLeast(1)
            surfaceNudgeListener?.let { mapView.removeOnLayoutChangeListener(it) }
            val listener = object : View.OnLayoutChangeListener {
                var sawShort = false
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    if (!sawShort && v.width == targetWidth && v.height == nudgedHeight) {
                        sawShort = true
                        v.layoutParams = LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
                        return
                    }
                    if (sawShort && v.width == targetWidth && v.height == targetHeight) {
                        v.removeOnLayoutChangeListener(this)
                        if (surfaceNudgeListener === this) surfaceNudgeListener = null
                        anchorMapRotation(targetWidth, targetHeight)
                    }
                }
            }
            surfaceNudgeListener = listener
            mapView.addOnLayoutChangeListener(listener)
            mapView.layoutParams = LayoutParams(targetWidth, nudgedHeight, Gravity.CENTER)
        }
    }

    private fun anchorMapRotation(targetWidth: Int, targetHeight: Int) {
        if (!MapRotationAnchor.readyToAnchor(mapView.width, mapView.height, targetWidth, targetHeight)) return
        mapView.pivotX = targetWidth / 2f
        mapView.pivotY = targetHeight / 2f
        mapView.rotation = contentRotationDegrees.toFloat()
        anchorCameraCenter()
        if (!userCameraLocked && convertedPoints.isNotEmpty()) updateCamera()
    }

    private fun moveWholeRouteCamera() {
        val builder = LatLngBounds.builder()
        convertedPoints.forEach { builder.include(it) }
        val room = minOf(mapView.width, mapView.height) / 4
        val padding = if (room <= 0) 0 else dp(18).coerceAtMost(room)
        anchorCameraCenter()
        runCatching {
            aMap.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    builder.build(),
                    mapView.width,
                    mapView.height,
                    padding
                )
            )
        }
    }

    private fun applyContentRotation() {
        if (width <= 0 || height <= 0) return
        val (viewWidth, viewHeight) = MapRotationAnchor.targetSize(width, height, contentRotationDegrees)
        val params = mapView.layoutParams
        if (params.width != viewWidth || params.height != viewHeight) {
            mapView.layoutParams = LayoutParams(viewWidth, viewHeight, Gravity.CENTER)
        }
        mapView.rotation = contentRotationDegrees.toFloat()
        rotationLayoutListener?.let { mapView.removeOnLayoutChangeListener(it) }
        if (MapRotationAnchor.readyToAnchor(mapView.width, mapView.height, viewWidth, viewHeight)) {
            rotationLayoutListener = null
            anchorMapRotation(viewWidth, viewHeight)
            return
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!MapRotationAnchor.readyToAnchor(mapView.width, mapView.height, viewWidth, viewHeight)) return@OnLayoutChangeListener
            rotationLayoutListener?.let { mapView.removeOnLayoutChangeListener(it) }
            rotationLayoutListener = null
            anchorMapRotation(viewWidth, viewHeight)
        }
        rotationLayoutListener = listener
        mapView.addOnLayoutChangeListener(listener)
    }

    private fun updateStats(extraLine: String? = null) {
        if (points.isEmpty()) {
            statsView.text = "高德官方地图"
            return
        }
        val currentDistance = TrackMetrics.distanceAt(points, elapsedMs)
        val mapLine = extraLine ?: mapStatusLine()
        statsView.text = listOfNotNull(
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
        button.textSize = 10f
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
        rememberWholeRoute = false
        setWholeRouteMode(false)
        clearCameraFollowHold()
        routeCameraInitialized = false
        updateCamera()
        updateButtons()
        notifyCameraPreference()
    }

    private fun notifyScaleChanged() {
        onScaleChanged?.invoke()
    }

    private var suppressCameraNotify = false

    private fun notifyCameraPreference() {
        if (suppressCameraNotify) return
        onCameraPreferenceChanged?.invoke()
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
        setWholeRouteMode(false)
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
    private var lastPlayedIndex = -1
    private var lastPlayedLatLng: LatLng? = null

    private fun routeWidthPx(scale: Float): Float = dp(ROUTE_BASE_WIDTH_DP).toFloat() * scale

    private fun renderPlayedRoute(playedIndex: Int, currentLatLng: LatLng) {
        if (playedIndex == lastPlayedIndex && currentLatLng == lastPlayedLatLng && playedPolylines.isNotEmpty()) {
            return
        }
        lastPlayedIndex = playedIndex
        lastPlayedLatLng = currentLatLng
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
        lastPlayedIndex = -1
        lastPlayedLatLng = null
    }

    private fun compactAmapName(name: String): String = AmapOfflineMapStore.compactName(name)

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
        /** 还没量出宽高时先用的缩放级别，大概城区尺度。 */
        private const val PRIME_ZOOM = 16f
        /** 让高德把最后一次缩放/惯性动画走完，再恢复自动居中。 */
        private const val CAMERA_RESUME_AFTER_GESTURE_MS = 150L
        /** 实时轨迹一次最多追补这么多个点；再多说明不是边走边画，整条重画更稳。 */
        private const val ROUTE_APPEND_MAX_POINTS = 60
        /** 实时轨迹隔这么多点查一次离线包省份，免得每秒都算一遍平均经纬度。 */
        private const val OFFLINE_REGION_PROBE_STEP = 60
        private const val CURRENT_DOT_RADIUS_DP = 2f
        private const val CURRENT_PULSE_MIN_DP = 8f / 3f
        private const val CURRENT_PULSE_MAX_DP = 16f / 3f
        private const val ROUTE_BASE_WIDTH_DP = 5
        /** 绿色轨迹。透明度由回放页的滑块决定，默认不透明。 */
        private val ROUTE_COLOR = Color.rgb(79, 214, 151)
        private const val WEB_MERCATOR_EQUATOR_METERS_PER_PIXEL = 156543.03392
    }
}
