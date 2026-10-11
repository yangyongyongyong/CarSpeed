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
            textSize = 18f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(3f * density, 1f * density, 1f * density, Color.BLACK)
        }
        val lineHeight = 24f * density
        val padding = 14f * density
        val width = lines.maxOf { textPaint.measureText(it) } + padding * 2
        val height = lineHeight * lines.size + padding * 2
        val left = padding
        val top = canvas.height - height - padding
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 12f * density, 12f * density, bgPaint)
        lines.forEachIndexed { index, text ->
            canvas.drawText(text, left + padding, top + padding + lineHeight * (index + 1) - 6f * density, textPaint)
        }
    }
}
