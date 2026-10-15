package com.thomas.carspeed

import android.view.Surface
import kotlin.math.abs
import kotlin.math.min

internal data class CaptureSize(val width: Int, val height: Int)

internal class StillCaptureUnavailable : IllegalStateException("当前录像没有拍照通道")

/**
 * 无缝录像已经占用相机。拍照不再另开分析流，只在同一路录像会话里抓一张 JPEG。
 * 尺寸不超过当前录像，避免 4K 录像再加一张超大照片把会话挤掉。
 */
internal object StillCapturePlan {
    fun jpegSizeCandidates(sizes: List<CaptureSize>, videoWidth: Int, videoHeight: Int): List<CaptureSize> {
        val preferred = chooseJpegSize(sizes, videoWidth, videoHeight) ?: return emptyList()
        val smallerPool = sizes.filter { it.area < preferred.area }
        val fallbackVideoWidth = min(videoWidth, 1920)
        val fallbackVideoHeight = min(videoHeight, 1080)
        val fallback = chooseJpegSize(smallerPool, fallbackVideoWidth, fallbackVideoHeight)
        return listOfNotNull(preferred, fallback).distinct()
    }

    fun chooseJpegSize(sizes: List<CaptureSize>, videoWidth: Int, videoHeight: Int): CaptureSize? {
        if (sizes.isEmpty() || videoWidth <= 0 || videoHeight <= 0) return null
        val videoArea = videoWidth.toLong() * videoHeight.toLong()
        val matching = sizes.filter { aspectMatches(it, videoWidth, videoHeight) }
        val pool = matching.ifEmpty { sizes }
        return pool.filter { it.area <= videoArea }.maxByOrNull { it.area }
            ?: pool.minByOrNull { it.area }
    }

    fun jpegOrientationDegrees(sensorOrientationDegrees: Int, displayRotation: Int): Int {
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientationDegrees - deviceDegrees + 360) % 360
    }

    private fun aspectMatches(size: CaptureSize, videoWidth: Int, videoHeight: Int): Boolean {
        val videoAspect = videoWidth.toDouble() / videoHeight
        val aspect = size.width.toDouble() / size.height.coerceAtLeast(1)
        val swapped = size.height.toDouble() / size.width.coerceAtLeast(1)
        return abs(aspect - videoAspect) <= ASPECT_TOLERANCE || abs(swapped - videoAspect) <= ASPECT_TOLERANCE
    }

    private val CaptureSize.area: Long
        get() = width.toLong() * height.toLong()

    private const val ASPECT_TOLERANCE = 0.08
}
