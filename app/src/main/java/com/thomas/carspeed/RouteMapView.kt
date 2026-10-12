package com.thomas.carspeed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RouteMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var points: List<VideoTrackPoint> = emptyList()
    private var elapsedMs: Long = 0L

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 27, 34)
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255)
        strokeWidth = 1f
    }
    private val fullRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val playedRoutePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 214, 151)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val markerAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 214, 151)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val path = Path()

    fun setRoute(points: List<VideoTrackPoint>, elapsedMs: Long) {
        this.points = points
        this.elapsedMs = elapsedMs.coerceAtLeast(0L)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 18f * resources.displayMetrics.density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)
        drawGrid(canvas)
        if (points.size < 2) {
            drawEmpty(canvas)
            return
        }
        val projector = RouteProjector(points, width.toFloat(), height.toFloat(), 24f * resources.displayMetrics.density)
        fullRoutePaint.strokeWidth = 4f * resources.displayMetrics.density
        playedRoutePaint.strokeWidth = 6f * resources.displayMetrics.density
        drawPath(canvas, points, projector, fullRoutePaint)
        drawPlayedPath(canvas, projector)
        drawEndpoints(canvas, projector)
        drawCurrentMarker(canvas, projector)
    }

    private fun drawGrid(canvas: Canvas) {
        val stepX = width / 4f
        val stepY = height / 4f
        for (i in 1..3) {
            canvas.drawLine(stepX * i, 0f, stepX * i, height.toFloat(), gridPaint)
            canvas.drawLine(0f, stepY * i, width.toFloat(), stepY * i, gridPaint)
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        canvas.drawText("暂无轨迹数据", 24f * resources.displayMetrics.density, height / 2f, textPaint)
    }

    private fun drawPath(
        canvas: Canvas,
        route: List<VideoTrackPoint>,
        projector: RouteProjector,
        paint: Paint
    ) {
        path.reset()
        route.forEachIndexed { index, point ->
            val p = projector.project(point)
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPlayedPath(canvas: Canvas, projector: RouteProjector) {
        val current = VideoTrackSync.pointAt(points, elapsedMs) ?: return
        val index = VideoTrackSync.lastPlayedIndex(points, elapsedMs)
        val played = points.take(index + 1) + current
        drawPath(canvas, played, projector, playedRoutePaint)
    }

    private fun drawEndpoints(canvas: Canvas, projector: RouteProjector) {
        val start = projector.project(points.first())
        val end = projector.project(points.last())
        val radius = 5f * resources.displayMetrics.density
        canvas.drawCircle(start.x, start.y, radius, markerAccentPaint)
        canvas.drawCircle(end.x, end.y, radius, markerPaint)
    }

    private fun drawCurrentMarker(canvas: Canvas, projector: RouteProjector) {
        val current = VideoTrackSync.pointAt(points, elapsedMs) ?: return
        val p = projector.project(current)
        val density = resources.displayMetrics.density
        canvas.drawCircle(p.x, p.y, 11f * density, markerPaint)
        canvas.drawCircle(p.x, p.y, 7f * density, markerAccentPaint)
        current.bearingDegrees?.let { bearing ->
            val rad = Math.toRadians(bearing.toDouble())
            val length = 22f * density
            val dx = sin(rad).toFloat() * length
            val dy = -cos(rad).toFloat() * length
            canvas.drawLine(p.x, p.y, p.x + dx, p.y + dy, markerPaint)
        }
        canvas.drawText(
            String.format(Locale.US, "%.1f km/h", current.speedKmh),
            18f * density,
            26f * density,
            textPaint
        )
    }

    private class RouteProjector(
        points: List<VideoTrackPoint>,
        width: Float,
        height: Float,
        private val padding: Float
    ) {
        private val minLat = points.minOf { it.latitude }
        private val maxLat = points.maxOf { it.latitude }
        private val minLon = points.minOf { it.longitude }
        private val maxLon = points.maxOf { it.longitude }
        private val drawWidth = max(1f, width - padding * 2)
        private val drawHeight = max(1f, height - padding * 2)
        private val latSpan = max(0.000001, maxLat - minLat)
        private val lonSpan = max(0.000001, maxLon - minLon)

        fun project(point: VideoTrackPoint): PointF {
            val xRatio = ((point.longitude - minLon) / lonSpan).toFloat()
            val yRatio = ((maxLat - point.latitude) / latSpan).toFloat()
            return PointF(
                padding + min(1f, max(0f, xRatio)) * drawWidth,
                padding + min(1f, max(0f, yRatio)) * drawHeight
            )
        }
    }
}
