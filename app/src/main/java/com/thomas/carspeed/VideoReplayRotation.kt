package com.thomas.carspeed

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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

    /**
     * 白底参数栏属于画面的一部分。参数是屏幕像素高度（回放页是 62dp，导出时换算到视频像素）。
     * 先按视频比例缩放，再把参数栏高度加到短边上。
     */
    fun fittedStageSize(
        frameWidth: Int,
        frameHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        barPx: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> {
        if (frameWidth <= 0 || frameHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return 1 to 1
        val quarter = isQuarterTurn(rotationDegrees)
        val availableWidth = (if (quarter) frameHeight else frameWidth).toFloat()
        val availableHeight = (if (quarter) frameWidth else frameHeight).toFloat()
        val bar = barPx.coerceAtLeast(0).toFloat().coerceAtMost(availableHeight * 0.4f)
        val scale = min(
            availableWidth / videoWidth.toFloat(),
            (availableHeight - bar) / videoHeight.toFloat()
        ).coerceAtLeast(0.01f)
        return (videoWidth * scale).toInt().coerceAtLeast(1) to
            (videoHeight * scale + bar).toInt().coerceAtLeast(1)
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

    /**
     * 旋转前画面里的点，绕画面中心顺时针转到成片上。
     * 和预览里 videoStage.rotation 是同一个方向：字幕贴在画面左下角时，顺时针 90 度会到成片左上角。
     */
    fun mapContentPoint(
        x: Float,
        y: Float,
        contentWidth: Float,
        contentHeight: Float,
        outputWidth: Float,
        outputHeight: Float,
        rotationDegrees: Int
    ): Pair<Float, Float> {
        val turns = normalize(rotationDegrees)
        val dx = x - contentWidth / 2f
        val dy = y - contentHeight / 2f
        val radians = Math.toRadians(turns.toDouble())
        val cosTurns = cos(radians).toFloat()
        val sinTurns = sin(radians).toFloat()
        val rotatedX = dx * cosTurns - dy * sinTurns
        val rotatedY = dx * sinTurns + dy * cosTurns
        return outputWidth / 2f + rotatedX to outputHeight / 2f + rotatedY
    }
}
