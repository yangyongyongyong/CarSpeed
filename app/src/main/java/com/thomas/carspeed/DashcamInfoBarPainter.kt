package com.thomas.carspeed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint

internal object DashcamInfoBarPainter {
    private val background = Paint().apply { color = Color.WHITE }
    private val title = paint(Color.BLACK, true)
    private val label = paint(Color.DKGRAY, false)
    private val parameters = paint(Color.BLACK, true)
    private val caption = paint(Color.DKGRAY, false)

    /**
     * [height] 是这条白栏实际占的高度（已按最多三行明细预留）。
     * [textScale] 是字号缩放基准：一行明细时白栏的高度。
     * 两者分开，加高白栏放第三行时不会把字号一起放大。
     */
    @Synchronized
    fun draw(
        canvas: Canvas,
        info: DashcamInfoBarText,
        top: Float,
        width: Float,
        height: Float,
        textScale: Float = height * ExportSubtitleStyle.ONE_LINE_BAR_DP /
            ExportSubtitleStyle.barHeightDp().toFloat()
    ) {
        if (width <= 0f || height <= 0f) return
        val scale = textScale.coerceAtLeast(1f) / ExportSubtitleStyle.ONE_LINE_BAR_DP
        val padding = ExportSubtitleStyle.INSET_HORIZONTAL_DP * scale
        title.textSize = ExportSubtitleStyle.TITLE_SP * scale
        label.textSize = ExportSubtitleStyle.LABEL_SP * scale
        parameters.textSize = ExportSubtitleStyle.PARAMETERS_SP * scale
        caption.textSize = ExportSubtitleStyle.CAPTION_SP * scale
        canvas.save()
        canvas.clipRect(0f, top, width, top + height)
        canvas.drawRect(0f, top, width, top + height, background)
        val center = top + height / 2f
        val showIdentity = info.title.isNotBlank() || info.label.isNotBlank()
        var identityWidth = 0f
        if (showIdentity) {
            val identityGap = ExportSubtitleStyle.LABEL_GAP_DP * scale
            if (info.title.isNotBlank() && info.label.isNotBlank()) {
                val titleHeight = textHeight(title)
                val labelHeight = textHeight(label)
                val identityTop = center - (titleHeight + identityGap + labelHeight) / 2f
                canvas.drawText(info.title, padding, identityTop - title.fontMetrics.ascent, title)
                canvas.drawText(info.label, padding, identityTop + titleHeight + identityGap - label.fontMetrics.ascent, label)
            } else if (info.title.isNotBlank()) {
                val titleHeight = textHeight(title)
                val identityTop = center - titleHeight / 2f
                canvas.drawText(info.title, padding, identityTop - title.fontMetrics.ascent, title)
            } else {
                val labelHeight = textHeight(label)
                val identityTop = center - labelHeight / 2f
                canvas.drawText(info.label, padding, identityTop - label.fontMetrics.ascent, label)
            }
            identityWidth = maxOf(
                if (info.title.isBlank()) 0f else title.measureText(info.title),
                if (info.label.isBlank()) 0f else label.measureText(info.label)
            )
        }

        val right = width - padding
        val identitySpace = if (showIdentity) identityWidth + ExportSubtitleStyle.IDENTITY_GAP_DP * scale else 0f
        val available = (right - padding - identitySpace).coerceAtLeast(1f)
        // 明细两段都可能换行，先把行切出来再算总高度，才能整体垂直居中。
        val parameterLines = SubtitleLineWrap.wrap(info.parameters, available) { parameters.measureText(it) }
        val captionLines = SubtitleLineWrap.wrap(info.caption, available) { caption.measureText(it) }
        val lineHeight = textHeight(parameters)
        val captionHeight = textHeight(caption)
        val firstHeight = lineHeight * parameterLines.size
        val secondHeight = captionHeight * captionLines.size
        val gap = if (firstHeight > 0f && secondHeight > 0f) ExportSubtitleStyle.DETAILS_GAP_DP * scale else 0f
        val detailsTop = center - (firstHeight + gap + secondHeight) / 2f
        parameters.textAlign = Paint.Align.RIGHT
        caption.textAlign = Paint.Align.RIGHT
        parameterLines.forEachIndexed { index, line ->
            canvas.drawText(line, width - padding,
                detailsTop + lineHeight * index - parameters.fontMetrics.ascent, parameters)
        }
        captionLines.forEachIndexed { index, line ->
            canvas.drawText(line, width - padding,
                detailsTop + firstHeight + gap + captionHeight * index - caption.fontMetrics.ascent, caption)
        }
        canvas.restore()
    }

    private fun textHeight(paint: Paint): Float = paint.fontMetrics.descent - paint.fontMetrics.ascent

    private fun paint(color: Int, medium: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
}
