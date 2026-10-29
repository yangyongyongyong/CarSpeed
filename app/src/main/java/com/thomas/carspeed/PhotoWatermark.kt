package com.thomas.carspeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

object PhotoWatermark {
    fun apply(context: Context, file: File, mode: VideoMode, timeMs: Long? = null) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        try {
            // 照片和视频用同一组字幕开关，时间也照开关走。
            val info = DashcamOverlayText.infoBar(context, mode, timeMs = timeMs)
            // 字幕开关全关时照片也不加白底栏，保持原图高度。
            val barHeight = if (!info.isEmpty() && DashcamSettings.subtitleFields(context).hasAnyField()) {
                ExportSubtitleStyle.barHeightPx(bitmap.width, bitmap.height)
            } else {
                0
            }
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height + barHeight, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                if (barHeight > 0) {
                    DashcamInfoBarPainter.draw(canvas, info, bitmap.height.toFloat(), bitmap.width.toFloat(), barHeight.toFloat())
                }
                FileOutputStream(file).use { out ->
                    check(output.compress(Bitmap.CompressFormat.JPEG, 92, out))
                }
            } finally {
                output.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
