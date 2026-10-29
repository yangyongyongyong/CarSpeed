package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 查询页的地图。选中的路线用和行车轨迹一样的绿色，测速雷达用同一个图标和限速牌。
 */
internal class RadarQueryMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val mapView = TextureMapView(context)
    private val aMap: AMap
    private val routeLines = mutableListOf<Polyline>()
    /** 高德的 Polyline 不能带自定义数据，路线 id 只能存在外面。 */
    private val routeLineIds = mutableMapOf<Polyline, Int>()
    private val radarMarkers = mutableListOf<Marker>()
    private val radarIcons = mutableMapOf<Int, RadarIcon>()
    private var endpointMarkers = mutableListOf<Marker>()
    private val routeMarkers = mutableMapOf<Marker, Int>()
    private val routeLabelIcons = mutableMapOf<Int, BitmapDescriptor>()
    private var myLocationMarker: Marker? = null
    private var myLocationIcon: BitmapDescriptor? = null
    private var followMe = true
    private var centeredOnMe = false
    private var pendingMe: LatLng? = null
    private var lastRoutes: List<PlannedRoute> = emptyList()
    private var lastStart: LatLng? = null
    private var lastEnd: LatLng? = null
    private lateinit var locateButton: LocationLocateButton

    var onRouteSelected: ((Int) -> Unit)? = null
    var onRadarClick: ((VideoTrackRadar) -> Unit)? = null
    /** 在地图上点一下（或长按）选出的坐标。用来不用打字就能指定起点终点。 */
    var onMapPicked: ((LatLng) -> Unit)? = null
    /** 点地图上现成的地点（小区名、楼名、店名）。 */
    var onPoiPicked: ((PickedPoi) -> Unit)? = null

    init {
        mapView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mapView.onCreate(null as Bundle?)
        addView(mapView)
        // 定位按钮要压在地图上，所以先建控件再加进这个 FrameLayout。
        locateButton = LocationLocateButton(context).apply {
            setOnClickListener { recenterOnMe() }
            visibility = View.GONE
        }
        addView(
            locateButton,
            LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(14)
                bottomMargin = dp(70)
            }
        )
        aMap = mapView.map
        aMap.showBuildings(true)
        // 默认是关的：不开的话点小区名、楼名根本不会回调，点了像没反应。
        aMap.setTouchPoiEnable(true)
        aMap.uiSettings.apply {
            isZoomControlsEnabled = false
            // 上北下南：双指旋转、俯仰都会把地图转歪，开车时看不出东南西北。
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            setLogoBottomMargin(dp(132))
        }
        aMap.setOnMapTouchListener { followMe = false }
        aMap.setOnMapLoadedListener {
            if (!followMe || routeLines.isNotEmpty()) return@setOnMapLoadedListener
            val position = pendingMe ?: return@setOnMapLoadedListener
            centeredOnMe = false
            showMyLocation(position)
        }
        aMap.setOnPolylineClickListener { line ->
            val id = routeLineIds[line] ?: return@setOnPolylineClickListener
            onRouteSelected?.invoke(id)
        }
        aMap.setOnMarkerClickListener { marker ->
            routeMarkers[marker]?.let { id ->
                onRouteSelected?.invoke(id)
                return@setOnMarkerClickListener true
            }
            val radar = marker.`object` as? VideoTrackRadar ?: return@setOnMarkerClickListener false
            onRadarClick?.invoke(radar)
            true
        }
        // 长按选点。用长按而不是单击：单击容易误触，而在地图上随手拖一下就选错了起点。
        aMap.setOnMapLongClickListener { position ->
            onMapPicked?.invoke(position)
        }
        // 点地图上现成的地点（小区、楼、店名）时，高德给的是 POI 而不是普通坐标。
        // 这是「单击选点」真正该走的路：用户点的往往就是这些名字，
        // 而且 POI 自带名称，省得再反查一次。
        aMap.setOnPOIClickListener { poi ->
            onPoiPicked?.invoke(
                PickedPoi(
                    name = poi.name.orEmpty(),
                    latitude = poi.coordinate.latitude,
                    longitude = poi.coordinate.longitude
                )
            )
        }
    }

    /** 在地图上点中的现成地点。 */
    data class PickedPoi(val name: String, val latitude: Double, val longitude: Double)

    /**
     * 把选中的坐标画成一个可移动的图钉，同时在底部留出确认区域的位置。
     * 之前没有这个，用户只能靠打字搜地址，搜不到的地方就没法选。
     *
     * 注意这里**不移动地图**：用户点/长按的是自己看得见的地方，
     * 镜头一挪反而会把刚才选的点和周边参照一起甩走。
     */
    fun showPicked(position: LatLng?) {
        pickedMarker?.remove()
        pickedMarker = null
        val target = position ?: return
        pickedMarker = aMap.addMarker(
            MarkerOptions()
                .position(target)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                .anchor(0.5f, 1f)
                .zIndex(70f)
        )
    }

    private var pickedMarker: Marker? = null

    /** 还没查出路线时，把地图停在当前位置，而不是高德默认的北京。 */
    fun showMyLocation(position: LatLng) {
        pendingMe = position
        val marker = myLocationMarker
        if (marker == null) {
            val created = aMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .icon(locationIcon())
                    .anchor(0.5f, 0.5f)
                    .zIndex(40f)
            ) ?: return
            created.isInfoWindowEnable = false
            myLocationMarker = created
        } else {
            marker.position = position
        }
        locateButton.visibility = View.VISIBLE
        if (!followMe || routeLines.isNotEmpty()) return
        val update = if (centeredOnMe) {
            CameraUpdateFactory.changeLatLng(position)
        } else {
            centeredOnMe = true
            CameraUpdateFactory.newLatLngZoom(position, 16f)
        }
        runCatching { aMap.moveCamera(update) }
    }

    /**
     * 定位按钮：把当前位置放回地图正中，并回到默认比例尺（16 级）。
     * 用户把地图拖远、缩得很小之后，靠一个按键就能回到自己身上。
     */
    private fun recenterOnMe() {
        val me = pendingMe ?: return
        followMe = true
        centeredOnMe = true
        runCatching { aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(me, 16f), 300, null) }
    }

    /**
     * 换路线只重画路线本身，不动地图镜头，也不重建起终点和雷达。
     * 之前每次换都整块重画，镜头被重新 fit，用户刚看好的位置就丢了。
     */
    fun selectRoute(selectedId: Int) {
        clearRoutes()
        drawRoutes(lastRoutes, selectedId)
    }

    fun show(routes: List<PlannedRoute>, selectedId: Int, start: LatLng, end: LatLng) {
        followMe = false
        locateButton.visibility = View.VISIBLE
        myLocationMarker?.remove()
        myLocationMarker = null
        clearOverlays()
        lastRoutes = routes
        lastStart = start
        lastEnd = end
        val selected = routes.firstOrNull { it.id == selectedId } ?: routes.firstOrNull()
        drawRoutes(routes, selected?.id ?: selectedId)
        addEndpoint(start, BitmapDescriptorFactory.HUE_AZURE)
        addEndpoint(end, BitmapDescriptorFactory.HUE_RED)
        selected?.radars.orEmpty().forEach { radar ->
            val icon = radarIcon(radar.speedLimit)
            val marker = aMap.addMarker(
                MarkerOptions()
                    .position(LatLng(radar.latitude, radar.longitude))
                    .icon(icon.bitmap)
                    .anchor(0.5f, icon.anchorY)
                    .zIndex(58f)
            ) ?: return@forEach
            marker.`object` = radar
            marker.isInfoWindowEnable = false
            radarMarkers += marker
        }
        fit(selected?.points.orEmpty().ifEmpty { routes.firstOrNull()?.points.orEmpty() }, start, end)
    }

    /**
     * 像高德那样画：当前路线是压在最上面的粗绿线，备选路线是细的浅色线。
     * 每条线（以及它上面的路线编号）都绑着自己的 route id，点哪条就切到哪条。
     */
    private fun drawRoutes(routes: List<PlannedRoute>, selectedId: Int) {
        // 先画备选，后画当前：后画的在上层，当前路线才不会被备选压在下面。
        routes.filter { it.id != selectedId }.forEach { addRoute(it, selected = false) }
        routes.firstOrNull { it.id == selectedId }?.let { addRoute(it, selected = true) }
    }

    private fun addRoute(route: PlannedRoute, selected: Boolean) {
        if (route.points.size < 2) return
        val line = aMap.addPolyline(
            PolylineOptions()
                .addAll(route.points)
                .width(if (selected) dp(7).toFloat() else dp(5).toFloat())
                .color(if (selected) ROUTE_COLOR else ALT_ROUTE_COLOR)
                .zIndex(if (selected) 26f else 10f)
        ) ?: return
        routeLines += line
        routeLineIds[line] = route.id
        // 线上再压一个编号，点编号和点线一个效果，细线不好点中时还有个抓手。
        val label = routeLabelPosition(route) ?: return
        val marker = aMap.addMarker(
            MarkerOptions()
                .position(label)
                .icon(routeLabelIcon(selected))
                .anchor(0.5f, 0.5f)
                .zIndex(if (selected) 27f else 11f)
        ) ?: return
        marker.isInfoWindowEnable = false
        routeMarkers[marker] = route.id
    }

    /** 编号放在路线中段，避开起点终点那一端。 */
    private fun routeLabelPosition(route: PlannedRoute): LatLng? {
        val points = route.points
        if (points.isEmpty()) return null
        return points[points.size / 2]
    }

    private fun routeLabelIcon(selected: Boolean): BitmapDescriptor {
        val key = if (selected) 1 else 0
        routeLabelIcons[key]?.let { return it }
        val size = dp(20)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f
        canvas.drawCircle(
            radius,
            radius,
            radius - dp(1),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (selected) ROUTE_COLOR else Color.rgb(188, 200, 216)
            }
        )
        val label = if (selected) "当前" else "备选"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (selected) Color.WHITE else Color.rgb(21, 32, 51)
            textSize = dp(9).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val baseline = radius - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        canvas.drawText(label, radius, baseline, textPaint)
        val icon = BitmapDescriptorFactory.fromBitmap(bitmap)
        routeLabelIcons[key] = icon
        return icon
    }

    private fun addEndpoint(position: LatLng, hue: Float) {
        val marker = aMap.addMarker(
            MarkerOptions()
                .position(position)
                .icon(BitmapDescriptorFactory.defaultMarker(hue))
                .anchor(0.5f, 1f)
                .zIndex(50f)
        ) ?: return
        marker.isInfoWindowEnable = false
        endpointMarkers += marker
    }

    private fun fit(points: List<LatLng>, start: LatLng, end: LatLng) {
        val bounds = LatLngBounds.builder().include(start).include(end)
        points.forEach { bounds.include(it) }
        val update = CameraUpdateFactory.newLatLngBoundsRect(
            bounds.build(),
            dp(36),
            dp(36),
            dp(36),
            dp(150)
        )
        runCatching { aMap.moveCamera(update) }
    }

    private fun clearRoutes() {
        routeLines.forEach { it.remove() }
        routeLines.clear()
        routeLineIds.clear()
        routeMarkers.keys.forEach { it.remove() }
        routeMarkers.clear()
    }

    private fun clearOverlays() {
        clearRoutes()
        radarMarkers.forEach { it.remove() }
        radarMarkers.clear()
        endpointMarkers.forEach { it.remove() }
        endpointMarkers.clear()
    }

    private fun radarIcon(speedLimit: Int?): RadarIcon {
        val limit = speedLimit?.takeIf { it > 0 }
        val key = limit ?: NO_LIMIT
        radarIcons[key]?.let { return it }
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
            canvas.drawRoundRect(
                pill,
                dp(3).toFloat(),
                dp(3).toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(21, 32, 51) }
            )
            val baseline = pill.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
            canvas.drawText(label, width / 2f, baseline, textPaint)
        }
        val icon = RadarIcon(BitmapDescriptorFactory.fromBitmap(bitmap), (iconSize / 2f) / height)
        radarIcons[key] = icon
        return icon
    }

    private var hostResumed = true

    fun onHostResume() {
        if (hostResumed) return
        hostResumed = true
        if (isAttachedToWindow) mapView.onResume()
    }

    fun onHostPause() {
        if (!hostResumed) return
        hostResumed = false
        if (isAttachedToWindow) mapView.onPause()
    }

    fun onHostLowMemory() {
        mapView.onLowMemory()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hostResumed) mapView.onResume()
    }

    override fun onDetachedFromWindow() {
        mapView.onPause()
        mapView.onDestroy()
        super.onDetachedFromWindow()
    }

    private fun locationIcon(): BitmapDescriptor {
        myLocationIcon?.let { return it }
        val size = dp(16)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 107, 255) }
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius - dp(1), fill)
        canvas.drawCircle(
            radius,
            radius,
            radius - dp(2),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dp(2).toFloat()
            }
        )
        val icon = BitmapDescriptorFactory.fromBitmap(bitmap)
        myLocationIcon = icon
        return icon
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private data class RadarIcon(val bitmap: BitmapDescriptor, val anchorY: Float)

    private companion object {
        const val NO_LIMIT = Int.MIN_VALUE
        val ROUTE_COLOR = Color.rgb(79, 214, 151)
        val ALT_ROUTE_COLOR = Color.argb(210, 156, 170, 190)
    }
}

/**
 * 地图上的定位按钮：白底圆角，中间一个「回正中」的准星。
 * 图标用代码画，省得为一个小三角再加一份 drawable。
 */
internal class LocationLocateButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : android.view.View(context, attrs) {
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 21, 32, 51)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 107, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(46, 107, 255) }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.radar_query_map_locate)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy) - dp(1f)
        canvas.drawCircle(cx, cy, radius, background)
        canvas.drawCircle(cx, cy, radius, stroke)
        val r = radius * 0.42f
        canvas.drawCircle(cx, cy, r, cross)
        canvas.drawCircle(cx, cy, dp(2.2f), fill)
        // 四向刻度：不像方向箭头那样会被误当成「指北」，表示的是「回到自身」。
        val tickOuter = radius * 0.92f
        val tickInner = radius * 0.68f
        listOf(0f, 90f, 180f, 270f).forEach { angle ->
            val rad = Math.toRadians(angle.toDouble())
            canvas.drawLine(
                cx + (kotlin.math.cos(rad) * tickInner).toFloat(),
                cy + (kotlin.math.sin(rad) * tickInner).toFloat(),
                cx + (kotlin.math.cos(rad) * tickOuter).toFloat(),
                cy + (kotlin.math.sin(rad) * tickOuter).toFloat(),
                cross
            )
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
