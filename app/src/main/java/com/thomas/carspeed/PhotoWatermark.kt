package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.io.FileOutputStream

object PhotoWatermark {
    fun apply(context: Context, file: File, mode: VideoMode) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val lines = DashcamOverlayText.lines(context, mode, forceTime = true)
        draw(context, canvas, lines)
        FileOutputStream(file).use { out ->
            mutable.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        if (mutable != bitmap) mutable.recycle()
        bitmap.recycle()
    }

    private fun draw(context: Context, canvas: Canvas, lines: List<String>) {
        if (lines.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(2f * density, 0.6f * density, 0.6f * density, Color.BLACK)
        }
        val lineHeight = (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) + 2f * density
        val padding = 8f * density
        val width = lines.maxOf { textPaint.measureText(it) } + padding * 2
        val height = lineHeight * lines.size + padding * 2
        val left = padding
        val top = canvas.height - height - padding
        val background = RectF(left, top, left + width, top + height)
        val corner = 18f * density
        // 与预览字幕 overlay_chip_dark_bg 一致：约 10% 的底，不是黑底。
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x1A, 0x13, 0x23, 0x3A) }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(0x33, 0x5D, 0x87, 0xC4)
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        canvas.drawRoundRect(background, corner, corner, bgPaint)
        canvas.drawRoundRect(background, corner, corner, strokePaint)
        val ascent = textPaint.fontMetrics.ascent
        lines.forEachIndexed { index, text ->
            canvas.drawText(text, left + padding, top + padding - ascent + lineHeight * index, textPaint)
        }
    }
}
