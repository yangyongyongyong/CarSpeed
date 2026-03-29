package com.thomas.carspeed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SpeedTrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class SpeedPoint(val timestampMs: Long, val speedKmh: Float)

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99CFE3FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66CFE3FF")
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66B3FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 100/200 km/h 对比辅助线（细、半透明黑）
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55000000")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAF2FF")
        textSize = 20f
    }

    private var points: List<SpeedPoint> = emptyList()
    private var yAxisMaxKmh: Float = 300f
    private var windowMs: Long = 60_000L


    fun setTransparentMode(useDarkText: Boolean) {
        if (useDarkText) {
            axisPaint.color = Color.parseColor("#55000000")
            gridPaint.color = Color.parseColor("#33000000")
            linePaint.color = Color.parseColor("#0E5CC7")
            labelPaint.color = Color.parseColor("#1A1A1A")
            guidePaint.color = Color.parseColor("#33000000")
        } else {
            axisPaint.color = Color.parseColor("#99CFE3FF")
            gridPaint.color = Color.parseColor("#66CFE3FF")
            linePaint.color = Color.parseColor("#66B3FF")
            labelPaint.color = Color.parseColor("#EAF2FF")
            guidePaint.color = Color.parseColor("#55000000")
        }
        invalidate()
    }

    fun updateData(
        speedPoints: List<SpeedPoint>,
        yMaxKmh: Float,
        recentWindowMs: Long = 60_000L
    ) {
        points = speedPoints
        yAxisMaxKmh = yMaxKmh.coerceAtLeast(30f)
        windowMs = recentWindowMs.coerceAtLeast(10_000L)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 左侧留空间显示Y轴刻度
        val left = 56f
        val top = 8f
        val right = w - 8f
        val bottom = h - 30f

        canvas.drawRect(left, top, right, bottom, axisPaint)

        // Y轴刻度文字：每50km/h标注
        var tick = 50
        while (tick <= yAxisMaxKmh.toInt()) {
            val ratio = (tick / yAxisMaxKmh).coerceIn(0f, 1f)
            val y = bottom - ratio * (bottom - top)
            canvas.drawText("${tick}", 6f, y + 6f, labelPaint)
            tick += 50
        }

        // 仅在 100 / 200 km/h 画对比线
        for (guide in listOf(100, 200)) {
            if (guide <= yAxisMaxKmh.toInt()) {
                val ratio = (guide / yAxisMaxKmh).coerceIn(0f, 1f)
                val y = bottom - ratio * (bottom - top)
                canvas.drawLine(left, y, right, y, guidePaint)
            }
        }

        // x轴时间标注：-10s -30s -60s
        canvas.drawText("-60s", left, h - 6f, labelPaint)
        canvas.drawText("-30s", left + (right - left) * 0.5f - 20f, h - 6f, labelPaint)
        canvas.drawText("-10s", right - 56f, h - 6f, labelPaint)

        if (points.size < 2) return

        val now = points.last().timestampMs
        val start = now - windowMs

        val filtered = points.filter { it.timestampMs >= start }
        if (filtered.size < 2) return

        var prevX = left
        var prevY = bottom - ((filtered[0].speedKmh.coerceIn(0f, yAxisMaxKmh)) / yAxisMaxKmh) * (bottom - top)

        for (i in 1 until filtered.size) {
            val p = filtered[i]
            val ratioX = ((p.timestampMs - start).toFloat() / windowMs).coerceIn(0f, 1f)
            val x = left + ratioX * (right - left)
            val y = bottom - ((p.speedKmh.coerceIn(0f, yAxisMaxKmh)) / yAxisMaxKmh) * (bottom - top)
            canvas.drawLine(prevX, prevY, x, y, linePaint)
            prevX = x
            prevY = y
        }
    }
}
