package com.thomas.carspeed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * 循环录像空间用量环。
 *
 * 只用 Canvas 画，不引入图表库：这里需要的就是一圈进度加中心百分比，
 * 用第三方图表反而更难和现有 Material 风格对齐。
 */
class StorageUsageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val arcRect = RectF()

    private var percent: Int = 0

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.storage_usage_track)
        progressPaint.color = ContextCompat.getColor(context, R.color.storage_usage_progress)
        textPaint.color = ContextCompat.getColor(context, R.color.storage_usage_text)
        captionPaint.color = ContextCompat.getColor(context, R.color.storage_usage_caption)
    }

    fun setUsage(percent: Int) {
        this.percent = percent.coerceIn(0, 100)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (132 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(desired, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val strokeWidth = 12f * density
        val inset = strokeWidth / 2f + 2f * density
        val size = min(width, height)
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        arcRect.set(left, top, left + size - inset * 2f, top + size - inset * 2f)
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        canvas.drawArc(arcRect, -90f, 360f, false, trackPaint)
        canvas.drawArc(arcRect, -90f, 360f * percent / 100f, false, progressPaint)

        textPaint.textSize = size * 0.24f
        val centerX = width / 2f
        val centerY = height / 2f - size * 0.02f
        canvas.drawText("$percent%", centerX, centerY, textPaint)
        captionPaint.textSize = size * 0.095f
        canvas.drawText(context.getString(R.string.storage_usage_chart_caption), centerX, centerY + size * 0.19f, captionPaint)
    }
}
