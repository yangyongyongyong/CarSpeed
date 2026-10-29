package com.thomas.carspeed

import kotlin.math.roundToInt

internal object ExportSubtitleStyle {
    const val INSET_HORIZONTAL_DP = 16
    const val INSET_VERTICAL_DP = 10
    const val IDENTITY_GAP_DP = 18
    const val LABEL_GAP_DP = 2
    const val DETAILS_GAP_DP = 5
    const val TITLE_SP = 20f
    const val LABEL_SP = 9f
    const val PARAMETERS_SP = 11f
    const val CAPTION_SP = 10f

    /** 屏幕预览时白栏的高度：明细一行时的 62dp，再加上多出来的 (行数-1) 行。 */
    const val ONE_LINE_BAR_DP = 62
    /** 明细每多一行，白栏就让出这么多 dp。 */
    const val DETAIL_LINE_DP = 15

    /** 屏幕/照片里白栏该多高。单行时等于原来的 62dp，不会让老画面突然变高。 */
    fun barHeightDp(detailLines: Int = SubtitleLineWrap.MAX_LINES): Int =
        ONE_LINE_BAR_DP + DETAIL_LINE_DP * (detailLines.coerceIn(1, SubtitleLineWrap.MAX_LINES) - 1)

    /**
     * 视频里按短边等比缩放。这里固定按"最多三行"预留，编码时高度不能变，
     * 否则同一趟录像的分段之间画面比例会跳。
     */
    fun barHeightPx(width: Int, height: Int): Int {
        val pixels = (minOf(width, height).coerceAtLeast(1) * 0.12f * barHeightDp() / ONE_LINE_BAR_DP)
            .roundToInt().coerceAtLeast(2)
        return pixels + pixels % 2
    }

    /** 字号基准：一行明细时白栏的高度，按同一比例换算到像素。加高白栏不会放大字号。 */
    fun textScalePx(width: Int, height: Int): Int {
        val shortEdge = minOf(width, height).coerceAtLeast(1)
        return (shortEdge * 0.12f).roundToInt().coerceAtLeast(2)
    }
}
