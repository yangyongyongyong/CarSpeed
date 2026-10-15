package com.thomas.carspeed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class PlateOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 214, 10)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(165, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private var frame: PlateOverlayFrame? = null

    fun setFrame(next: PlateOverlayFrame?) {
        val visibleFrame = next?.takeIf { it.imageWidth > 0 && it.imageHeight > 0 && it.plates.isNotEmpty() }
        frame = visibleFrame
        visibility = if (visibleFrame == null) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame ?: return
        val sourceWidth = if (current.rotationDegrees == 90 || current.rotationDegrees == 270) {
            current.imageHeight.toFloat()
        } else {
            current.imageWidth.toFloat()
        }
        val sourceHeight = if (current.rotationDegrees == 90 || current.rotationDegrees == 270) {
            current.imageWidth.toFloat()
        } else {
            current.imageHeight.toFloat()
        }
        if (sourceWidth <= 0f || sourceHeight <= 0f || width <= 0 || height <= 0) return
        val scale = minOf(width / sourceWidth, height / sourceHeight)
        val dx = (width - sourceWidth * scale) / 2f
        val dy = (height - sourceHeight * scale) / 2f
        current.plates.forEach { plate ->
            rect.set(
                dx + plate.x1 * scale,
                dy + plate.y1 * scale,
                dx + plate.x2 * scale,
                dy + plate.y2 * scale
            )
            if (rect.width() <= 0f || rect.height() <= 0f) return@forEach
            rect.inset(-dp(6f), -dp(4f))
            rect.set(
                rect.left.coerceAtLeast(0f),
                rect.top.coerceAtLeast(0f),
                rect.right.coerceAtMost(width.toFloat()),
                rect.bottom.coerceAtMost(height.toFloat())
            )
            canvas.drawRoundRect(rect, dp(5f), dp(5f), boxPaint)
            drawLabel(canvas, plate.code, rect.left, rect.top)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, left: Float, top: Float) {
        val paddingX = dp(6f)
        val paddingY = dp(4f)
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.descent() - textPaint.ascent()
        val labelLeft = left.coerceIn(0f, (width - textWidth - paddingX * 2).coerceAtLeast(0f))
        val labelBottom = (top - dp(4f)).takeIf { it - textHeight - paddingY * 2 >= 0f }
            ?: (top + textHeight + paddingY * 2 + dp(4f))
        val labelTop = labelBottom - textHeight - paddingY * 2
        val labelRect = RectF(labelLeft, labelTop, labelLeft + textWidth + paddingX * 2, labelBottom)
        canvas.drawRoundRect(labelRect, dp(4f), dp(4f), textBgPaint)
        canvas.drawText(text, labelLeft + paddingX, labelBottom - paddingY - textPaint.descent(), textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
