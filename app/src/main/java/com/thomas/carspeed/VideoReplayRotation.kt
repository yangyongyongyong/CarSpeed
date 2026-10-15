package com.thomas.carspeed

import kotlin.math.min

/**
 * 回放页手动把画面按 90 度转到竖屏时，用交换后的宽高去铺满画面。
 * 地图用同一角度旋转整个地图视图，路名和地名会跟着画面一起转。
 */
internal object VideoReplayRotation {
    fun normalize(degrees: Int): Int {
        val mod = degrees % 360
        return if (mod < 0) mod + 360 else mod
    }

    fun isQuarterTurn(degrees: Int): Boolean {
        val normalized = normalize(degrees)
        return normalized == 90 || normalized == 270
    }

    fun fittedContentSize(
        frameWidth: Int,
        frameHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        if (frameWidth <= 0 || frameHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return 1 to 1
        val quarter = isQuarterTurn(rotationDegrees)
        val availableWidth = if (quarter) frameHeight else frameWidth
        val availableHeight = if (quarter) frameWidth else frameHeight
        val scale = min(
            availableWidth.toFloat() / videoWidth.toFloat(),
            availableHeight.toFloat() / videoHeight.toFloat()
        )
        return (videoWidth * scale).toInt().coerceAtLeast(1) to (videoHeight * scale).toInt().coerceAtLeast(1)
    }

    fun onScreenSize(contentWidth: Int, contentHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        return if (isQuarterTurn(rotationDegrees)) contentHeight to contentWidth else contentWidth to contentHeight
    }

    fun videoAreaFraction(
        containerWidth: Int,
        containerHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        rotationDegrees: Int,
        minFraction: Float,
        maxFraction: Float
    ): Float {
        if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return minFraction
        }
        val (displayWidth, displayHeight) = if (isQuarterTurn(rotationDegrees)) {
            videoHeight to videoWidth
        } else {
            videoWidth to videoHeight
        }
        val neededHeight = containerWidth.toFloat() * displayHeight.toFloat() / displayWidth.toFloat()
        return (neededHeight / containerHeight.toFloat()).coerceIn(minFraction, maxFraction)
    }
}
