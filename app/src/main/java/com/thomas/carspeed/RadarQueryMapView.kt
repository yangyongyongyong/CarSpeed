package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
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
    private val routeIds = mutableMapOf<Polyline, Int>()
    private val radarMarkers = mutableListOf<Marker>()
    private val radarIcons = mutableMapOf<Int, RadarIcon>()
    private var endpointMarkers = mutableListOf<Marker>()
    private var myLocationMarker: Marker? = null
    private var myLocationIcon: BitmapDescriptor? = null
    private var followMe = true
    private var centeredOnMe = false
    private var pendingMe: LatLng? = null

    var onRouteSelected: ((Int) -> Unit)? = null
    var onRadarClick: ((VideoTrackRadar) -> Unit)? = null

    init {
        mapView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        mapView.onCreate(null as Bundle?)
        addView(mapView)
        aMap = mapView.map
        aMap.showBuildings(true)
        aMap.uiSettings.apply {
            isZoomControlsEnabled = false
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
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
            val id = routeIds[line] ?: return@setOnPolylineClickListener
            onRouteSelected?.invoke(id)
        }
        aMap.setOnMarkerClickListener { marker ->
            val radar = marker.`object` as? VideoTrackRadar ?: return@setOnMarkerClickListener false
            onRadarClick?.invoke(radar)
            true
        }
    }

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
        if (!followMe || routeLines.isNotEmpty()) return
        val update = if (centeredOnMe) {
            CameraUpdateFactory.changeLatLng(position)
        } else {
            centeredOnMe = true
            CameraUpdateFactory.newLatLngZoom(position, 16f)
        }
        runCatching { aMap.moveCamera(update) }
    }

    fun show(routes: List<PlannedRoute>, selectedId: Int, start: LatLng, end: LatLng) {
        followMe = false
        myLocationMarker?.remove()
        myLocationMarker = null
        clearOverlays()
        val selected = routes.firstOrNull { it.id == selectedId } ?: routes.firstOrNull()
        routes.filter { it.id != selected?.id }.forEach { route ->
            addRoute(route, selected = false)
        }
        selected?.let { addRoute(it, selected = true) }
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

    private fun addRoute(route: PlannedRoute, selected: Boolean) {
        if (route.points.size < 2) return
        val line = aMap.addPolyline(
            PolylineOptions()
                .addAll(route.points)
                .width(if (selected) dp(5).toFloat() else dp(3).toFloat())
                .color(if (selected) ROUTE_COLOR else ALT_ROUTE_COLOR)
                .zIndex(if (selected) 20f else 10f)
        ) ?: return
        routeLines += line
        routeIds[line] = route.id
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

    private fun clearOverlays() {
        routeLines.forEach { it.remove() }
        routeLines.clear()
        routeIds.clear()
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mapView.onResume()
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
