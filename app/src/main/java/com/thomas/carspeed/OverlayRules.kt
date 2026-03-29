package com.thomas.carspeed

object OverlayRules {
    fun shouldCollapseToBubble(
        overlayLeftX: Int,
        overlayWidth: Int,
        overlayTopY: Int,
        overlayHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        edgeThresholdPx: Int
    ): Boolean {
        val rightTopX = overlayLeftX + overlayWidth
        val nearRightEdge = rightTopX >= (screenWidth - edgeThresholdPx)

        // 规则：悬浮框“右上角”必须位于屏幕“右侧边缘的上1/2区域”
        // 即：贴右边 + topY 落在 [0, screenHeight/2]
        val upperHalfMaxY = screenHeight / 2
        val rightTopInUpperHalf = overlayTopY in 0..upperHalfMaxY

        return nearRightEdge && rightTopInUpperHalf
    }
}
