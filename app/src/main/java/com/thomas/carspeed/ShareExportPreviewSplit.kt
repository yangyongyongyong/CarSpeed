package com.thomas.carspeed

import kotlin.math.roundToInt

/**
 * 导出预览里，上面视频和下面地图的高度比例。
 * 地图占比和导出成片的地图高度用同一套 18%–45%，拖动分隔条看到的就是会导出的比例。
 */
internal object ShareExportPreviewSplit {
    const val MIN_MAP_PERCENT = 18
    const val MAX_MAP_PERCENT = 45

    fun videoWeight(mapPercent: Int): Float {
        return 1f - mapPercent.coerceIn(MIN_MAP_PERCENT, MAX_MAP_PERCENT) / 100f
    }

    fun mapPercent(videoHeight: Int, totalHeight: Int): Int {
        if (totalHeight <= 0) return MIN_MAP_PERCENT
        val mapFraction = 1f - videoHeight.toFloat() / totalHeight.toFloat()
        return (mapFraction * 100f).roundToInt().coerceIn(MIN_MAP_PERCENT, MAX_MAP_PERCENT)
    }

    fun clampVideoHeight(videoHeight: Int, totalHeight: Int): Int {
        if (totalHeight <= 0) return videoHeight.coerceAtLeast(0)
        val minVideo = (totalHeight * videoWeight(MAX_MAP_PERCENT)).toInt().coerceAtLeast(1)
        val maxVideo = (totalHeight * videoWeight(MIN_MAP_PERCENT)).toInt().coerceAtLeast(minVideo)
        return videoHeight.coerceIn(minVideo, maxVideo)
    }

    /** 预览根布局改成叠放后，分区模式仍按导出地图高度把视频、分隔条、地图从上到下排开。 */
    fun splitSections(parentHeight: Int, mapPercent: Int, dividerPx: Int): Triple<PreviewSection, PreviewSection, PreviewSection> {
        val divider = dividerPx.coerceAtLeast(0)
        val space = (parentHeight - divider).coerceAtLeast(2)
        val video = (space * videoWeight(mapPercent)).roundToInt().coerceIn(1, space - 1)
        val map = (space - video).coerceAtLeast(1)
        return Triple(
            PreviewSection(0, video),
            PreviewSection(video, divider),
            PreviewSection(video + divider, map)
        )
    }
}

internal data class PreviewSection(val top: Int, val height: Int)
