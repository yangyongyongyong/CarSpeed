package com.thomas.carspeed

/**
 * 地图控件要按画面旋转对调宽高。轴心必须等它真的排到这个尺寸再设，
 * 提前用旧尺寸会把旋转中心锁死，窗口里就只剩一半地图。
 */
internal object MapRotationAnchor {
    fun targetSize(parentWidth: Int, parentHeight: Int, rotationDegrees: Int): Pair<Int, Int> {
        return if (VideoReplayRotation.isQuarterTurn(rotationDegrees)) {
            parentHeight to parentWidth
        } else {
            parentWidth to parentHeight
        }
    }

    fun readyToAnchor(measuredWidth: Int, measuredHeight: Int, targetWidth: Int, targetHeight: Int): Boolean {
        return targetWidth > 2 && targetHeight > 2 &&
            measuredWidth == targetWidth && measuredHeight == targetHeight
    }
}
